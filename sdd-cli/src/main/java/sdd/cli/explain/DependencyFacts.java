package sdd.cli.explain;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.ContractEdges;
import sdd.core.kb.EntityKind;
import sdd.core.kb.KbEntities;
import sdd.core.kb.Resolution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code dependency_path} intent: a BFS shortest path over {@code v_repo_dep_edge} from the
 * subject entity's repo(s) to the object entity's repo(s), plus per-hop {@code dep_edge} detail,
 * {@code api_usage} evidence between each hop's modules, and contract edges (REST/Kafka) touching
 * either side.
 *
 * <p><strong>No path is always an explicit fact, never a fabricated reason.</strong> BFS marks
 * every subject repo visited before it explores a single edge, so a target reached only by
 * starting there (the subject and object resolve to the same repo — a repo trivially has no
 * dependency path to itself) is never mistaken for a zero-hop "path": a node is only accepted as
 * the destination when it is discovered by <em>following an edge into it</em>, never at the
 * moment it is seeded as a start. The graph is a plain repo dependency structure that can
 * genuinely contain cycles (the estate has SCCs — see {@code Closure}'s Tarjan pass) but BFS with
 * a single shared {@code visited} set is cycle-safe by construction: each repo is enqueued at
 * most once, so a cycle just stops the walk from revisiting it rather than looping.
 */
final class DependencyFacts {
    private DependencyFacts() {
    }

    static List<Section> of(Jdbi jdbi, RetrievalRequest request) {
        EntityRef subject = find(request.entities(), false);
        EntityRef object = find(request.entities(), true);
        Resolution subjectRes = KbEntities.resolve(jdbi, subject.kind(), subject.value());
        Resolution objectRes = KbEntities.resolve(jdbi, object.kind(), object.value());

        List<Section> sections = new ArrayList<>();
        if (subject.kind() != EntityKind.REPO) {
            sections.add(EvidenceCollector.citation(subject, subjectRes));
        }
        if (object.kind() != EntityKind.REPO) {
            sections.add(EvidenceCollector.citation(object, objectRes));
        }

        List<String> fromRepos = subjectRes.repos();
        List<String> toRepos = objectRes.repos();
        Set<String> toSet = new HashSet<>(toRepos);

        List<String> path = jdbi.withHandle(h -> bfs(h, fromRepos, toSet));

        if (path == null) {
            sections.add(Section.of("Dependency path", "v_repo_dep_edge", List.of(new Fact(
                    "no internal Gradle dependency path from " + subject.value() + " to "
                            + object.value() + " in the knowledge base"))));
        } else {
            sections.add(Section.of("Dependency path", "v_repo_dep_edge",
                    List.of(new Fact(String.join(" -> ", path)))));
            sections.add(jdbi.withHandle(h -> hopDetail(h, path)));
            sections.add(jdbi.withHandle(h -> apiUsage(h, path)));
        }
        sections.add(restEdges(jdbi, fromRepos, toRepos));
        sections.add(kafkaEdges(jdbi, fromRepos, toRepos));
        return sections;
    }

    private static EntityRef find(List<EntityRef> entities, boolean object) {
        return entities.stream().filter(e -> e.object() == object).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "dependency_path request missing its " + (object ? "object" : "subject")
                                + " entity — QuestionInterpreter's invariant was violated"));
    }

    /**
     * Multi-source BFS over the repo-level dependency graph (consumer -&gt; provider). Sources
     * are seeded in {@code fromRepos}' order (already sorted by {@link Resolution#repos()}) and
     * every adjacency list is sorted by neighbor name, so ties resolve deterministically. Returns
     * {@code null} when no target repo is reachable — including when {@code fromRepos} and
     * {@code toRepos} overlap, since a start node is only ever accepted as a destination via an
     * incoming edge, never by being a start node itself.
     */
    private static List<String> bfs(Handle h, List<String> fromRepos, Set<String> toRepos) {
        Map<String, List<String>> adjacency = adjacency(h);
        Set<String> visited = new HashSet<>();
        Deque<List<String>> queue = new ArrayDeque<>();
        for (String start : fromRepos) {
            if (visited.add(start)) {
                queue.addLast(List.of(start));
            }
        }
        while (!queue.isEmpty()) {
            List<String> path = queue.removeFirst();
            String node = path.get(path.size() - 1);
            for (String neighbor : adjacency.getOrDefault(node, List.of())) {
                if (!visited.add(neighbor)) {
                    continue;
                }
                List<String> next = new ArrayList<>(path);
                next.add(neighbor);
                if (toRepos.contains(neighbor)) {
                    return next;
                }
                queue.addLast(next);
            }
        }
        return null;
    }

    private static Map<String, List<String>> adjacency(Handle h) {
        List<Map<String, Object>> rows = h.createQuery("""
                        SELECT rf.name AS from_name, rt.name AS to_name
                        FROM v_repo_dep_edge v
                        JOIN repo rf ON rf.id = v.from_repo_id
                        JOIN repo rt ON rt.id = v.to_repo_id
                        ORDER BY rf.name, rt.name""")
                .mapToMap().list();
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            adjacency.computeIfAbsent(String.valueOf(row.get("from_name")), k -> new ArrayList<>())
                    .add(String.valueOf(row.get("to_name")));
        }
        return adjacency;
    }

    private static Section hopDetail(Handle h, List<String> path) {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            String from = path.get(i);
            String to = path.get(i + 1);
            List<Map<String, Object>> rows = h.createQuery("""
                            SELECT e.to_grp AS to_grp, e.to_name AS to_name,
                                   e.configuration AS configuration, e.declared_version AS declared_version,
                                   e.declared_via AS declared_via, e.mode AS mode
                            FROM dep_edge e
                            JOIN module mf ON mf.id = e.from_module_id
                            JOIN module mt ON mt.id = e.to_module_id
                            JOIN repo rf ON rf.id = mf.repo_id
                            JOIN repo rt ON rt.id = mt.repo_id
                            WHERE rf.name = :from AND rt.name = :to AND e.is_internal = 1
                            ORDER BY e.to_grp, e.to_name, e.configuration""")
                    .bind("from", from).bind("to", to).mapToMap().list();
            for (Map<String, Object> row : rows) {
                facts.add(new Fact(from + " -> " + to + ": " + row.get("to_grp") + ":" + row.get("to_name")
                        + " (configuration=" + row.get("configuration")
                        + ", declared_version=" + row.get("declared_version")
                        + ", declared_via=" + row.get("declared_via")
                        + ", mode=" + row.get("mode") + ")"));
            }
        }
        return Section.capped("Dependency edges", "dep_edge", facts, Section.DEFAULT_LIMIT);
    }

    private static Section apiUsage(Handle h, List<String> path) {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < path.size() - 1; i++) {
            String from = path.get(i);
            String to = path.get(i + 1);
            List<Map<String, Object>> rows = h.createQuery("""
                            SELECT DISTINCT u.target_fqcn AS target_fqcn, u.ref_kind AS ref_kind
                            FROM api_usage u
                            JOIN module mf ON mf.id = u.from_module_id
                            JOIN module mt ON mt.id = u.target_module_id
                            JOIN repo rf ON rf.id = mf.repo_id
                            JOIN repo rt ON rt.id = mt.repo_id
                            WHERE rf.name = :from AND rt.name = :to
                            ORDER BY u.target_fqcn""")
                    .bind("from", from).bind("to", to).mapToMap().list();
            for (Map<String, Object> row : rows) {
                facts.add(new Fact(from + " -> " + to + ": " + row.get("target_fqcn")
                        + " (" + row.get("ref_kind") + ")"));
            }
        }
        return Section.capped("API usage", "api_usage", facts, Section.MEMBER_LIMIT);
    }

    /**
     * REST contract edges where both sides are among the repos in play (either resolved side of
     * the question) — relevant even (especially) when there is no internal Gradle path, since a
     * REST call is a real cross-repo relationship the dependency graph does not capture.
     */
    private static Section restEdges(Jdbi jdbi, List<String> fromRepos, List<String> toRepos) {
        Set<String> inPlay = new HashSet<>(fromRepos);
        inPlay.addAll(toRepos);
        List<Fact> facts = new ArrayList<>();
        for (ContractEdges.RestEdge edge : ContractEdges.rest(jdbi)) {
            if (inPlay.contains(edge.consumerRepo()) && inPlay.contains(edge.providerRepo())) {
                facts.add(new Fact(edge.consumerRepo() + " calls " + edge.verb() + " " + edge.normPath()
                        + " on " + edge.providerRepo() + " (confidence=" + edge.confidence()
                        + ", matched_by=" + edge.matchedBy() + ")"));
            }
        }
        return Section.capped("REST calls (contract)", "rest_call_edge", facts, Section.DEFAULT_LIMIT);
    }

    private static Section kafkaEdges(Jdbi jdbi, List<String> fromRepos, List<String> toRepos) {
        Set<String> inPlay = new HashSet<>(fromRepos);
        inPlay.addAll(toRepos);
        List<Fact> facts = new ArrayList<>();
        for (ContractEdges.KafkaEdge edge : ContractEdges.kafka(jdbi)) {
            if (inPlay.contains(edge.producerRepo()) && inPlay.contains(edge.consumerRepo())) {
                facts.add(new Fact(edge.producerRepo() + " produces " + edge.topic()
                        + " consumed by " + edge.consumerRepo()));
            }
        }
        return Section.capped("Kafka topics (contract)", "kafka_role", facts, Section.DEFAULT_LIMIT);
    }
}
