package sdd.core.retrieve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FtsRetrieverTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES (?, ?, ?, ?)",
                    "PriceCalculator", "com.acme.pricing.PriceCalculator", IdentifierWords.split("PriceCalculator"), 1);
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES (?, ?, ?, ?)",
                    "LoyaltyTier", "com.acme.pricing.LoyaltyTier", IdentifierWords.split("LoyaltyTier"), 1);
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES (?, ?, ?, ?)",
                    "OrderController", "com.acme.orders.OrderController", IdentifierWords.split("OrderController"), 2);
        });
    }

    @Test
    void findsByIdentifierToken() {
        List<Hit> hits = new FtsRetriever(db.jdbi()).search("loyalty tier pricing", 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).fqcn()).isEqualTo("com.acme.pricing.LoyaltyTier");
    }

    @Test
    void limitIsRespected() {
        assertThat(new FtsRetriever(db.jdbi()).search("com.acme", 1)).hasSize(1);
    }

    @Test
    void blankQueryReturnsEmpty() {
        assertThat(new FtsRetriever(db.jdbi()).search("   ", 10)).isEmpty();
    }

    @Test
    void punctuationOnlyQueryReturnsEmptyInsteadOfThrowing() {
        assertThat(new FtsRetriever(db.jdbi()).search("...///:::", 10)).isEmpty();
    }

    @Test
    void stemmedQueryMatchesUnstemmedIdentifier() {
        // The miss this stemmer exists for: asking "how are client tiers resolved" over a
        // TierResolver. Unstemmed, "tiers" is not "tier" and "resolved" is not "resolver", so the
        // query matches no token at all and the answer is empty rather than merely mediocre.
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES (?, ?, ?, ?)",
                "TierResolver", "com.acme.pricing.TierResolver",
                IdentifierWords.split("TierResolver"), 1));

        List<Hit> hits = new FtsRetriever(db.jdbi()).search("tiers resolved", 10);

        assertThat(hits).extracting(Hit::identifier).contains("TierResolver");
        // and it beats LoyaltyTier, which the same query reaches on "tier" alone
        assertThat(hits.get(0).identifier()).isEqualTo("TierResolver");
    }

    @Test
    void aQueryMatchingOnlyJavadocProseFindsTheTypeAndFlagsItDocOnly() {
        // The miss the doc column exists for: the answer to "how does the ordering gap get closed"
        // is in prose, and no identifier in the estate contains any of those words.
        db.jdbi().useHandle(h -> FtsSymbolWriter.insert(h, 1L, "GroupDirectory",
                "com.acme.groups.GroupDirectory",
                "Write-through state directory closing the ordering gap between a successful "
                        + "admin PUT and this service's own watcher."));

        List<Hit> hits = new FtsRetriever(db.jdbi()).search("watcher", 10);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.identifier()).isEqualTo("GroupDirectory");
            assertThat(hit.docOnly()).isTrue();
        });
    }

    @Test
    void theSameTermIsWorthLessInProseThanInAnIdentifier() {
        // The weight floor, asserted rather than assumed — and only the property that actually
        // holds. A bm25 column weight scales one column's contribution to ONE row's score, so
        // between two rows that match the same single term and are alike in everything else, the
        // one that matched in prose scores lower. That is per-term and same-row; it is not a
        // general "identifiers beat prose" rule, and column weights cannot express one. See
        // aProseHeavyRowCanOutrankAShortIdentifierMatch for the case that goes the other way.
        db.jdbi().useHandle(h -> {
            FtsSymbolWriter.insert(h, 1L, "Checkout", "com.acme.checkout.Checkout", "");
            FtsSymbolWriter.insert(h, 1L, "GroupDirectory", "com.acme.groups.GroupDirectory",
                    "Closes the ordering gap during checkout so a stale entry never reaches "
                            + "the caller.");
        });

        List<Hit> hits = new FtsRetriever(db.jdbi()).search("checkout", 10);

        assertThat(hits).extracting(Hit::identifier).containsExactly("Checkout", "GroupDirectory");
        assertThat(hits.get(0).docOnly()).isFalse();
        assertThat(hits.get(1).docOnly()).isTrue();
    }

    @Test
    void aProseHeavyRowCanOutrankAShortIdentifierMatch() {
        // The boundary of the weight floor, pinned rather than only described. Measured on the real
        // estate before it was written down here: a doc-only hit scoring -15.13 above an identifier
        // hit at -13.01. bm25 aggregates term frequency, inverse document frequency and field
        // length across the whole row, so a row matching most of the query in the doc column at 2.0
        // beats a row matching one term in the identifier column at 10.0 — no weighting can prevent
        // it, which is exactly why Hit.docOnly is reported to the reader instead.
        db.jdbi().useHandle(h -> {
            // one term, in the highest-weighted columns there are
            FtsSymbolWriter.insert(h, 1L, "Reachable", "com.acme.graph.Reachable", "");
            // several terms, all of them in the lowest-weighted column there is
            FtsSymbolWriter.insert(h, 1L, "Action", "com.acme.audit.Action",
                    "Records which reviewer decision closed a finding and what the follow-up was.");
        });

        List<Hit> hits = new FtsRetriever(db.jdbi())
                .search("reachable which reviewer decision closed the follow up", 10);

        assertThat(hits).extracting(Hit::identifier).containsSubsequence("Action", "Reachable");
        assertThat(hits).filteredOn(hit -> hit.identifier().equals("Action")).singleElement()
                .satisfies(hit -> assertThat(hit.docOnly()).isTrue());
    }

    @Test
    void aHitWhoseIdentifierAndProseBothMatchIsNotDocOnly() {
        db.jdbi().useHandle(h -> FtsSymbolWriter.insert(h, 1L, "TierResolver",
                "com.acme.pricing.TierResolver",
                "Resolves a customer's tier from their order history."));

        List<Hit> hits = new FtsRetriever(db.jdbi()).search("tier", 10);

        // "tier" is in this type's name AND in its javadoc; the marker is about how the reader
        // found it, so a name match anywhere clears it.
        assertThat(hits).filteredOn(hit -> hit.identifier().equals("TierResolver"))
                .singleElement()
                .satisfies(hit -> assertThat(hit.docOnly()).isFalse());
    }

    @Test
    void aNullDocColumnReadsAsNoMatchRatherThanAMatchOrAnNpe() {
        // No production path can produce a NULL doc: FtsSymbolWriter is the sole write path and
        // binds the column on every insert, coalescing null to "" — rebuildFrom included, so even a
        // migrated workspace holds "" there. What this pins is therefore the defensive guard in
        // FtsRetriever.matched, against a row this codebase did not write: hand-inserted, or a
        // database edited outside the tool. The seeded rows construct exactly that state, their raw
        // INSERT omitting the column.
        Integer nullDocs = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM fts_symbol WHERE doc IS NULL").mapTo(Integer.class).one());
        assertThat(nullDocs).isEqualTo(3); // the seeded rows, whose INSERT names no doc column

        List<Hit> hits = new FtsRetriever(db.jdbi()).search("loyalty tier order", 10);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.docOnly()).isFalse());
    }

    @Test
    void tiedScoresOrderDeterministicallyByIdentifierThenModule() {
        // two rows with identical tokens => identical bm25 score; order must be pinned
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES ('ZetaWidget','com.acme.z.ZetaWidget','zeta widget',2)");
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES ('AlphaWidget','com.acme.a.AlphaWidget','alpha widget',1)");
        });

        List<Hit> first = new FtsRetriever(db.jdbi()).search("widget", 10);
        List<Hit> second = new FtsRetriever(db.jdbi()).search("widget", 10);

        assertThat(first).extracting(Hit::identifier).containsExactly("AlphaWidget", "ZetaWidget");
        assertThat(second).extracting(Hit::identifier).isEqualTo(
                first.stream().map(Hit::identifier).toList());
    }
}
