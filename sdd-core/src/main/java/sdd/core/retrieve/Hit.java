package sdd.core.retrieve;

/**
 * A single retrieval result.
 *
 * <p>{@code score} follows the SQLite {@code bm25()} convention: ascending order
 * means best-first, i.e. <strong>lower scores are better</strong>. Every {@link Retriever}
 * implementation must produce scores with this ordering semantics, regardless of the
 * underlying scoring formula (bm25, cosine distance, etc.) — callers can always sort
 * ascending by {@code score} to get best-first results.
 */
public record Hit(String identifier, String fqcn, long moduleId, double score) {}
