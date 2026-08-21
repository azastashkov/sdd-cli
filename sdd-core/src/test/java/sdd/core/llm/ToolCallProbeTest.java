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

    // ---------------------------------------------------------------- declaration count

    @Test
    void oneDeclarationIsStillTheDefault() {
        int[] sent = {-1};
        ChatModel model = req -> {
            sent[0] = req.tools().size();
            return new ChatResponse(new ChatMessage("assistant", null,
                    List.of(new ToolCall("1", "report_status", "{\"status\":\"ok\"}")), null),
                    "tool_calls", new Usage(30, 12));
        };

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model);

        assertThat(sent[0]).isEqualTo(1);
        assertThat(r.declarationsSent()).isEqualTo(1);
    }

    @Test
    void aCountProbePutsThatManyDeclarationsOnTheWire() {
        List<List<ToolSpec>> seen = new java.util.ArrayList<>();
        ChatModel model = req -> {
            seen.add(req.tools());
            return new ChatResponse(new ChatMessage("assistant", null,
                    List.of(new ToolCall("1", "report_status", "{\"status\":\"ok\"}")), null),
                    "tool_calls", new Usage(30, 12));
        };

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model, 12);

        assertThat(seen).singleElement().satisfies(tools -> {
            assertThat(tools).hasSize(12);
            // The real tool leads, so a failure is about carrying the set, not choosing within it.
            assertThat(tools.get(0).name()).isEqualTo("report_status");
            // Duplicate names would be a malformed request -- it would fail for a reason that is
            // not the count, which is the one thing this probe must not confuse itself about.
            assertThat(tools).extracting(ToolSpec::name).doesNotHaveDuplicates();
            // Decoys carry real schemas: the payload shape is what degrades, not the name count.
            assertThat(tools.get(1).parametersSchemaJson()).contains("properties").contains("type");
        });
        assertThat(r.declarationsSent()).isEqualTo(12);
    }

    @Test
    void aCountBeyondTheDecoyPoolKeepsGeneratingUniqueNames() {
        List<List<ToolSpec>> seen = new java.util.ArrayList<>();
        ChatModel model = req -> {
            seen.add(req.tools());
            return new ChatResponse(new ChatMessage("assistant", null,
                    List.of(new ToolCall("1", "report_status", "{}")), null),
                    "tool_calls", new Usage(30, 12));
        };

        ToolCallProbe.probe(endpoint(4096), model, 40);

        assertThat(seen.get(0)).hasSize(40)
                .extracting(ToolSpec::name).doesNotHaveDuplicates();
    }

    @Test
    void aCountBelowOneStillSendsTheRealTool() {
        List<List<ToolSpec>> seen = new java.util.ArrayList<>();
        ChatModel model = req -> {
            seen.add(req.tools());
            return new ChatResponse(new ChatMessage("assistant", null,
                    List.of(new ToolCall("1", "report_status", "{}")), null),
                    "tool_calls", new Usage(30, 12));
        };

        ToolCallProbe.probe(endpoint(4096), model, 0);

        assertThat(seen.get(0)).extracting(ToolSpec::name).containsExactly("report_status");
    }

    /** Choosing a decoy is a quality problem; the transport question still answered yes. */
    @Test
    void callingTheWrongToolStillCountsAsCallingOneAndSaysSo() {
        ChatModel model = req -> new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall("1", "search_code", "{\"path\":\"x\"}")), null),
                "tool_calls", new Usage(30, 12));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model, 10);

        assertThat(r.ok()).isTrue();
        assertThat(r.toolName()).isEqualTo("search_code");
        assertThat(r.detail()).contains("chose the wrong one of 10");
    }

    /** An HTTP failure carries the count, so a sweep's transcript says what it was asking. */
    @Test
    void aFailureStillReportsHowManyDeclarationsItWasCarrying() {
        ChatModel model = req -> {
            throw new ModelException("HTTP 500 from the gateway", 500);
        };

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model, 9);

        assertThat(r.ok()).isFalse();
        assertThat(r.declarationsSent()).isEqualTo(9);
        assertThat(r.detail()).contains("HTTP 500");
    }

    // ---------------------------------------------------------------- the nudge

    private static ChatResponse prose(String said) {
        return new ChatResponse(ChatMessage.assistant(said), "stop", new Usage(30, 8));
    }

    private static ChatResponse called() {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall("1", "report_status", "{\"status\":\"ok\"}")), null),
                "tool_calls", new Usage(30, 12));
    }

    @Test
    void aColdSuccessCostsNoSecondCall() {
        int[] calls = {0};
        ChatModel model = req -> {
            calls[0]++;
            return called();
        };

        ToolCallProbe.Nudged n = ToolCallProbe.probeNudged(endpoint(4096), model, 5);

        assertThat(calls[0]).isEqualTo(1);
        assertThat(n.cold().ok()).isTrue();
        assertThat(n.afterNudge()).isNull();
        assertThat(n.recovered()).isFalse();
    }

    /** The shape must match the loop's next turn, or the number does not transfer. */
    @Test
    void theRetryReplaysExactlyWhatTheLoopWouldSend() {
        List<List<ChatMessage>> sent = new java.util.ArrayList<>();
        ChatModel model = req -> {
            sent.add(req.messages());
            return sent.size() == 1 ? prose("Sure, the status is ok.") : called();
        };

        ToolCallProbe.Nudged n = ToolCallProbe.probeNudged(endpoint(4096), model, 3);

        assertThat(sent).hasSize(2);
        List<ChatMessage> retry = sent.get(1);
        assertThat(retry).hasSize(4);
        assertThat(retry.get(0).role()).isEqualTo("system");
        assertThat(retry.get(1)).isEqualTo(sent.get(0).get(1));           // the same instruction
        assertThat(retry.get(2).role()).isEqualTo("assistant");
        assertThat(retry.get(2).content()).isEqualTo("Sure, the status is ok.");
        assertThat(retry.get(3).role()).isEqualTo("user");
        assertThat(retry.get(3).content()).isEqualTo(ToolCallProbe.NUDGE);
        assertThat(n.recovered()).isTrue();
    }

    @Test
    void aGatewayThatKeepsAnsweringInProseIsReportedAsNotRecovered() {
        ChatModel model = req -> prose("Still prose.");

        ToolCallProbe.Nudged n = ToolCallProbe.probeNudged(endpoint(4096), model, 3);

        assertThat(n.cold().ok()).isFalse();
        assertThat(n.afterNudge()).isNotNull();
        assertThat(n.afterNudge().ok()).isFalse();
        assertThat(n.recovered()).isFalse();
    }

    /** Nothing came back at all, so there is no prose turn to replay and no retry to make. */
    @Test
    void aTransportFailureIsNotNudged() {
        int[] calls = {0};
        ChatModel model = req -> {
            calls[0]++;
            throw new ModelException("HTTP 500", 500);
        };

        ToolCallProbe.Nudged n = ToolCallProbe.probeNudged(endpoint(4096), model, 9);

        assertThat(calls[0]).isEqualTo(1);
        assertThat(n.afterNudge()).isNull();
        assertThat(n.recovered()).isFalse();
    }

    @Test
    void theRetryCarriesTheSameDeclarationSet() {
        List<Integer> counts = new java.util.ArrayList<>();
        ChatModel model = req -> {
            counts.add(req.tools().size());
            return counts.size() == 1 ? prose("nope") : called();
        };

        ToolCallProbe.probeNudged(endpoint(4096), model, 11);

        assertThat(counts).containsExactly(11, 11);
    }
}
