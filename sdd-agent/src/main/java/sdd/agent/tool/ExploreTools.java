package sdd.agent.tool;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.EntityKind;
import sdd.core.kb.EntityMatch;
import sdd.core.kb.KbEntities;
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
import java.util.LinkedHashSet;
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
        this.jdbi = jdbi;
        this.jail = jail;
        this.singleTool = singleTool;
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
        return singleTool ? List.of(multiplexed()) : List.of(
                new ToolSpec("list_repos", "List the indexed repositories.",
                        "{\"type\":\"object\",\"properties\":{}}"),
                new ToolSpec("list_files", "List the entries of a directory.",
                        one("path", "<repo>/<dir>")),
                new ToolSpec("read_file", "Read a file (capped).",
                        one("path", "<repo>/<path>")),
                new ToolSpec("search_code", "Regex-search every repo's text.",
                        """
                        {"type":"object","properties":{\
                        "regex":{"type":"string","description":"Java regex"},\
                        "repo":{"type":"string","description":"Optional repo filter"},\
                        "glob":{"type":"string","description":"Optional path glob"}},\
                        "required":["regex"]}"""),
                new ToolSpec("search_symbols", "Search indexed type and member names.",
                        one("query", "Words or an identifier")),
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
                        + "read_file(path) | search_code(regex[,repo][,glob]) | "
                        + "search_symbols(query) | kb_resolve(kind,value) | "
                        + "propose_touchpoint(kind,value) | record_finding(claim,citation) | "
                        + "done(result,summary)",
                """
                {"type":"object","properties":{\
                "action":{"type":"string","enum":["list_repos","list_files","read_file",\
                "search_code","search_symbols","kb_resolve","propose_touchpoint",\
                "record_finding","done"]},\
                "path":{"type":"string"},"regex":{"type":"string"},"repo":{"type":"string"},\
                "glob":{"type":"string"},"query":{"type":"string"},"kind":{"type":"string"},\
                "value":{"type":"string"},"claim":{"type":"string"},"citation":{"type":"string"},\
                "result":{"type":"string"},"summary":{"type":"string"}},"required":["action"]}""");
    }

    @Override
    public String dispatch(String name, String argsJson) {
        JsonNode args = parse(name, argsJson);
        return switch (name) {
            case "list_repos" -> listRepos();
            case "list_files" -> listFiles(str(args, "path"));
            case "read_file" -> readFile(str(args, "path"));
            case "search_code" -> searchCode(str(args, "regex"), optional(args, "repo"),
                    optional(args, "glob"));
            case "search_symbols" -> searchSymbols(str(args, "query"));
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

    private String readFile(String path) {
        Path file = jail.resolveExisting(path);
        seen.add(normalize(path));
        return FileTools.readCapped(file, path);
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

    private static String optional(JsonNode args, String field) {
        JsonNode value = args.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
