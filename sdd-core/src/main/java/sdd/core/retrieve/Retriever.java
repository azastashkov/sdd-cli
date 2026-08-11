package sdd.core.retrieve;

import java.util.List;

/**
 * Seam for code retrieval implementations (full-text search, embeddings, etc.).
 *
 * <p><strong>Score contract:</strong> {@link Hit#score()} values order results ascending,
 * where lower is better — the SQLite {@code bm25()} convention. Every implementation of
 * this interface must produce scores whose ascending order corresponds to best-first
 * results, whatever the underlying scoring backend. {@link #search} must always return
 * its list best-first (ascending by score), regardless of backend.
 */
public interface Retriever {
    List<Hit> search(String query, int limit);
}
