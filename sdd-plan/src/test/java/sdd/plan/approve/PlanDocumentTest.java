package sdd.plan.approve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanDocumentTest {

    @Test
    void recordsAreDefensiveNullHostileAndResolutionIsNullable() {
        java.util.List<List<String>> mutableOrder = new java.util.ArrayList<>(
                List.of(List.of("a")));
        PlanDocument doc = new PlanDocument("SPEC-1", 1, "S.",
                List.of(new PlanDocument.PlanQuestion(1, true, "q", null)),
                List.of(new PlanDocument.PlanRepo("a", "seed", "SEED", List.of("R1"), "why")),
                List.of(), mutableOrder, List.of(), List.of(), List.of());
        mutableOrder.clear();
        assertThat(doc.order()).hasSize(1);
        assertThat(doc.questions().get(0).resolution()).isNull();

        assertThatThrownBy(() -> new PlanDocument.PlanQuestion(1, true, null, "r"))
                .isInstanceOf(NullPointerException.class);

        PlanParseException e = new PlanParseException(7, "boom");
        assertThat(e.getMessage()).isEqualTo("line 7: boom");
        assertThat(e.line()).isEqualTo(7);
    }

    @Test
    void orderInnerListsAreImmutable() {
        // Create mutable inner list and outer list
        java.util.List<String> mutableInnerList = new java.util.ArrayList<>(List.of("a"));
        java.util.List<List<String>> mutableOrder = new java.util.ArrayList<>(
                List.of(mutableInnerList));

        PlanDocument doc = new PlanDocument("SPEC-1", 1, "S.",
                List.of(), List.of(), List.of(), mutableOrder, List.of(), List.of(), List.of());

        // Mutate the inner list after construction
        mutableInnerList.add("b");

        // Assert that doc.order().get(0) is unaffected
        assertThat(doc.order().get(0)).containsExactly("a");

        // Assert that the inner list in the document is immutable
        assertThatThrownBy(() -> doc.order().get(0).add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
