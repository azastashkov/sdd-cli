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
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, module_id) VALUES "
                    + "('PriceCalculator', 'com.acme.pricing.PriceCalculator', 1)");
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, module_id) VALUES "
                    + "('LoyaltyTier', 'com.acme.pricing.LoyaltyTier', 1)");
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, module_id) VALUES "
                    + "('OrderController', 'com.acme.orders.OrderController', 2)");
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
}
