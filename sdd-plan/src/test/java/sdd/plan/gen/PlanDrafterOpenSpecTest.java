package sdd.plan.gen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drafter's two OpenSpec fields. Everything the model proposes goes through
 * {@code OpenSpecPlan} — the same parser a human's Gate-1 edit goes through — so what plan.md
 * renders is guaranteed to parse back, and both are held to one grammar with one set of messages.
 */
class PlanDrafterOpenSpecTest {

    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void setUp() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path) VALUES('lib-core','/w/lib-core')");
            h.execute("INSERT INTO module(repo_id, gradle_path) VALUES(1,':')");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "tier pricing"), new SpecItem("R2", "second")),
                List.of(new SpecItem("A1", "acc one"), new SpecItem("A2", "acc two")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ImpactResult impact() {
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1", "R2"),
                        List.of("touchpoint class:LoyaltyTier"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private PlanDrafter.Draft draftWith(String stepExtras) {
        String json = """
                {"summary": "s", "questions": [], "contracts": [],
                 "repo_steps": [
                   {"repo": "lib-core", "covers": ["R1", "R2"], "sub_spec": "Do it.",
                    "files": [], "provides_contracts": [], "consumes_contracts": [],
                    "version_action": "minor", "verification": ["./gradlew test"]%s}]}"""
                .formatted(stepExtras);
        ScriptedChatModel planner = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant(json), "stop", new Usage(1, 1))));
        return PlanDrafter.draft(db.jdbi(), spec(), impact(),
                ExecutionOrder.order(db.jdbi(), impact()), planner, "m", 4096);
    }

    @Test
    void theSystemPromptAsksForBothFieldsAndSaysWhenToOmitThem() {
        assertThat(PlanDrafter.SYSTEM_PROMPT)
                .contains("openspec_capability")
                .contains("acceptance_for")
                // The capability names a durable area, not the change — the single most likely way
                // to get a directory named after a ticket that outlives the ticket.
                .contains("Name the area, never the change")
                .contains("Omit either when unsure");
    }

    @Test
    void carriesTheCapabilityAndAllocationTheModelProposed() {
        PlanDrafter.Draft draft = draftWith(
                ", \"openspec_capability\": \"tier-resolution\", "
                        + "\"acceptance_for\": {\"R1\": [\"A1\"], \"R2\": [\"A2\"]}");

        assertThat(draft.steps()).singleElement().satisfies(step ->
                assertThat(step.openspec()).containsExactly(
                        "capability: tier-resolution", "R1 -> A1", "R2 -> A2"));
        assertThat(draft.notes()).isEmpty();
    }

    @Test
    void omittingBothFieldsIsNotAnErrorAndLeavesTheBlockOff() {
        // Both have deterministic fallbacks in the export; a wrong allocation is worse than none.
        PlanDrafter.Draft draft = draftWith("");

        assertThat(draft.steps()).singleElement()
                .satisfies(step -> assertThat(step.openspec()).isEmpty());
        assertThat(draft.notes()).isEmpty();
    }

    @Test
    void aCapabilityThatIsNotKebabIsCoercedAndTheCoercionIsVisibleBeforeGateOne() {
        PlanDrafter.Draft draft =
                draftWith(", \"openspec_capability\": \"Tier Resolution\"");

        assertThat(draft.steps()).singleElement().satisfies(step ->
                assertThat(step.openspec()).containsExactly("capability: tier-resolution"));
        assertThat(draft.notes()).anySatisfy(n -> assertThat(n)
                .contains("lib-core").contains("not a legal OpenSpec path segment"));
    }

    @Test
    void anInventedAcceptanceIdIsDroppedAndReportedRatherThanExported() {
        // The same discipline every other drafted field follows: validate against the spec, drop
        // what does not resolve, and say so in Generation Notes.
        PlanDrafter.Draft draft = draftWith(
                ", \"acceptance_for\": {\"R1\": [\"A1\", \"A9\"], \"R7\": [\"A2\"]}");

        assertThat(draft.steps()).singleElement().satisfies(step ->
                assertThat(step.openspec()).containsExactly("R1 -> A1"));
        assertThat(draft.notes())
                .anySatisfy(n -> assertThat(n).contains("A9").contains("not in the spec"))
                .anySatisfy(n -> assertThat(n).contains("R7").contains("does not cover it"));
    }

    @Test
    void anAllocationOfNothingRoundTripsAsNone() {
        PlanDrafter.Draft draft = draftWith(", \"acceptance_for\": {\"R1\": []}");

        assertThat(draft.steps()).singleElement()
                .satisfies(step -> assertThat(step.openspec()).containsExactly("R1 -> none"));
    }
}
