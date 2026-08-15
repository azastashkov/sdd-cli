package sdd.cli.explain;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for turning a nullable SQL column into evidence-fact text without ever printing
 * the literal word "null" — {@code String.valueOf(null)} on a {@code NULL} column renders the
 * 4-character string "null", indistinguishable from a value actually named "null", which is
 * exactly the misreading {@code sdd explain}'s evidence sections exist to prevent (a human reads
 * this text to check an answer). Generalizes the guard {@code DependencyFacts.hopDetail} first
 * established for {@code dep_edge} rows, so every renderer that concatenates a nullable column
 * shares one implementation instead of repeating it ad hoc.
 */
final class Attributes {
    private Attributes() {
    }

    /**
     * Renders a {@code key=value, key=value} attribute list from alternating key/value pairs
     * ({@code "key1", value1, "key2", value2, ...}), skipping any pair whose value is {@code null}.
     * Used where the value is one of several <em>parenthesised attributes</em> of a fact — omitting
     * a {@code null} one is right, not a loss: {@code declared_version} is legitimately {@code
     * null} for a BOM-managed dependency, and {@code declared_via=BOM} already explains why it is
     * absent, so asserting a version of "null" would be actively misleading rather than merely
     * incomplete.
     */
    static String attributes(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("attributes() takes alternating key, value pairs");
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value != null) {
                parts.add(keysAndValues[i] + "=" + value);
            }
        }
        return String.join(", ", parts);
    }

    /**
     * Renders {@code " (value)"} for a non-null bare (unlabeled) parenthetical, or {@code ""} when
     * {@code null} — for a single attribute that is not part of a {@code key=value} list, where the
     * fact reads fine with the parenthetical dropped entirely (e.g. "svc-orders uses PriceApi")
     * rather than printed as "(null)".
     */
    static String parenthetical(Object value) {
        return value == null ? "" : " (" + value + ")";
    }

    /**
     * Renders {@code value}, or {@code fallback} when {@code null} — for a value that is
     * <strong>structural</strong> to the sentence rather than an optional attribute, where omitting
     * it outright would leave a malformed fragment (e.g. a bare path with a leading space) rather
     * than a shorter valid one. Callers should pick a {@code fallback} that cannot be mistaken for
     * real data and, where an equivalent convention already exists elsewhere (e.g. {@code
     * KbEntities.resolveEndpoint}'s {@code "ANY"} for a {@code null} {@code http_method}), reuse it
     * rather than inventing a new one.
     */
    static String orElse(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
