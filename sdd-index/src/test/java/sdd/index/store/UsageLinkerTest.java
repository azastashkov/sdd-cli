package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UsageLinkerTest {
    @TempDir Path ws;

    @Test
    void linksInternalPrunesExternalAndSelf() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r1', '/w/r1', 'LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r2', '/w/r2', 'SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'SERVICE')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1, 'com.acme.Lib', 'CLASS')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (2, 'com.acme.Svc', 'CLASS')");
                // internal ref: svc -> lib; self ref: svc -> svc; external ref
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Lib', 'IMPORT')");
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Svc', 'CALL')");
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'org.ext.Gone', 'IMPORT')");
            });

            UsageLinker.Report report = UsageLinker.link(db.jdbi());

            assertThat(report.internalRefs()).isEqualTo(1);
            assertThat(report.prunedExternal()).isEqualTo(2);
            var rows = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT target_fqcn, target_module_id FROM api_usage").mapToMap().list());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("target_fqcn", "com.acme.Lib")
                    .containsEntry("target_module_id", 1);
        }
    }
}
