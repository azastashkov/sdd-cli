package sdd.core.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTest {
    @TempDir Path ws;

    @Test
    void createsDbFileAndAppliesSchema() throws Exception {
        try (Database db = Database.open(ws)) {
            assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isTrue();
            assertThat(db.schemaVersion()).isEqualTo(1);
            List<String> tables = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')")
                            .mapTo(String.class).list());
            assertThat(tables).contains("repo", "module", "artifact", "dep_edge",
                    "java_type", "api_member", "api_usage", "file_ref",
                    "rest_endpoint", "rest_client", "rest_call_edge",
                    "kafka_topic", "kafka_role", "config_property", "repo_card",
                    "meta", "fts_symbol", "v_repo_dep_edge");
        }
    }

    @Test
    void reopenIsIdempotent() throws Exception {
        try (Database first = Database.open(ws)) {
            first.jdbi().withHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('r1', '/x', 'SERVICE')"));
        }
        try (Database again = Database.open(ws)) {
            assertThat(again.schemaVersion()).isEqualTo(1);
            Integer count = again.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM repo").mapTo(Integer.class).one());
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void foreignKeysAreEnforced() throws Exception {
        try (Database db = Database.open(ws)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    db.jdbi().withHandle(h -> h.execute(
                            "INSERT INTO module(repo_id, gradle_path, kind) VALUES (999, ':', 'LIBRARY')")))
                    .hasMessageContaining("FOREIGN KEY");
        }
    }
}
