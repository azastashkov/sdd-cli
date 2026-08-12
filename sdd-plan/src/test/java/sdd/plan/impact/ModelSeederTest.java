package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSeederTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/p','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/l','LIBRARY')");
            h.execute("INSERT INTO repo_card(repo_id, card_md, card_line, model, input_hash, created_at) "
                    + "VALUES (1,'## Purpose\\nPrices things.','Pricing service.','qwen','h','t')");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "tier pricing")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
    }

    @Test
    void promptCarriesSpecCardsAndSeedProvenance() {
        String input = ModelSeeder.composeInput(db.jdbi(), spec(),
                List.of(new Seed("svc-pricing", "touchpoint", "repo:svc-pricing")),
                List.of(new Seed("lib-core", "fts", "R1 hit: LoyaltyTier")));

        assertThat(input).contains("- R1: tier pricing")
                .contains("svc-pricing (SERVICE): Pricing service.")
                .contains("Prices things.")
                .contains("lib-core (LIBRARY)")
                .contains("touchpoint repo:svc-pricing")
                .contains("fts R1 hit: LoyaltyTier");
    }

    @Test
    void validResponseYieldsSeedsAndFiltersUnknowns() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("""
                {"repos": [
                  {"repo": "svc-pricing", "role": "primary", "covers": ["R1", "R9"], "reason": "owns pricing"},
                  {"repo": "ghost-repo", "role": "primary", "covers": [], "reason": "hallucinated"}
                ]}""", "stop")));

        ModelSeeder.SeedingOutcome outcome = ModelSeeder.seed(db.jdbi(), spec(),
                List.of(), List.of(), planner, "deepseek-v4-flash", 4096);

        assertThat(outcome.seeds()).singleElement().satisfies(s -> {
            assertThat(s.repo()).isEqualTo("svc-pricing");
            assertThat(s.role()).isEqualTo("primary");
            assertThat(s.covers()).containsExactly("R1");
            assertThat(s.reason()).isEqualTo("owns pricing");
        });
        assertThat(outcome.warnings()).anySatisfy(w -> assertThat(w).contains("ghost-repo"))
                .anySatisfy(w -> assertThat(w).contains("R9"));
        assertThat(outcome.unavailable()).isFalse();
        assertThat(planner.requests()).singleElement().satisfies(r ->
                assertThat(r.maxTokens()).isEqualTo(4096));
    }

    @Test
    void fencedJsonResponseIsUnwrapped() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(
                "```json\n{\"repos\": [{\"repo\": \"svc-pricing\", \"role\": \"primary\", "
                        + "\"covers\": [\"R1\"], \"reason\": \"r\"}]}\n```", "stop")));

        ModelSeeder.SeedingOutcome outcome = ModelSeeder.seed(db.jdbi(), spec(),
                List.of(), List.of(), planner, "m", 256);

        assertThat(outcome.seeds()).singleElement().satisfies(s ->
                assertThat(s.repo()).isEqualTo("svc-pricing"));
        assertThat(outcome.warnings()).isEmpty();
        assertThat(outcome.unavailable()).isFalse();
    }

    @Test
    void modelFailuresDegradeToWarningsNeverThrow() {
        ScriptedChatModel truncated = new ScriptedChatModel(List.of(response("{", "length")));
        ModelSeeder.SeedingOutcome truncatedOutcome = ModelSeeder.seed(db.jdbi(), spec(), List.of(), List.of(), truncated, "m", 16);
        assertThat(truncatedOutcome.warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"));
        assertThat(truncatedOutcome.unavailable()).isTrue();

        ScriptedChatModel garbage = new ScriptedChatModel(List.of(response("not json", "stop")));
        ModelSeeder.SeedingOutcome garbageOutcome = ModelSeeder.seed(db.jdbi(), spec(), List.of(), List.of(), garbage, "m", 16);
        assertThat(garbageOutcome.warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"));
        assertThat(garbageOutcome.unavailable()).isTrue();

        sdd.core.llm.ChatModel refusing = req -> {
            throw new sdd.core.llm.ModelException("connection refused", 0);
        };
        ModelSeeder.SeedingOutcome refusingOutcome = ModelSeeder.seed(db.jdbi(), spec(), List.of(), List.of(), refusing, "m", 16);
        assertThat(refusingOutcome.warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"));
        assertThat(refusingOutcome.unavailable()).isTrue();
    }
}
