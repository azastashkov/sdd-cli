package sdd.core.db;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import sdd.core.retrieve.FtsSymbolWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Database implements AutoCloseable {
    private static final List<String> MIGRATIONS =
            List.of("V1__init.sql", "V2__fts_porter.sql", "V3__type_javadoc.sql");

    /**
     * The migration that recreates fts_symbol and so has to repopulate it — see
     * {@link #applyMigration}. V3 only widens {@code java_type}; it leaves fts_symbol alone and
     * must not trigger a rebuild.
     */
    private static final int FTS_REBUILD_VERSION = 2;

    private final Jdbi jdbi;
    private final int schemaVersion;

    private Database(Jdbi jdbi, int schemaVersion) {
        this.jdbi = jdbi;
        this.schemaVersion = schemaVersion;
    }

    public static Database open(Path workspace) {
        try {
            Files.createDirectories(workspace.resolve(".sdd"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int version = migrate(workspace);
        return new Database(
                Jdbi.create(dataSource(workspace, SQLiteConfig.TransactionMode.DEFERRED)), version);
    }

    /**
     * Every connection this class hands out, configured identically apart from how it opens a
     * transaction.
     *
     * <p>Migrations run on their own connection in {@code IMMEDIATE} mode; everything else stays on
     * SQLite's {@code DEFERRED} default. The difference matters only when two processes open the
     * same workspace at once, and there it is what makes {@link #applyMigration}'s version check
     * mean anything. {@code DEFERRED} takes the transaction's read snapshot at its
     * first read, so a second process reads the pre-migration version, finds nothing to skip, and
     * then fails its first write outright — measured: {@code [SQLITE_BUSY] database is locked} on
     * V2's {@code DROP TABLE fts_symbol}, which the 5s busy timeout does not rescue, because a
     * snapshot that has already been overtaken cannot be resolved by waiting. {@code IMMEDIATE}
     * takes the write lock at {@code BEGIN} instead, before the check reads anything: the second
     * process waits out the first (this is where the busy timeout does its work), then reads the
     * version the first committed and skips. Lock first, then check.
     */
    private static SQLiteDataSource dataSource(Path workspace, SQLiteConfig.TransactionMode mode) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setTransactionMode(mode);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + workspace.resolve(".sdd/index.db"));
        return ds;
    }

    /**
     * Brings the database up to the newest migration this binary carries, and returns the version the
     * <em>database</em> then records rather than the number of migrations this binary knows about.
     * Those differ whenever an older binary opens a workspace a newer one has already upgraded, so
     * reporting the count would make {@link #schemaVersion()} lie in exactly the mixed-version
     * situation someone consults it to diagnose.
     *
     * <p>The version read here is a hint that lets the common case skip the loop entirely; it is not
     * load-bearing, because it is taken outside the migration transaction and another process may
     * move the database on before the loop reaches it. {@link #applyMigration} re-reads inside its own
     * transaction and that reading is the authority.
     */
    private static int migrate(Path workspace) {
        Jdbi jdbi = Jdbi.create(dataSource(workspace, SQLiteConfig.TransactionMode.IMMEDIATE));
        int current = jdbi.withHandle(Database::recordedVersion);
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            current = applyMigration(jdbi, v, readResource("/sdd/db/" + MIGRATIONS.get(v - 1)));
        }
        return current;
    }

    /**
     * Applies one migration if the database has not already recorded it, and returns the version it
     * records afterwards.
     *
     * <p>The re-read is what makes a concurrent upgrade safe. Two processes opening the same v1
     * workspace can both see version 1 before either takes a lock — the window is short, but nothing
     * bounds it — and without this check the second re-runs a migration the first has just
     * committed. That is not idempotent here: V2's
     * {@code DROP TABLE fts_symbol} plus {@link FtsSymbolWriter#rebuildFrom} would discard the first
     * process's work, and V3's {@code ALTER TABLE ... ADD COLUMN javadoc} would then fail with
     * {@code duplicate column name} and roll back — leaving the database recording version 2 with
     * V3's column already present, a state every subsequent open re-attempts and every subsequent
     * open therefore fails on. That is unrecoverable without hand-editing SQLite, and it would happen
     * on {@link #open}, which every command including the read-only ones performs.
     */
    private static int applyMigration(Jdbi jdbi, int version, String script) {
        return jdbi.inTransaction(h -> {
            int recorded = recordedVersion(h);
            if (recorded >= version) {
                return recorded;
            }
            for (String statement : script.split("\\n;\\n")) {
                if (!statement.isBlank()) {
                    h.execute(statement);
                }
            }
            // V2 drops and recreates fts_symbol (fts5 cannot restem or widen a table in place), so
            // by this point the search index is empty while java_type is untouched — a state that
            // answers every search with nothing and reports no error. Rebuilding here, inside the
            // migration's own transaction, means an upgrade either lands whole or not at all.
            // Keyed on the version rather than on "is the table empty" so that a legitimately
            // empty index is not rebuilt on every open. applyMigrationForTest names its own version
            // and so can reach this branch; the tests deliberately stay off version 2, since their
            // scripts are arbitrary and touch no real schema.
            if (version == FTS_REBUILD_VERSION) {
                FtsSymbolWriter.rebuildFrom(h);
            }
            h.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            h.execute("INSERT INTO meta(key, value) VALUES ('schema_version', ?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", version);
            return version;
        });
    }

    /** The version the database records; 0 for one that has never been migrated. */
    private static int recordedVersion(Handle h) {
        boolean hasMeta = !h.createQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")
                .mapTo(String.class).list().isEmpty();
        if (!hasMeta) {
            return 0;
        }
        return h.createQuery("SELECT value FROM meta WHERE key='schema_version'")
                .mapTo(Integer.class).findOne().orElse(0);
    }

    /**
     * Applies one arbitrary script as {@code version}, on a connection configured exactly as
     * production's, and returns the version recorded afterwards. The version is a parameter because
     * the two things worth testing here are opposites: a version above anything recorded exercises
     * the failure path, and one at or below it exercises the skip that makes a concurrent upgrade
     * safe.
     */
    static int applyMigrationForTest(Path workspace, int version, String script) {
        return applyMigration(
                Jdbi.create(dataSource(workspace, SQLiteConfig.TransactionMode.IMMEDIATE)),
                version, script);
    }

    private static String readResource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing migration resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Jdbi jdbi() { return jdbi; }

    /**
     * The schema version the database recorded when it was opened — read back from {@code meta},
     * not inferred from how many migrations this binary carries, so it stays truthful when a newer
     * binary has already upgraded the workspace past what this one knows.
     */
    public int schemaVersion() { return schemaVersion; }

    @Override
    public void close() { /* one physical connection per Jdbi handle, closed per call; nothing pooled to release */ }
}
