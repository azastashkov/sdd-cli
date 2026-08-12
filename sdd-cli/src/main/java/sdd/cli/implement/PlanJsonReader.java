package sdd.cli.implement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses an approved plan.json into a {@link PlanModel}. Tree-based on purpose: the writer's records
 * are package-private in sdd-plan and there is no jackson parameter-names module on the classpath,
 * so record data-binding by component name would not work. Field names are literal snake_case.
 */
public final class PlanJsonReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanJsonReader() {
    }

    public static PlanModel read(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("plan.json is not valid JSON: " + e.getOriginalMessage());
        }
        List<PlanModel.PlanRepo> repos = new ArrayList<>();
        for (JsonNode r : root.path("repos")) {
            repos.add(new PlanModel.PlanRepo(text(r, "name"), text(r, "role"), text(r, "annotation"),
                    text(r, "version_action"), text(r, "base_sha")));
        }
        List<List<String>> order = new ArrayList<>();
        for (JsonNode group : root.path("order")) {
            order.add(strings(group));
        }
        List<PlanModel.PlanEdge> edges = new ArrayList<>();
        for (JsonNode e : root.path("edges")) {
            edges.add(new PlanModel.PlanEdge(text(e, "from_repo"), text(e, "to_repo"),
                    text(e, "mode"), text(e, "mechanism")));
        }
        List<PlanModel.PlanContract> contracts = new ArrayList<>();
        for (JsonNode c : root.path("contracts")) {
            contracts.add(new PlanModel.PlanContract(text(c, "id"), text(c, "kind"), text(c, "provider"),
                    strings(c.path("consumers")), text(c, "body")));
        }
        List<PlanModel.PlanStep> steps = new ArrayList<>();
        for (JsonNode s : root.path("steps")) {
            steps.add(new PlanModel.PlanStep(text(s, "repo"), strings(s.path("covers")),
                    text(s, "version_action"), strings(s.path("provides")), strings(s.path("consumes")),
                    strings(s.path("files")), strings(s.path("verification")), text(s, "sub_spec")));
        }
        return new PlanModel(text(root, "spec_id"), root.path("plan_version").asInt(),
                text(root, "spec_sha256"), text(root, "plan_sha256"),
                repos, order, edges, contracts, steps);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asText());
        }
        return out;
    }
}
