package sdd.core.db;

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
    private static final List<String> MIGRATIONS = List.of("V1__init.sql", "V2__fts_porter.sql");

    /** The migration that recreates fts_symbol and so has to repopulate it — see {@link #applyMigration}. */
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
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + workspace.resolve(".sdd/index.db"));
        Jdbi jdbi = Jdbi.create(ds);
        int version = migrate(jdbi);
        return new Database(jdbi, version);
    }

    private static int migrate(Jdbi jdbi) {
        int current = jdbi.withHandle(h -> {
            boolean hasMeta = !h.createQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")
                    .mapTo(String.class).list().isEmpty();
            if (!hasMeta) {
                return 0;
            }
            return h.createQuery("SELECT value FROM meta WHERE key='schema_version'")
                    .mapTo(Integer.class).findOne().orElse(0);
        });
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            applyMigration(jdbi, v, readResource("/sdd/db/" + MIGRATIONS.get(v - 1)));
        }
        return MIGRATIONS.size();
    }

    private static void applyMigration(Jdbi jdbi, int version, String script) {
        jdbi.useTransaction(h -> {
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
            // empty index is not rebuilt on every open; applyMigrationForTest's version 999 is
            // deliberately outside this, since its scripts are arbitrary and touch no real schema.
            if (version == FTS_REBUILD_VERSION) {
                FtsSymbolWriter.rebuildFrom(h);
            }
            h.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            h.execute("INSERT INTO meta(key, value) VALUES ('schema_version', ?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", version);
        });
    }

    static void applyMigrationForTest(Path workspace, String script) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + workspace.resolve(".sdd/index.db"));
        applyMigration(Jdbi.create(ds), 999, script);
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

    public int schemaVersion() { return schemaVersion; }

    @Override
    public void close() { /* one physical connection per Jdbi handle, closed per call; nothing pooled to release */ }
}
