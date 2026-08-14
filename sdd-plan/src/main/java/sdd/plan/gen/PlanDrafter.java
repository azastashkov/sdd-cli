package sdd.plan.gen;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.contract.Markdown;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plan-drafting model call (the only model call Phase 3C-1 adds). Output is validated
 * field-by-field against the affected set, the spec's requirement ids, and the KB — never
 * trusted. Total failure degrades to an unavailable Draft whose single BLOCKING question
 * tells the human to rerun; every deterministic plan.md section renders regardless.
 */
public final class PlanDrafter {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int EVIDENCE_CAP = 4000;
    private static final Set<String> VERSION_ACTIONS = Set.of("none", "patch", "minor", "major");
    private static final Set<String> CONTRACT_KINDS = Set.of("java-api", "rest", "kafka");
    static final String SYSTEM_PROMPT = """
            You draft the repo-by-repo implementation plan for a feature specification across a \
            multi-repo estate. You receive the spec, the impact analysis (which repos and why), \
            the execution order, and knowledge-base evidence (real classes, files, API members, \
            endpoints). Return exactly ONE JSON object, no markdown fences:
            {"summary": string,
             "questions": [{"text": string, "blocking": boolean}, ...],
             "contracts": [{"id": string, "kind": "java-api"|"rest"|"kafka", "provider": string,
                            "consumers": [string, ...], "body": string,
                            "compat": "binary-compatible" (OPTIONAL, java-api only — declare it \
            when consumers must keep binary compatibility),
                            "declarations": [string, ...] (OPTIONAL — the machine-checkable \
            members this contract exposes, one exact line per member, no prose, using EXACTLY \
            this grammar for the contract's kind: java-api "<fqcn>#<signature>: <returnType>" \
            e.g. "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier" \
            (the type before '#' MUST be fully qualified — it is matched against the extracted \
            type's full name); \
            rest "<METHOD> <path>" e.g. "GET /api/admin/tier-spreads"; kafka \
            "produces <topic>" or "consumes <topic>" e.g. "produces orders.v1". Omit this list, \
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
            if (compat != null && !"binary-compatible".equals(compat)) {
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
        for (AffectedRepo repo : result.affected()) {
            input.append("\n## ").append(repo.repo()).append('\n');
            input.append(evidence(jdbi, repo.repo()));
        }
        if (!priorQa.isBlank()) {
            input.append("\n# Prior questions and human resolutions\n\n").append(priorQa);
        }
        return input.toString();
    }

    private static String evidence(Jdbi jdbi, String repo) {
        StringBuilder evidence = new StringBuilder();
        jdbi.useHandle(h -> {
            for (Map<String, Object> row : h.createQuery("""
                            SELECT t.fqcn AS fqcn, t.kind AS kind, t.file_path AS path
                            FROM java_type t
                            JOIN module m ON m.id = t.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r ORDER BY t.is_api DESC, t.fqcn LIMIT 25""")
                    .bind("r", repo).mapToMap().list()) {
                evidence.append("- ").append(row.get("fqcn")).append(" (").append(row.get("kind"))
                        .append(") @ ").append(row.get("path")).append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT jt.fqcn AS fqcn, am.signature AS sig, am.return_type AS ret
                            FROM api_member am
                            JOIN java_type jt ON jt.id = am.type_id
                            JOIN module m ON m.id = jt.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r AND jt.is_api = 1
                            ORDER BY jt.fqcn, am.signature LIMIT 40""")
                    .bind("r", repo).mapToMap().list()) {
                evidence.append("- ").append(row.get("fqcn")).append('#').append(row.get("sig"))
                        .append(": ").append(row.get("ret")).append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT e.http_method AS verb, e.norm_path AS norm,
                                   e.request_type AS req, e.response_type AS res
                            FROM rest_endpoint e
                            JOIN module m ON m.id = e.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r ORDER BY e.norm_path, e.http_method LIMIT 25""")
                    .bind("r", repo).mapToMap().list()) {
                evidence.append("- ").append(row.get("verb")).append(' ').append(row.get("norm"))
                        .append(" req=").append(row.get("req")).append(" res=").append(row.get("res"))
                        .append('\n');
            }
        });
        if (evidence.length() > EVIDENCE_CAP) {
            evidence.setLength(EVIDENCE_CAP);
            evidence.append("…(truncated)\n");
        }
        return evidence.toString();
    }
}
