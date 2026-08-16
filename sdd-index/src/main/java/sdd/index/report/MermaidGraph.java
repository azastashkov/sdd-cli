package sdd.index.report;

import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the knowledge base's estate graph as Mermaid (design amendment 2026-08-12).
 * Read-only, model-free, deterministic: same KB => byte-identical output.
 *
 * <p>Two independent things are encoded on a repo node, and keeping them independent is the point:
 * FILL says what the repo is (service, library, both, unknown) and SHAPE says which ecosystem
 * builds it. Overloading fill with both would make a mixed-ecosystem estate unreadable, and the
 * questions "is this a library?" and "is this npm?" are asked separately.
 *
 * <p>Dependency edges carry the consumption mode whatever the ecosystem; REST and Kafka links use
 * distinct arrow styles so the relationship families read apart at a glance.
 */
public final class MermaidGraph {

    private MermaidGraph() {
    }

    public static String render(Jdbi jdbi) {
        StringBuilder md = new StringBuilder("graph LR\n");
        md.append("  classDef service fill:#1f6feb,color:#ffffff\n");
        md.append("  classDef library fill:#2da44e,color:#ffffff\n");
        md.append("  classDef unknown fill:#6e7781,color:#ffffff\n");
        // rollupKind has always been able to return MIXED — a repo that both publishes a library
        // and deploys a service — and without a class of its own it fell through to `unknown`,
        // which reads as "we could not tell" rather than "it is both". A workspaces monorepo makes
        // that common rather than rare.
        md.append("  classDef mixed fill:#8250df,color:#ffffff\n");

        Map<String, String> idOf = new LinkedHashMap<>();
        jdbi.useHandle(h -> {
            for (Map<String, Object> row : h.createQuery(
                            "SELECT name, kind, build_system FROM repo ORDER BY name")
                    .mapToMap().list()) {
                String name = String.valueOf(row.get("name"));
                String kind = String.valueOf(row.get("kind"));
                String styleClass = switch (kind) {
                    case "SERVICE" -> "service";
                    case "LIBRARY" -> "library";
                    case "MIXED" -> "mixed";
                    default -> "unknown";
                };
                String id = uniqueId(idOf, name);
                idOf.put(name, id);
                // Rounded for npm, square for everything else. Shape rather than colour so the
                // ecosystem is legible without spending the fill, which already means kind.
                boolean npm = "NPM".equals(String.valueOf(row.get("build_system")));
                md.append("  ").append(id).append(npm ? "(\"" : "[\"").append(name)
                        .append(npm ? "\")" : "\"]").append(":::")
                        .append(styleClass).append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rf.name AS consumer, rt.name AS provider, v.mode AS mode
                            FROM v_repo_dep_edge v
                            JOIN repo rf ON rf.id = v.from_repo_id
                            JOIN repo rt ON rt.id = v.to_repo_id
                            ORDER BY rf.name, rt.name, v.mode""")
                    .mapToMap().list()) {
                md.append("  ").append(idOf.get(String.valueOf(row.get("consumer"))))
                        .append(" -->|").append(label(String.valueOf(row.get("mode"))))
                        .append("| ").append(idOf.get(String.valueOf(row.get("provider"))))
                        .append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rc.name AS client, rp.name AS provider, ce.confidence AS confidence
                            FROM rest_call_edge ce
                            JOIN rest_client c ON c.id = ce.client_id
                            JOIN module mc ON mc.id = c.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            JOIN rest_endpoint e ON e.id = ce.endpoint_id
                            JOIN module mp ON mp.id = e.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            WHERE rc.name <> rp.name
                            ORDER BY rc.name, rp.name, ce.confidence""")
                    .mapToMap().list()) {
                md.append("  ").append(idOf.get(String.valueOf(row.get("client"))))
                        .append(" -.->|REST ").append(label(String.valueOf(row.get("confidence"))))
                        .append("| ").append(idOf.get(String.valueOf(row.get("provider"))))
                        .append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rp.name AS producer, rc.name AS consumer, t.name AS topic
                            FROM kafka_role prod
                            JOIN kafka_topic t ON t.id = prod.topic_id
                            JOIN module mp ON mp.id = prod.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            JOIN kafka_role cons ON cons.topic_id = prod.topic_id AND cons.role = 'CONSUMER'
                            JOIN module mc ON mc.id = cons.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            WHERE prod.role = 'PRODUCER' AND rp.name <> rc.name
                            ORDER BY rp.name, rc.name, t.name""")
                    .mapToMap().list()) {
                md.append("  ").append(idOf.get(String.valueOf(row.get("producer"))))
                        .append(" ==>|").append(label(String.valueOf(row.get("topic"))))
                        .append("| ").append(idOf.get(String.valueOf(row.get("consumer"))))
                        .append('\n');
            }
        });
        return md.toString();
    }

    private static String uniqueId(Map<String, String> idOf, String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
        String candidate = base;
        int suffix = 2;
        while (idOf.containsValue(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private static String label(String value) {
        return value.replace("|", "/");
    }
}
