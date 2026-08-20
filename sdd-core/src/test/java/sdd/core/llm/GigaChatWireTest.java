package sdd.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

/**
 * The request shape the corp GigaChat gateway is observed to receive from clients that reach it
 * WITHOUT the {@code gpt2giga} proxy, asserted field by field against a capture of that traffic.
 *
 * <p>This is the whole point of {@link WireFormat#GIGACHAT}: sdd's function calling failed by
 * declaration count through the proxy (one declaration 20/20, six 13/20, nine 0/20 — fast, uniform
 * HTTP 500s, i.e. a rejection rather than a timeout), while the gateway itself takes OpenAI
 * {@code tools} directly and answers with several parallel {@code tool_calls} per turn. Every
 * assertion below is a fact read off that capture, not a guess about what a gateway might like.
 */
class GigaChatWireTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"\\n\\n\\n",
              "reasoning_content":"The user wants a thorough exploration of the project structure.",
              "tool_calls":[
                {"id":"call_c784f2","type":"function",
                 "function":{"name":"list_directory","arguments":"{\\"path\\":\\"/src\\"}"}},
                {"id":"call_44062f","type":"function",
                 "function":{"name":"glob","arguments":"{\\"path\\":\\"/src\\",\\"pattern\\":\\"**/*.yml\\"}"}}]},
              "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":42,"completion_tokens":7}}
            """;

    private static ChatModel model(WireFormat wire) {
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "Qwen3.6-35B", "sk-key",
                256, 0.0, Duration.ofSeconds(5), Map.of(), null, null, wire);
        return new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { });
    }

    /** A full round trip: system, user, an assistant turn with two parallel calls, both results. */
    private static ChatRequest conversation(ChatMessage assistantTurn) {
        return new ChatRequest("Qwen3.6-35B", List.of(
                ChatMessage.system("You are a file search specialist agent."),
                ChatMessage.user("Very thoroughly explore the src directory structure."),
                assistantTurn,
                ChatMessage.tool("call_c784f2", "Listed 2 item(s) in /src:\n[DIR] main\n[DIR] test"),
                ChatMessage.tool("call_44062f", "Found 3 file(s) matching **/*.yml")),
                List.of(new ToolSpec("list_directory", "List a directory.",
                        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")),
                256, 0.0);
    }

    private static ChatMessage toolCallTurn(String content, String reasoning) {
        return new ChatMessage("assistant", content,
                List.of(new ToolCall("call_c784f2", "list_directory", "{\"path\":\"/src\"}"),
                        new ToolCall("call_44062f", "glob", "{\"path\":\"/src\"}")),
                null, reasoning);
    }

    private static JsonNode sentBody() throws Exception {
        List<LoggedRequest> sent = wm.findAll(postRequestedFor(urlEqualTo("/v1/chat/completions")));
        assertThat(sent).hasSize(1);
        return JSON.readTree(sent.get(0).getBodyAsString());
    }

    // HTTP 422 INVALID_PARAMS: function content must contain FunctionResult — what this gateway
    // answers a plain-text tool result. Almost every tool here returns prose (a directory listing,
    // search hits, a build log), so almost every result needs wrapping.
    @Test
    void aToolResultIsSentAsAJsonObject() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn("\n\n\n", null)));

        JsonNode toolMsg = sentBody().path("messages").get(3);
        assertThat(toolMsg.path("content").isTextual()).isTrue();
        JsonNode result = JSON.readTree(toolMsg.path("content").asText());
        assertThat(result.isObject()).isTrue();
        // Wrapped, not replaced: the text is what the model reads to keep working.
        assertThat(result.path("result").asText())
                .isEqualTo("Listed 2 item(s) in /src:\n[DIR] main\n[DIR] test");
        assertThat(toolMsg.path("tool_call_id").asText()).isEqualTo("call_c784f2");
    }

    // Not double-wrapped into {"result":"{...}"}, which would hand the model its own JSON back as
    // an escaped string to re-parse.
    @Test
    void aResultThatIsAlreadyAJsonObjectPassesThroughUntouched() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ChatRequest req = new ChatRequest("Qwen3.6-35B", List.of(
                ChatMessage.user("go"),
                ChatMessage.tool("call_c784f2", "{\"files\":2}")), List.of(), 256, 0.0);

        model(WireFormat.GIGACHAT).complete(req);

        assertThat(sentBody().path("messages").get(1).path("content").asText())
                .isEqualTo("{\"files\":2}");
    }

    // The requirement is an OBJECT specifically, not merely valid JSON.
    @Test
    void anArrayOrScalarResultIsStillWrapped() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ChatRequest req = new ChatRequest("Qwen3.6-35B", List.of(
                ChatMessage.user("go"),
                ChatMessage.tool("call_c784f2", "[1,2]")), List.of(), 256, 0.0);

        model(WireFormat.GIGACHAT).complete(req);

        assertThat(sentBody().path("messages").get(1).path("content").asText())
                .isEqualTo("{\"result\":\"[1,2]\"}");
    }

    // Every other role stays a plain string. The parts-array encoding this wire first shipped with
    // was copied from a capture of a DIFFERENT service in the same estate and returned HTTP 400
    // "Your request contains invalid JSON syntax" here; it was removed rather than left behind a
    // flag, since a measured-wrong option under a name an operator reaches for is worse than none.
    @Test
    void systemUserAndAssistantContentAreAllPlainStrings() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn("\n\n\n", null)));

        JsonNode messages = sentBody().path("messages");
        assertThat(messages.get(0).path("content").isTextual()).isTrue();
        assertThat(messages.get(1).path("content").isTextual()).isTrue();
        assertThat(messages.get(1).path("content").asText())
                .isEqualTo("Very thoroughly explore the src directory structure.");
        assertThat(messages.get(2).path("content").isTextual()).isTrue();
    }

    @Test
    void parallelToolCallsKeepTheOpenAiFunctionShapeWithArgumentsAsAJsonString() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn("\n\n\n", null)));

        JsonNode calls = sentBody().path("messages").get(2).path("tool_calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path("id").asText()).isEqualTo("call_c784f2");
        assertThat(calls.get(0).path("type").asText()).isEqualTo("function");
        assertThat(calls.get(0).path("function").path("name").asText()).isEqualTo("list_directory");
        // A string, not a nested object — the gateway receives it exactly as OpenAI spells it.
        assertThat(calls.get(0).path("function").path("arguments").isTextual()).isTrue();
        assertThat(calls.get(0).path("function").path("arguments").asText())
                .isEqualTo("{\"path\":\"/src\"}");
        assertThat(calls.get(1).path("function").path("name").asText()).isEqualTo("glob");
    }

    // The captured assistant turns always carry a content string even when their whole payload is
    // tool_calls. Omitting the key is legal OpenAI and is what sdd did through the proxy.
    @Test
    void anAssistantTurnThatIsOnlyToolCallsStillCarriesAContentString() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn(null, null)));

        JsonNode assistant = sentBody().path("messages").get(2);
        assertThat(assistant.has("content")).isTrue();
        assertThat(assistant.path("content").asText()).isEmpty();
    }

    @Test
    void reasoningIsReadOffTheReplyAndSentBackWithTheTurnItBelongsTo() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ChatModel model = model(WireFormat.GIGACHAT);

        ChatResponse resp = model.complete(conversation(toolCallTurn("\n\n\n", null)));

        assertThat(resp.message().reasoningContent())
                .isEqualTo("The user wants a thorough exploration of the project structure.");
        assertThat(resp.message().content()).isEqualTo("\n\n\n");
        assertThat(resp.message().toolCalls()).hasSize(2);

        // Now hand that same message back, as an agent loop does.
        wm.resetRequests();
        model.complete(conversation(resp.message()));
        assertThat(sentBody().path("messages").get(2).path("reasoning_content").asText())
                .isEqualTo("The user wants a thorough exploration of the project structure.");
    }

    /** A gateway that accepts parts may also return them; the caller must still see plain text. */
    @Test
    void aReplyWhoseContentArrivesAsTextPartsIsReadAsText() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"role":"assistant",
                  "content":[{"type":"text","text":"one "},{"type":"text","text":"two"}]},
                  "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """)));

        ChatResponse resp = model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn("x", null)));

        assertThat(resp.message().content()).isEqualTo("one two");
    }

    // The default wire must be untouched by all of the above: every existing workspace is on it.
    @Test
    void theOpenAiWireSendsExactlyWhatItAlwaysSentAndNeverReadsReasoning() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        ChatResponse resp = model(WireFormat.OPENAI).complete(conversation(toolCallTurn(null, "thinking")));

        JsonNode messages = sentBody().path("messages");
        assertThat(messages.get(1).path("content").isTextual()).isTrue();
        // Unwrapped: the OPENAI wire sends a tool result exactly as the tool produced it.
        assertThat(messages.get(3).path("content").asText())
                .isEqualTo("Listed 2 item(s) in /src:\n[DIR] main\n[DIR] test");
        assertThat(messages.get(2).has("content")).isFalse();
        assertThat(messages.get(2).has("reasoning_content")).isFalse();
        assertThat(resp.message().reasoningContent()).isNull();
    }
}
