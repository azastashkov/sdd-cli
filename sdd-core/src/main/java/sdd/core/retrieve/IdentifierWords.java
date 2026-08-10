package sdd.core.retrieve;

import java.util.Locale;

/** Splits code identifiers into lowercase words for FTS indexing. */
public final class IdentifierWords {
    private IdentifierWords() {}

    public static String split(String identifier) {
        return identifier
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([A-Za-z])(\\d)", "$1 $2")
                .replaceAll("[_$]+", " ")
                .toLowerCase(Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }
}
