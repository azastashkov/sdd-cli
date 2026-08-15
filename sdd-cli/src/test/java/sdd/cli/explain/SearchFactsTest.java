package sdd.cli.explain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.core.retrieve.Retriever;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Driven through a real {@link FtsRetriever} over real rows rather than a stubbed retriever: the
 * question this class answers is whether a reader can tell a javadoc-only hit from a name hit, and
 * a stub would let the test assert the marker against a {@code docOnly} flag the test itself set.
 */
class SearchFactsTest {
    /** A word that appears in one type's javadoc and in no identifier, fqcn or member anywhere. */
    private static final String PROSE_ONLY_TERM = "watcher";

    @TempDir Path ws;
    private Database db;
    private Retriever retriever;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        ExplainFixture.seed(db.jdbi());
        db.jdbi().useHandle(h -> FtsSymbolWriter.insert(h, 2, "GroupDirectory",
                "com.acme.api.GroupDirectory",
                "Write-through state directory closing the ordering gap between a successful "
                        + "admin PUT and this service's own " + PROSE_ONLY_TERM + "."));
        retriever = new FtsRetriever(db.jdbi());
    }

    private List<String> searchFacts(String... terms) {
        RetrievalRequest request = new RetrievalRequest(Intent.SEARCH, List.of(), List.of(terms),
                "find these", List.of(), false);
        List<Section> sections = SearchFacts.of(db.jdbi(), retriever, request);
        assertThat(sections).singleElement()
                .satisfies(s -> assertThat(s.source()).isEqualTo("fts_symbol (bm25)"));
        return sections.get(0).facts().stream().map(Fact::text).toList();
    }

    @Test
    void aHitReachedOnlyThroughJavadocSaysSo() {
        List<String> facts = searchFacts(PROSE_ONLY_TERM);

        assertThat(facts).singleElement().satisfies(text -> {
            assertThat(text).startsWith("GroupDirectory (com.acme.api.GroupDirectory) — "
                    + ExplainFixture.LIB_API);
            // the marker is appended to the existing line, not a replacement for it
            assertThat(text).contains("[score=").endsWith(" — matched on javadoc");
        });
    }

    @Test
    void aHitReachedThroughItsNameCarriesNoJavadocMarker() {
        List<String> facts = searchFacts("PriceApi");

        assertThat(facts).singleElement().satisfies(text -> {
            assertThat(text).startsWith("PriceApi (" + ExplainFixture.PRICE_API_FQCN + ") — "
                    + ExplainFixture.LIB_API);
            assertThat(text).contains("[score=").doesNotContain("javadoc");
        });
    }
}
