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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTest {
    /**
     * The version a freshly-opened workspace lands on: {@code Database.MIGRATIONS.size()}. Named
     * once so adding a migration is a one-line change here rather than a hunt through a dozen
     * literals — which is exactly how the number silently drifts out of agreement with production.
     */
    private static final int CURRENT = 8;

    @TempDir Path ws;

    @Test
    void createsDbFileAndAppliesSchema() throws Exception {
        try (Database db = Database.open(ws)) {
            assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isTrue();
            assertThat(db.schemaVersion()).isEqualTo(CURRENT);
            List<String> tables = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')")
                            .mapTo(String.class).list());
            assertThat(tables).contains("repo", "module", "artifact", "dep_edge",
                    "java_type", "api_member", "api_usage", "file_ref",
                    "rest_endpoint", "rest_client", "rest_call_edge",
                    "kafka_topic", "kafka_role", "config_property", "repo_card",
                    "attachment_description",
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
            assertThat(again.schemaVersion()).isEqualTo(CURRENT);
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
            assertThat(db.schemaVersion()).isEqualTo(CURRENT);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                Database.applyMigrationForTest(ws, 999, "CREATE TABLE t_ok(id INTEGER);\n;\nCREATE BROKEN SYNTAX"))
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
        seedV1Workspace(ws);
        Jdbi v1 = rawJdbi(ws);
        v1.useHandle(h -> {
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
            assertThat(db.schemaVersion()).isEqualTo(CURRENT);
            List<String> repos = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT name FROM repo ORDER BY id").mapTo(String.class).list());
            assertThat(repos).containsExactly("pricing");
            List<String> types = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT fqcn FROM java_type ORDER BY id").mapTo(String.class).list());
            assertThat(types).containsExactly(
                    "com.acme.pricing.TierResolver", "com.acme.pricing.LoyaltyTier");
            Integer members = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM api_member").mapTo(Integer.class).one());
            assertThat(members).isEqualTo(4);   // the four api_member rows seeded above, not a version
            // The table really was recreated with the new tokenizer and the new column...
            String ddl = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT sql FROM sqlite_master WHERE name='fts_symbol'").mapTo(String.class).one());
            assertThat(ddl).contains("porter").contains("doc");
            // ...and rebuildFrom put back the identical row set, not an approximation of it. A
            // mismatch here is the silent degradation the rebuild exists to prevent.
            assertThat(symbolRows(db.jdbi())).isEqualTo(symbolsBefore);
            // The new column is empty text, not NULL. Asserted because comments elsewhere have twice
            // claimed the opposite: rebuildFrom passes "" and insert coalesces null to "", so a
            // migrated workspace holds no NULL doc anywhere.
            List<String> docTypes = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT DISTINCT typeof(doc) FROM fts_symbol").mapTo(String.class).list());
            assertThat(docTypes).containsExactly("text");
        }
    }

    @Test
    void concurrentOpensOfAV1WorkspaceLeaveItUpgradedAndStillOpenable() throws Exception {
        // The corruption this guards: every process reads schema_version before any of them takes a
        // lock, so all of them see 1 and all of them queue up to run every later migration. The
        // named-migration reasoning below is about V2 and V3 because those are where the two
        // failure shapes first appear; V4 behaves like V3 (ADD COLUMN, so a re-run is an error).
        // Re-running V2 is
        // harmless in effect (DROP + CREATE + rebuild reconstructs fts_symbol from java_type and
        // api_member, which neither migration touches, so it reproduces the same rows) but re-running
        // V3 is an error (duplicate column javadoc) — and because the version stamp for V2 has already
        // committed by then, the failure leaves the database at 2 with V3's column present, which
        // every later open re-attempts and every later open fails on. Unrecoverable, on the one
        // operation a read-only command like `sdd review` performs as a reader.
        seedV1Workspace(ws);
        int openers = 4;
        CyclicBarrier allReady = new CyclicBarrier(openers);
        ExecutorService pool = Executors.newFixedThreadPool(openers);
        try {
            List<Future<Integer>> reported = new ArrayList<>();
            for (int i = 0; i < openers; i++) {
                reported.add(pool.submit(() -> {
                    allReady.await(10, TimeUnit.SECONDS);
                    try (Database db = Database.open(ws)) {
                        return db.schemaVersion();
                    }
                }));
            }
            for (Future<Integer> f : reported) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(CURRENT);
            }
        } finally {
            pool.shutdownNow();
        }

        // The state that matters is the one left behind: a bricked database still reports 3 to
        // whichever opener won, and only fails the next reader.
        try (Database after = Database.open(ws)) {
            assertThat(after.schemaVersion()).isEqualTo(CURRENT);
            String ddl = after.jdbi().withHandle(h -> h.createQuery(
                    "SELECT sql FROM sqlite_master WHERE name='fts_symbol'").mapTo(String.class).one());
            assertThat(ddl).contains("porter").contains("doc");
        }
    }

    @Test
    void reapplyingAnAlreadyRecordedMigrationChangesNothingAndReportsTheRecordedVersion() throws Exception {
        // The deterministic half of the case above: the losing process's second and third migrations
        // arrive at a database that has already recorded them. The script here would be plainly
        // visible if it ran, so "no-op" is asserted rather than assumed.
        try (Database db = Database.open(ws)) {
            assertThat(db.schemaVersion()).isEqualTo(CURRENT);
        }

        int reported = Database.applyMigrationForTest(ws, CURRENT, "CREATE TABLE t_reapplied(id INTEGER)");

        assertThat(reported).isEqualTo(CURRENT);
        try (Database db = Database.open(ws)) {
            List<String> tables = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT name FROM sqlite_master WHERE type='table'").mapTo(String.class).list());
            assertThat(tables).doesNotContain("t_reapplied");
        }
    }

    @Test
    void aWorkspaceUpgradedByANewerBinaryReportsItsOwnVersionNotThisBinarysMigrationCount() throws Exception {
        // Carried item 14. `migrate` used to return MIGRATIONS.size() unconditionally, so an older
        // binary reading a newer database reported its own count — a diagnostic lying in exactly the
        // mixed-version situation someone reads it to diagnose.
        try (Database db = Database.open(ws)) {
            assertThat(db.schemaVersion()).isEqualTo(CURRENT);
        }
        rawJdbi(ws).useHandle(h -> h.execute("UPDATE meta SET value='" + (CURRENT + 1) + "' WHERE key='schema_version'"));

        try (Database db = Database.open(ws)) {
            assertThat(db.schemaVersion()).isEqualTo(CURRENT + 1);
        }
    }

    @Test
    void v3WorkspaceUpgradesToV4KeepingJavadocAndEverySymbolRow() throws Exception {
        // V4 widens three tables and must leave fts_symbol strictly alone. The failure this guards
        // is silent: if V4 ever recreated fts_symbol without FTS_REBUILD_VERSION moving AND
        // rebuildFrom learning to carry java_type.javadoc across, every upgraded workspace would
        // come back with an empty or doc-less search index and report no error at all.
        seedV3Workspace(ws);
        Jdbi v3 = rawJdbi(ws);
        v3.useHandle(h -> {
            h.execute("INSERT INTO repo(id, name, path, kind) VALUES (1, 'pricing', '/w/pricing', 'SERVICE')");
            h.execute("INSERT INTO module(id, repo_id, gradle_path) VALUES (10, 1, ':')");
            h.execute("INSERT INTO java_type(id, module_id, fqcn, kind, javadoc) VALUES "
                    + "(100, 10, 'com.acme.pricing.TierResolver', 'CLASS', 'Resolves a client tier.')");
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, doc, module_id) VALUES "
                    + "('TierResolver', 'com.acme.pricing.TierResolver', 'tier resolver', "
                    + "'Resolves a client tier.', 10)");
        });
        List<String> symbolsBefore = symbolRows(v3);

        try (Database db = Database.open(ws)) {
            assertThat(db.schemaVersion()).isEqualTo(CURRENT);

            String javadoc = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT javadoc FROM java_type WHERE id=100").mapTo(String.class).one());
            assertThat(javadoc).isEqualTo("Resolves a client tier.");

            assertThat(symbolRows(db.jdbi())).isEqualTo(symbolsBefore);
            String ftsDoc = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT doc FROM fts_symbol WHERE identifier='TierResolver'")
                    .mapTo(String.class).one());
            assertThat(ftsDoc).isEqualTo("Resolves a client tier.");

            // The added NOT NULL columns backfill to JAVA, which is true of everything indexed
            // before this migration existed.
            String typeLanguage = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT language FROM java_type WHERE id=100").mapTo(String.class).one());
            assertThat(typeLanguage).isEqualTo("JAVA");
            String moduleLanguage = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT language FROM module WHERE id=10").mapTo(String.class).one());
            assertThat(moduleLanguage).isEqualTo("JAVA");

            // build_system deliberately stays NULL: it is the signal IndexService reads as
            // "this row predates V4, re-extract it" rather than a claim about the build system.
            java.util.Optional<String> buildSystem = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT build_system FROM repo WHERE id=1").mapTo(String.class).findOne());
            assertThat(buildSystem).isEmpty();
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
     * A genuine v1 workspace: v1's own script, through the production statement splitter, stamped
     * with the version a real v1 database holds. The tables are empty — callers that need rows add
     * them through {@link #rawJdbi}, which does not migrate.
     */
    private static void seedV1Workspace(Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve(".sdd"));
        Database.applyMigrationForTest(workspace, 1, readMigration("V1__init.sql"));
    }

    /**
     * A genuine v3 workspace, built by running the real V1-V3 scripts in order through the
     * production migration path — so V2's fts_symbol recreation and rebuild really happen, and the
     * database this leaves behind is the one a pre-V4 binary would have written.
     */
    private static void seedV3Workspace(Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve(".sdd"));
        Database.applyMigrationForTest(workspace, 1, readMigration("V1__init.sql"));
        Database.applyMigrationForTest(workspace, 2, readMigration("V2__fts_porter.sql"));
        Database.applyMigrationForTest(workspace, 3, readMigration("V3__type_javadoc.sql"));
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
