package sdd.core.retrieve;

import org.jdbi.v3.core.Jdbi;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class FtsRetriever implements Retriever {
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
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT identifier, fqcn, module_id, bm25(fts_symbol) AS score
                        FROM fts_symbol WHERE fts_symbol MATCH :match
                        ORDER BY score, identifier, module_id LIMIT :limit""")
                .bind("match", match)
                .bind("limit", limit)
                .map((rs, ctx) -> new Hit(
                        rs.getString("identifier"),
                        rs.getString("fqcn"),
                        rs.getLong("module_id"),
                        rs.getDouble("score")))
                .list());
    }
}
