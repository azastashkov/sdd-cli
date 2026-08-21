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
                ChatMessage.tool("call_c784f2", "list_directory",
                        "Listed 2 item(s) in /src:\n[DIR] main\n[DIR] test"),
                ChatMessage.tool("call_44062f", "glob", "Found 3 file(s) matching **/*.yml")),
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
        // Keyed by name, not by an id this protocol does not have.
        assertThat(toolMsg.path("name").asText()).isEqualTo("list_directory");
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

    // The one shape a live gateway accepted, out of ten tried. Each assertion below is a rule the
    // refusals named; see WireFormat for the errors they came from.
    @Test
    void anAssistantCallIsAFunctionCallObjectNotAToolCallsArray() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn("\n\n\n", null)));

        JsonNode assistant = sentBody().path("messages").get(2);
        assertThat(assistant.has("tool_calls")).isFalse();
        JsonNode call = assistant.path("function_call");
        assertThat(call.path("name").asText()).isEqualTo("list_directory");
        // An object, where OpenAI uses a JSON string.
        assertThat(call.path("arguments").isObject()).isTrue();
        assertThat(call.path("arguments").path("path").asText()).isEqualTo("/src");
    }

    @Test
    void aResultIsRoleFunctionKeyedByNameWithNoCallId() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn("\n\n\n", null)));

        JsonNode result = sentBody().path("messages").get(3);
        assertThat(result.path("role").asText()).isEqualTo("function");
        assertThat(result.path("name").asText()).isEqualTo("list_directory");
        assertThat(result.has("tool_call_id")).isFalse();
        // A string that itself parses as JSON: an object gets HTTP 400, a bare string a 422.
        assertThat(result.path("content").isTextual()).isTrue();
        assertThat(JSON.readTree(result.path("content").asText()).isObject()).isTrue();
    }

    // function_call is one object, not an array, so a reply carrying several calls would build a
    // history this wire cannot serialize — and the NEXT request would be refused, losing the run
    // rather than one call the model will simply ask for again.
    @Test
    void aReplyWithSeveralCallsIsTruncatedToOneOnThisWire() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        ChatResponse resp = model(WireFormat.GIGACHAT)
                .complete(conversation(toolCallTurn("x", null)));

        assertThat(resp.message().toolCalls()).hasSize(1);
        assertThat(resp.message().toolCalls().get(0).name()).isEqualTo("list_directory");
    }

    @Test
    void theOpenAiWireKeepsEveryCallAndTheToolRoleAndTheCallId() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        ChatResponse resp = model(WireFormat.OPENAI)
                .complete(conversation(toolCallTurn(null, null)));

        assertThat(resp.message().toolCalls()).hasSize(2);
        JsonNode result = sentBody().path("messages").get(3);
        assertThat(result.path("role").asText()).isEqualTo("tool");
        assertThat(result.path("tool_call_id").asText()).isEqualTo("call_c784f2");
        assertThat(result.has("name")).isFalse();
    }

    @Test
    void parallelToolCallsKeepTheOpenAiFunctionShapeWithArgumentsAsAJsonString() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.OPENAI).complete(conversation(toolCallTurn("\n\n\n", null)));

        JsonNode calls = sentBody().path("messages").get(2).path("tool_calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).path("id").asText()).isEqualTo("call_c784f2");
        assertThat(calls.get(0).path("type").asText()).isEqualTo("function");
        assertThat(calls.get(0).path("function").path("name").asText()).isEqualTo("list_directory");
        // A string, not a nested object — the OpenAI wire spells it exactly as OpenAI does.
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
        // Truncated to one: this protocol has a single function_call per turn.
        assertThat(resp.message().toolCalls()).hasSize(1);

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
    // That guarantee is about what is SENT, and it is unchanged. Reading is a separate axis — see
    // the test below, which used to be the last assertion of this one.
    @Test
    void theOpenAiWireSendsExactlyWhatItAlwaysSent() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.OPENAI).complete(conversation(toolCallTurn(null, "thinking")));

        JsonNode messages = sentBody().path("messages");
        assertThat(messages.get(1).path("content").isTextual()).isTrue();
        // Unwrapped: the OPENAI wire sends a tool result exactly as the tool produced it.
        assertThat(messages.get(3).path("content").asText())
                .isEqualTo("Listed 2 item(s) in /src:\n[DIR] main\n[DIR] test");
        assertThat(messages.get(2).has("content")).isFalse();
        // The load-bearing half: reasoning is never SENT on this wire, whatever was read.
        assertThat(messages.get(2).has("reasoning_content")).isFalse();
    }

    /**
     * Reading {@code reasoning_content} is not wire-specific, and used to be.
     *
     * <p>This assertion was inverted deliberately. It previously pinned "the OPENAI wire never
     * reads reasoning", on the belief that no gateway returns the field there. A captured exchange
     * disproved it: a corp gateway on the plain OpenAI wire answered with a reasoning_content that
     * explained, in one sentence, why it had refused a tool call — and sdd threw it away, which
     * cost several round trips of inference on a closed network to re-derive.
     *
     * <p>Reading a field that is usually absent costs nothing and yields null when it is. The
     * protocol-specific decision is whether to send it BACK, and that is still gated on the wire,
     * which is what the test above pins.
     */
    @Test
    void reasoningIsReadOnEveryWireEvenThoughOnlyOneSendsItBack() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"role":"assistant","content":"prose",
                  "reasoning_content":"I have a tool called \\"zulu\\"."},
                  "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """)));

        ChatResponse resp = model(WireFormat.OPENAI).complete(conversation(toolCallTurn(null, null)));

        assertThat(resp.message().reasoningContent()).isEqualTo("I have a tool called \"zulu\".");
    }

    // ---------------------------------------------------- declarations: tools[] vs functions[]

    /**
     * The measurement this wire exists to honour, and the one that took longest to find.
     *
     * <p>Same conversation, sent to one gateway twice, differing only in this key. Under
     * {@code tools[]} the model answered "there is no tool specified in the conversation… I don't
     * have tools unless specified" — HTTP 200, and NOTHING reached the model. Under
     * {@code functions[]} the same model returned a structured
     * {@code function_call {"name":"sdd_probe_ack"}}.
     */
    @Test
    void theGigachatWireDeclaresFunctionsNotTools() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.GIGACHAT).complete(conversation(toolCallTurn("\n\n\n", null)));

        JsonNode body = sentBody();
        assertThat(body.has("tools")).isFalse();
        JsonNode functions = body.path("functions");
        assertThat(functions.isArray()).isTrue();
        assertThat(functions).hasSize(1);
        // The inner object DIRECTLY, not wrapped in {"type":"function","function":{…}}.
        JsonNode fn = functions.get(0);
        assertThat(fn.has("type")).isFalse();
        assertThat(fn.has("function")).isFalse();
        assertThat(fn.path("name").asText()).isEqualTo("list_directory");
        assertThat(fn.path("description").asText()).isEqualTo("List a directory.");
        assertThat(fn.path("parameters").path("type").asText()).isEqualTo("object");
    }

    /** And the default wire is untouched: every existing workspace is on it. */
    @Test
    void theOpenAiWireStillDeclaresToolsInTheNestedShape() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        model(WireFormat.OPENAI).complete(conversation(toolCallTurn(null, null)));

        JsonNode body = sentBody();
        assertThat(body.has("functions")).isFalse();
        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("list_directory");
    }

    /** A request that declares nothing must not grow an empty key on either wire. */
    @Test
    void noDeclarationsMeansNoKeyAtAll() throws Exception {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ChatRequest bare = new ChatRequest("Qwen3.6-35B", List.of(ChatMessage.user("go")),
                List.of(), 256, 0.0);

        model(WireFormat.GIGACHAT).complete(bare);

        assertThat(sentBody().has("functions")).isFalse();
        assertThat(sentBody().has("tools")).isFalse();
    }
}
