package sdd.plan.approve;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.PlanMdRenderer;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanMdParserTest {

    static final String MINIMAL = """
            ---
            spec: SPEC-9
            plan_version: 2
            ---

            ## Summary
            Do the thing.

            ## Open Questions
            - Q1 [blocking]: Which method?
              - resolution: Use tierFor(String).
            - Q2: Optional nicety?

            ## Affected Repos
            - lib-core — seed/SEED — covers: R1 — why: touchpoint class:LoyaltyTier

            ## Excluded Candidates
            - none

            ## Execution Order
            1. lib-core

            ## Interface Contracts
            - none

            ## Repo Steps
            - none

            ## Generation Notes
            - drafter note
            """;

    /** Pre-5C-1 shape: a contract entry with a prose body but no ```contract fence. */
    static final String LEGACY_PLAN_MD = """
            ---
            spec: SPEC-9
            plan_version: 1
            ---

            ## Summary
            S.

            ## Open Questions
            - none

            ## Affected Repos
            - lib-core — seed/SEED — covers: R1 — why: w

            ## Excluded Candidates
            - none

            ## Execution Order
            1. lib-core

            ## Interface Contracts

            ### C-1 (java-api) — lib-core -> svc-a
            ```yaml
            Add to existing JdbcTierResolver
            ```

            ## Repo Steps
            - none

            ## Generation Notes
            - none
            """;

    @Test
    void parsesFrontMatterSummaryQuestionsWithResolutionsAndNotes() {
        PlanDocument doc = PlanMdParser.parse(MINIMAL);

        assertThat(doc.specId()).isEqualTo("SPEC-9");
        assertThat(doc.planVersion()).isEqualTo(2);
        assertThat(doc.summary()).isEqualTo("Do the thing.");
        assertThat(doc.questions()).containsExactly(
                new PlanDocument.PlanQuestion(1, true, "Which method?", "Use tierFor(String)."),
                new PlanDocument.PlanQuestion(2, false, "Optional nicety?", null));
        assertThat(doc.notes()).containsExactly("drafter note");
    }

    @Test
    void noneSentinelsYieldEmptyLists() {
        PlanDocument doc = PlanMdParser.parse(MINIMAL.replace("""
                - Q1 [blocking]: Which method?
                  - resolution: Use tierFor(String).
                - Q2: Optional nicety?""", "- none"));

        assertThat(doc.questions()).isEmpty();
    }

    @Test
    void resolutionOnANonBlockingQuestionIsKept() {
        PlanDocument doc = PlanMdParser.parse(MINIMAL.replace("- Q2: Optional nicety?",
                "- Q2: Optional nicety?\n  - resolution: sure."));

        assertThat(doc.questions().get(1)).isEqualTo(
                new PlanDocument.PlanQuestion(2, false, "Optional nicety?", "sure."));
    }

    @Test
    void frontMatterAndStructureViolationsFailWithLineNumbers() {
        assertThatThrownBy(() -> PlanMdParser.parse("## Summary\nX\n"))
                .isInstanceOf(PlanParseException.class)
                .hasMessageStartingWith("line 1: plan must start with '---' front matter");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("plan_version: 2", "version: 2")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageStartingWith("line 3: expected 'plan_version: <n>'");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("- Q2: Optional nicety?", "* Q2 bad")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("Open Questions items must look like");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("## Generation Notes\n- drafter note\n", "")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("missing required section '## Generation Notes'");
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace("## Excluded Candidates", "## Excluded")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("unknown section");
    }

    @Test
    void resolutionWithoutAQuestionFails() {
        assertThatThrownBy(() -> PlanMdParser.parse(MINIMAL.replace(
                "- Q1 [blocking]: Which method?\n  - resolution: Use tierFor(String).",
                "  - resolution: orphan")))
                .isInstanceOf(PlanParseException.class)
                .hasMessageContaining("resolution without a question");
    }

    @Test
    void theContractFenceRoundTripsThroughTheParser() {
        NormalizedSpec spec = new NormalizedSpec("SPEC-9", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ImpactResult impact = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"), List.of("owns it"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
        PlanDrafter.Draft draft = new PlanDrafter.Draft("S.", List.of(),
                List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "Add to existing JdbcTierResolver", null,
                        List.of("com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier"))),
                List.of(), List.of(), false);
        String md = PlanMdRenderer.render(spec, impact,
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), draft);

        PlanDocument doc = PlanMdParser.parse(md);

        assertThat(doc.contracts()).singleElement()
                .extracting(PlanDocument.PlanContract::declared, InstanceOfAssertFactories.list(String.class))
                .containsExactly("com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier");
        // the prose block must be untouched by the new fence
        assertThat(doc.contracts().get(0).body()).contains("Add to existing JdbcTierResolver");
    }

    @Test
    void aDeclaredLineCarryingAFenceMarkerSurvivesRenderAndReparseIntact() {
        NormalizedSpec spec = new NormalizedSpec("SPEC-9", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ImpactResult impact = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"), List.of("owns it"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
        // A bare "```" declared entry would, unescaped, close the `contract` fence early and
        // throw off the odd/even fence count PlanMdParser tracks for the rest of the document.
        PlanDrafter.Draft draft = new PlanDrafter.Draft("S.", List.of(),
                List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "Add to existing JdbcTierResolver", null,
                        List.of("com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier",
                                "```"))),
                List.of(), List.of("trailing note"), false);

        String md = PlanMdRenderer.render(spec, impact,
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), draft);

        PlanDocument doc = PlanMdParser.parse(md);   // must not throw, must not merge sections

        assertThat(doc.contracts()).singleElement()
                .extracting(PlanDocument.PlanContract::declared, InstanceOfAssertFactories.list(String.class))
                .containsExactly(
                        "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier", "'''");
        // the rest of the document parsed as its own section, untouched by the neutralized fence
        assertThat(doc.notes()).containsExactly("trailing note");
    }

    @Test
    void aPreExistingPlanWithNoContractFenceParsesWithEmptyDeclarations() {
        PlanDocument doc = PlanMdParser.parse(LEGACY_PLAN_MD);
        assertThat(doc.contracts().get(0).declared()).isEmpty();
    }
}
