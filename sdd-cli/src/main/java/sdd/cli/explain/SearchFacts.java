package sdd.cli.explain;

import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.KbEntities;
import sdd.core.retrieve.Hit;
import sdd.core.retrieve.Retriever;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code search} intent: a full-text fallback over {@code fts_symbol} for when no entity named in
 * the question resolved. Labelled {@code "fts_symbol (bm25)"} rather than a generic "search" so
 * the output is honest about which backend actually answered — {@code SddConfig.retrieval} is
 * validated but read nowhere (a phase-6 known-carried item), so an {@code embeddings}-configured
 * estate silently gets FTS results too, and this label is the one place that is not hidden.
 */
final class SearchFacts {
    /**
     * How many hits to pull from the retriever before capping the section at
     * {@link Section#MEMBER_LIMIT}. {@link Retriever} has no "count matches" query, so the
     * section's {@code totalCount} is bounded by this fetch size, not a true database total —
     * an estate whose top FTS matches exceed this figure would under-report {@code M} in the
     * "+N more (showing L of M)" marker. Generous relative to the member cap so that in practice
     * this only under-counts on a pathologically broad query.
     */
    private static final int FETCH_LIMIT = 200;

    /**
     * Appended to a hit the query reached only through the type's javadoc prose. Javadoc is
     * unverified text that nothing in this pipeline checks against the code, so a doc-only hit has
     * to be distinguishable from one whose name matched — otherwise a stale doc comment reaches the
     * reader looking exactly like code-derived evidence.
     */
    private static final String DOC_ONLY_MARKER = " — matched on javadoc";

    private SearchFacts() {
    }

    static List<Section> of(Jdbi jdbi, Retriever retriever, RetrievalRequest request) {
        String query = String.join(" ", request.searchTerms());
        List<Hit> hits = query.isBlank() ? List.of() : retriever.search(query, FETCH_LIMIT);
        List<Fact> facts = new ArrayList<>();
        for (Hit hit : hits) {
            String repo = KbEntities.repoOfModule(jdbi, hit.moduleId());
            facts.add(new Fact(hit.identifier() + " (" + hit.fqcn() + ") — "
                    + (repo != null ? repo : "unknown repo")
                    + String.format(Locale.ROOT, " [score=%.4f]", hit.score())
                    + (hit.docOnly() ? DOC_ONLY_MARKER : "")));
        }
        return List.of(Section.capped("Search hits", "fts_symbol (bm25)", facts, Section.MEMBER_LIMIT));
    }
}
