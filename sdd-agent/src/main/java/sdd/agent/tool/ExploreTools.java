package sdd.agent.tool;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.EntityKind;
import sdd.core.kb.EntityMatch;
import sdd.core.kb.KbEntities;
import sdd.core.kb.KbRefGraph;
import sdd.core.kb.Resolution;
import sdd.core.llm.ToolCall;
import sdd.core.llm.ToolSpec;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.Hit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/**
 * The read-only tool set for {@code sdd explore}: roam every indexed repo, and record what was
 * found in a form a human can check.
 *
 * <p><b>Read-only is structural, not advertised.</b> There is no {@code apply_edit} and no build
 * tool at all — dropping the edit tool alone would not be enough, since a Gradle or npm task writes
 * to disk freely. {@link EstateJail} has no writable resolve to offer in the first place.
 *
 * <p><b>Two gates make the output checkable, and neither is a prompt instruction.</b>
 *
 * <ol>
 *   <li>{@code propose_touchpoint} resolves through {@link KbEntities} before it is accepted. The
 *       explorer proposes hints and the KB verifies them, so it cannot name a class that does not
 *       exist — the same "hints verified, never trusted" rule {@code SeedFinder} applies at plan
 *       time, applied early enough that the model can correct itself.
 *   <li>{@code record_finding} refuses a citation for a file this run has not actually read, then
 *       <b>re-reads the file itself</b> to capture the line verbatim. The model never supplies the
 *       quoted text. This is what turns "cite what you read" from a rule a model can quietly ignore
 *       into one it cannot.
 * </ol>
 */
public final class ExploreTools implements Tools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int FTS_LIMIT = 30;
    private static final int MAX_CITED_LINE_CHARS = 300;
    /** Reference lists are long for a hub type; truncation is always named. */
    private static final int MAX_REFERENCES = 30;
    /** Default question ceiling; overridable through {@code explore.max_questions}. */
    public static final int MAX_QUESTIONS = 10;
    /** How many times one question may be repeated before the tool refuses instead of answering. */
    private static final int MAX_REPEATS = 3;

    /**
     * The nine operations behind one declaration.
     *
     * <p>Measured against a GigaChat gateway, its function-calling path fails probabilistically
     * as the declaration set grows: identifier-shaped text in the message failed 0 of 3 attempts
     * with one declaration, 2 of 3 with four, and 3 of 3 with five or more, regardless of the
     * text. One declaration is the only size that cleared it outright, and
     * {@code HttpChatModel}'s 5xx retries cannot rescue a failure that is certain.
     *
     * <p>It is off by default and always should be. Nine declarations with their own schemas is
     * the better interface — the model is told what each operation takes, and a wrong argument
     * is a schema error rather than a runtime one. This trades that away to survive an endpoint
     * that cannot carry it, so it belongs behind {@code explore.single_tool} rather than in the
     * default path.
     */
    private static final String MULTIPLEXED = "sdd";

    private final Jdbi jdbi;
    private final EstateJail jail;
    private final boolean singleTool;
    /**
     * One line per tool call, as it happens, or null for silence.
     *
     * <p>It lives here rather than in {@link sdd.agent.loop.AgentLoop} because this is the only
     * place that knows what a call MEANT — "search_code found 12 files in 4 repos" instead of a
     * tool name and an opaque blob. A survey that roams 53 repos for two hundred turns with no
     * output is indistinguishable from one that is wedged.
     */
    private final java.util.function.Consumer<String> trace;
    private final HumanAsk asker;
    private final int maxQuestions;
    /** Normalized question -> how many times it has been asked, for the repeat rule. */
    private final java.util.Map<String, Integer> asked = new java.util.LinkedHashMap<>();
    private long blockedNanos;
    private final EstateSearch search;
    private final FtsRetriever fts;
    private final Notebook notebook = new Notebook();
    /** Estate paths this run has surfaced to the model — the provenance set the citation gate uses. */
    private final Set<String> seen = new LinkedHashSet<>();

    public ExploreTools(Jdbi jdbi, EstateJail jail) {
        this(jdbi, jail, false);
    }

    /** @param singleTool advertise one multiplexed declaration instead of nine — see
     *                    {@link #MULTIPLEXED} for what that buys and what it costs */
    public ExploreTools(Jdbi jdbi, EstateJail jail, boolean singleTool) {
        this(jdbi, jail, singleTool, null);
    }

    public ExploreTools(Jdbi jdbi, EstateJail jail, boolean singleTool,
                        java.util.function.Consumer<String> trace) {
        this(jdbi, jail, singleTool, trace, null, MAX_QUESTIONS);
    }

    /**
     * @param asker        where a question reaches a human, or null when nobody is attached — in
     *                     which case {@code ask_user_question} is not advertised at all, so the
     *                     default non-interactive run keeps exactly the declaration count it has
     *                     today, and with it the measured tool-call reliability on gateways that
     *                     degrade as that count grows
     * @param maxQuestions how many questions one run may ask. The only ceiling on how many
     *                     human-minutes a survey can consume; turns and tokens do not bound it,
     *                     because waiting costs neither
     */
    public ExploreTools(Jdbi jdbi, EstateJail jail, boolean singleTool,
                        java.util.function.Consumer<String> trace, HumanAsk asker,
                        int maxQuestions) {
        this.jdbi = jdbi;
        this.jail = jail;
        this.singleTool = singleTool;
        this.trace = trace;
        this.asker = asker;
        this.maxQuestions = maxQuestions;
        this.search = new EstateSearch(jail);
        this.fts = new FtsRetriever(jdbi);
    }

    public Notebook notebook() {
        return notebook;
    }

    @Override
    public String digest() {
        return notebook.isEmpty() ? null : notebook.digest();
    }

    /**
     * The declarations, deliberately terse.
     *
     * <p>Everything these used to explain — that search_code reaches what the index has no
     * concept of, that a citation must be a file this run opened, that a proposal is verified
     * rather than trusted — is stated in {@link sdd.agent.run.Explorer#SYSTEM_PROMPT}, which the
     * model reads first. Repeating it here bought nothing and cost bytes on the wire, and at
     * least one gateway's function-calling path degrades measurably as that payload grows:
     * measured on GigaChat, identifier-shaped text in the message failed 3 of 3 attempts with
     * these descriptions at full length and 2 of 3 with them shortened, at the same tool count.
     */
    /**
     * Unwraps a multiplexed call into the operation it names.
     *
     * <p>A call that already names an operation passes through untouched, so a model that
     * remembers the nine-tool shape from an earlier turn — or an endpoint that ignores the
     * single declaration — still works. Failing closed here would turn a cosmetic difference
     * into a dead turn.
     */
    @Override
    public ToolCall route(ToolCall call) {
        if (!singleTool || !MULTIPLEXED.equals(call.name())) {
            return call;
        }
        JsonNode args = parse(call.name(), call.argumentsJson());
        JsonNode action = args.get("action");
        if (action == null || !action.isTextual()) {
            throw new MalformedCallException("missing required string argument: action");
        }
        com.fasterxml.jackson.databind.node.ObjectNode rest = ((com.fasterxml.jackson.databind.node.ObjectNode) args).deepCopy();
        rest.remove("action");
        return new ToolCall(call.id(), action.asText(), rest.toString());
    }

    @Override
    public List<ToolSpec> specs() {
        // Filtered, not conditionally built: keeping one list means the multiplexed schema and
        // the per-tool declarations can never disagree about which operations exist.
        return singleTool ? List.of(multiplexed()) : declarations().stream()
                .filter(spec -> asker != null || !spec.name().equals("ask_user_question"))
                .toList();
    }

    private List<ToolSpec> declarations() {
        return List.of(
                new ToolSpec("list_repos", "List the indexed repositories.",
                        "{\"type\":\"object\",\"properties\":{}}"),
                new ToolSpec("list_files", "List the entries of a directory.",
                        one("path", "<repo>/<dir>")),
                new ToolSpec("read_file",
                        "Read a file, numbered. offset = first line (1-based), limit = how many. "
                                + "startLine/endLine also work. Read around a search hit's line.",
                        """
                        {"type":"object","properties":{\
                        "path":{"type":"string","description":"<repo>/<path>"},\
                        "offset":{"type":"integer","description":"First line, 1-based"},\
                        "limit":{"type":"integer","description":"How many lines"}},\
                        "required":["path"]}"""),
                new ToolSpec("search_code",
                        "Search file contents by regex, and/or list files by path glob. "
                                + "Give regex to search, glob to list matching paths "
                                + "(e.g. src/**/*.ts), or both to search only within those paths.",
                        """
                        {"type":"object","properties":{\
                        "regex":{"type":"string","description":"Java regex over file contents; \
                        omit to just list the files the glob matches"},\
                        "repo":{"type":"string","description":"Optional repo filter"},\
                        "glob":{"type":"string","description":"Path glob over the repo-relative \
                        path, e.g. **/*.sql or src/**/*.ts"}}}"""),
                new ToolSpec("search_symbols", "Search indexed type and member names.",
                        one("query", "Words or an identifier")),
                new ToolSpec("ask_user_question",
                        "Ask the human one blocking question. Only when their answer changes what "
                                + "you would do next.",
                        """
                        {"type":"object","properties":{\
                        "question":{"type":"string","description":"One question, answerable in a \
                        sentence"},\
                        "why":{"type":"string","description":"What you would do differently per \
                        answer"},\
                        "options":{"type":"array","items":{"type":"string"},\
                        "description":"Suggested answers, optional"}},\
                        "required":["question"]}"""),
                new ToolSpec("who_references",
                        "Types that reference a fully-qualified type (direction=in, the default), "
                                + "or that it references (direction=out). Use it to tell a real "
                                + "user of a symbol from a name collision.",
                        """
                        {"type":"object","properties":{\
                        "fqcn":{"type":"string","description":"Fully-qualified type name"},\
                        "direction":{"type":"string","enum":["in","out"],\
                        "description":"in = who references it (default), out = what it references"}},\
                        "required":["fqcn"]}"""),
                new ToolSpec("kb_resolve", "Resolve a value against the knowledge base.",
                        kindValueSchema("Value to resolve")),
                new ToolSpec("propose_touchpoint", "Propose a touchpoint. Refused if unresolvable.",
                        kindValueSchema("The value")),
                new ToolSpec("record_finding", "Record one claim plus the file:line you read.",
                        """
                        {"type":"object","properties":{\
                        "claim":{"type":"string","description":"One checkable sentence"},\
                        "citation":{"type":"string","description":"<repo>/<path>:<line>"}},\
                        "required":["claim","citation"]}"""),
                new ToolSpec("done", "Finish: result is 'success' or 'blocked'.",
                        """
                        {"type":"object","properties":{\
                        "result":{"type":"string","enum":["success","blocked"]},\
                        "summary":{"type":"string"}},"required":["result","summary"]}"""));
    }

    /**
     * One declaration carrying every operation, with the union of their arguments.
     *
     * <p>The per-operation argument lists live in the description rather than in the schema,
     * because a JSON Schema cannot express "path is required when action is read_file" in a
     * form these endpoints act on. The runtime checks that {@code dispatch} already performs
     * are unchanged, so a missing argument is still a {@code MalformedCallException} the model
     * is told about and can retry — the same recovery it has today.
     */
    private static ToolSpec multiplexed() {
        return new ToolSpec(MULTIPLEXED,
                "Explore the estate. One action per call: list_repos | list_files(path) | "
                        + "read_file(path[,offset][,limit]) | search_code([regex][,repo][,glob]) | "
                        + "search_symbols(query) | who_references(fqcn[,direction]) | "
                        + "ask_user_question(question[,why][,options]) | "
                        + "kb_resolve(kind,value) | "
                        + "propose_touchpoint(kind,value) | record_finding(claim,citation) | "
                        + "done(result,summary)",
                """
                {"type":"object","properties":{\
                "action":{"type":"string","enum":["list_repos","list_files","read_file",\
                "search_code","search_symbols","who_references","ask_user_question","kb_resolve",\
                "propose_touchpoint",\
                "record_finding","done"]},\
                "path":{"type":"string"},"offset":{"type":"string"},"limit":{"type":"string"},\
                "regex":{"type":"string"},"repo":{"type":"string"},\
                "glob":{"type":"string"},"query":{"type":"string"},"kind":{"type":"string"},\
                "value":{"type":"string"},"claim":{"type":"string"},"citation":{"type":"string"},\
                "result":{"type":"string"},"summary":{"type":"string"}},"required":["action"]}""");
    }

    @Override
    public String dispatch(String name, String argsJson) {
        // Announced BEFORE it runs, not after. A search over a large repo makes no model call
        // while it works, so a trace written only on return is silent for exactly as long as the
        // slow thing takes — which is indistinguishable from a hang, and was.
        if (trace != null) {
            trace.accept(name + subject(name, argsJson) + " ...");
        }
        long started = System.nanoTime();
        String result = run(name, argsJson);
        if (trace != null) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            int lines = (int) result.lines().count();
            trace.accept("  → " + lines + (lines == 1 ? " line" : " lines") + ", " + ms + "ms");
        }
        return result;
    }

    /** A call rendered as what it is about, not as what it was called. */
    private String subject(String name, String argsJson) {
        JsonNode args;
        try {
            args = parse(name, argsJson);
        } catch (MalformedCallException e) {
            return "";
        }
        return switch (name) {
            case "list_files" -> " " + args.path("path").asText("");
            case "read_file" -> " " + args.path("path").asText("")
                    + (args.hasNonNull("offset") ? "@" + args.get("offset").asText() : "");
            case "search_code" -> " " + args.path("regex").asText("")
                    + (args.hasNonNull("repo") ? " in " + args.get("repo").asText() : "")
                    + (args.hasNonNull("glob") ? " glob " + args.get("glob").asText() : "");
            case "search_symbols" -> " " + args.path("query").asText("");
            case "ask_user_question" -> " " + args.path("question").asText("");
            case "who_references" -> " " + args.path("fqcn").asText("")
                    + ("out".equals(args.path("direction").asText("")) ? " (out)" : "");
            case "kb_resolve", "propose_touchpoint" ->
                    " " + args.path("kind").asText("") + ":" + args.path("value").asText("");
            case "record_finding" -> " " + args.path("citation").asText("");
            default -> "";
        };
    }

    private String run(String name, String argsJson) {
        JsonNode args = parse(name, argsJson);
        return switch (name) {
            case "list_repos" -> listRepos();
            case "list_files" -> listFiles(str(args, "path"));
            case "read_file" -> readFile(str(args, "path"), args);
            case "search_code" -> searchCode(optional(args, "regex"), optional(args, "repo"),
                    optional(args, "glob"));
            case "search_symbols" -> searchSymbols(str(args, "query"));
            case "who_references" -> whoReferences(str(args, "fqcn"), optional(args, "direction"));
            // Handled explicitly rather than falling to default's "unknown tool", which would cost
            // a strike: a model calling it from memory in single-tool mode, or after seeing it in
            // an earlier run, deserves the real explanation.
            case "ask_user_question" -> ask(str(args, "question"), strings(args, "options"));
            case "kb_resolve" -> describe(resolve(str(args, "kind"), str(args, "value")));
            case "propose_touchpoint" -> propose(str(args, "kind"), str(args, "value"));
            case "record_finding" -> recordFinding(str(args, "claim"), str(args, "citation"));
            case "done" -> throw new MalformedCallException("done is handled by the loop, not dispatched");
            default -> throw new MalformedCallException("unknown tool: " + name);
        };
    }

    private String listRepos() {
        StringBuilder out = new StringBuilder();
        for (String repo : jail.repos()) {
            out.append(repo).append('\n');
        }
        return out.toString();
    }

    private String listFiles(String path) {
        return FileTools.listEntries(jail.resolveExisting(path), path);
    }

    private String readFile(String path, JsonNode args) {
        int[] window = window(args);
        Path file = jail.resolveExisting(path);
        seen.add(normalize(path));
        return FileTools.readWindow(file, path, window[0], window[1]);
    }

    /** Argument names {@link #window} understands, and the only ones {@code read_file} accepts. */
    private static final Set<String> OFFSET_KEYS =
            Set.of("offset", "startline", "start_line", "start", "from", "line");
    private static final Set<String> LIMIT_KEYS = Set.of("limit", "count", "lines");
    private static final Set<String> END_KEYS = Set.of("endline", "end_line", "end", "to");
    /** {@code path} names the file; {@code repo} is accepted and ignored because {@code search_code}
     *  takes one and a model reasonably reuses it — the repo is already the path's first segment. */
    private static final Set<String> OTHER_KEYS = Set.of("path", "repo");

    /**
     * {@code [offset, limit]} for a read, from whatever the model called the arguments.
     *
     * <p>Two failures produced the same symptom on live runs, twice: a read that asked for line 363
     * and one that asked for lines 339-557 both came back as line 1, because the argument name was
     * not recognised and was silently ignored. The model then re-searched to confirm the line it
     * already had, read again, got the same imports, and was killed by the wedge detector for doing
     * exactly the right thing three times.
     *
     * <p>So two rules. Recognise the spellings that have actually been observed —
     * {@code offset}/{@code limit} and {@code startLine}/{@code endLine} — since a round trip spent
     * teaching a name costs a turn on an endpoint that manages one call per turn. And <b>refuse an
     * argument name that is not understood</b>, naming the accepted ones, rather than reading
     * somewhere else and calling it an answer. A malformed call is recoverable and measurably is:
     * on a live run the model was told {@code search_code} takes {@code regex} rather than
     * {@code query} and corrected itself on the very next turn. A wrong window is not recoverable,
     * because nothing tells the model it got one.
     */
    private static int[] window(JsonNode args) {
        int offset = 1;
        int limit = FileTools.MAX_READ_LINES;
        Integer end = null;
        List<String> unknown = new ArrayList<>();
        Iterator<String> names = args.fieldNames();
        while (names.hasNext()) {
            String raw = names.next();
            String key = raw.toLowerCase(Locale.ROOT);
            if (OFFSET_KEYS.contains(key)) {
                offset = number(args, raw, offset);
            } else if (LIMIT_KEYS.contains(key)) {
                limit = number(args, raw, limit);
            } else if (END_KEYS.contains(key)) {
                end = number(args, raw, -1);
            } else if (!OTHER_KEYS.contains(key)) {
                unknown.add(raw);
            }
        }
        if (!unknown.isEmpty()) {
            throw new MalformedCallException("read_file does not take " + String.join(", ", unknown)
                    + " — it takes path, and optionally offset (first line, 1-based) and limit "
                    + "(how many lines). startLine/endLine work too.");
        }
        // An end LINE is not a count. Reading 339-557 as "339 plus 557 more" would overshoot the
        // file and look like it worked.
        if (end != null && end >= offset) {
            limit = end - offset + 1;
        }
        return new int[] {offset, limit};
    }

    private String searchCode(String regex, String repo, String glob) {
        EstateSearch.Result result = search.find(regex, repo, glob);
        seen.addAll(result.paths());
        return result.rendered();
    }

    private String searchSymbols(String query) {
        List<Hit> hits = fts.search(query, FTS_LIMIT);
        if (hits.isEmpty()) {
            // Naming the limitation is the point: fts_symbol holds type and member NAMES only, so
            // an empty result here says nothing about whether the term exists in the estate.
            return "no indexed symbol matches '" + query + "' — the symbol index holds type and "
                    + "member names only, so try search_code for config keys, tables, channels or "
                    + "anything written in a non-Java file\n";
        }
        StringBuilder out = new StringBuilder();
        for (Hit hit : hits) {
            String repo = KbEntities.repoOfModule(jdbi, hit.moduleId());
            out.append(repo == null ? "?" : repo).append(": ").append(hit.fqcn())
                    .append(hit.identifier().equals(hit.fqcn()) ? "" : " (" + hit.identifier() + ")")
                    .append(hit.docOnly() ? "  [javadoc match only]" : "")
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * Puts one question to a human, or explains why it cannot.
     *
     * <p><b>Never blocks a run that has nobody to ask.</b> With no asker attached, or once stdin
     * has closed, this is a {@link ToolException} — a well-formed call that failed legitimately,
     * which {@code AgentLoop} treats as free (it resets the strike counter) and which points the
     * model at what it should do instead. The alternative, refusing to start the command, would
     * make an unattended survey impossible; degrading the tool and finishing the survey is strictly
     * better.
     *
     * <p><b>A repeat is answered from the notebook, not from the human.</b> That is what replaces
     * the wedge protection this tool opts out of: it compares the question's normalized text, so it
     * also catches a reworded repeat, and it stops the one thing a person actually resents — being
     * asked the same thing twice. Past {@link #MAX_REPEATS} it refuses and quotes the answer back,
     * because a model looping on a question it has already had answered is not making progress.
     *
     * <p>Writes nothing. Read-only-ness is structural here, and asking does not change that; the
     * answer reaches disk only through the caller's spec rewrite.
     */
    private String ask(String question, List<String> options) {
        String normalized = normalizeQuestion(question);
        if (normalized.isEmpty()) {
            throw new MalformedCallException("question must not be blank");
        }
        String previous = notebook.answerTo(normalized);
        int seen = asked.merge(normalized, 1, Integer::sum);
        if (previous != null) {
            if (seen > MAX_REPEATS) {
                throw new ToolException("you have already asked this " + (seen - 1) + " times and "
                        + "been told: " + previous + " — act on it, or record what is still "
                        + "unclear as a finding");
            }
            return "already answered — " + question + " → " + previous + "\n";
        }
        if (asker == null) {
            throw new ToolException("no human is attached to this run — record what you would have "
                    + "asked as a finding, with the searches you tried, or call done(blocked) "
                    + "naming it");
        }
        if (notebook.clarifications().size() >= maxQuestions) {
            throw new ToolException("this run's question limit (" + maxQuestions + ") is used up — "
                    + "record what is still unclear as a finding, or call done(blocked)");
        }
        long start = System.nanoTime();
        String answer;
        try {
            answer = asker.ask(question, options);
        } finally {
            blockedNanos += System.nanoTime() - start;
        }
        if (answer == null || answer.isBlank()) {
            // One signal for "no terminal", "stdin closed" and "they pressed enter on nothing" —
            // the same collapse InteractiveReview makes on a null readLine.
            throw new ToolException("nobody answered — record what you would have asked as a "
                    + "finding, with the searches you tried, or call done(blocked) naming it");
        }
        notebook.addClarification(new Notebook.Clarification(normalized, question.strip(),
                answer.strip()));
        return "answered — " + question + " → " + answer.strip() + "\n";
    }

    /** Case- and whitespace-insensitive, so a reworded repeat is still recognised as one. */
    private static String normalizeQuestion(String question) {
        return question == null ? ""
                : question.replaceAll("(?U)\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> strings(com.fasterxml.jackson.databind.JsonNode args, String field) {
        List<String> out = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode value : args.path(field)) {
            if (!value.asText("").isBlank()) {
                out.add(value.asText());
            }
        }
        return out;
    }

    @Override
    public boolean repeatable(String name) {
        return name.equals("ask_user_question");
    }

    @Override
    public java.time.Duration blockedOnHuman() {
        return java.time.Duration.ofNanos(blockedNanos);
    }

    /**
     * Reference structure around one type — the signal {@code search_symbols} structurally cannot
     * give.
     *
     * <p>Measured on the real estate, FTS reaches the right repositories with full recall but its
     * top candidates are token collisions: a task about {@code tier.update} surfaces
     * {@code PayloadKeySpec} and {@code CandleMdRejectListener} ahead of the listeners it is
     * actually about. Which types genuinely use a symbol is what tells those apart, and it is a
     * fact the name index does not hold.
     *
     * <p>Deliberately does NOT add to the {@code seen} set. It returns names, repos and paths but
     * no file <em>content</em>, so it cannot ground a citation — exactly like
     * {@code search_symbols}, and unlike {@code read_file} and {@code search_code}, which surface
     * real text. The model must still read a file before {@code record_finding} will accept it.
     *
     * <p>One hop only. Walking further is a second call, which keeps each turn's output readable
     * and lets the model stop when it has what it needs.
     */
    private String whoReferences(String fqcn, String direction) {
        boolean outbound = "out".equals(direction);
        if (direction != null && !outbound && !"in".equals(direction)) {
            throw new MalformedCallException(
                    "direction must be 'in' or 'out', got '" + direction + "'");
        }
        List<KbRefGraph.Edge> edges = outbound
                ? KbRefGraph.outbound(jdbi, fqcn)
                : KbRefGraph.inbound(jdbi, fqcn);
        if (edges.isEmpty()) {
            // Absence here has two very different causes and the agent must be able to tell them
            // apart, so say which one it is rather than returning a bare "none".
            boolean known = !KbRefGraph.inbound(jdbi, fqcn).isEmpty()
                    || !KbRefGraph.outbound(jdbi, fqcn).isEmpty();
            return known
                    ? "no " + (outbound ? "outbound" : "inbound") + " references for " + fqcn + "\n"
                    : "no reference edges recorded for '" + fqcn + "' — check the name is the "
                            + "fully-qualified one (kb_resolve class:<name> finds it), and note "
                            + "that references FROM a type nested inside a class are attributed to "
                            + "its outer type\n";
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (KbRefGraph.Edge e : edges) {
            if (shown++ == MAX_REFERENCES) {
                out.append("(showing ").append(MAX_REFERENCES).append(" of ").append(edges.size())
                        .append(" — a cap the reader cannot see is a lie)\n");
                break;
            }
            out.append(e.repo()).append(": ").append(e.fqcn())
                    .append("  [").append(e.refKind().toLowerCase(java.util.Locale.ROOT))
                    .append(e.refCount() > 1 ? " x" + e.refCount() : "").append("] @ ")
                    .append(e.filePath()).append('\n');
        }
        return out.toString();
    }

    private Resolution resolve(String kindKey, String value) {
        EntityKind kind;
        try {
            kind = EntityKind.valueOf(kindKey.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("unknown kind '" + kindKey + "' — one of: "
                    + String.join(", ", kindKeys()));
        }
        return KbEntities.resolve(jdbi, kind, value);
    }

    private static List<String> kindKeys() {
        List<String> keys = new ArrayList<>();
        for (EntityKind kind : EntityKind.values()) {
            keys.add(kind.label());
        }
        return keys;
    }

    private static String describe(Resolution resolution) {
        if (resolution.matches().isEmpty()) {
            return "no match: " + KbEntities.missReason(resolution.kind()) + "\n";
        }
        StringBuilder out = new StringBuilder();
        for (EntityMatch match : resolution.matches()) {
            out.append(match.repo()).append(": ").append(match.detail())
                    .append("  [").append(match.source()).append("]\n");
        }
        return out.toString();
    }

    private String propose(String kindKey, String value) {
        Resolution resolution = resolve(kindKey, value);
        if (resolution.matches().isEmpty()) {
            // A rejection, not a warning. An unresolvable touchpoint becomes a blocking problem at
            // plan time, so letting it through here would convert the explorer's guess into the
            // human's obstacle.
            throw new ToolException("not proposed — " + KbEntities.missReason(resolution.kind())
                    + " for '" + value + "'. Use kb_resolve to find the exact value, or record it "
                    + "as a finding instead.");
        }
        String repos = String.join(", ", resolution.repos());
        boolean added = notebook.addProposal(new Notebook.Proposal(
                resolution.kind().label(), value, repos));
        return (added ? "proposed " : "already proposed ") + resolution.kind().label() + ": " + value
                + " (resolves in " + repos + ")\n";
    }

    /**
     * The citation gate. Both halves matter: the provenance check stops a citation for a file the
     * run never opened, and the re-read stops a real file being quoted as saying something it does
     * not.
     */
    private String recordFinding(String claim, String citation) {
        int colon = citation.lastIndexOf(':');
        if (colon < 0) {
            throw new ToolException("citation must be <repo>/<path>:<line>, got '" + citation + "'");
        }
        String path = normalize(citation.substring(0, colon));
        int line;
        try {
            line = Integer.parseInt(citation.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            throw new ToolException("citation must end in a line number, got '" + citation + "'");
        }
        if (!seen.contains(path)) {
            throw new ToolException("cannot cite " + path
                    + " — this run has not read it. Call read_file or search_code on it first.");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(jail.resolveExisting(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("cannot re-read " + path + " to verify the citation: "
                    + e.getMessage());
        }
        if (line < 1 || line > lines.size()) {
            throw new ToolException(path + " has " + lines.size() + " lines — line " + line
                    + " does not exist");
        }
        String text = lines.get(line - 1).strip();
        if (text.length() > MAX_CITED_LINE_CHARS) {
            text = text.substring(0, MAX_CITED_LINE_CHARS) + "…";
        }
        boolean added = notebook.addFinding(
                new Notebook.Finding(claim.strip(), path + ":" + line, text));
        return (added ? "recorded" : "already recorded") + " — " + path + ":" + line
                + " reads: " + text + "\n";
    }

    /** Estate paths are forward-slashed and never leading-slashed, whichever way the model spells it. */
    private static String normalize(String path) {
        String cleaned = path.strip().replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        return cleaned.startsWith("/") ? cleaned.substring(1) : cleaned;
    }

    private static String one(String field, String desc) {
        return """
                {"type":"object","properties":{"%s":{"type":"string","description":"%s"}},\
                "required":["%s"]}""".formatted(field, desc, field);
    }

    private static String kindValueSchema(String valueDesc) {
        return """
                {"type":"object","properties":{\
                "kind":{"type":"string","enum":["repo","class","symbol","endpoint","topic","artifact","config"]},\
                "value":{"type":"string","description":"%s"}},"required":["kind","value"]}"""
                .formatted(valueDesc);
    }

    private static JsonNode parse(String name, String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if (!node.isObject()) {
                throw new MalformedCallException("arguments for " + name + " must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new MalformedCallException("unparseable arguments for " + name + ": "
                    + e.getOriginalMessage());
        }
    }

    private static String str(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual()) {
            throw new MalformedCallException("missing required string argument: " + field);
        }
        return value.asText();
    }

    /**
     * A numeric argument, however the endpoint spelled it.
     *
     * <p>Accepts a JSON number and a numeric string alike, because that is not the model's choice:
     * a gateway that cannot structure tool calls returns them as tag markup where every value is
     * text, so {@code offset} arrives as {@code "363"} there and as {@code 363} elsewhere.
     * Rejecting one spelling would make {@code read_file} work on some endpoints and not others.
     *
     * <p>A non-numeric or non-positive value falls back to the default rather than failing the
     * call: the model asked to read a file and the useful answer is the file, not a lecture about
     * the argument it fumbled.
     */
    private static int number(JsonNode args, String field, int fallback) {
        JsonNode value = args.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt() > 0 ? value.asInt() : fallback;
        }
        try {
            int parsed = Integer.parseInt(value.asText().trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String optional(JsonNode args, String field) {
        JsonNode value = args.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
