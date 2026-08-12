package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.PlanMdRenderer;
import sdd.plan.gen.Question;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanMdParserSectionsTest {

    private static String rendered() {
        NormalizedSpec spec = new NormalizedSpec("SPEC-9", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ImpactResult impact = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"),
                                List.of("touchpoint class:X", "model covers R1; owns it")),
                        new AffectedRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY",
                                List.of(), List.of("depends on lib-core (PINNED)"))),
                List.of(new Seed("svc-x", "fts", "R1 hit: Y — not selected")),
                List.of("lib-core <-> svc-a"), List.of(), List.of(), List.of("a warning"));
        PlanDrafter.Draft draft = new PlanDrafter.Draft("Summary here.",
                List.of(new PlanDrafter.DraftStep("lib-core", List.of("R1"), "Do it.\nCarefully.",
                        List.of("src/A.java"), List.of("C-1"), List.of(), "minor",
                        List.of("./gradlew test"))),
                List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "method: Tier tierFor(String)")),
                List.of(new Question("Drafted?", false)), List.of("note1"), false);
        return PlanMdRenderer.render(spec, impact,
                List.of(new ExecutionOrder.Unit(List.of("lib-core", "svc-a")),
                        new ExecutionOrder.Unit(List.of("svc-x"))),
                List.of(new Question("no repo covers R1", true)), draft);
    }

    @Test
    void roundTripsARealRenderedPlan() {
        PlanDocument doc = PlanMdParser.parse(rendered());

        assertThat(doc.specId()).isEqualTo("SPEC-9");
        assertThat(doc.summary()).isEqualTo("Summary here.");
        assertThat(doc.questions()).containsExactly(
                new PlanDocument.PlanQuestion(1, true, "no repo covers R1", null),
                new PlanDocument.PlanQuestion(2, false, "Drafted?", null));
        assertThat(doc.affected()).containsExactly(
                new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"),
                        "touchpoint class:X; model covers R1; owns it"),
                new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(),
                        "depends on lib-core (PINNED)"));
        assertThat(doc.excluded()).containsExactly(
                new PlanDocument.PlanExcluded("svc-x", "R1 hit: Y — not selected"));
        assertThat(doc.order()).containsExactly(List.of("lib-core", "svc-a"), List.of("svc-x"));
        assertThat(doc.contracts()).containsExactly(new PlanDocument.PlanContract(
                "C-1", "java-api", "lib-core", List.of("svc-a"), "method: Tier tierFor(String)"));
        assertThat(doc.steps()).containsExactly(new PlanDocument.PlanStep(
                "lib-core", List.of("R1"), "minor", List.of("C-1"), List.of(),
                List.of("src/A.java"), List.of("./gradlew test"), "Do it.\nCarefully."));
        assertThat(doc.notes()).containsExactly("note1", "a warning");
    }

    @Test
    void malformedRowsFailWithLineNumbers() {
        String plan = rendered();
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace(
                "- svc-a — dependent/CODE_CHANGE_LIKELY", "- svc-a ; dependent")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("Affected Repos rows must look like");
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace("2. svc-x", "3. svc-x")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("Execution Order must be numbered sequentially");
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace("```yaml", "```json")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("contract body must open with '```yaml'");
        assertThatThrownBy(() -> PlanMdParser.parse(plan.replace("- version_action: minor", "")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("expected '- version_action:");
    }

    @Test
    void contractBodyHeadingLookalikeAndForgedSummaryHeadingRoundTrip() {
        NormalizedSpec spec = new NormalizedSpec("SPEC-9", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ImpactResult impact = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"), List.of("owns it"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
        PlanDrafter.Draft draft = new PlanDrafter.Draft("## Sneaky summary",
                List.of(new PlanDrafter.DraftStep("lib-core", List.of("R1"), "Do it.",
                        List.of("src/A.java"), List.of("C-1"), List.of(), "minor",
                        List.of("./gradlew test"))),
                List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core", List.of(),
                        "## response shape\nmethod: x")),
                List.of(), List.of(), false);

        String md = PlanMdRenderer.render(spec, impact,
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), draft);
        PlanDocument doc = PlanMdParser.parse(md);

        assertThat(doc.contracts()).containsExactly(new PlanDocument.PlanContract(
                "C-1", "java-api", "lib-core", List.of(), "## response shape\nmethod: x"));
        assertThat(doc.summary()).isEqualTo("Sneaky summary");
    }

    @Test
    void unavailableSentinelsParseToEmptyLists() {
        String plan = rendered()
                .replaceAll("(?s)## Interface Contracts.*?## Repo Steps",
                        "## Interface Contracts\n- none (drafting unavailable)\n\n## Repo Steps")
                .replaceAll("(?s)## Repo Steps.*?## Generation Notes",
                        "## Repo Steps\n- none (drafting unavailable)\n\n## Generation Notes");

        PlanDocument doc = PlanMdParser.parse(plan);

        assertThat(doc.contracts()).isEmpty();
        assertThat(doc.steps()).isEmpty();
    }
}
