package sdd.core.retrieve;

/**
 * A single retrieval result.
 *
 * <p>{@code score} follows the SQLite {@code bm25()} convention: ascending order
 * means best-first, i.e. <strong>lower scores are better</strong>. Every {@link Retriever}
 * implementation must produce scores with this ordering semantics, regardless of the
 * underlying scoring formula (bm25, cosine distance, etc.) — callers can always sort
 * ascending by {@code score} to get best-first results.
 *
 * <p>{@code docOnly} marks a hit the query reached <strong>only</strong> through the type's
 * javadoc prose — no part of the query matched the identifier, its split words, or the fqcn.
 * Javadoc is unverified text that may describe behaviour the code no longer has, so a reader
 * has to be able to tell such a hit apart from one whose name matched: the same fact rendered
 * without that distinction would present a stale doc comment as if it were a code-derived match.
 * A backend with no prose to search reports {@code false} for every hit.
 */
public record Hit(String identifier, String fqcn, long moduleId, double score, boolean docOnly) {}
