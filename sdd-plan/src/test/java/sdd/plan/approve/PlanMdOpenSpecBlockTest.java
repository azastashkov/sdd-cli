package sdd.plan.approve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code - openspec:} sublist is a THIRD optional sublist inside a repo step, not a new
 * {@code ## } section. Every name in {@link PlanMdParser}'s {@code SECTIONS} is required, so a new
 * section would fail every plan.md written before the OpenSpec export existed with
 * "missing required section". The first test here is the regression that would catch that.
 */
class PlanMdOpenSpecBlockTest {

    private static String plan(String stepTail) {
        return """
                ---
                spec: SPEC-9
                plan_version: 1
                ---

                ## Summary
                S.

                ## Open Questions
                - none

                ## Affected Repos
                - lib-core — seed/SEED — covers: R1 — why: touchpoint class:X

                ## Excluded Candidates
                - none

                ## Execution Order
                1. lib-core

                ## Interface Contracts
                - none

                ## Repo Steps

                ### lib-core
                - covers: R1
                - version_action: minor
                - provides: -
                - consumes: -
                """ + stepTail + """

                ## Generation Notes
                - none
                """;
    }

    @Test
    void aPlanWrittenBeforeTheOpenSpecExportExistedStillParses() {
        // THE regression. Every .plan.md on disk today has no openspec block.
        PlanDocument parsed = PlanMdParser.parse(plan("""
                - files:
                  - src/A.java
                - verification:
                  - ./gradlew test

                Do it."""));

        assertThat(parsed.steps()).singleElement().satisfies(step -> {
            assertThat(step.openspec()).isEmpty();
            assertThat(step.files()).containsExactly("src/A.java");
            assertThat(step.subSpec()).isEqualTo("Do it.");
        });
    }

    @Test
    void theBlockIsReadWhenPresent() {
        PlanDocument parsed = PlanMdParser.parse(plan("""
                - files:
                  - src/A.java
                - verification:
                  - ./gradlew test
                - openspec:
                  - capability: tier-resolution
                  - R1 -> A1, A3

                Do it."""));

        assertThat(parsed.steps()).singleElement().satisfies(step -> {
            assertThat(step.openspec())
                    .containsExactly("capability: tier-resolution", "R1 -> A1, A3");
            // The block must not swallow the sub-spec prose that follows it.
            assertThat(step.subSpec()).isEqualTo("Do it.");
        });
    }

    @Test
    void theBlockIsOptionalIndependentlyOfTheOtherTwoSublists() {
        assertThat(PlanMdParser.parse(plan("""
                - openspec:
                  - capability: tier-resolution

                Do it.""")).steps()).singleElement().satisfies(step -> {
            assertThat(step.files()).isEmpty();
            assertThat(step.verification()).isEmpty();
            assertThat(step.openspec()).containsExactly("capability: tier-resolution");
        });
    }

    @Test
    void anEmptyBlockIsNotAParseError() {
        // A label with no items is structurally fine; OpenSpecPlan decides what it means.
        assertThat(PlanMdParser.parse(plan("""
                - openspec:

                Do it.""")).steps()).singleElement()
                .satisfies(step -> assertThat(step.openspec()).isEmpty());
    }

    @Test
    void aSecondStepAfterTheBlockIsStillFound() {
        PlanDocument parsed = PlanMdParser.parse(plan("""
                - openspec:
                  - capability: a

                First.

                ### svc-a
                - covers: R1
                - version_action: none
                - provides: -
                - consumes: -
                - openspec:
                  - capability: b

                Second."""));

        assertThat(parsed.steps()).hasSize(2);
        assertThat(parsed.steps().get(1).repo()).isEqualTo("svc-a");
        assertThat(parsed.steps().get(1).openspec()).containsExactly("capability: b");
    }

    @Test
    void aBlockBeforeFilesIsNotSilentlyAccepted() {
        // Sublists are positional in this grammar; out of order, the block reads as sub-spec prose
        // rather than as data. Pinned so the ordering is a decision, not an accident.
        PlanDocument parsed = PlanMdParser.parse(plan("""
                - openspec:
                  - capability: a
                - files:
                  - src/A.java

                Do it."""));

        assertThat(parsed.steps()).singleElement().satisfies(step -> {
            assertThat(step.openspec()).containsExactly("capability: a");
            assertThat(step.files()).isEmpty();
            assertThat(step.subSpec()).contains("- files:");
        });
    }
}
