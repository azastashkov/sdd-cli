package sdd.core.retrieve;

import org.jdbi.v3.core.Handle;

/** Sole write path for fts_symbol — enforces the words-column invariant. */
public final class FtsSymbolWriter {
    private FtsSymbolWriter() {}

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
}
