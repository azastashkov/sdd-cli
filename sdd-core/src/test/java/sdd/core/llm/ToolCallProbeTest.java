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
                        List.of(new ToolCall("1", "sdd_probe_ack", "{\"status\":\"ok\"}")), null),
                "tool_calls", new Usage(30, 12));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model);

        assertThat(r.ok()).isTrue();
        assertThat(r.calledTool()).isTrue();
        assertThat(r.toolName()).isEqualTo("sdd_probe_ack");
        assertThat(r.detail()).contains("sdd_probe_ack");
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
                        assertThat(t.name()).isEqualTo("sdd_probe_ack")));
    }

    // ---------------------------------------------------------------- declaration count

    @Test
    void oneDeclarationIsStillTheDefault() {
        int[] sent = {-1};
        ChatModel model = req -> {
            sent[0] = req.tools().size();
            return new ChatResponse(new ChatMessage("assistant", null,
                    List.of(new ToolCall("1", "sdd_probe_ack", "{\"status\":\"ok\"}")), null),
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
                    List.of(new ToolCall("1", "sdd_probe_ack", "{\"status\":\"ok\"}")), null),
                    "tool_calls", new Usage(30, 12));
        };

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model, 12);

        assertThat(seen).singleElement().satisfies(tools -> {
            assertThat(tools).hasSize(12);
            // The real tool leads, so a failure is about carrying the set, not choosing within it.
            assertThat(tools.get(0).name()).isEqualTo("sdd_probe_ack");
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
                    List.of(new ToolCall("1", "sdd_probe_ack", "{}")), null),
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
                    List.of(new ToolCall("1", "sdd_probe_ack", "{}")), null),
                    "tool_calls", new Usage(30, 12));
        };

        ToolCallProbe.probe(endpoint(4096), model, 0);

        assertThat(seen.get(0)).extracting(ToolSpec::name).containsExactly("sdd_probe_ack");
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
                List.of(new ToolCall("1", "sdd_probe_ack", "{\"status\":\"ok\"}")), null),
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

    // ---------------------------------------------------------------- arguments-only

    /**
     * The fault a live gateway actually produced, and the reason the probe's own prompt changed:
     * the model complied but the reply does not say WHICH tool, so it cannot be run.
     */
    @Test
    void argumentsWithNoFunctionNameAreCalledThatAndNotProse() {
        ChatModel model = req -> new ChatResponse(
                ChatMessage.assistant("{\"status\": \"ok\"}"), "stop", new Usage(30, 6));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model, 11);

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("ARGUMENTS ONLY")
                .contains("11 declared tools")
                .contains("Not the same fault as answering in prose");
        assertThat(r.detail()).doesNotContain("answered in prose with");
    }

    @Test
    void realProseIsStillCalledProse() {
        ChatModel model = req -> new ChatResponse(
                ChatMessage.assistant("The system status is ok."), "stop", new Usage(30, 6));

        assertThat(ToolCallProbe.probe(endpoint(4096), model, 11).detail())
                .contains("answered in prose").doesNotContain("ARGUMENTS ONLY");
    }

    /** A properly named call in content is TextToolCalls' job, not an arguments-only reply. */
    @Test
    void aNamedJsonCallIsNotMistakenForArgumentsOnly() {
        ChatModel model = req -> new ChatResponse(
                ChatMessage.assistant("{\"name\": \"sdd_probe_ack\", \"arguments\": {}}"),
                "stop", new Usage(30, 6));

        assertThat(ToolCallProbe.probe(endpoint(4096), model, 11).detail())
                .doesNotContain("ARGUMENTS ONLY");
    }

    /**
     * The instruction must neither name the tool nor describe an outcome a name can be built from.
     *
     * <p>Both were tried live and both measured the probe rather than the endpoint: naming it
     * produced arguments-only replies, and describing the outcome produced a name paraphrased from
     * the sentence. The tool name is now unguessable so that emitting it PROVES the declaration
     * list was read.
     */
    @Test
    void theInstructionDoesNotNameTheToolItIsTesting() {
        List<List<ChatMessage>> sent = new java.util.ArrayList<>();
        ChatModel model = req -> {
            sent.add(req.messages());
            return new ChatResponse(new ChatMessage("assistant", null,
                    List.of(new ToolCall("1", "sdd_probe_ack", "{}")), null),
                    "tool_calls", new Usage(30, 12));
        };

        ToolCallProbe.probe(endpoint(4096), model, 3);

        String user = sent.get(0).get(1).content();
        assertThat(user).doesNotContain("sdd_probe_ack");
        // ...and carries no word the name could be paraphrased out of.
        assertThat(user.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("probe").doesNotContain("ack").doesNotContain("report");
    }

    /** The live shape: a well-formed call to a tool that was never offered. */
    @Test
    void aCallNamingAnUndeclaredToolIsItsOwnDiagnosis() {
        ChatModel model = req -> new ChatResponse(ChatMessage.assistant(
                "{\"function\": \"report_system_status\", \"parameters\": {\"status\": \"ok\"}}"),
                "stop", new Usage(30, 9));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model, 11);

        assertThat(r.ok()).isFalse();
        assertThat(r.fault()).isEqualTo(ToolCallProbe.Fault.UNDECLARED_NAME);
        assertThat(r.detail()).contains("report_system_status").contains("INVENTING tool names");
        assertThat(r.detail()).doesNotContain("ARGUMENTS ONLY").doesNotContain("answered in prose");
    }

    @Test
    void aCallNamingADeclaredToolIsNotFlaggedAsInvented() {
        ChatModel model = req -> new ChatResponse(ChatMessage.assistant(
                "{\"function\": \"sdd_probe_ack\", \"parameters\": {\"status\": \"ok\"}}"),
                "stop", new Usage(30, 9));

        // Structured parsing is HttpChatModel's job; the probe sees no tool_calls here, but it must
        // not accuse the model of inventing a name it was actually offered.
        assertThat(ToolCallProbe.probe(endpoint(4096), model, 11).fault())
                .isNotEqualTo(ToolCallProbe.Fault.UNDECLARED_NAME);
    }

    /** glm-5.1's live shape: an invented name, in tool/input keys, inside a ```json fence. */
    @Test
    void aFencedCallWithAnInventedNameIsNotReportedAsProse() {
        ChatModel model = req -> new ChatResponse(ChatMessage.assistant(
                "```json\n{ \"tool\": \"set_status\", \"input\": { \"status\": \"ok\" } }\n```"),
                "stop", new Usage(30, 12));

        ToolCallProbe.Result r = ToolCallProbe.probe(endpoint(4096), model, 10);

        assertThat(r.fault()).isEqualTo(ToolCallProbe.Fault.UNDECLARED_NAME);
        assertThat(r.detail()).contains("set_status").contains("INVENTING tool names");
    }

    /** And the same shape naming a DECLARED tool is a call, not a fault. */
    @Test
    void aFencedCallNamingADeclaredToolIsNotAFault() {
        ChatModel model = req -> new ChatResponse(ChatMessage.assistant(
                "```json\n{ \"tool\": \"sdd_probe_ack\", \"input\": { \"status\": \"ok\" } }\n```"),
                "stop", new Usage(30, 12));

        assertThat(ToolCallProbe.probe(endpoint(4096), model, 10).fault())
                .isNotEqualTo(ToolCallProbe.Fault.UNDECLARED_NAME);
    }
}
