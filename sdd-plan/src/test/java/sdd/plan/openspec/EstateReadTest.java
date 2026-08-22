package sdd.plan.openspec;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class EstateReadTest {

    private static final String DESIGN = """
            ## Context
            The estate-wide view.

            - resolution: this one is prose, not an answer

            ## Open Questions
            - Q1 [blocking]: Which method?
              - resolution: Use tierFor(String).
            - Q2: Optional nicety?
            - Q3 [blocking]: Who owns the config?
              - resolution: trading-core owns it.

            ## Risks / Trade-offs
            - C1: No schema change.
            """;

    @Test
    void readsAResolutionWrittenUnderItsQuestion() {
        assertThat(EstateRead.resolutions(DESIGN))
                .containsExactly(entry(1, "Use tierFor(String)."), entry(3, "trading-core owns it."));
    }

    /** An unanswered question must be absent, not present and empty: absent is what blocks approve. */
    @Test
    void anUnansweredQuestionIsAbsent() {
        assertThat(EstateRead.resolutions(DESIGN)).doesNotContainKey(2);
    }

    /**
     * A line that looks like a resolution but sits in another section is prose. Reading it would
     * resolve a blocking question nobody answered, which is the one failure this must not have.
     */
    @Test
    void aResolutionShapedLineOutsideTheSectionIsIgnored() {
        assertThat(EstateRead.resolutions("## Context\n  - resolution: not an answer\n")).isEmpty();
    }

    /** A resolution must follow ITS question — attaching an answer to the wrong one is worse
     *  than reading none. */
    @Test
    void aResolutionSeparatedFromItsQuestionByOtherProseIsNotAttached() {
        String stray = """
                ## Open Questions
                - Q1 [blocking]: Which method?
                - some other note
                  - resolution: Use tierFor(String).
                """;

        assertThat(EstateRead.resolutions(stray)).isEmpty();
    }

    @Test
    void aBlankLineBetweenAQuestionAndItsResolutionIsTolerated() {
        String spaced = "## Open Questions\n- Q1 [blocking]: Which?\n\n  - resolution: This one.\n";

        assertThat(EstateRead.resolutions(spaced)).containsExactly(entry(1, "This one."));
    }

    @Test
    void anAbsentDocumentIsNotACrash() {
        assertThat(EstateRead.resolutions(null)).isEmpty();
        assertThat(EstateRead.resolutions("")).isEmpty();
    }
}
