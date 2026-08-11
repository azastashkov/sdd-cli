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
}
