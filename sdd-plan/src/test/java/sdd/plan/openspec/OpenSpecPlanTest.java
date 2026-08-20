package sdd.plan.openspec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenSpecPlanTest {

    private static final List<String> COVERS = List.of("R1", "R2");
    private static final List<String> ACCEPT = List.of("A1", "A2", "A3");

    private OpenSpecPlan parse(String... lines) {
        return OpenSpecPlan.parse(List.of(lines), COVERS, ACCEPT);
    }

    @Test
    void parsesACapabilityAndAnAllocation() {
        OpenSpecPlan plan = parse("capability: tier-resolution", "R1 -> A1, A3", "R2 -> A2");

        assertThat(plan.problems()).isEmpty();
        assertThat(plan.capability()).isEqualTo("tier-resolution");
        assertThat(plan.acceptanceFor()).containsExactly(
                org.assertj.core.api.Assertions.entry("R1", List.of("A1", "A3")),
                org.assertj.core.api.Assertions.entry("R2", List.of("A2")));
    }

    @Test
    void anAbsentBlockIsNotAMalformedOne() {
        // A plan.md written before this feature existed has no block at all. That must be silent,
        // not a Gate-1 problem — otherwise every existing plan fails approve.
        assertThat(OpenSpecPlan.absent().problems()).isEmpty();
        assertThat(OpenSpecPlan.absent().isAbsent()).isTrue();
        assertThat(parse().problems()).isEmpty();
    }

    @Test
    void noneMeansTheRequirementHasNoAcceptanceCriterion() {
        assertThat(parse("R1 -> none").acceptanceFor()).containsEntry("R1", List.of());
        assertThat(parse("R1 -> -").acceptanceFor()).containsEntry("R1", List.of());
        assertThat(parse("R1 -> NONE").problems()).isEmpty();
    }

    @Test
    void aCapabilityThatIsNotKebabIsCoercedAndReported() {
        // Coerced rather than rejected: the human meant a capability, and failing Gate 1 over
        // letter case helps nobody. But it is reported, so the next regeneration shows the fix.
        OpenSpecPlan plan = parse("capability: Tier Resolution");

        assertThat(plan.capability()).isEqualTo("tier-resolution");
        assertThat(plan.problems()).singleElement().asString()
                .contains("not a legal OpenSpec path segment").contains("tier-resolution");
    }

    @Test
    void anAllocationForARequirementThisStepDoesNotCoverIsAProblem() {
        // It means the human edited `- covers:` and not the openspec block, or the reverse.
        assertThat(parse("R9 -> A1").problems()).singleElement().asString()
                .contains("R9").contains("does not cover it");
    }

    @Test
    void anAcceptanceIdThatIsNotInTheSpecIsAProblem() {
        assertThat(parse("R1 -> A9").problems()).singleElement().asString()
                .contains("A9").contains("not in the spec's Acceptance Criteria");
    }

    @Test
    void somethingThatIsNotAnAcceptanceIdAtAllIsAProblem() {
        assertThat(parse("R1 -> the smoke test").problems())
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p).contains("is not an acceptance id"));
    }

    @Test
    void duplicatesAreProblemsNotSilentOverwrites() {
        assertThat(parse("capability: a", "capability: b").problems())
                .anySatisfy(p -> assertThat(p).contains("capability declared more than once"));
        assertThat(parse("R1 -> A1", "R1 -> A2").problems())
                .anySatisfy(p -> assertThat(p).contains("R1 is allocated more than once"));
    }

    @Test
    void anUnrecognisedLineNamesWhatWasExpected() {
        assertThat(parse("R1 covers A1").problems()).singleElement().asString()
                .contains("unrecognised line").contains("capability: <name>").contains("R1 -> A1");
    }

    @Test
    void repeatedAcceptanceIdsWithinOneAllocationAreDeduped() {
        assertThat(parse("R1 -> A1, A1, A2").acceptanceFor())
                .containsEntry("R1", List.of("A1", "A2"));
    }

    @Test
    void renderRoundTripsThroughTheParser() {
        // The rendered lines are what plan.json freezes, so they must parse back to the same thing.
        OpenSpecPlan original = parse("capability: tier-resolution", "R1 -> A1, A3", "R2 -> none");

        OpenSpecPlan reparsed = OpenSpecPlan.parse(original.render(), COVERS, ACCEPT);

        assertThat(reparsed.problems()).isEmpty();
        assertThat(reparsed.capability()).isEqualTo(original.capability());
        assertThat(reparsed.acceptanceFor()).isEqualTo(original.acceptanceFor());
    }
}
