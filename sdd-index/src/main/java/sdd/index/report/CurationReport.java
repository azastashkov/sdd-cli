package sdd.index.report;

import org.jdbi.v3.core.Jdbi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Markdown report of extraction data that needs human attention: unresolved/dynamic/ambiguous
 * REST and Kafka bindings, repos that failed or degraded, and internal libraries nothing depends
 * on. Purely a read over already-persisted tables — it never re-runs matching or linking.
 */
public final class CurationReport {
    /** Rows shown per section before collapsing the rest into "+N more". */
    private static final int PAGE_LIMIT = 200;

    /** SQLite GLOB for an absolute URI, mirroring {@code RestMatcher.ABSOLUTE_URL}'s regex. */
    private static final String ABSOLUTE_URL_GLOB = "'[a-z]*://*'";

    private CurationReport() {}

    public static Path write(Jdbi jdbi, Path workspace) {
        StringBuilder md = new StringBuilder();
        md.append("# Curation Report\n\n");

        appendSection(md, jdbi, "Unresolved REST clients", """
                        SELECT r.name AS repo, c.kind AS kind, c.method_or_site AS site,
                               c.raw_expr AS raw_expr
                        FROM rest_client c
                        JOIN module m ON m.id = c.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE c.resolution = 'DYNAMIC' OR c.norm_path IS NULL""",
                "r.name, c.method_or_site, c.id",
                row -> "- `%s` [%s] `%s` — `%s`".formatted(row.get("repo"), row.get("kind"),
                        row.get("site"), orDash(row.get("raw_expr"))));

        appendSection(md, jdbi, "Absolute-URL clients", """
                        SELECT r.name AS repo, c.uri_template AS uri_template
                        FROM rest_client c
                        JOIN module m ON m.id = c.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE c.uri_template GLOB """ + ABSOLUTE_URL_GLOB,
                "r.name, c.uri_template, c.id",
                row -> "- `%s` — `%s`".formatted(row.get("repo"), row.get("uri_template")));

        appendSection(md, jdbi, "Unmatched clients", """
                        SELECT r.name AS repo, c.kind AS kind, c.method_or_site AS site,
                               c.norm_path AS norm_path
                        FROM rest_client c
                        JOIN module m ON m.id = c.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE c.norm_path IS NOT NULL
                          AND (c.uri_template IS NULL OR c.uri_template NOT GLOB """
                        + ABSOLUTE_URL_GLOB + ")\n"
                        + "                  AND NOT EXISTS "
                        + "(SELECT 1 FROM rest_call_edge e WHERE e.client_id = c.id)",
                "r.name, c.method_or_site, c.id",
                row -> "- `%s` [%s] `%s` → `%s`".formatted(row.get("repo"), row.get("kind"),
                        row.get("site"), row.get("norm_path")));

        appendSection(md, jdbi, "Ambiguous matches (LOW)", """
                        SELECT r.name AS repo, c.norm_path AS path,
                               GROUP_CONCAT(DISTINCT er.name) AS candidates
                        FROM rest_call_edge e
                        JOIN rest_client c ON c.id = e.client_id
                        JOIN module m ON m.id = c.module_id
                        JOIN repo r ON r.id = m.repo_id
                        JOIN rest_endpoint ep ON ep.id = e.endpoint_id
                        JOIN module em ON em.id = ep.module_id
                        JOIN repo er ON er.id = em.repo_id
                        WHERE e.confidence = 'LOW'
                        GROUP BY c.id, r.name, c.norm_path""",
                "r.name, c.norm_path, c.id",
                row -> "- `%s`/`%s` → %s".formatted(row.get("repo"), row.get("path"),
                        row.get("candidates")));

        appendSection(md, jdbi, "Dynamic Kafka topics", """
                        SELECT r.name AS repo, kr.role AS role, kt.name AS name, kt.id AS topic_id
                        FROM kafka_topic kt
                        JOIN kafka_role kr ON kr.topic_id = kt.id
                        JOIN module m ON m.id = kr.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE kt.resolution = 'DYNAMIC'""",
                "r.name, kt.name, kr.role, kt.id",
                row -> "- `%s` [%s] `%s`".formatted(row.get("repo"), row.get("role"),
                        stripQuotes((String) row.get("name"))));

        appendSection(md, jdbi, "Unparsed stream modules", """
                        SELECT r.name AS repo, m.gradle_path AS gradle_path
                        FROM module m
                        JOIN repo r ON r.id = m.repo_id
                        WHERE m.kafka_status = 'UNPARSED_STREAM'""",
                "r.name, m.gradle_path, m.id",
                row -> "- `%s` `%s`".formatted(row.get("repo"), row.get("gradle_path")));

        appendSection(md, jdbi, "Repo problems", """
                        SELECT name, gradle_status, parse_status, error
                        FROM repo
                        WHERE (gradle_status IS NULL OR gradle_status != 'OK')
                           OR parse_status IN ('DEGRADED', 'FAILED')
                           OR error IS NOT NULL""",
                "name, id",
                row -> "- `%s` gradle=%s parse=%s — %s".formatted(row.get("name"),
                        orDash(row.get("gradle_status")), orDash(row.get("parse_status")),
                        firstLine((String) row.get("error"))));

        appendSection(md, jdbi, "Partial API confidence", """
                        SELECT r.name AS repo, COUNT(*) AS cnt
                        FROM java_type t
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE t.api_confidence = 'PARTIAL'
                        GROUP BY r.name""",
                "r.name",
                row -> "- `%s`: %s".formatted(row.get("repo"), row.get("cnt")));

        appendSection(md, jdbi, "Orphan artifacts", """
                        SELECT r.name AS repo, (a.grp || ':' || a.name) AS artifact, a.id AS artifact_id
                        FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE m.kind = 'LIBRARY'
                          AND NOT EXISTS (
                            SELECT 1 FROM dep_edge e
                            WHERE e.to_grp = a.grp AND e.to_name = a.name AND e.is_internal = 1)""",
                "r.name, artifact, a.id",
                row -> "- `%s` — `%s`".formatted(row.get("repo"), row.get("artifact")));

        md.append("---\n\n");
        md.append("_Generated: ").append(Instant.now()).append("_\n\n");
        md.append("Pin ambiguous/unmatched clients via `sdd.yml` `manual_edges`.\n");

        try {
            Files.createDirectories(workspace.resolve(".sdd"));
            Path out = workspace.resolve(".sdd/curation-report.md");
            Files.writeString(out, md.toString());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Renders one section: a plain query capped at {@code PAGE_LIMIT + 1} rows so a 201st row
     * signals "more exist" without a separate existence check; when it does, a count query over
     * the same (unordered, unlimited) {@code baseSql} supplies the real total for "+N more".
     * Omits the section header entirely when the query returns no rows.
     */
    private static void appendSection(StringBuilder md, Jdbi jdbi, String title, String baseSql,
                                       String orderBy, Function<Map<String, Object>, String> render) {
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery(
                        baseSql + "\nORDER BY " + orderBy + "\nLIMIT " + (PAGE_LIMIT + 1))
                .mapToMap().list());
        if (rows.isEmpty()) {
            return;
        }
        md.append("## ").append(title).append("\n\n");
        int shown = Math.min(PAGE_LIMIT, rows.size());
        for (int i = 0; i < shown; i++) {
            md.append(render.apply(rows.get(i))).append('\n');
        }
        if (rows.size() > PAGE_LIMIT) {
            int total = jdbi.withHandle(h -> h.createQuery(
                            "SELECT COUNT(*) FROM (" + baseSql + ")")
                    .mapTo(Integer.class).one());
            md.append("\n+").append(total - PAGE_LIMIT).append(" more\n");
        }
        md.append('\n');
    }

    private static String stripQuotes(String name) {
        return name == null ? null : name.replaceAll("^\"|\"$", "");
    }

    private static String orDash(Object o) {
        return o == null ? "-" : String.valueOf(o);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "-";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
