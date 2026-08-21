package sdd.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sdd.core.config.ConfigException;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.Backoff;
import sdd.core.http.HttpClients;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HttpChatModel implements ChatModel {
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    // Retry/backoff math lives in sdd.core.http.Backoff (Task 2), shared with RestClient. Kept as
    // a same-valued alias here rather than calling Backoff.DEFAULT_MAX_ATTEMPTS directly at the
    // point of use below, so this class's own retry contract (6 attempts by default) reads as a
    // fact about HttpChatModel, not as "whatever Backoff happens to default to today".
    private static final int MAX_ATTEMPTS = Backoff.DEFAULT_MAX_ATTEMPTS;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Marks an id this class invented for a reply that carried none — see
     *  {@link #readLegacyFunctionCall}. Distinct so a transcript reader can see it. */
    public static final String SYNTHETIC_CALL_ID_PREFIX = "sdd-synthesized-";

    private static final Set<String> PROTECTED_BODY_KEYS =
            Set.of("model", "messages", "tools", "max_tokens", "temperature", "stream");

    private final ModelEndpoint endpoint;
    private final int maxAttempts;
    private final HttpClient client;
    private final Sleeper sleeper;
    /** Null unless SDD_HTTP_DUMP names a file — see {@link WireDump} for why this exists. Read
     *  from the environment here rather than threaded through a constructor: WireDump's own logic
     *  is unit-tested directly, and a getenv call is not the part worth a test seam. */
    private final WireDump dump = WireDump.fromEnv(System.getenv());

    public HttpChatModel(ModelEndpoint endpoint) {
        this(endpoint, MAX_ATTEMPTS);
    }

    /**
     * Phase 2: {@code HttpClient.newHttpClient()} was inlined here, which could never see a model
     * endpoint's {@code tls} block. Routed through {@link HttpClients#buildClient} instead — it
     * returns exactly {@code HttpClient.newHttpClient()} when {@code endpoint.tls()} is null (every
     * existing workspace, api-key-only), so this is a no-op for every endpoint that predates this
     * feature; a cert-configured endpoint gets an {@link javax.net.ssl.SSLContext} built from its
     * client certificate instead. No proxy is passed — models have never had a per-endpoint proxy
     * setting and this phase does not add one.
     */
    public HttpChatModel(ModelEndpoint endpoint, int maxAttempts) {
        this(endpoint, maxAttempts, HttpClients.buildClient(endpoint.tls(), null), Thread::sleep);
    }

    public HttpChatModel(ModelEndpoint endpoint, HttpClient client, Sleeper sleeper) {
        this(endpoint, MAX_ATTEMPTS, client, sleeper);
    }

    public HttpChatModel(ModelEndpoint endpoint, int maxAttempts, HttpClient client, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        // Deferred from ConfigLoader (Fix 1): an unset api_key ${VAR} does not fail config
        // loading, since a read-only command may never construct a chat model at all. A
        // HttpChatModel IS about to make a network call against this endpoint, so this
        // constructor is the earliest point to raise it — with the exact message ConfigLoader
        // would have thrown eagerly before this change.
        if (endpoint.apiKeyError() != null) {
            throw new ConfigException(endpoint.apiKeyError());
        }
        this.endpoint = endpoint;
        this.maxAttempts = maxAttempts;
        this.client = client;
        this.sleeper = sleeper;
    }

    @Override
    public ChatResponse complete(ChatRequest req) {
        String body = toJson(req);
        ModelException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> resp = send(body);
                int status = resp.statusCode();
                if (dump != null) {
                    dump.record(completionsUrl(), body, status, resp.body());
                }
                if (status >= 200 && status < 300) {
                    return parse(resp.body(), req);
                }
                if (status == 429) {
                    last = new ModelException("HTTP 429: " + resp.body(), status);
                    backoff(attempt, retryAfterMillis(resp));
                    continue;
                }
                if (status >= 500) {
                    last = new ModelException("HTTP " + status + ": " + resp.body(), status);
                    backoff(attempt, null);
                    continue;
                }
                throw new ModelException("HTTP " + status + ": " + resp.body(), status);
            } catch (IOException e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (dump != null) {
                    dump.recordFailure(completionsUrl(), body, detail);
                }
                last = new ModelException("transport error: " + detail, e);
                backoff(attempt, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("interrupted", e);
            }
        }
        throw last;
    }

    private String completionsUrl() {
        return endpoint.baseUrl() + "/chat/completions";
    }

    private HttpResponse<String> send(String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(completionsUrl()))
                .timeout(endpoint.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (endpoint.apiKey() != null) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void backoff(int attempt, Long retryAfterMillis) {
        if (attempt >= maxAttempts) {
            return;
        }
        long delay = Backoff.delayMillis(attempt, retryAfterMillis);
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("interrupted during backoff", e);
        }
    }

    private static Long retryAfterMillis(HttpResponse<String> resp) {
        return Backoff.retryAfterMillis(resp.headers().firstValue("Retry-After"));
    }

    private String toJson(ChatRequest req) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", req.model());
        WireFormat wire = endpoint.wire();
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : req.messages()) {
            ObjectNode msg = messages.addObject();
            // GigaChat pairs a result to its call by NAME and has no tool_call_id at all, so the
            // role and the key both change together — see WireFormat for the measurement.
            boolean functionShape = wire.usesFunctionCall();
            msg.put("role", functionShape && "tool".equals(m.role()) ? "function" : m.role());
            putContent(msg, m, wire);
            if (functionShape && "tool".equals(m.role())) {
                if (m.name() != null) {
                    msg.put("name", m.name());
                }
            } else if (m.toolCallId() != null) {
                msg.put("tool_call_id", m.toolCallId());
            }
            if (wire.carriesReasoning() && m.reasoningContent() != null) {
                msg.put("reasoning_content", m.reasoningContent());
            }
            if (!m.toolCalls().isEmpty()) {
                if (functionShape) {
                    // A single object, not an array: the protocol cannot express a second call, and
                    // parse() has already truncated to one so the history stays representable.
                    ToolCall c = m.toolCalls().get(0);
                    ObjectNode fn = msg.putObject("function_call");
                    fn.put("name", c.name());
                    fn.set("arguments", argumentsNode(c.argumentsJson()));
                } else {
                    ArrayNode calls = msg.putArray("tool_calls");
                    for (ToolCall c : m.toolCalls()) {
                        ObjectNode call = calls.addObject();
                        call.put("id", c.id());
                        call.put("type", "function");
                        ObjectNode fn = call.putObject("function");
                        fn.put("name", c.name());
                        fn.put("arguments", c.argumentsJson());
                    }
                }
            }
        }
        if (!req.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : req.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                try {
                    fn.set("parameters", JSON.readTree(t.parametersSchemaJson()));
                } catch (IOException e) {
                    throw new ModelException("bad tool schema for " + t.name(), e);
                }
            }
        }
        if (req.maxTokens() != null) {
            root.put("max_tokens", req.maxTokens());
        }
        if (req.temperature() != null) {
            root.put("temperature", req.temperature());
        }
        for (Map.Entry<String, Object> e : endpoint.extraBody().entrySet()) {
            if (PROTECTED_BODY_KEYS.contains(e.getKey())) {
                continue;
            }
            root.set(e.getKey(), JSON.valueToTree(e.getValue()));
        }
        return root.toString();
    }

    /**
     * Writes one message's {@code content} in the dialect this endpoint speaks.
     *
     * <p>A string on every role and every wire; the key is absent when there is nothing to say,
     * except on {@link WireFormat#GIGACHAT}, where an assistant turn carrying only {@code tool_calls}
     * still writes an empty one. A null content becomes {@code ""} rather than a fabricated
     * placeholder: the model said nothing, and saying so is the honest encoding of that.
     *
     * <p>A tool result is the one shape that genuinely differs — see {@link #functionResult}.
     */
    private static void putContent(ObjectNode msg, ChatMessage m, WireFormat wire) {
        if ("tool".equals(m.role()) && wire.wrapsToolResults()) {
            msg.put("content", functionResult(m.content()));
            return;
        }
        if (m.content() != null) {
            msg.put("content", m.content());
        } else if (wire.assistantContentAlwaysPresent() && "assistant".equals(m.role())) {
            msg.put("content", "");
        }
    }

    /** {@code function_call.arguments} as GigaChat spells it — an object, where OpenAI uses a
     *  JSON string. Unparseable arguments become an empty object rather than failing the whole
     *  request: the call still names a tool, and a tool that rejects bad arguments reports it far
     *  more usefully than a gateway rejecting the conversation. */
    private static JsonNode argumentsNode(String argumentsJson) {
        try {
            JsonNode node = JSON.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return node.isObject() ? node : JSON.createObjectNode();
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    /**
     * A tool result as GigaChat requires it: a JSON object, serialized.
     *
     * <p>{@code HTTP 422 INVALID_PARAMS: function content must contain FunctionResult} is what a
     * plain-text result gets. Almost every tool in this codebase returns prose — a directory
     * listing, search hits, a build log — so almost every result needs wrapping.
     *
     * <p>A result that is ALREADY a JSON object passes through untouched, so a tool that returns
     * structured data is not double-wrapped into {@code {"result":"{...}"}}, which would hand the
     * model its own JSON as an escaped string to re-parse. An array or a bare scalar is wrapped:
     * the requirement is an object specifically, not merely valid JSON.
     */
    private static String functionResult(String content) {
        String text = content == null ? "" : content;
        try {
            if (JSON.readTree(text).isObject()) {
                return text;
            }
        } catch (IOException e) {
            // Not JSON at all, which is the common case. Fall through and wrap it.
        }
        ObjectNode wrapped = JSON.createObjectNode();
        wrapped.put("result", text);
        return wrapped.toString();
    }

    private ChatResponse parse(String body, ChatRequest req) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                toolCalls.add(new ToolCall(
                        call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        arguments(call.path("function").path("arguments"))));
            }
            if (toolCalls.isEmpty()) {
                readLegacyFunctionCall(message).ifPresent(toolCalls::add);
            }
            JsonNode contentNode = message.path("content");
            String content = readContent(contentNode);
            // A reasoning model with no request-side off switch returns its thinking inline. Every
            // consumer of a response parses it as JSON, so a reply that opens with a think block is
            // not truncated but "unparseable" — stripping at this boundary fixes all of them at
            // once, and is a no-op for a model that never emits the tags. See ReasoningContent.
            content = ReasoningContent.strip(content);
            // Carried, never authored: a reply that separates its thinking from its answer gets
            // that thinking handed back with the turn it belongs to on the next request, which is
            // what the gateway's own clients do.
            //
            // READ on every wire, SENT only on the one that carries it (see the request builder).
            // This used to be read only under `wire: gigachat`, on the belief that no other wire
            // returns the field. A captured exchange disproved it: the same gateway, on the plain
            // OpenAI wire, answered with a reasoning_content that said in one sentence why a tool
            // call had been refused — and sdd discarded it, which cost several round trips of
            // inference on a closed network. Reading a field that is usually absent is free; the
            // protocol-specific decision is whether to send it BACK, and that stays gated.
            JsonNode reasoningNode = message.path("reasoning_content");
            String reasoning = readContent(reasoningNode);
            // Last resort, and only where an operator has said this endpoint needs it: the call
            // arrived as ordinary content because the gateway never structured it. content is
            // cleared when it IS the call — otherwise the model would be shown its own call twice,
            // once as text and once as a tool_call, on the next turn.
            if (toolCalls.isEmpty() && endpoint.toolCallStyle() == ToolCallStyle.TEXT) {
                List<ToolCall> fromText = TextToolCalls.read(content, declaredToolNames(req));
                if (!fromText.isEmpty()) {
                    toolCalls.addAll(fromText);
                    content = null;
                }
            }
            if (endpoint.wire().usesFunctionCall() && toolCalls.size() > 1) {
                // This protocol has one function_call per turn and no way to say more. Keeping
                // extras would build a history it cannot serialize, and the request after it would
                // be refused — losing the whole run rather than one call the model will re-request.
                toolCalls = new ArrayList<>(toolCalls.subList(0, 1));
            }
            ChatMessage msg = new ChatMessage("assistant", content, List.copyOf(toolCalls), null,
                    reasoning);
            Usage usage = new Usage(
                    root.path("usage").path("prompt_tokens").asInt(),
                    root.path("usage").path("completion_tokens").asInt());
            // A gateway that does not structure a tool call also does not say it made one — the
            // reply that carried it reported finish_reason=stop. Normalized so no caller has to
            // know two names, or three, for the same outcome.
            String finishReason = choice.path("finish_reason").asText();
            if (!toolCalls.isEmpty() && !"tool_calls".equals(finishReason)) {
                finishReason = "tool_calls";
            }
            return new ChatResponse(msg, finishReason, usage);
        } catch (IOException e) {
            throw new ModelException("unparseable model response: " + body, e);
        }
    }

    /** The tool names THIS request offered — the check that keeps {@link TextToolCalls} from
     *  reading a JSON answer as a call to something that was never on the table. */
    private static java.util.Set<String> declaredToolNames(ChatRequest req) {
        return req.tools().stream().map(ToolSpec::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The pre-{@code tools} single-call shape, read only when {@code tool_calls} is absent.
     *
     * <p>We have captured a gateway REQUEST but never a reply, and the assistant turns in that
     * capture were composed by the client, so they do not settle what the endpoint actually
     * returns. GigaChat's native surface is separately recorded as answering with
     * {@code function_call} rather than {@code tool_calls}. If a gateway does that, the parser
     * above finds nothing, {@link sdd.core.llm.ChatResponse} looks like a prose turn, and three of
     * those end an agent run as MALFORMED — a symptom that names neither its cause nor its fix.
     * Reading the older shape costs a few lines and removes that whole class of confusion.
     *
     * <p>The id is synthesized, because the legacy shape carries none and the agent loop pairs
     * results by id. That is the part worth distrusting: if this path ever fires against a real
     * gateway, the id we send back with the result has never been verified as acceptable to it.
     * A caller that cares can tell this happened — the id is prefixed distinctly.
     */
    private static java.util.Optional<ToolCall> readLegacyFunctionCall(JsonNode message) {
        JsonNode fn = message.path("function_call");
        if (fn.isMissingNode() || fn.isNull() || fn.path("name").asText("").isEmpty()) {
            return java.util.Optional.empty();
        }
        String name = fn.path("name").asText();
        return java.util.Optional.of(
                new ToolCall(SYNTHETIC_CALL_ID_PREFIX + name, name, arguments(fn.path("arguments"))));
    }

    /**
     * A call's arguments as the JSON string {@link ToolCall} holds.
     *
     * <p>OpenAI sends a string containing JSON; GigaChat's native shape sends the object itself.
     * {@code asText()} on an object node returns the EMPTY STRING rather than its JSON, so without
     * this an object-valued arguments field would silently become a call with no arguments — a
     * wrong answer, not a failure, which is the worse of the two.
     */
    private static String arguments(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        return node.isTextual() ? node.asText() : node.toString();
    }

    /**
     * Reads a content field that may be a string or an array of typed parts.
     *
     * <p>A gateway that ACCEPTS parts may also return them, and a reply whose content arrived as
     * {@code [{"type":"text","text":"…"}]} must not reach a caller as the literal text of a JSON
     * array — every consumer of a response parses it as JSON, so that failure would surface as
     * "unparseable" far from its cause. Non-text parts are skipped rather than rendered: sdd has
     * no use for an image part and inventing a placeholder for one would be a fabricated fact.
     *
     * <p>Extraction only — {@code <think>}-tag stripping stays at the one call site that wants it,
     * since reasoning is the POINT of the field this also reads.
     */
    private static String readContent(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : node) {
                if ("text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText());
                }
            }
            return text.toString();
        }
        return node.asText();
    }
}
