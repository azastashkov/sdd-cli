package sdd.cli.explain;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.ContractEdges;
import sdd.core.kb.EntityKind;
import sdd.core.kb.EntityMatch;
import sdd.core.kb.Resolution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code consumers} intent: "what depends on / calls / uses this thing", dispatched on the
 * resolved entity's {@link EntityKind}. One already-resolved {@link Resolution} in, its consumer
 * facts out — {@link EvidenceCollector} owns the loop over a request's several entities (and the
 * citation section for non-repo kinds), calling this once per entity, exactly like
 * {@code RepoFacts}/{@code DependencyFacts} do for their own intents.
 *
 * <p><strong>Endpoint ambiguity is never silently resolved.</strong> {@code Routes.templatesMatch}
 * (via {@code KbEntities.resolveEndpoint}) can legitimately match several {@code rest_endpoint}
 * rows across different repos for one value — every match's consumers are rendered, and when more
 * than one repo matched, an explicit ambiguity fact says so, rather than narrating "the" endpoint.
 */
final class ConsumerFacts {
    private ConsumerFacts() {
    }

    static List<Section> of(Jdbi jdbi, Resolution resolution) {
        return switch (resolution.kind()) {
            case REPO -> repoConsumers(jdbi, resolution.value());
            case ENDPOINT -> endpointConsumers(jdbi, resolution);
            case CLASS -> classConsumers(jdbi, resolution);
            case TOPIC -> topicRoles(jdbi, resolution.value());
            case ARTIFACT -> artifactConsumers(jdbi, resolution.value());
        };
    }

    // --- repo: inbound v_repo_dep_edge, inbound api_usage, contract edges ---------------------

    private static List<Section> repoConsumers(Jdbi jdbi, String repo) {
        List<Section> sections = new ArrayList<>();
        sections.add(gradleConsumers(jdbi, repo));
        sections.add(apiUsageConsumers(jdbi, repo));
        sections.add(restContractConsumers(jdbi, repo));
        sections.add(kafkaContractConsumers(jdbi, repo));
        return sections;
    }

    private static Section gradleConsumers(Jdbi jdbi, String repo) {
        List<String> names = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT r2.name AS name
                        FROM v_repo_dep_edge e
                        JOIN repo r1 ON r1.id = e.to_repo_id
                        JOIN repo r2 ON r2.id = e.from_repo_id
                        WHERE r1.name = :r ORDER BY r2.name""")
                .bind("r", repo).mapTo(String.class).list());
        List<Fact> facts = names.stream().map(Fact::new).toList();
        return Section.capped("Consumers via Gradle: " + repo, "v_repo_dep_edge", facts, Section.DEFAULT_LIMIT);
    }

    private static Section apiUsageConsumers(Jdbi jdbi, String repo) {
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rc.name AS consumer, u.target_fqcn AS target_fqcn, u.ref_kind AS ref_kind
                        FROM api_usage u
                        JOIN module mc ON mc.id = u.from_module_id
                        JOIN repo rc ON rc.id = mc.repo_id
                        JOIN module mt ON mt.id = u.target_module_id
                        JOIN repo rt ON rt.id = mt.repo_id
                        WHERE rt.name = :r
                        ORDER BY rc.name, u.target_fqcn""")
                .bind("r", repo).mapToMap().list());
        List<Fact> facts = rows.stream()
                .map(row -> new Fact(row.get("consumer") + " uses " + row.get("target_fqcn")
                        + " (" + row.get("ref_kind") + ")"))
                .toList();
        return Section.capped("Consumers via API usage: " + repo, "api_usage", facts, Section.MEMBER_LIMIT);
    }

    private static Section restContractConsumers(Jdbi jdbi, String repo) {
        List<Fact> facts = new ArrayList<>();
        for (ContractEdges.RestEdge edge : ContractEdges.rest(jdbi)) {
            if (edge.providerRepo().equals(repo)) {
                facts.add(new Fact(edge.consumerRepo() + " calls " + edge.verb() + " " + edge.normPath()
                        + " on " + edge.providerRepo() + " (confidence=" + edge.confidence()
                        + ", matched_by=" + edge.matchedBy() + ")"));
            }
        }
        return Section.capped("Consumers via REST (contract): " + repo, "rest_call_edge", facts, Section.DEFAULT_LIMIT);
    }

    private static Section kafkaContractConsumers(Jdbi jdbi, String repo) {
        List<Fact> facts = new ArrayList<>();
        for (ContractEdges.KafkaEdge edge : ContractEdges.kafka(jdbi)) {
            if (edge.producerRepo().equals(repo)) {
                facts.add(new Fact(edge.producerRepo() + " produces " + edge.topic()
                        + " consumed by " + edge.consumerRepo()));
            }
        }
        return Section.capped("Consumers via Kafka (contract): " + repo, "kafka_role", facts, Section.DEFAULT_LIMIT);
    }

    // --- endpoint: rest_call_edge rows for every resolved endpoint match ----------------------

    private static List<Section> endpointConsumers(Jdbi jdbi, Resolution resolution) {
        List<Section> sections = new ArrayList<>();
        if (new TreeSet<>(resolution.repos()).size() > 1) {
            sections.add(endpointAmbiguity(resolution));
        }
        List<Fact> facts = new ArrayList<>();
        for (EntityMatch match : resolution.matches()) {
            int space = match.detail().indexOf(' ');
            String verb = space > 0 ? match.detail().substring(0, space) : "ANY";
            String norm = space > 0 ? match.detail().substring(space + 1) : match.detail();
            facts.addAll(jdbi.withHandle(h -> callersOf(h, match.repo(), verb, norm)));
        }
        sections.add(Section.capped("Consumers of endpoint: " + resolution.value(), "rest_call_edge",
                facts, Section.DEFAULT_LIMIT));
        return sections;
    }

    private static Section endpointAmbiguity(Resolution resolution) {
        List<String> matchTexts = resolution.matches().stream()
                .map(m -> m.repo() + " (" + m.detail() + ")")
                .distinct()
                .toList();
        return Section.of("Endpoint match is ambiguous: '" + resolution.value() + "'", "rest_endpoint",
                List.of(new Fact("'" + resolution.value() + "' matched " + new TreeSet<>(resolution.repos()).size()
                        + " distinct repos: " + String.join(", ", matchTexts)
                        + " — every match's consumers are listed below, none silently chosen")));
    }

    private static List<Fact> callersOf(Handle h, String providerRepo, String verb, String norm) {
        List<Map<String, Object>> rows = h.createQuery("""
                        SELECT rc2.name AS consumer_repo, ce.confidence AS confidence, ce.matched_by AS matched_by
                        FROM rest_endpoint e
                        JOIN module m ON m.id = e.module_id
                        JOIN repo r ON r.id = m.repo_id
                        JOIN rest_call_edge ce ON ce.endpoint_id = e.id
                        JOIN rest_client c ON c.id = ce.client_id
                        JOIN module mc ON mc.id = c.module_id
                        JOIN repo rc2 ON rc2.id = mc.repo_id
                        WHERE r.name = :repo AND e.norm_path = :norm
                          AND ((:verb = 'ANY' AND e.http_method IS NULL) OR e.http_method = :verb)
                        ORDER BY rc2.name, ce.confidence, ce.matched_by""")
                .bind("repo", providerRepo).bind("norm", norm).bind("verb", verb).mapToMap().list();
        List<Fact> facts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            facts.add(new Fact(row.get("consumer_repo") + " calls " + verb + " " + norm + " on " + providerRepo
                    + " (confidence=" + row.get("confidence") + ", matched_by=" + row.get("matched_by") + ")"));
        }
        return facts;
    }

    // --- class: api_usage WHERE target_fqcn, grouped by consumer with ref_kind ----------------

    private static List<Section> classConsumers(Jdbi jdbi, Resolution resolution) {
        TreeSet<String> fqcns = new TreeSet<>();
        for (EntityMatch match : resolution.matches()) {
            fqcns.add(match.detail());
        }
        List<Fact> facts = new ArrayList<>();
        for (String fqcn : fqcns) {
            facts.addAll(jdbi.withHandle(h -> h.createQuery("""
                            SELECT DISTINCT r.name AS consumer, u.ref_kind AS ref_kind
                            FROM api_usage u
                            JOIN module m ON m.id = u.from_module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE u.target_fqcn = :fqcn
                            ORDER BY r.name, u.ref_kind""")
                    .bind("fqcn", fqcn).mapToMap().list()).stream()
                    .map(row -> new Fact(row.get("consumer") + " uses " + fqcn + " (" + row.get("ref_kind") + ")"))
                    .toList());
        }
        return List.of(Section.capped("Consumers of class: " + resolution.value(), "api_usage",
                facts, Section.MEMBER_LIMIT));
    }

    // --- topic: both roles, with group_id and payload_type ------------------------------------

    private static List<Section> topicRoles(Jdbi jdbi, String topic) {
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT r.name AS repo, kr.role AS role, kr.group_id AS group_id, kr.payload_type AS payload_type
                        FROM kafka_role kr
                        JOIN kafka_topic t ON t.id = kr.topic_id
                        JOIN module m ON m.id = kr.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE t.name = :t ORDER BY r.name, kr.role""")
                .bind("t", topic).mapToMap().list());
        List<Fact> facts = rows.stream()
                .map(row -> new Fact(row.get("repo") + " (" + row.get("role") + ")"
                        + (row.get("group_id") != null ? ", group_id=" + row.get("group_id") : "")
                        + (row.get("payload_type") != null ? ", payload_type=" + row.get("payload_type") : "")))
                .toList();
        return List.of(Section.capped("Topic roles: " + topic, "kafka_role", facts, Section.DEFAULT_LIMIT));
    }

    // --- artifact: dep_edge by to_grp/to_name --------------------------------------------------

    private static List<Section> artifactConsumers(Jdbi jdbi, String artifact) {
        int colon = artifact.indexOf(':');
        if (colon <= 0 || colon == artifact.length() - 1) {
            return List.of(Section.of("Consumers of artifact: " + artifact, "dep_edge", List.of()));
        }
        String grp = artifact.substring(0, colon);
        String name = artifact.substring(colon + 1);
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT r.name AS consumer, e.configuration AS configuration,
                               e.declared_version AS declared_version, e.mode AS mode
                        FROM dep_edge e
                        JOIN module m ON m.id = e.from_module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE e.to_grp = :g AND e.to_name = :n
                        ORDER BY r.name, e.configuration""")
                .bind("g", grp).bind("n", name).mapToMap().list());
        List<Fact> facts = rows.stream()
                .map(row -> new Fact(row.get("consumer") + " depends on " + artifact
                        + " (configuration=" + row.get("configuration")
                        + ", declared_version=" + row.get("declared_version")
                        + ", mode=" + row.get("mode") + ")"))
                .toList();
        return List.of(Section.capped("Consumers of artifact: " + artifact, "dep_edge", facts, Section.DEFAULT_LIMIT));
    }
}
