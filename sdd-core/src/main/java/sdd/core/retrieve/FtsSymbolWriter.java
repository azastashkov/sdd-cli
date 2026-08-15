package sdd.core.retrieve;

import org.jdbi.v3.core.Handle;

import java.util.ArrayList;
import java.util.List;

/** Sole write path for fts_symbol — enforces the words-column invariant. */
public final class FtsSymbolWriter {
    private FtsSymbolWriter() {}

    /** One prospective fts_symbol row, read out of java_type/api_member before anything is written. */
    private record Symbol(long moduleId, String identifier, String fqcn) {}

    public static void insert(Handle handle, long moduleId, String identifier, String fqcn) {
        if (identifier == null || identifier.isBlank() || fqcn == null || fqcn.isBlank()) {
            throw new IllegalArgumentException(
                    "identifier and fqcn must be non-blank (identifier=" + identifier + ", fqcn=" + fqcn + ")");
        }
        handle.createUpdate("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) "
                        + "VALUES (:id, :fqcn, :words, :mod)")
                .bind("id", identifier)
                .bind("fqcn", fqcn)
                .bind("words", IdentifierWords.split(identifier))
                .bind("mod", moduleId)
                .execute();
    }

    public static void deleteForModule(Handle handle, long moduleId) {
        handle.createUpdate("DELETE FROM fts_symbol WHERE module_id = :mod")
                .bind("mod", moduleId).execute();
    }

    /**
     * Drops every symbol row belonging to a repo's modules. Needed because re-indexing a repo
     * deletes and reinserts its modules with NEW ids: rows keyed to the old ids are unreachable
     * by {@link #deleteForModule} afterwards and would accumulate as orphans that still answer
     * searches. fts5 has no foreign keys, so the sweep has to be explicit — and, per this class's
     * sole-write-path rule, it lives here rather than in the caller.
     */
    public static void deleteForRepo(Handle handle, long repoId) {
        handle.createUpdate(
                        "DELETE FROM fts_symbol WHERE module_id IN "
                                + "(SELECT id FROM module WHERE repo_id = :r)")
                .bind("r", repoId).execute();
    }

    /**
     * Repopulates fts_symbol from java_type and api_member. Exists for schema migrations that have
     * to recreate the virtual table: fts5 can neither change a table's tokenizer nor add a column
     * in place, so the only way to restem or widen the index is DROP + CREATE — which throws away
     * every indexed row while java_type stays full. A workspace left in that state answers no
     * search at all and reports no error, so the migration has to put the rows back rather than
     * leave the user to notice and re-index.
     *
     * <p>The reconstruction mirrors the indexer's own write path (see {@code
     * SourcePersistence.insertType}): one row per type carrying the simple name — the fqcn after
     * its last dot — plus one row per <em>distinct</em> member name per type, constructors
     * ({@code <init>}) excluded. Duplicates that path exactly rather than tidily: dedup is
     * per type, so a member name shared by two types earns a row under each, and a member whose
     * name equals its type's simple name is emitted twice, once as the type row and once as the
     * member row. Any "improvement" here makes a migrated index rank differently from a freshly
     * indexed one, which is the same silent divergence in a subtler form.
     *
     * <p>Limitation: these two tables are the whole source of truth. This class lives in sdd-core
     * and cannot call the indexer, so anything a future indexer derives from data it does not
     * persist would be lost by an upgrade — such a change has to persist its input, or accept
     * that upgraded workspaces stay degraded until re-indexed. The {@code doc} column is left
     * unwritten for the same reason: nothing in the schema holds javadoc yet.
     *
     * <p>Assumes fts_symbol is empty — it does not clear first, because its caller has just
     * created the table. Every row it writes went through {@link #insert} once already at index
     * time, so its argument validation cannot newly reject anything a real workspace holds.
     */
    public static void rebuildFrom(Handle handle) {
        List<Symbol> symbols = new ArrayList<>(handle.createQuery("""
                        SELECT module_id, fqcn FROM java_type ORDER BY id""")
                .map((rs, ctx) -> {
                    String fqcn = rs.getString("fqcn");
                    return new Symbol(rs.getLong("module_id"),
                            fqcn.substring(fqcn.lastIndexOf('.') + 1), fqcn);
                })
                .list());
        // GROUP BY type_id, name is the "distinct member name per type" rule; module_id and fqcn
        // are functionally determined by type_id, so picking them bare from the group is exact.
        symbols.addAll(handle.createQuery("""
                        SELECT t.module_id AS module_id, t.fqcn AS fqcn, m.name AS name
                        FROM api_member m JOIN java_type t ON t.id = m.type_id
                        WHERE m.name <> '<init>'
                        GROUP BY m.type_id, m.name
                        ORDER BY m.type_id, m.name""")
                .map((rs, ctx) -> new Symbol(rs.getLong("module_id"),
                        rs.getString("name"), rs.getString("fqcn")))
                .list());
        // Read fully before writing: inserting into fts_symbol while a result set over the source
        // tables is still open would interleave reads and writes on one connection.
        for (Symbol s : symbols) {
            insert(handle, s.moduleId(), s.identifier(), s.fqcn());
        }
    }
}
