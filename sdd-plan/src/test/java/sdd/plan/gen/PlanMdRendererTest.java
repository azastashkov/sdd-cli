package sdd.plan.gen;

import org.junit.jupiter.api.Test;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanMdRendererTest {

    private static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-9", "Tier pricing", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ImpactResult impact() {
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"),
                                List.of("touchpoint class:LoyaltyTier", "model covers R1; owns it")),
                        new AffectedRepo("svc-pricing", "dependent", "CODE_CHANGE_LIKELY",
                                List.of(), List.of("depends on lib-core (PINNED)"))),
                List.of(new Seed("svc-legacy", "fts", "R1 hit: X — not selected by model, not required by graph")),
                List.of(), List.of(), List.of(), List.of("model seeding unavailable: down"));
    }

    @Test
    void rendersEverySectionInOrderWithNumberedQuestions() {
        PlanDrafter.Draft draft = new PlanDrafter.Draft("Do the thing.",
                List.of(new PlanDrafter.DraftStep("lib-core", List.of("R1"), "Add lookup.",
                        List.of("src/main/java/A.java"), List.of("C-1"), List.of(), "minor",
                        List.of("./gradlew test"))),
                List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core",
                        List.of("svc-pricing"), "method: Tier tierFor(String)\n```evil```")),
                List.of(new Question("From the model?", false)),
                List.of("drafter note"), false);
        List<Question> detectors = List.of(new Question("no repo covers R1", true));
        List<ExecutionOrder.Unit> order = List.of(
                new ExecutionOrder.Unit(List.of("lib-core")),
                new ExecutionOrder.Unit(List.of("a", "b")));

        String md = PlanMdRenderer.render(spec(), impact(), order, detectors, draft);

        assertThat(md).startsWith("---\nspec: SPEC-9\nplan_version: 1\n---\n");
        int summary = md.indexOf("## Summary");
        int questions = md.indexOf("## Open Questions");
        int affected = md.indexOf("## Affected Repos");
        int excluded = md.indexOf("## Excluded Candidates");
        int orderIdx = md.indexOf("## Execution Order");
        int contracts = md.indexOf("## Interface Contracts");
        int steps = md.indexOf("## Repo Steps");
        int notes = md.indexOf("## Generation Notes");
        assertThat(summary).isLessThan(questions);
        assertThat(questions).isLessThan(affected);
        assertThat(affected).isLessThan(excluded);
        assertThat(excluded).isLessThan(orderIdx);
        assertThat(orderIdx).isLessThan(contracts);
        assertThat(contracts).isLessThan(steps);
        assertThat(steps).isLessThan(notes);

        assertThat(md).contains("Do the thing.")
                .contains("- Q1 [blocking]: no repo covers R1")
                .contains("- Q2: From the model?")
                .contains("- lib-core — seed/SEED — covers: R1 — why: touchpoint class:LoyaltyTier; model covers R1; owns it")
                .contains("- svc-pricing — dependent/CODE_CHANGE_LIKELY — covers: - — why: depends on lib-core (PINNED)")
                .contains("- svc-legacy — R1 hit: X — not selected by model, not required by graph")
                .contains("1. lib-core").contains("2. a + b (co-scheduled)")
                .contains("### C-1 (java-api) — lib-core -> svc-pricing")
                .contains("method: Tier tierFor(String)")
                .contains("'''evil'''")
                .doesNotContain("```evil```")
                .contains("### lib-core")
                .contains("- version_action: minor")
                .contains("Add lookup.")
                .contains("- drafter note")
                .contains("- model seeding unavailable: down");
    }

    @Test
    void drafterTextCannotForgeStructure() {
        ImpactResult hostileImpact = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"),
                        List.of("why\n## Forged\ntext"))),
                List.of(new Seed("svc-legacy", "fts", "detail\n## Forged2\nmore")),
                List.of(), List.of(), List.of(), List.of());
        PlanDrafter.Draft hostile = new PlanDrafter.Draft(
                "Sneaky.\n## Repo Steps\n- fake",
                List.of(new PlanDrafter.DraftStep("lib-core", List.of(), "line\n# Fake heading\n---\n```\nrest",
                        List.of(), List.of(), List.of(), "none", List.of())),
                List.of(),
                List.of(new Question("q\n## Affected Repos", false)),
                List.of("note\n---"), false);

        String md = PlanMdRenderer.render(spec(), hostileImpact,
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), hostile);

        assertThat(md.lines().filter(l -> l.startsWith("## ")).count())
                .isEqualTo(8);                                            // only the renderer's own sections
        assertThat(md.lines().filter(l -> l.equals("---")).count())
                .isEqualTo(2);                                            // only the front-matter pair
        assertThat(md).contains("Sneaky. ## Repo Steps - fake")            // collapsed, not structural
                .contains("- Q1: q ## Affected Repos")
                .contains("why ## Forged text")                            // reasons collapsed, not structural
                .contains("detail ## Forged2 more")                        // excluded detail collapsed
                .contains("line\nFake heading\n—\n'''\nrest")              // fence neutralized
                .contains("- note ---");
    }

    @Test
    void unavailableDraftStillRendersDeterministicSections() {
        PlanDrafter.Draft draft = new PlanDrafter.Draft("", List.of(), List.of(),
                List.of(new Question("plan drafting unavailable: down — rerun sdd plan", true)),
                List.of(), true);

        String md = PlanMdRenderer.render(spec(), impact(),
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), draft);

        assertThat(md).contains("## Summary")
                .contains("Impact analysis for 'Tier pricing': 2 repos affected.")
                .contains("- Q1 [blocking]: plan drafting unavailable: down — rerun sdd plan")
                .contains("## Interface Contracts\n- none")
                .contains("## Repo Steps\n- none (drafting unavailable)");
    }
}
