package sdd.core.kb;

import org.jdbi.v3.core.Jdbi;

import java.util.List;

/**
 * Cross-repo contract edges: REST call matches (rest_client -&gt; rest_endpoint via
 * rest_call_edge) and Kafka producer/consumer pairs on the same topic. One definition shared by
 * sdd-plan's impact closure ({@code Closure.contracts}) and execution ordering
 * ({@code ExecutionOrder.edges}), which previously each re-issued this SQL and projected away
 * the detail columns (verb, path, confidence, matched-by, topic) that sdd explain needs.
 *
 * <p>{@code OpenQuestions.disconnectedSeeds} keeps its own copy rather than delegating here: its
 * projection is not filtered by {@code rc.name <> rp.name} / {@code rp.name <> rc.name} — it
 * needs same-repo pairs too, to tell whether a seed repo is connected to anything at all —
 * so forcing it through this shape would change its semantics.
 */
public final class ContractEdges {

    public record RestEdge(String consumerRepo, String providerRepo, String verb, String normPath,
                            String confidence, String matchedBy) {
    }

    public record KafkaEdge(String producerRepo, String consumerRepo, String topic) {
    }

    private ContractEdges() {
    }

    public static List<RestEdge> rest(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rc.name AS consumer_repo, rp.name AS provider_repo,
                               e.http_method AS verb, e.norm_path AS norm_path,
                               ce.confidence AS confidence, ce.matched_by AS matched_by
                        FROM rest_call_edge ce
                        JOIN rest_client c ON c.id = ce.client_id
                        JOIN module mc ON mc.id = c.module_id
                        JOIN repo rc ON rc.id = mc.repo_id
                        JOIN rest_endpoint e ON e.id = ce.endpoint_id
                        JOIN module mp ON mp.id = e.module_id
                        JOIN repo rp ON rp.id = mp.repo_id
                        WHERE rc.name <> rp.name
                        ORDER BY rc.name, rp.name""")
                .map((rs, ctx) -> new RestEdge(rs.getString("consumer_repo"), rs.getString("provider_repo"),
                        rs.getString("verb"), rs.getString("norm_path"), rs.getString("confidence"),
                        rs.getString("matched_by")))
                .list());
    }

    public static List<KafkaEdge> kafka(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rp.name AS producer_repo, rc.name AS consumer_repo, t.name AS topic
                        FROM kafka_role prod
                        JOIN kafka_topic t ON t.id = prod.topic_id
                        JOIN module mp ON mp.id = prod.module_id
                        JOIN repo rp ON rp.id = mp.repo_id
                        JOIN kafka_role cons ON cons.topic_id = prod.topic_id AND cons.role = 'CONSUMER'
                        JOIN module mc ON mc.id = cons.module_id
                        JOIN repo rc ON rc.id = mc.repo_id
                        WHERE prod.role = 'PRODUCER' AND rp.name <> rc.name
                        ORDER BY rp.name, rc.name""")
                .map((rs, ctx) -> new KafkaEdge(rs.getString("producer_repo"), rs.getString("consumer_repo"),
                        rs.getString("topic")))
                .list());
    }
}
