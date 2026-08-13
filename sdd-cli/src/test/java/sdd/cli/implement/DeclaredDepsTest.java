package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeclaredDepsTest {
    @TempDir Path ws;

    @Test
    void returnsDistinctInternalDeclarationsBetweenTwoRepos() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
                // same GA on two configurations -> one DISTINCT row
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1, 'com.acme', 'lib', 'compileClasspath', '1.2.3', 'DIRECT', 'PINNED', 1, 2)");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1, 'com.acme', 'lib', 'runtimeClasspath', '1.2.3', 'DIRECT', 'PINNED', 1, 2)");
                // external edge -> excluded
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal) "
                        + "VALUES (1, 'org.ext', 'thing', 'compileClasspath', '9.0', 'DIRECT', 'PINNED', 0)");
            });

            List<DeclaredDeps.Declared> deps = DeclaredDeps.between(db.jdbi(), "svc", "lib");

            assertThat(deps).containsExactly(
                    new DeclaredDeps.Declared("com.acme", "lib", "1.2.3", "DIRECT"));
            assertThat(DeclaredDeps.between(db.jdbi(), "lib", "svc")).isEmpty();   // direction matters
        }
    }
}
