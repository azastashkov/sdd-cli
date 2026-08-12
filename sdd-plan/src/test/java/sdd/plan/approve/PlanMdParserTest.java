package sdd.plan.approve;

import org.junit.jupiter.api.Test;

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
}
