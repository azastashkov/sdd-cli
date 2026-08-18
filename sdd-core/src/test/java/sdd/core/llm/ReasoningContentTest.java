package sdd.core.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningContentTest {

    @Test
    void stripsAThinkBlockThatPrecedesTheAnswer() {
        // The shape GigaChat actually returns: reasoning inline, then the JSON sdd must parse.
        assertThat(ReasoningContent.strip("<think>Let me consider the repo.</think>{\"card_md\":\"m\"}"))
                .isEqualTo("{\"card_md\":\"m\"}");
    }

    @Test
    void stripsSeveralBlocksAndKeepsEverythingBetweenThem() {
        assertThat(ReasoningContent.strip("A<think>x</think>B<think>y</think>C")).isEqualTo("ABC");
    }

    @Test
    void anUnclosedBlockSwallowsTheRestRatherThanLeakingReasoningAsAnAnswer() {
        // Truncation mid-thought: what follows was never the answer, and passing it on would hand a
        // JSON parser a sentence.
        assertThat(ReasoningContent.strip("{\"a\":1}<think>still thinking")).isEqualTo("{\"a\":1}");
    }

    @Test
    void contentWithoutTagsIsReturnedUnchangedIncludingItsWhitespace() {
        // Identity on the common path matters: every other provider goes through here too.
        String plain = "  {\"card_md\":\"m\"}  ";
        assertThat(ReasoningContent.strip(plain)).isSameAs(plain);
        assertThat(ReasoningContent.strip(null)).isNull();
    }

    @Test
    void tagCasingDoesNotMatter() {
        assertThat(ReasoningContent.strip("<THINK>x</THINK>done")).isEqualTo("done");
    }
}
