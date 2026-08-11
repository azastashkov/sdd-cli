package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsageLinkerTest {
    @TempDir Path ws;

    private void seedTwoRepos(Database db) {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('r1', '/w/r1', 'LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('r2', '/w/r2', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'SERVICE')");
        });
    }

    private List<Map<String, Object>> usageRows(Database db) {
        return db.jdbi().withHandle(h -> h.createQuery(
                        "SELECT target_fqcn, target_module_id FROM api_usage ORDER BY target_fqcn")
                .mapToMap().list());
    }

    @Test
    void linksInternalKeepsUnmatchedAndPrunesSelfRefs() {
        try (Database db = Database.open(ws)) {
            seedTwoRepos(db);
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1, 'com.acme.Lib', 'CLASS')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (2, 'com.acme.Svc', 'CLASS')");
                // internal ref: svc -> lib; self ref: svc -> svc; ref to a type we do not index
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Lib', 'IMPORT')");
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Svc', 'CALL')");
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'org.ext.Gone', 'IMPORT')");
            });

            UsageLinker.Report report = UsageLinker.link(db.jdbi());

            assertThat(report.internalRefs()).isEqualTo(1);
            assertThat(report.prunedSelfRefs()).isEqualTo(1);
            List<Map<String, Object>> rows = usageRows(db);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("target_fqcn", "com.acme.Lib")
                    .containsEntry("target_module_id", 1);
            // an unmatched (external, or not-yet-indexed) target is NOT destroyed: the row stays,
            // unlinked, so a later run can still resolve it
            assertThat(rows.get(1)).containsEntry("target_fqcn", "org.ext.Gone");
            assertThat(rows.get(1).get("target_module_id")).isNull();
        }
    }

    @Test
    void unmatchedRowIsLinkedOnceTheTargetTypeAppears() {
        try (Database db = Database.open(ws)) {
            seedTwoRepos(db);
            // r1's parse failed on the first run, so its types are missing entirely
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Lib', 'IMPORT')"));

            assertThat(UsageLinker.link(db.jdbi()).internalRefs()).isZero();
            assertThat(usageRows(db)).hasSize(1);
            assertThat(usageRows(db).get(0).get("target_module_id")).isNull();

            // r1 re-parses successfully and its type shows up — the edge must come back by itself
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO java_type(module_id, fqcn, kind) VALUES (1, 'com.acme.Lib', 'CLASS')"));

            assertThat(UsageLinker.link(db.jdbi()).internalRefs()).isEqualTo(1);
            assertThat(usageRows(db).get(0)).containsEntry("target_module_id", 1);
        }
    }

    @Test
    void relinkingAfterTheTargetDisappearsResetsTheStaleModuleId() {
        try (Database db = Database.open(ws)) {
            seedTwoRepos(db);
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1, 'com.acme.Lib', 'CLASS')");
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Lib', 'IMPORT')");
            });
            assertThat(UsageLinker.link(db.jdbi()).internalRefs()).isEqualTo(1);

            // the target type is gone this run (renamed, moved, failed parse): the link must be
            // dropped rather than left pointing at a module that no longer declares it
            db.jdbi().useHandle(h -> h.execute("DELETE FROM java_type"));

            UsageLinker.Report report = UsageLinker.link(db.jdbi());
            assertThat(report.internalRefs()).isZero();
            assertThat(usageRows(db)).hasSize(1);
            assertThat(usageRows(db).get(0).get("target_module_id")).isNull();
        }
    }
}
