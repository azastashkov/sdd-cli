package sdd.plan.gen;

import org.jdbi.v3.core.Jdbi;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic Open-Questions detectors (design Component 2). The drafter's model-emitted
 * questions are appended by the renderer AFTER these; blocking resolution enforcement is 3C-2.
 */
public final class OpenQuestions {

    private OpenQuestions() {
    }

    public static List<Question> detect(Jdbi jdbi, ImpactResult result) {
        List<Question> questions = new ArrayList<>();
        for (String problem : result.problems()) {
            questions.add(new Question(problem, true));
        }
        for (String discrepancy : result.discrepancies()) {
            questions.add(new Question("model/graph discrepancy: " + discrepancy, false));
        }
        for (String warning : result.warnings()) {
            if (warning.contains("indexed with status")) {
                questions.add(new Question(warning, false));
            }
        }
        disconnectedSeeds(jdbi, result, questions);
        dynamicCallers(jdbi, result, questions);
        dynamicKafka(jdbi, result, questions);
        return questions;
    }

    private static void disconnectedSeeds(Jdbi jdbi, ImpactResult result, List<Question> questions) {
        Set<String> affected = new LinkedHashSet<>();
        for (AffectedRepo repo : result.affected()) {
            affected.add(repo.repo());
        }
        if (affected.size() < 2) {
            return;
        }
        Set<String> connected = new LinkedHashSet<>();
        jdbi.useHandle(h -> {
            for (Map<String, Object> row : h.createQuery("""
                            SELECT rf.name AS a, rt.name AS b FROM v_repo_dep_edge v
                            JOIN repo rf ON rf.id = v.from_repo_id
                            JOIN repo rt ON rt.id = v.to_repo_id""")
                    .mapToMap().list()) {
                markPair(connected, affected, row);
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rc.name AS a, rp.name AS b
                            FROM rest_call_edge ce
                            JOIN rest_client c ON c.id = ce.client_id
                            JOIN module mc ON mc.id = c.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            JOIN rest_endpoint e ON e.id = ce.endpoint_id
                            JOIN module mp ON mp.id = e.module_id
                            JOIN repo rp ON rp.id = mp.repo_id""")
                    .mapToMap().list()) {
                markPair(connected, affected, row);
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rp.name AS a, rc.name AS b
                            FROM kafka_role prod
                            JOIN module mp ON mp.id = prod.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            JOIN kafka_role cons ON cons.topic_id = prod.topic_id AND cons.role = 'CONSUMER'
                            JOIN module mc ON mc.id = cons.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            WHERE prod.role = 'PRODUCER'""")
                    .mapToMap().list()) {
                markPair(connected, affected, row);
            }
        });
        Set<String> lonely = new TreeSet<>();
        for (AffectedRepo repo : result.affected()) {
            if (repo.role().equals("seed") && !connected.contains(repo.repo())) {
                lonely.add(repo.repo());
            }
        }
        for (String repo : lonely) {
            questions.add(new Question(
                    "seed " + repo + " is disconnected from the rest of the affected set — verify the spec's scope",
                    false));
        }
    }

    private static void markPair(Set<String> connected, Set<String> affected, Map<String, Object> row) {
        String a = String.valueOf(row.get("a"));
        String b = String.valueOf(row.get("b"));
        if (!a.equals(b) && affected.contains(a) && affected.contains(b)) {
            connected.add(a);
            connected.add(b);
        }
    }

    private static void dynamicCallers(Jdbi jdbi, ImpactResult result, List<Question> questions) {
        List<String> affectedNames = result.affected().stream().map(AffectedRepo::repo).toList();
        if (affectedNames.isEmpty()) {
            return;
        }
        boolean anyEndpoints = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM rest_endpoint e
                        JOIN module m ON m.id = e.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name IN (<names>)""")
                .bindList("names", affectedNames).mapTo(Integer.class).one()) > 0;
        if (!anyEndpoints) {
            return;
        }
        int dynamic = jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM rest_client WHERE norm_path IS NULL").mapTo(Integer.class).one());
        if (dynamic > 0) {
            questions.add(new Question(dynamic + " unresolved (DYNAMIC) REST clients exist in the estate"
                    + " — callers of affected endpoints may be missing (see curation report)", false));
        }
    }

    private static void dynamicKafka(Jdbi jdbi, ImpactResult result, List<Question> questions) {
        List<String> affectedNames = result.affected().stream().map(AffectedRepo::repo).toList();
        if (affectedNames.isEmpty()) {
            return;
        }
        boolean anyKafka = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM kafka_role kr
                        JOIN module m ON m.id = kr.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name IN (<names>)""")
                .bindList("names", affectedNames).mapTo(Integer.class).one()) > 0;
        if (!anyKafka) {
            return;
        }
        int dynamic = jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM kafka_topic WHERE resolution = 'DYNAMIC'").mapTo(Integer.class).one());
        if (dynamic > 0) {
            questions.add(new Question(dynamic + " unresolved (DYNAMIC) Kafka topics exist in the estate"
                    + " — messaging links touching affected repos may be missing (see curation report)", false));
        }
    }
}
