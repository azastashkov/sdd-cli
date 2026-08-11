package sdd.core.retrieve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FtsSymbolWriterTest {
    @TempDir Path ws;

    @Test
    void insertsWithSplitWordsAndSearchFinds() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h ->
                    FtsSymbolWriter.insert(h, 7L, "LoyaltyTier", "com.acme.pricing.LoyaltyTier"));
            List<Hit> hits = new FtsRetriever(db.jdbi()).search("loyalty", 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).moduleId()).isEqualTo(7L);
        }
    }

    @Test
    void deleteForModuleRemovesRows() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                FtsSymbolWriter.insert(h, 7L, "A", "p.A");
                FtsSymbolWriter.insert(h, 8L, "B", "p.B");
                FtsSymbolWriter.deleteForModule(h, 7L);
            });
            Integer count = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM fts_symbol").mapTo(Integer.class).one());
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void rejectsNullAndBlank() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                assertThatThrownBy(() -> FtsSymbolWriter.insert(h, 1L, null, "p.A"))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> FtsSymbolWriter.insert(h, 1L, " ", "p.A"))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> FtsSymbolWriter.insert(h, 1L, "A", null))
                        .isInstanceOf(IllegalArgumentException.class);
            });
        }
    }
}
