package sdd.plan.gen;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.contract.ContractKinds;
import org.jdbi.v3.core.Jdbi;
import sdd.core.contract.Markdown;
import sdd.core.contract.TsNames;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.SpecRenderer;
import sdd.plan.spec.Touchpoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The plan-drafting model call (the only model call Phase 3C-1 adds). Output is validated
 * field-by-field against the affected set, the spec's requirement ids, and the KB — never
 * trusted. Total failure degrades to an unavailable Draft whose single BLOCKING question
 * tells the human to rerun; every deterministic plan.md section renders regardless.
 */
public final class PlanDrafter {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Row budgets. Raised from 25/40 on measured evidence: on the real estate an implementor of a
    // changed interface sat at rank 26 against a budget of 25 and was lost by a single position.
    // They are deliberately NOT raised far enough to brute-force the problem — implementors also sat
    // at ranks 48 and 65, and reaching those by budget alone would not survive a wider repo. Getting
    // the right rows PROMOTED into the window is the actual fix; see salientTerms.
    private static final int TYPE_BUDGET = 40;
    private static final int MEMBER_BUDGET = 60;
    private static final int ENDPOINT_BUDGET = 25;

    // Per-section character caps, replacing the single whole-block cap. Their sum is the effective
    // per-repo ceiling; splitting them is what stops a wide type list from starving the sections
    // that render after it.
    //
    // Each is derived from its ROW budget times a generous per-line allowance, and that direction
    // matters: the row budget is meant to be the binding constraint and the character cap only a
    // guard against pathological line lengths. Setting a cap below what its budget can emit makes
    // the cap the real limiter again — measured, when a first attempt at 2000 chars cut the type
    // section at ~15 lines against a budget of 40 and dropped a type that used to be shown.
    private static final int MAX_LINE = 150;
    static final int TYPE_CAP = TYPE_BUDGET * MAX_LINE;
    static final int MEMBER_CAP = MEMBER_BUDGET * MAX_LINE;
    static final int ENDPOINT_CAP = ENDPOINT_BUDGET * MAX_LINE;
    /** The per-repo ceiling, kept as one name for tests and for reasoning about prompt size. */
    static final int EVIDENCE_CAP = TYPE_CAP + MEMBER_CAP + ENDPOINT_CAP;

    /**
     * What a repo gets when the closure says it only needs rebuilding.
     *
     * <p>Every budget here is per-repo, so the prompt is O(affected repos) with no ceiling of its
     * own — measured at ~11k chars per repo, which is 16k tokens on a six-repo estate and 147k on a
     * fifty-three-repo one. Most of that is spent on repos that will not be edited: a
     * BUMP_REBUILD_ONLY repo's step is a version bump and a rebuild, so shipping its full API
     * surface is evidence for work that is not happening.
     *
     * <p>This is safe in exactly the direction the annotation errs. Anchored, the annotation
     * measured 5/5 on real changes; unanchored it falls back to the unfiltered count, which
     * OVER-reports CODE_CHANGE_LIKELY — so a repo whose status is uncertain gets the full share,
     * and only a repo confidently marked rebuild-only is trimmed.
     */
    static final int REBUILD_ONLY_CAP = 900;
    private static final Set<String> VERSION_ACTIONS = Set.of("none", "patch", "minor", "major");
    /** Whatever DeclaredContract can parse — the drafter must never be able to propose a kind the
     *  checker cannot re-derive, and the two lists drifting apart is exactly how that happens. */
    private static final Set<String> CONTRACT_KINDS = Set.of(ContractKinds.JAVA_API,
            ContractKinds.REST, ContractKinds.KAFKA, ContractKinds.TS_API, ContractKinds.REST_CLIENT,
            ContractKinds.STREAM_DESCRIPTOR);
    private static final Set<String> COMPAT_VALUES = Set.of("binary-compatible", "type-compatible");
    static final String SYSTEM_PROMPT = """
            You draft the repo-by-repo implementation plan for a feature specification across a \
            multi-repo estate. You receive the spec, the impact analysis (which repos and why), \
            the execution order, and knowledge-base evidence (real classes, files, API members, \
            endpoints). Return exactly ONE JSON object, no markdown fences:
            {"summary": string,
             "questions": [{"text": string, "blocking": boolean}, ...],
             "contracts": [{"id": string, "kind": "java-api"|"rest"|"kafka"|"ts-api"|"rest-client",
                            "provider": string,
                            "consumers": [string, ...], "body": string,
                            "compat": "binary-compatible" (OPTIONAL, java-api only) or \
            "type-compatible" (OPTIONAL, ts-api only) — declare it when consumers must keep \
            compatibility,
                            "declarations": [string, ...] (OPTIONAL — the machine-checkable \
            members this contract exposes, one exact line per member, no prose, using EXACTLY \
            this grammar for the contract's kind: java-api "<fqcn>#<signature>: <returnType>" \
            e.g. "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier" \
            (the type before '#' MUST be fully qualified — it is matched against the extracted \
            type's full name); \
            rest "<METHOD> <path>" e.g. "GET /api/admin/tier-spreads"; kafka \
            "produces <topic>" or "consumes <topic>" e.g. "produces orders.v1"; ts-api \
            "<moduleSpecifier>#<Export>[.<member>]: <type>" e.g. \
            "@azastashkov/web-sdk#Tick.price: number" (the specifier is what a consumer IMPORTS, \
            never a file path); rest-client "<METHOD> <path>" e.g. "POST /api/orders" — same \
            grammar as rest, but the provider is the repo making the calls. The last two kinds \
            belong to npm/TypeScript repos and the first three to Gradle repos; a kind on the \
            wrong kind of repo is rejected before the plan is approved. Omit this list, \
            or leave it empty, whenever you are not certain of the exact signature or path — an \
            invented declaration is checked against the real implementation later and reports a \
            false alarm)}, ...],
             "repo_steps": [{"repo": string, "covers": [requirement ids], "sub_spec": string,
                             "files": [string, ...], "provides_contracts": [contract ids],
                             "consumes_contracts": [contract ids],
                             "version_action": "none"|"patch"|"minor"|"major",
                             "verification": [string, ...]}, ...]}
            Rules:
            - One repo_steps entry per affected repo that needs work; name only affected repos.
            - Name only files and classes present in the evidence; contracts' bodies are concrete
              interface deltas (java signatures, REST verb+path+types, topic+payload).
            - Every contract referenced by a step must be defined in "contracts".
            - Anything uncertain becomes a question, not an invention; the same goes for
              "declarations" — when unsure, omit the list rather than guess.
            """;

    public record DraftStep(String repo, List<String> covers, String subSpec, List<String> files,
                            List<String> providesContracts, List<String> consumesContracts,
                            String versionAction, List<String> verification) {
        public DraftStep {
            covers = List.copyOf(covers);
            files = List.copyOf(files);
            providesContracts = List.copyOf(providesContracts);
            consumesContracts = List.copyOf(consumesContracts);
            verification = List.copyOf(verification);
        }
    }

    public record DraftContract(String id, String kind, String provider, List<String> consumers,
                                String body, String compat, List<String> declared) {
        public DraftContract {
            consumers = List.copyOf(consumers);
            declared = List.copyOf(declared);
        }
    }

    public record Draft(String summary, List<DraftStep> steps, List<DraftContract> contracts,
                        List<Question> questions, List<String> notes, boolean unavailable) {
        public Draft {
            steps = List.copyOf(steps);
            contracts = List.copyOf(contracts);
            questions = List.copyOf(questions);
            notes = List.copyOf(notes);
        }
    }

    private PlanDrafter() {
    }

    public static Draft draft(Jdbi jdbi, NormalizedSpec spec, ImpactResult result,
                              List<ExecutionOrder.Unit> order, ChatModel planner,
                              String modelName, int maxTokens) {
        return draft(jdbi, spec, result, order, "", planner, modelName, maxTokens);
    }

    public static Draft draft(Jdbi jdbi, NormalizedSpec spec, ImpactResult result,
                              List<ExecutionOrder.Unit> order, String priorQa, ChatModel planner,
                              String modelName, int maxTokens) {
        String input = composeInput(jdbi, spec, result, order, priorQa);
        ChatResponse response;
        try {
            response = planner.complete(new ChatRequest(modelName,
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
                    List.of(), maxTokens, 0.15));
        } catch (ModelException e) {
            return unavailable(e.getMessage());
        }
        if ("length".equals(response.finishReason())) {
            return unavailable("response truncated (finish_reason=length)");
        }
        return parse(jdbi, spec, result, response.message().content());
    }

    private static Draft unavailable(String detail) {
        return new Draft("", List.of(), List.of(),
                List.of(new Question("plan drafting unavailable: " + detail + " — rerun sdd plan", true)),
                List.of(), true);
    }

    private static Draft parse(Jdbi jdbi, NormalizedSpec spec, ImpactResult result, String content) {
        if (content == null) {
            return unavailable("empty response");
        }
        String stripped = content.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                stripped = stripped.substring(firstNewline + 1, lastFence).strip();
            }
        }
        JsonNode root;
        try {
            root = JSON.readTree(stripped);
        } catch (JacksonException e) {
            return unavailable("response is not valid JSON");
        }
        if (!root.isObject()) {
            return unavailable("response is not a JSON object");
        }

        Set<String> affected = new LinkedHashSet<>();
        for (AffectedRepo repo : result.affected()) {
            affected.add(repo.repo());
        }
        Set<String> requirementIds = new LinkedHashSet<>(
                spec.requirements().stream().map(SpecItem::id).toList());
        List<String> notes = new ArrayList<>();

        List<DraftContract> contracts = new ArrayList<>();
        Set<String> contractIds = new LinkedHashSet<>();
        for (JsonNode node : root.path("contracts")) {
            String id = node.path("id").asText().strip();
            String provider = node.path("provider").asText().strip();
            if (id.isBlank() || !affected.contains(provider)) {
                notes.add("drafter contract '" + id + "' has unknown provider '" + provider + "' — dropped");
                continue;
            }
            String kind = node.path("kind").asText();
            if (!CONTRACT_KINDS.contains(kind)) {
                notes.add("drafter contract '" + id + "' kind '" + kind + "' coerced to java-api");
                kind = "java-api";
            }
            List<String> consumers = new ArrayList<>();
            for (JsonNode consumer : node.path("consumers")) {
                String name = consumer.asText().strip();
                if (affected.contains(name)) {
                    consumers.add(name);
                } else {
                    notes.add("drafter contract '" + id + "' names unknown consumer '" + name + "' — dropped");
                }
            }
            String compat = node.path("compat").asText(null);
            // Only the vocabulary is checked here. Whether a compat value is legal ON THIS KIND is
            // PlanValidator's call, which is where the message a human can act on already lives.
            if (compat != null && !COMPAT_VALUES.contains(compat)) {
                notes.add("drafter contract '" + id + "' compat '" + compat + "' dropped");
                compat = null;
            }
            // Absence is silence, never invention: a model that omits "declarations" (or the
            // key is missing entirely) leaves this list empty rather than deriving one from
            // "body" or "kind" — an invented declaration would be checked against the real
            // implementation later and report a false divergence the human never approved.
            List<String> declared = new ArrayList<>();
            for (JsonNode d : node.path("declarations")) {
                String line = sanitizeDeclaredLine(d.asText());
                if (!line.isBlank()) {
                    declared.add(line);
                }
            }
            contracts.add(new DraftContract(id, kind, provider, consumers,
                    node.path("body").asText(), compat, declared));
            contractIds.add(id);
        }

        List<DraftStep> steps = new ArrayList<>();
        for (JsonNode node : root.path("repo_steps")) {
            String repo = node.path("repo").asText().strip();
            if (!affected.contains(repo)) {
                notes.add("drafter named unknown repo '" + repo + "' — step dropped");
                continue;
            }
            List<String> covers = filtered(node, "covers", requirementIds, notes,
                    "step " + repo + " claims unknown requirement");
            List<String> provides = filtered(node, "provides_contracts", contractIds, notes,
                    "step " + repo + " references undefined contract");
            List<String> consumes = filtered(node, "consumes_contracts", contractIds, notes,
                    "step " + repo + " references undefined contract");
            String versionAction = node.path("version_action").asText();
            if (!VERSION_ACTIONS.contains(versionAction)) {
                notes.add("step " + repo + " version_action '" + versionAction + "' coerced to none");
                versionAction = "none";
            }
            List<String> files = new ArrayList<>();
            for (JsonNode file : node.path("files")) {
                String path = file.asText().strip();
                if (!path.isBlank()) {
                    files.add(path);
                    if (!fileKnown(jdbi, repo, path)) {
                        notes.add("step file '" + path + "' not found in the knowledge base for " + repo);
                    }
                }
            }
            List<String> verification = new ArrayList<>();
            for (JsonNode v : node.path("verification")) {
                if (!v.asText().isBlank()) {
                    verification.add(v.asText());
                }
            }
            steps.add(new DraftStep(repo, covers, node.path("sub_spec").asText(), files,
                    provides, consumes, versionAction, verification));
        }

        List<Question> questions = new ArrayList<>();
        for (JsonNode node : root.path("questions")) {
            String text = node.path("text").asText().strip();
            if (!text.isBlank()) {
                questions.add(new Question(text, node.path("blocking").asBoolean(false)));
            }
        }
        return new Draft(root.path("summary").asText().strip(), steps, contracts, questions,
                notes, false);
    }

    /** Shares {@link Markdown#neutralizeFences} with {@code PlanMdRenderer} (the established
     *  anti-forgery pattern) so a declared line can never smuggle a ``` sequence into the rendered
     *  contract fence before a human ever reviews the plan. Grammar checking is PlanValidator's
     *  job at approve time. */
    private static String sanitizeDeclaredLine(String raw) {
        return Markdown.neutralizeFences(raw.strip());
    }

    private static List<String> filtered(JsonNode node, String field, Set<String> allowed,
                                         List<String> notes, String notePrefix) {
        List<String> kept = new ArrayList<>();
        for (JsonNode value : node.path(field)) {
            String id = value.asText().strip();
            if (allowed.contains(id)) {
                kept.add(id);
            } else if (!id.isBlank()) {
                notes.add(notePrefix + " '" + id + "' — dropped");
            }
        }
        return kept;
    }

    private static boolean fileKnown(Jdbi jdbi, String repo, String path) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM java_type t
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r AND t.file_path = :p""")
                .bind("r", repo).bind("p", path).mapTo(Integer.class).one()) > 0;
    }

    static String composeInput(Jdbi jdbi, NormalizedSpec spec, ImpactResult result,
                               List<ExecutionOrder.Unit> order, String priorQa) {
        StringBuilder input = new StringBuilder("# Specification\n\n");
        input.append(SpecRenderer.render(spec));
        input.append("\n# Impact\n\n");
        for (AffectedRepo repo : result.affected()) {
            input.append("- ").append(repo.repo()).append(" | ").append(repo.role())
                    .append(" | ").append(repo.annotation())
                    .append(" | covers: ").append(String.join(",", repo.covers()))
                    .append(" | why: ").append(String.join("; ", repo.reasons())).append('\n');
        }
        input.append("\n# Execution order\n\n");
        for (int i = 0; i < order.size(); i++) {
            ExecutionOrder.Unit unit = order.get(i);
            input.append(i + 1).append(". ").append(String.join(" + ", unit.repos()));
            if (unit.repos().size() > 1) {
                input.append(" (co-scheduled)");
            }
            input.append('\n');
        }
        input.append("\n# Knowledge-base evidence\n");
        // The spec's vocabulary PLUS the simple names of whatever the analysis anchored on.
        // That second half is the point: ranking previously promoted only rows the spec named, so a
        // type the developer had not yet identified — the implementor of a changed interface, a
        // type touched by a --since commit — ranked past the budget and was never shown, which is
        // precisely the case where the plan most needed it. An anchor supplies the name without
        // requiring the human to already know the answer.
        Set<String> specTerms = new HashSet<>(salientTerms(spec));
        for (String fqcn : result.anchorTypes()) {
            specTerms.add(lower(fqcn.substring(fqcn.lastIndexOf('.') + 1)));
        }
        for (AffectedRepo repo : result.affected()) {
            // The repo's own impact reasons carry the touchpoints and hits that put it in the plan,
            // so they rank its evidence alongside the spec's own vocabulary.
            Set<String> terms = new HashSet<>(specTerms);
            terms.addAll(tokens(String.join(" ", repo.reasons())));
            input.append("\n## ").append(repo.repo()).append('\n');
            input.append(evidence(jdbi, repo.repo(), terms,
                    "BUMP_REBUILD_ONLY".equals(repo.annotation())));
        }
        if (!priorQa.isBlank()) {
            input.append("\n# Prior questions and human resolutions\n\n").append(priorQa);
        }
        return input.toString();
    }

    /**
     * Evidence for one repo, ranked by what the spec is actually about.
     *
     * <p>The budgets below are unchanged; what changed is which rows fill them. They used to be
     * filled by whatever sorted first, which on any real package is an accident: trading-web-sdk
     * has 260 api_members, so an alphabetical window of 40 ends in the C's and never reaches the
     * type the plan is being written about. A model cannot declare a member it was never shown,
     * and the prompt tells it to omit rather than guess — so undeclared contracts were the
     * predictable outcome rather than model reticence.
     *
     * <p>Ranking is a stable partition, not a score: rows the spec names come first in their
     * existing order, everything else follows in its existing order. That keeps the output a
     * deterministic function of the knowledge base and the spec — which it has to be, because
     * {@code sdd plan approve} hashes the plan.md this text produces. It also means
     * {@link #EVIDENCE_CAP} now truncates the least relevant lines rather than the last ones
     * alphabetically.
     */
    private static String evidence(Jdbi jdbi, String repo, Set<String> terms, boolean rebuildOnly) {
        int typeCap = rebuildOnly ? REBUILD_ONLY_CAP : TYPE_CAP;
        int memberCap = rebuildOnly ? 0 : MEMBER_CAP;
        int endpointCap = rebuildOnly ? 0 : ENDPOINT_CAP;
        StringBuilder evidence = new StringBuilder();
        jdbi.useHandle(h -> {
            StringBuilder types = new StringBuilder();
            List<Map<String, Object>> typeRows = h.createQuery("""
                            SELECT t.fqcn AS fqcn, t.kind AS kind, t.file_path AS path,
                                   t.language AS lang
                            FROM java_type t
                            JOIN module m ON m.id = t.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r ORDER BY t.is_api DESC, t.fqcn LIMIT 400""")
                    .bind("r", repo).mapToMap().list();
            for (Map<String, Object> row : ranked(typeRows, TYPE_BUDGET,
                    row -> names(terms, String.valueOf(row.get("fqcn"))))) {
                String fqcn = String.valueOf(row.get("fqcn"));
                types.append("- ").append(isTypeScript(row) ? TsNames.address(fqcn) : fqcn)
                        .append(" (").append(row.get("kind"))
                        .append(") @ ").append(row.get("path")).append('\n');
            }
            appendCapped(evidence, types, typeCap, "types");

            StringBuilder members = new StringBuilder();
            List<Map<String, Object>> memberRows = h.createQuery("""
                            SELECT jt.fqcn AS fqcn, am.name AS mname, am.signature AS sig,
                                   am.return_type AS ret, jt.language AS lang
                            FROM api_member am
                            JOIN java_type jt ON jt.id = am.type_id
                            JOIN module m ON m.id = jt.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r
                            ORDER BY jt.is_api DESC, jt.fqcn, am.signature LIMIT 4000""")
                    .bind("r", repo).mapToMap().list();
            for (Map<String, Object> row : ranked(memberRows, MEMBER_BUDGET,
                    row -> names(terms, String.valueOf(row.get("fqcn")))
                            || terms.contains(lower(String.valueOf(row.get("mname")))))) {
                String fqcn = String.valueOf(row.get("fqcn"));
                String sig = String.valueOf(row.get("sig"));
                // A declaration is copied from this line, so it has to BE a declaration. The
                // java-api template transposes both separators for TypeScript — see TsNames.
                String left = isTypeScript(row)
                        ? TsNames.memberAddress(fqcn, String.valueOf(row.get("mname")), sig)
                        : fqcn + "#" + sig;
                members.append("- ").append(left).append(": ").append(row.get("ret")).append('\n');
            }
            appendCapped(evidence, members, memberCap, "members");

            StringBuilder endpoints = new StringBuilder();
            for (Map<String, Object> row : h.createQuery("""
                            SELECT e.http_method AS verb, e.norm_path AS norm,
                                   e.request_type AS req, e.response_type AS res
                            FROM rest_endpoint e
                            JOIN module m ON m.id = e.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r ORDER BY e.norm_path, e.http_method LIMIT :cap""")
                    .bind("r", repo).bind("cap", ENDPOINT_BUDGET).mapToMap().list()) {
                endpoints.append("- ").append(row.get("verb")).append(' ').append(row.get("norm"))
                        .append(" req=").append(row.get("req")).append(" res=").append(row.get("res"))
                        .append('\n');
            }
            appendCapped(evidence, endpoints, endpointCap, "endpoints");
        });
        return evidence.toString();
    }

    /**
     * Appends one evidence section under its own character cap.
     *
     * <p>Sections used to share a single cap applied to the concatenation, which meant whichever
     * rendered last was the one that vanished: on a repo with a wide type surface the endpoint
     * section never appeared at all, silently, and exactly for the repos most likely to own an API.
     * A per-section cap cannot express that failure. The marker names its section so a truncated
     * section is legible as truncated rather than as empty — absence and truncation must never look
     * the same to the reader, human or model.
     */
    private static void appendCapped(StringBuilder out, StringBuilder section, int cap, String label) {
        if (cap <= 0) {
            // A rebuild-only repo omits this section entirely. Saying so beats an empty block,
            // which a reader cannot distinguish from "this repo has no members".
            if (section.length() > 0) {
                out.append("…(").append(label).append(" omitted — rebuild only)\n");
            }
            return;
        }
        if (section.length() <= cap) {
            out.append(section);
            return;
        }
        // Cut on a line boundary: half a rendered line is a line a model can copy and get wrong.
        int cut = section.lastIndexOf("\n", cap);
        out.append(section, 0, cut < 0 ? cap : cut + 1)
                .append("…(").append(label).append(" truncated)\n");
    }

    /** A row's language, tolerant of the NULL a pre-npm knowledge base carries on every row. */
    private static boolean isTypeScript(Map<String, Object> row) {
        return "TYPESCRIPT".equals(row.get("lang"));
    }


    /** Rows the spec names, in their existing order, then the rest in theirs — capped at budget. */
    private static List<Map<String, Object>> ranked(List<Map<String, Object>> rows, int budget,
                                                    Predicate<Map<String, Object>> relevant) {
        List<Map<String, Object>> named = new ArrayList<>();
        List<Map<String, Object>> rest = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            (relevant.test(row) ? named : rest).add(row);
        }
        named.addAll(rest);
        return named.size() <= budget ? named : new ArrayList<>(named.subList(0, budget));
    }

    /**
     * Whether the spec names this type, by its simple name.
     *
     * <p>Exact identifier match, case-insensitively — a spec that means a type almost always writes
     * its name. Deliberately NOT fuzzy: this decides what a model is shown, and a loose match would
     * fill the budget with near-misses, which is the failure being fixed rather than a milder
     * version of it. The simple name is everything after the last dot, which is the class for Java
     * and the export for TypeScript.
     */
    private static boolean names(Set<String> terms, String fqcn) {
        return terms.contains(lower(fqcn.substring(fqcn.lastIndexOf('.') + 1)));
    }

    /** Every identifier-shaped token in the spec: its prose, its IDs' text, and its touchpoints. */
    static Set<String> salientTerms(NormalizedSpec spec) {
        StringBuilder text = new StringBuilder(spec.title()).append(' ')
                .append(spec.goal()).append(' ').append(spec.background());
        for (SpecItem item : spec.requirements()) {
            text.append(' ').append(item.text());
        }
        for (SpecItem item : spec.acceptance()) {
            text.append(' ').append(item.text());
        }
        for (SpecItem item : spec.constraints()) {
            text.append(' ').append(item.text());
        }
        for (Touchpoint touchpoint : spec.touchpoints()) {
            text.append(' ').append(touchpoint.value());
        }
        return tokens(text.toString());
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.split("[^A-Za-z0-9_]+")) {
            if (token.length() >= 3) {
                tokens.add(lower(token));
            }
        }
        return tokens;
    }

    private static String lower(String s) {
        return s.toLowerCase(java.util.Locale.ROOT);
    }
}
