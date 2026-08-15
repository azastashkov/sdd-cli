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
    void anIdentifierMatchOutranksAProseOnlyMatchOnTheSameTerm() {
        // The weight floor, asserted rather than assumed: javadoc is unverified text, so it may
        // surface a candidate no identifier could reach but must never come first when a real name
        // matched the same term.
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
