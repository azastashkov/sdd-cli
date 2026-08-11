package sdd.plan.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecValidatorTest {

    private static NormalizedSpec valid() {
        return new NormalizedSpec("SPEC-1", "Loyalty tiers", "ana", "draft",
                "Add loyalty tiers to pricing.", "",
                List.of(new SpecItem("R1", "Price response includes the customer tier.")),
                List.of(new SpecItem("A1", "GET /price returns tier for gold customers.")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void validSpecHasNoProblems() {
        assertThat(SpecValidator.problems(valid())).isEmpty();
    }

    @Test
    void blankFrontMatterAndEmptyRequiredSectionsAreNamed() {
        NormalizedSpec s = new NormalizedSpec("", "Loyalty tiers", " ", "draft",
                "", "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(SpecValidator.problems(s)).contains(
                "front matter: id is blank",
                "front matter: owner is blank",
                "Goal section is empty",
                "Requirements: at least one R item is required",
                "Acceptance Criteria: at least one A item is required");
    }

    @Test
    void idShapeDuplicatesAndBlankTextAreNamed() {
        NormalizedSpec s = new NormalizedSpec("SPEC-1", "T", "o", "draft", "G", "",
                List.of(new SpecItem("R1", "a"), new SpecItem("R1", "b"), new SpecItem("X2", "c"),
                        new SpecItem("R3", " ")),
                List.of(new SpecItem("A1", "ok")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(SpecValidator.problems(s)).contains(
                "Requirements: duplicate id 'R1'",
                "Requirements: id 'X2' must match R<number>",
                "Requirements: R3 has no text");
    }

    @Test
    void listsAreDefensivelyCopiedAndNullsRejected() {
        java.util.List<SpecItem> mutable = new java.util.ArrayList<>(
                List.of(new SpecItem("R1", "x")));
        NormalizedSpec s = new NormalizedSpec("i", "t", "o", "s", "g", "",
                mutable, List.of(new SpecItem("A1", "y")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        mutable.clear();
        assertThat(s.requirements()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NormalizedSpec(null, "t", "o", "s", "g", "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
