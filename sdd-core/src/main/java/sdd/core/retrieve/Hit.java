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
 *
 * <p><strong>What it does not mark, and this is part of the contract.</strong> {@code docOnly} is
 * provenance about <em>presence</em>, not about <em>rank</em>: it fires only when prose was the sole
 * column that matched, so a hit that prose alone lifted to the top still reports {@code false} the
 * moment any query term also reaches a code column — a package fragment in the fqcn is enough.
 * Measured, not hypothetical: a type climbing from rank 42 to rank 1 entirely on its javadoc carried
 * no marker, because the question and its package shared a word (carried item 13 of {@code
 * docs/superpowers/plans/2026-08-15-retrieval-corpus.md}; disclosed to readers in {@code
 * docs/commands.md}'s {@code sdd explain} section). So {@code true} means "prose is why this row is
 * here"; {@code false} does not mean "prose is not why it ranks here". A second backend implementing
 * this record inherits that boundary and must not quietly widen it — a flag that sometimes meant
 * rank provenance and sometimes presence provenance would be worse than one that never did.
 */
public record Hit(String identifier, String fqcn, long moduleId, double score, boolean docOnly) {}
