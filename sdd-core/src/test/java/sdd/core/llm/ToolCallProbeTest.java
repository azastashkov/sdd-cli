package sdd.core.llm;

import org.junit.jupiter.api.Test;
import sdd.core.config.ModelEndpoint;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallProbeTest {

    private static ModelEndpoint endpoint(int maxTokens) {
        return new ModelEndpoint("https://x/v1", "some-model", "k", maxTokens, 0.15,
                java.time.Duration.ofSeconds(30), Map.of(), null);
    }

    @Test
    void anEndpointThatReturnsAToolCallPasses() {
        ChatModel model = req -> new ChatResponse(
                new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "report_status", "{\"status\":\"ok\"}")), null),
                "tool_calls", new Usage(30, 12));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model);

        assertThat(r.ok()).isTrue();
        assertThat(r.calledTool()).isTrue();
        assertThat(r.toolName()).isEqualTo("report_status");
        assertThat(r.detail()).contains("report_status");
    }

    @Test
    void proseInsteadOfACallIsReportedAsTheEndpointNotTheRequest() {
        // The instruction leaves no room for prose, so answering anyway is evidence about the
        // endpoint — a gateway stripping tool_calls, or a model without function calling.
        ChatModel model = req -> new ChatResponse(
                ChatMessage.assistant("Sure! The status is ok."), "stop", new Usage(30, 8));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model);

        assertThat(r.ok()).isFalse();
        assertThat(r.calledTool()).isFalse();
        assertThat(r.detail()).contains("cannot drive sdd implement");
        assertThat(r.contentExcerpt()).contains("The status is ok");
    }

    @Test
    void aTruncatedReplyIsDiagnosedAsBudgetNotAsMissingFunctionCalling() {
        // The two causes need opposite fixes, so they must not share a message.
        ChatModel model = req -> new ChatResponse(
                ChatMessage.assistant("<think>considering</think>"), "length", new Usage(30, 4090));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model);

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("raise max_tokens").contains("4090 of 4096");
        assertThat(r.detail()).doesNotContain("cannot drive");
    }

    @Test
    void theProbeOffersExactlyOneToolAndAsksForIt() {
        // If the probe sent no tools, a missing tool_call would prove nothing at all.
        var seen = new java.util.ArrayList<ChatRequest>();
        ChatModel model = req -> {
            seen.add(req);
            return new ChatResponse(ChatMessage.assistant("no"), "stop", new Usage(1, 1));
        };

        ToolCallProbe.probe(endpoint(4096), model);

        assertThat(seen).singleElement().satisfies(req ->
                assertThat(req.tools()).singleElement().satisfies(t ->
                        assertThat(t.name()).isEqualTo("report_status")));
    }
}
