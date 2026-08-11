package sdd.index;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only: a canonical, scrubbed JSON dump of the entire schema, used by {@link
 * GoldenEstateTest} to pin the full knowledge base a fixed estate produces. Every table is dumped
 * verbatim via {@code SELECT *}, keyed by table name; volatile fields that vary run-to-run
 * (timestamps, absolute paths, commit/dirty hashes) are scrubbed first so the dump is stable
 * across machines and repeated runs of the same fixed fixture content.
 */
final class DbDump {
    /** Every table in the schema. Dump order here doesn't matter — Jackson sorts keys on output. */
    private static final List<String> TABLES = List.of(
            "repo", "module", "artifact", "dep_edge", "java_type", "api_member", "api_usage",
            "file_ref", "rest_endpoint", "rest_client", "rest_call_edge", "kafka_topic",
            "kafka_role", "config_property", "repo_card", "fts_symbol");

    private DbDump() {}

    static String canonicalJson(Jdbi jdbi, Path workspace) {
        Map<String, Object> dump = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("SELECT * FROM " + table)
                    .mapToMap().list());
            // Scrub first, then sort by the scrubbed row's string form: sorting must not see the
            // volatile values it is about to erase, or row order itself would become volatile.
            List<Map<String, Object>> scrubbed = rows.stream()
                    .map(row -> scrub(table, row, workspace))
                    .sorted(Comparator.comparing(String::valueOf))
                    .toList();
            dump.put(table, scrubbed);
        }
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(dump);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize DB dump", e);
        }
    }

    private static Map<String, Object> scrub(String table, Map<String, Object> row, Path workspace) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        switch (table) {
            case "repo" -> scrubRepo(out, workspace);
            case "repo_card" -> scrubRepoCard(out);
            default -> { }
        }
        return out;
    }

    /**
     * repo.path is an absolute, symlink-canonicalized path under the test's temp workspace — never
     * reproducible across runs — so it is replaced with just the repo's own name (already present
     * as the {@code name} column). head_commit/dirty_hash/indexed_at are git- and clock-derived; a
     * non-null error may embed the workspace's absolute path (e.g. from a Gradle failure message),
     * so only that prefix is replaced rather than the whole field.
     */
    private static void scrubRepo(Map<String, Object> row, Path workspace) {
        if (row.containsKey("path")) {
            row.put("path", row.get("name"));
        }
        for (String field : List.of("head_commit", "dirty_hash", "indexed_at")) {
            if (row.get(field) != null) {
                row.put(field, "<scrubbed>");
            }
        }
        Object error = row.get("error");
        if (error != null) {
            row.put("error", error.toString().replace(workspace.toString(), "<ws>"));
        }
    }

    private static void scrubRepoCard(Map<String, Object> row) {
        if (row.get("created_at") != null) {
            row.put("created_at", "<scrubbed>");
        }
    }
}
