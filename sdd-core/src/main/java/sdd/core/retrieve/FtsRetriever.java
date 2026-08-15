package sdd.core.retrieve;

import org.jdbi.v3.core.Jdbi;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * bm25 search over {@code fts_symbol}.
 *
 * <p><strong>Column weights.</strong> {@code bm25()}'s weights are positional over the table's
 * columns as declared — {@code identifier, fqcn, words, doc, module_id} — and an {@code UNINDEXED}
 * column still occupies its slot, so {@code module_id}'s trailing {@code 0.0} is a placeholder that
 * keeps the four real weights aligned. Passing too few weights defaults the rest to 1.0 and passing
 * too many is silently ignored, so an off-by-one here would mis-weight every column without ever
 * raising: the order is fixed by {@code V2__fts_porter.sql} and must be re-checked against it if
 * that table is ever recreated.
 *
 * <p>The ordering the weights encode is deliberate. An exact identifier match is what the caller
 * asked for; the camel-split {@code words} column is the same evidence one step removed; the fqcn
 * matches on package fragments shared by many unrelated types, so it is worth less; and {@code doc}
 * — unverified javadoc prose, typically an order of magnitude longer than any identifier — sits at
 * the floor. Prose there breaks ties and surfaces types no identifier could reach, but it can never
 * win against a code-derived match.
 */
public final class FtsRetriever implements Retriever {
    /**
     * Opens a matched term in {@code highlight()}'s output — the SQL's {@code char(2)}, STX. A C0
     * control character no Java identifier, fqcn or javadoc summary can contain, so its presence in
     * a column's highlighted text means that column matched, and its absence means it did not.
     */
    private static final char MATCH_OPEN = (char) 2;

    private final Jdbi jdbi;

    public FtsRetriever(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override
    public List<Hit> search(String query, int limit) {
        String match = Arrays.stream(query.split("[^A-Za-z0-9_$]+"))
                .filter(t -> !t.isBlank())
                .map(t -> '"' + t + '"')
                .collect(Collectors.joining(" OR "));
        if (match.isEmpty()) {
            return List.of();
        }
        // highlight() per column is how a hit learns which columns it matched, without a second
        // round-trip: fts5 exposes no per-column match flag, and re-running the query once per
        // column would multiply the cost of every search by four. It is bounded by LIMIT, so the
        // tokenizer only re-runs over the rows actually returned.
        //
        // The ORDER BY runs to fqcn because (identifier, module_id) is not unique: a member row and
        // its own type's row share both when a member's name equals its type's simple name, and two
        // types in one module can expose the same member name. Without the last key those rows fall
        // back to rowid, and FtsSymbolWriter.rebuildFrom writes rows in a different physical order
        // than the indexer does (all types, then all members, versus interleaved) — so a migrated
        // workspace would break such a tie differently from a freshly indexed one, and `sdd plan
        // approve` SHA-pins a plan.md whose seed list depends on this order.
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT identifier, fqcn, module_id,
                               bm25(fts_symbol, 10.0, 3.0, 8.0, 2.0, 0.0) AS score,
                               highlight(fts_symbol, 0, char(2), char(3)) AS hl_identifier,
                               highlight(fts_symbol, 1, char(2), char(3)) AS hl_fqcn,
                               highlight(fts_symbol, 2, char(2), char(3)) AS hl_words,
                               highlight(fts_symbol, 3, char(2), char(3)) AS hl_doc
                        FROM fts_symbol WHERE fts_symbol MATCH :match
                        ORDER BY score, identifier, module_id, fqcn LIMIT :limit""")
                .bind("match", match)
                .bind("limit", limit)
                .map((rs, ctx) -> {
                    // docOnly means "the javadoc is the only reason this row is here", so every
                    // other indexed column has to be clear — including fqcn, which matches on
                    // package fragments and would otherwise let a package-name hit be reported as
                    // a javadoc one.
                    boolean codeMatched = matched(rs.getString("hl_identifier"))
                            || matched(rs.getString("hl_fqcn"))
                            || matched(rs.getString("hl_words"));
                    return new Hit(
                            rs.getString("identifier"),
                            rs.getString("fqcn"),
                            rs.getLong("module_id"),
                            rs.getDouble("score"),
                            matched(rs.getString("hl_doc")) && !codeMatched);
                })
                .list());
    }

    /**
     * Whether a highlighted column carries a match marker. Null-tolerant: rows written before the
     * {@code doc} column was populated hold NULL there, and {@code highlight()} passes that through.
     */
    private static boolean matched(String highlighted) {
        return highlighted != null && highlighted.indexOf(MATCH_OPEN) >= 0;
    }
}
