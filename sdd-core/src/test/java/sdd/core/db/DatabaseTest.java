package sdd.core.db;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import sdd.core.retrieve.IdentifierWords;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
            assertThat(db.schemaVersion()).isEqualTo(3);
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
            assertThat(again.schemaVersion()).isEqualTo(3);
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

    @Test
    void migrationIsAtomic() throws Exception {
        // Corrupt path: a migration that fails half-way must leave no trace.
        // Simulate by opening a db, then attempting a second migrate with a bad script
        // via the package-visible seam.
        try (Database db = Database.open(ws)) {
            assertThat(db.schemaVersion()).isEqualTo(3);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                Database.applyMigrationForTest(ws, "CREATE TABLE t_ok(id INTEGER);\n;\nCREATE BROKEN SYNTAX"))
                .isInstanceOf(Exception.class);
        try (Database db = Database.open(ws)) {
            List<String> tables = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT name FROM sqlite_master WHERE type='table'").mapTo(String.class).list());
            assertThat(tables).doesNotContain("t_ok"); // first statement rolled back with the failure
        }
    }

    @Test
    void existingV1DatabaseUpgradesInPlaceKeepingItsRows() throws Exception {
        // V2 is the first migration this codebase applies on top of an existing database — until
        // now the loop in migrate() has only ever run from version 0. So build a genuine v1
        // workspace (v1's own script, through the production statement splitter) and hand it to
        // Database.open cold.
        Files.createDirectories(ws.resolve(".sdd"));
        Database.applyMigrationForTest(ws, readMigration("V1__init.sql"));
        Jdbi v1 = rawJdbi(ws);
        v1.useHandle(h -> {
            // applyMigrationForTest stamps its own test-only version; a real v1 workspace holds 1.
            h.execute("UPDATE meta SET value='1' WHERE key='schema_version'");
            h.execute("INSERT INTO repo(id, name, path, kind) VALUES (1, 'pricing', '/w/pricing', 'SERVICE')");
            h.execute("INSERT INTO module(id, repo_id, gradle_path) VALUES (10, 1, ':')");
            h.execute("INSERT INTO java_type(id, module_id, fqcn, kind) VALUES "
                    + "(100, 10, 'com.acme.pricing.TierResolver', 'CLASS')");
            h.execute("INSERT INTO java_type(id, module_id, fqcn, kind) VALUES "
                    + "(101, 10, 'com.acme.pricing.LoyaltyTier', 'ENUM')");
            // <init> is never indexed; the repeated 'resolve' is an overload (one row per type,
            // not per member); the same name under two types earns a row under each.
            h.execute("INSERT INTO api_member(type_id, name, signature) VALUES (100, '<init>', 'TierResolver()')");
            h.execute("INSERT INTO api_member(type_id, name, signature) VALUES (100, 'resolve', 'resolve(String)')");
            h.execute("INSERT INTO api_member(type_id, name, signature) VALUES (100, 'resolve', 'resolve(String,int)')");
            h.execute("INSERT INTO api_member(type_id, name, signature) VALUES (101, 'resolve', 'resolve()')");
            // fts_symbol exactly as the v1 indexer left it. Written raw rather than through
            // FtsSymbolWriter.insert: that path now writes the doc column, which the v1 table does
            // not have — the sole-write-path rule is about the current schema, and reconstructing
            // an older one is precisely the case it cannot serve.
            insertV1Symbol(h, 10L, "TierResolver", "com.acme.pricing.TierResolver");
            insertV1Symbol(h, 10L, "LoyaltyTier", "com.acme.pricing.LoyaltyTier");
            insertV1Symbol(h, 10L, "resolve", "com.acme.pricing.TierResolver");
            insertV1Symbol(h, 10L, "resolve", "com.acme.pricing.LoyaltyTier");
        });
        List<String> symbolsBefore = symbolRows(v1);

        try (Database db = Database.open(ws)) {
            assertThat(db.schemaVersion()).isEqualTo(3);
            List<String> repos = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT name FROM repo ORDER BY id").mapTo(String.class).list());
            assertThat(repos).containsExactly("pricing");
            List<String> types = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT fqcn FROM java_type ORDER BY id").mapTo(String.class).list());
            assertThat(types).containsExactly(
                    "com.acme.pricing.TierResolver", "com.acme.pricing.LoyaltyTier");
            Integer members = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM api_member").mapTo(Integer.class).one());
            assertThat(members).isEqualTo(4);
            // The table really was recreated with the new tokenizer and the new column...
            String ddl = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT sql FROM sqlite_master WHERE name='fts_symbol'").mapTo(String.class).one());
            assertThat(ddl).contains("porter").contains("doc");
            // ...and rebuildFrom put back the identical row set, not an approximation of it. A
            // mismatch here is the silent degradation the rebuild exists to prevent.
            assertThat(symbolRows(db.jdbi())).isEqualTo(symbolsBefore);
        }
    }

    @Test
    void busyTimeoutIsConfigured() throws Exception {
        try (Database db = Database.open(ws)) {
            Integer timeout = db.jdbi().withHandle(h ->
                    h.createQuery("PRAGMA busy_timeout").mapTo(Integer.class).one());
            assertThat(timeout).isEqualTo(5000);
        }
    }

    /**
     * One row in the v1 shape of fts_symbol — no doc column. Uses {@link IdentifierWords} for the
     * words column because that split is what v1 wrote and what the rebuild must reproduce; only
     * the column list is frozen at v1, not the derivation.
     */
    private static void insertV1Symbol(Handle h, long moduleId, String identifier, String fqcn) {
        h.createUpdate("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) "
                        + "VALUES (:id, :fqcn, :words, :mod)")
                .bind("id", identifier).bind("fqcn", fqcn)
                .bind("words", IdentifierWords.split(identifier))
                .bind("mod", moduleId).execute();
    }

    /** Identity of every fts_symbol row, comparable across the v1 and v2 shapes of the table. */
    private static List<String> symbolRows(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT identifier || '|' || fqcn || '|' || words || '|' || module_id AS row
                        FROM fts_symbol ORDER BY identifier, fqcn, module_id""")
                .mapTo(String.class).list());
    }

    /** A connection that does not migrate, so a pre-upgrade database can be built and read as-is. */
    private static Jdbi rawJdbi(Path workspace) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + workspace.resolve(".sdd/index.db"));
        return Jdbi.create(ds);
    }

    private static String readMigration(String name) throws Exception {
        try (InputStream in = Database.class.getResourceAsStream("/sdd/db/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
