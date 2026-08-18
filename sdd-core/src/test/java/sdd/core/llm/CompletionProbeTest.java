package sdd.core.llm;

import org.junit.jupiter.api.Test;
import sdd.core.config.ModelEndpoint;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionProbeTest {

    private static ModelEndpoint endpoint(int maxTokens) {
        return new ModelEndpoint("https://x/v1", "some-model", "k", maxTokens, 0.15,
                java.time.Duration.ofSeconds(30), Map.of(), null);
    }

    private static ChatModel replying(String content, String finish, int completionTokens) {
        return req -> new ChatResponse(ChatMessage.assistant(content), finish,
                new Usage(10, completionTokens));
    }

    @Test
    void aTruncatedReasoningReplyIsReportedWithTheNumbersThatExplainIt() {
        // The signature of "thought until it ran out": budget spent, no answer produced. That pair
        // is what turns finish_reason=length from a symptom into a diagnosis.
        CompletionProbe.Result r = CompletionProbe.probe(endpoint(8192),
                replying("<think>" + "reasoning ".repeat(50) + "</think>", "length", 8190));

        assertThat(r.ok()).isFalse();
        assertThat(r.finishReason()).isEqualTo("length");
        assertThat(r.completionTokens()).isEqualTo(8190);
        assertThat(r.maxTokensSent()).isEqualTo(8192);
        assertThat(r.reasoningChars()).isGreaterThan(0);
        assertThat(r.answerChars()).isZero();
        assertThat(r.detail()).contains("TRUNCATED").contains("8190 of 8192");
        assertThat(r.rawExcerpt()).startsWith("<think>");
    }

    @Test
    void aHealthyReplyPassesAndReportsNoReasoning() {
        CompletionProbe.Result r = CompletionProbe.probe(endpoint(4096),
                replying("{\"card_md\":\"ok\",\"card_line\":\"ok\"}", "stop", 18));

        assertThat(r.ok()).isTrue();
        assertThat(r.reasoningChars()).isZero();
        assertThat(r.answerChars()).isGreaterThan(0);
    }

    @Test
    void aReplyThatStoppedCleanlyButSaidNothingStillFails() {
        // finish_reason=stop with an empty body is not success: cards would report it as
        // "unparseable", one step further from the cause.
        CompletionProbe.Result r = CompletionProbe.probe(endpoint(4096), replying("", "stop", 0));

        assertThat(r.ok()).isFalse();
        assertThat(r.answerChars()).isZero();
    }

    @Test
    void aTransportFailureIsReportedAsItsOwnMessageRatherThanAsTruncation() {
        ChatModel broken = req -> { throw new ModelException("connection refused", 0); };

        CompletionProbe.Result r = CompletionProbe.probe(endpoint(4096), broken);

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isEqualTo("connection refused");
        assertThat(r.finishReason()).isNull();
    }
}
