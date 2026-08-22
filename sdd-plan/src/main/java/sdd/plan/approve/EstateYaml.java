package sdd.plan.approve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
