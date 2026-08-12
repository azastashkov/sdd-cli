package sdd.plan.approve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the approved plan + KB facts into the immutable run input (design M5): subgraph
 * edges with modes and LIVE-probed propagation mechanisms, base SHAs, and the SHA-256 pins
 * of both gate artifacts. Deterministic given its inputs and the probe results.
 */
public final class PlanJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Root(String spec_id, int plan_version, String spec_sha256, String plan_sha256,
                List<Repo> repos, List<List<String>> order, List<Edge> edges,
                List<Contract> contracts, List<Step> steps) {
    }

    record Repo(String name, String role, String annotation, String version_action, String base_sha) {
    }

    record Edge(String from_repo, String to_repo, String mode, String mechanism) {
    }

    record Contract(String id, String kind, String provider, List<String> consumers, String body) {
    }

    record Step(String repo, List<String> covers, String version_action, List<String> provides,
                List<String> consumes, List<String> files, List<String> verification, String sub_spec) {
    }

    private PlanJson() {
    }

    public static String compile(Jdbi jdbi, PlanDocument plan, String specSha, String planSha,
                                 SmokeRunner smoke, List<String> warningsOut) {
        Set<String> names = new LinkedHashSet<>();
        for (PlanDocument.PlanRepo repo : plan.affected()) {
            names.add(repo.repo());
        }
        Map<String, String> heads = new HashMap<>();
        Map<String, String> paths = new HashMap<>();
        jdbi.useHandle(h -> h.createQuery("SELECT name, path, head_commit FROM repo").mapToMap()
                .forEach(row -> {
                    heads.put(String.valueOf(row.get("name")),
                            row.get("head_commit") == null ? "" : String.valueOf(row.get("head_commit")));
                    paths.put(String.valueOf(row.get("name")), String.valueOf(row.get("path")));
                }));
        Map<String, String> versionActions = new HashMap<>();
        for (PlanDocument.PlanStep step : plan.steps()) {
            versionActions.put(step.repo(), step.versionAction());
        }

        List<Repo> repos = new ArrayList<>();
        for (PlanDocument.PlanRepo repo : plan.affected()) {
            repos.add(new Repo(repo.repo(), repo.role(), repo.annotation(),
                    versionActions.getOrDefault(repo.repo(), "none"),
                    heads.getOrDefault(repo.repo(), "")));
        }

        List<Edge> edges = new ArrayList<>();
        Map<String, SmokeRunner.Result> probeCache = new HashMap<>();
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT rf.name AS from_repo, rt.name AS to_repo, v.mode AS mode
                        FROM v_repo_dep_edge v
                        JOIN repo rf ON rf.id = v.from_repo_id
                        JOIN repo rt ON rt.id = v.to_repo_id
                        ORDER BY rf.name, rt.name, v.mode""")
                .mapToMap().list());
        for (Map<String, Object> row : rows) {
            String from = String.valueOf(row.get("from_repo"));
            String to = String.valueOf(row.get("to_repo"));
            if (!names.contains(from) || !names.contains(to)) {
                continue;
            }
            String mode = String.valueOf(row.get("mode"));
            String mechanism;
            if ("COMPOSITE".equals(mode)) {
                mechanism = "NONE";
            } else {
                String key = from + "->" + to;
                SmokeRunner.Result result = probeCache.computeIfAbsent(key, k ->
                        smoke.probe(Path.of(paths.get(from)), Path.of(paths.get(to))));
                if (result.ok()) {
                    mechanism = "INCLUDE_BUILD";
                } else {
                    mechanism = "MAVEN_LOCAL";
                    String warning = "edge " + from + "->" + to + ": include-build probe failed ("
                            + result.detail() + ") — falling back to mavenLocal";
                    if (!warningsOut.contains(warning)) {
                        warningsOut.add(warning);
                    }
                }
            }
            edges.add(new Edge(from, to, mode, mechanism));
        }

        List<Contract> contracts = new ArrayList<>();
        for (PlanDocument.PlanContract contract : plan.contracts()) {
            contracts.add(new Contract(contract.id(), contract.kind(), contract.provider(),
                    contract.consumers(), contract.body()));
        }
        List<Step> steps = new ArrayList<>();
        for (PlanDocument.PlanStep step : plan.steps()) {
            steps.add(new Step(step.repo(), step.covers(), step.versionAction(), step.provides(),
                    step.consumes(), step.files(), step.verification(), step.subSpec()));
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(new Root(
                    plan.specId(), plan.planVersion(), specSha, planSha, repos, plan.order(),
                    edges, contracts, steps)) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
