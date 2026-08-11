package sdd.index.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CurationReportTest {
    @TempDir Path ws;

    @Test
    void writesSectionsForProblemsOmitsEmptyOnes() throws Exception {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind, gradle_status, parse_status) "
                        + "VALUES ('svc', '/w/svc', 'SERVICE', 'DEGRADED', 'OK')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind, kafka_status) "
                        + "VALUES (1, ':', 'SERVICE', 'UNPARSED_STREAM')");
                h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, "
                        + "http_method, resolution, raw_expr) "
                        + "VALUES (1, 'RESTTEMPLATE', 'C', 'go', 'GET', 'DYNAMIC', 'System.getenv(\"URL\")')");
                h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('\"orders.v1\"', 'DYNAMIC')");
                h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1, 1, 'CONSUMER')");
            });
            Path report = CurationReport.write(db.jdbi(), ws);

            assertThat(report).isEqualTo(ws.resolve(".sdd/curation-report.md"));
            String text = Files.readString(report);
            assertThat(text).contains("## Unresolved REST clients").contains("System.getenv");
            assertThat(text).contains("## Dynamic Kafka topics").contains("orders.v1")
                    .doesNotContain("\"orders.v1\"");
            assertThat(text).contains("## Unparsed stream modules");
            assertThat(text).contains("## Repo problems").contains("svc");
            assertThat(text).doesNotContain("## Orphan artifacts");   // none seeded
            assertThat(text).contains("manual_edges");
        }
    }
}
