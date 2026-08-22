package sdd.plan.approve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.Question;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The estate graph as a file OpenSpec ignores.
 *
 * <p>OpenSpec has nowhere to put what sdd knows. Its own documentation says so — "OpenSpec currently
 * does not route tasks to repos" — and there is no cross-repo ordering primitive in the format at
 * all. So when the workspace becomes an OpenSpec project, the affected set, the execution order, the
 * dependency edges with their probed mechanisms, the contracts and the per-repo steps move here,
 * beside the markdown rather than inside it. Extra files in a change directory are explicitly
 * tolerated by the validator, which is what makes this safe rather than a smuggling exercise.
 *
 * <p>Derived from {@code plan.json}'s own bytes rather than recompiled from the {@link PlanDocument}.
 * Two independent compilations of the same facts would drift, and the drift would be silent: this
 * file and that one would disagree about which repos are in the change while both looking correct.
 * Re-reading the JSON makes them the same data by construction, and makes the equivalence testable.
 *
 * <p>It also carries four things {@code plan.json} has always dropped on the floor — the summary,
 * the open questions with their blocking flags and human resolutions, the excluded candidates and
 * the generation notes. Those live only in {@code plan.md} today, which is why nothing after
 * approve can see them; a reviewer at Gate 2 cannot currently learn that a blocking question was
 * answered, or why a repo was excluded.
 *
 * <p>Generated, never hand-edited — exactly the status {@code plan.json} has.
 */
public final class EstateYaml {

    private static final ObjectMapper JSON = new ObjectMapper();

    private EstateYaml() {
    }

    /**
     * The estate at PLAN time, before anything is approved.
     *
     * <p>What makes the change directory self-contained: with this beside the markdown, everything
     * {@code approve} needs is inside {@code openspec/changes/<id>/} and nothing has to be found by
     * suffix arithmetic on a filename somewhere else. That is the step the workspace has to take
     * before the OpenSpec tree can be the primary artifact rather than a second view of one.
     *
     * <p>Three keys the approve-time render has are necessarily absent, and their absence is the
     * signal that this file is not yet approved: {@code edges} needs a live mechanism probe,
     * and both SHA pins are struck at Gate 1 over bytes that do not exist yet. Approve fills them
     * in and rewrites the file.
     *
     * <p>{@code spec} is a snapshot of the normalized specification, carried because the human-facing
     * markdown cannot express all of it — {@code ## Why} merges goal and background irreversibly,
     * and attachments and sources have nowhere to go in the format at all. A reader that guessed at
     * those from prose would silently drop the Jira sources the write-back depends on.
     */
    public static String fromDraft(NormalizedSpec spec, ImpactResult result,
            List<ExecutionOrder.Unit> order, List<Question> detectorQuestions,
            PlanDrafter.Draft draft, int planVersion, Map<String, String> baseShas) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spec_id", spec.id());
        out.put("plan_version", planVersion);
        out.put("approved", false);
        out.put("summary", draft.summary());
        out.put("questions", draftQuestions(detectorQuestions, draft));
        out.put("spec", specSnapshot(spec));
        out.put("repos", repos(result, draft, baseShas));
        out.put("excluded", List.of());
        out.put("order", order.stream().map(ExecutionOrder.Unit::repos).toList());
        out.put("contracts", contracts(draft));
        out.put("steps", steps(draft));
        out.put("notes", new ArrayList<>(draft.notes()));
        return dump(out);
    }

    private static List<Map<String, Object>> draftQuestions(List<Question> detectorQuestions,
            PlanDrafter.Draft draft) {
        List<Question> all = new ArrayList<>(detectorQuestions);
        all.addAll(draft.questions());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", i + 1);
            row.put("blocking", all.get(i).blocking());
            row.put("text", all.get(i).text());
            row.put("resolution", "");
            out.add(row);
        }
        return out;
    }

    /** Everything {@code NormalizedSpec} holds, so nothing has to be recovered from prose. */
    private static Map<String, Object> specSnapshot(NormalizedSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", spec.id());
        out.put("title", spec.title());
        out.put("owner", spec.owner());
        out.put("status", spec.status());
        out.put("goal", spec.goal());
        out.put("background", spec.background());
        out.put("requirements", specItems(spec.requirements()));
        out.put("acceptance", specItems(spec.acceptance()));
        out.put("constraints", specItems(spec.constraints()));
        out.put("touchpoints", spec.touchpoints().stream()
                .map(t -> Map.of("kind", t.kind().key(), "value", t.value())).toList());
        out.put("evidence", new ArrayList<>(spec.evidence()));
        out.put("out_of_scope", new ArrayList<>(spec.outOfScope()));
        out.put("open_questions", specItems(spec.openQuestions()));
        out.put("attachments", new ArrayList<>(spec.attachments()));
        out.put("sources", new ArrayList<>(spec.sources()));
        return out;
    }

    private static List<Map<String, Object>> specItems(List<SpecItem> items) {
        return items.stream().map(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i.id());
            row.put("text", i.text());
            return row;
        }).toList();
    }

    private static List<Map<String, Object>> repos(ImpactResult result, PlanDrafter.Draft draft,
            Map<String, String> baseShas) {
        Map<String, String> versionActions = new LinkedHashMap<>();
        draft.steps().forEach(step -> versionActions.put(step.repo(), step.versionAction()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AffectedRepo repo : result.affected()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", repo.repo());
            row.put("role", repo.role());
            row.put("annotation", repo.annotation());
            row.put("version_action", versionActions.getOrDefault(repo.repo(), "none"));
            row.put("base_sha", baseShas.getOrDefault(repo.repo(), ""));
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> contracts(PlanDrafter.Draft draft) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanDrafter.DraftContract contract : draft.contracts()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", contract.id());
            row.put("kind", contract.kind());
            row.put("provider", contract.provider());
            row.put("consumers", new ArrayList<>(contract.consumers()));
            row.put("body", contract.body());
            row.put("compat", contract.compat() == null ? "" : contract.compat());
            row.put("declared", new ArrayList<>(contract.declared()));
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> steps(PlanDrafter.Draft draft) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanDrafter.DraftStep step : draft.steps()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repo", step.repo());
            row.put("covers", new ArrayList<>(step.covers()));
            row.put("version_action", step.versionAction());
            row.put("provides", new ArrayList<>(step.providesContracts()));
            row.put("consumes", new ArrayList<>(step.consumesContracts()));
            row.put("files", new ArrayList<>(step.files()));
            row.put("verification", new ArrayList<>(step.verification()));
            row.put("sub_spec", step.subSpec());
            row.put("openspec", new ArrayList<>(step.openspec()));
            out.add(row);
        }
        return out;
    }

    /**
     * @param planJson the exact text {@link PlanJson#compile} produced for this approval
     * @param plan     the parsed plan.md, for the four fields the JSON does not carry
     */
    public static String render(String planJson, PlanDocument plan) {
        JsonNode root;
        try {
            root = JSON.readTree(planJson);
        } catch (Exception e) {
            throw new IllegalStateException("plan.json is not readable back: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spec_id", root.path("spec_id").asText(""));
        out.put("plan_version", root.path("plan_version").asInt());
        out.put("spec_sha256", root.path("spec_sha256").asText(""));
        out.put("plan_sha256", root.path("plan_sha256").asText(""));
        out.put("summary", plan.summary());
        out.put("questions", questions(plan));
        out.put("repos", listOfMaps(root.path("repos")));
        out.put("excluded", excluded(plan));
        out.put("order", nestedStrings(root.path("order")));
        out.put("edges", listOfMaps(root.path("edges")));
        out.put("contracts", listOfMaps(root.path("contracts")));
        out.put("steps", listOfMaps(root.path("steps")));
        out.put("notes", new ArrayList<>(plan.notes()));
        return dump(out);
    }

    /**
     * The same document as JSON, so a reader can be the one that already exists.
     *
     * <p>{@code PlanJsonReader} is careful code — it tolerates a missing key, reads a
     * {@code MissingNode} as an empty list so a plan frozen before the OpenSpec export still
     * resumes, and is the only thing that knows how each field maps onto {@code PlanModel}. A
     * second parser for the same fields in YAML would be a second place for those decisions to
     * live, and they would diverge silently: a run would load one way from one file and another way
     * from the other. Converting instead makes the two readings the same reading.
     *
     * <p>This is also why sdd-cli takes no YAML dependency: the format stays known to exactly one
     * class, in the module that renders it.
     */
    public static String toJson(String estateYaml) {
        Object loaded = new Yaml().load(estateYaml);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("estate.yaml must be a mapping, got: "
                    + (loaded == null ? "an empty document" : loaded.getClass().getSimpleName()));
        }
        try {
            return JSON.writeValueAsString(loaded);
        } catch (Exception e) {
            throw new IllegalArgumentException("estate.yaml is not convertible: " + e.getMessage(), e);
        }
    }

    private static List<Map<String, Object>> questions(PlanDocument plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanDocument.PlanQuestion question : plan.questions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", question.number());
            row.put("blocking", question.blocking());
            row.put("text", question.text());
            // Empty, not absent and not null: a reader must be able to tell "asked and unanswered"
            // from "asked and answered with nothing", and PlanValidator blocks on the first.
            row.put("resolution", question.resolution() == null ? "" : question.resolution());
            out.add(row);
        }
        return out;
    }

    private static List<Map<String, Object>> excluded(PlanDocument plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanDocument.PlanExcluded row : plan.excluded()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("repo", row.repo());
            entry.put("detail", row.detail());
            out.add(entry);
        }
        return out;
    }

    /** Jackson's ObjectNode iterates in document order, so field order survives the round trip. */
    private static List<Map<String, Object>> listOfMaps(JsonNode array) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode element : array) {
            Map<String, Object> row = new LinkedHashMap<>();
            element.fields().forEachRemaining(field -> row.put(field.getKey(), plain(field.getValue())));
            out.add(row);
        }
        return out;
    }

    private static List<List<String>> nestedStrings(JsonNode array) {
        List<List<String>> out = new ArrayList<>();
        for (JsonNode inner : array) {
            List<String> unit = new ArrayList<>();
            inner.forEach(value -> unit.add(value.asText()));
            out.add(unit);
        }
        return out;
    }

    private static Object plain(JsonNode value) {
        if (value.isArray()) {
            List<Object> out = new ArrayList<>();
            value.forEach(element -> out.add(plain(element)));
            return out;
        }
        if (value.isInt() || value.isLong()) {
            return value.asLong();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.asText();
    }

    /**
     * Block style, 2-space indent, no line wrapping, insertion order preserved.
     *
     * <p>Every one of those is a determinism decision, not a formatting preference. Wrapping would
     * make a long contract body's line breaks a function of its length; flow style would collapse
     * short lists and not long ones; and a sorted or hashed key order would move bytes for no
     * reason. This file is compared byte-for-byte, the same way the OpenSpec export already is.
     */
    private static String dump(Map<String, Object> data) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(options).dump(data);
    }
}
