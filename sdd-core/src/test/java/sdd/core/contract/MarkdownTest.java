package sdd.core.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownTest {

    @Test
    void aFenceMarkerIsNeutralized() {
        assertThat(Markdown.neutralizeFences("body with a ```yaml fence marker```"))
                .isEqualTo("body with a '''yaml fence marker'''");
    }

    @Test
    void plainTextWithNoFenceIsUnchanged() {
        assertThat(Markdown.neutralizeFences("nothing to escape here"))
                .isEqualTo("nothing to escape here");
    }

    @Test
    void applyingItTwiceIsTheSameAsApplyingItOnce() {
        // Two call sites compose today: PlanDrafter sanitizes at parse time, PlanMdRenderer
        // escapes again at render time. A second pass over already-neutralized text must not
        // mangle it further.
        String once = Markdown.neutralizeFences("smuggled ``` fence");
        String twice = Markdown.neutralizeFences(once);
        assertThat(twice).isEqualTo(once);
    }
}
