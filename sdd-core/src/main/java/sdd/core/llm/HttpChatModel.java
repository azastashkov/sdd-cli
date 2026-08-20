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
    private static final Set<String> PROTECTED_BODY_KEYS =
            Set.of("model", "messages", "tools", "max_tokens", "temperature", "stream");

    private final ModelEndpoint endpoint;
    private final int maxAttempts;
    private final HttpClient client;
    private final Sleeper sleeper;

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
                if (status >= 200 && status < 300) {
                    return parse(resp.body());
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
                last = new ModelException("transport error: " + detail, e);
                backoff(attempt, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("interrupted", e);
            }
        }
        throw last;
    }

    private HttpResponse<String> send(String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.baseUrl() + "/chat/completions"))
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
            msg.put("role", m.role());
            putContent(msg, m, wire);
            if (m.toolCallId() != null) {
                msg.put("tool_call_id", m.toolCallId());
            }
            if (wire.carriesReasoning() && m.reasoningContent() != null) {
                msg.put("reasoning_content", m.reasoningContent());
            }
            if (!m.toolCalls().isEmpty()) {
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
     * <p>On {@link WireFormat#OPENAI} this is what it always was: a string, and the key is absent
     * when there is nothing to say (an assistant turn that is purely {@code tool_calls}).
     *
     * <p>On {@link WireFormat#GIGACHAT} it follows the gateway's observed traffic — {@code user}
     * and {@code tool} messages carry an array of {@code {"type":"text","text":…}} parts,
     * {@code system} and {@code assistant} carry a string, and an assistant turn always carries
     * one even when empty. A null content becomes {@code ""} rather than a fabricated placeholder:
     * the model said nothing, and saying so is the honest encoding of that.
     */
    private static void putContent(ObjectNode msg, ChatMessage m, WireFormat wire) {
        if (wire.partsFor(m.role())) {
            ObjectNode part = msg.putArray("content").addObject();
            part.put("type", "text");
            part.put("text", m.content() == null ? "" : m.content());
            return;
        }
        if (m.content() != null) {
            msg.put("content", m.content());
        } else if (wire.assistantContentAlwaysPresent() && "assistant".equals(m.role())) {
            msg.put("content", "");
        }
    }

    private ChatResponse parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                toolCalls.add(new ToolCall(
                        call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText()));
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
            // what the gateway's own clients do. Absent on every other wire, so this is null there.
            JsonNode reasoningNode = message.path("reasoning_content");
            String reasoning = endpoint.wire().carriesReasoning() ? readContent(reasoningNode) : null;
            ChatMessage msg = new ChatMessage("assistant", content, List.copyOf(toolCalls), null,
                    reasoning);
            Usage usage = new Usage(
                    root.path("usage").path("prompt_tokens").asInt(),
                    root.path("usage").path("completion_tokens").asInt());
            return new ChatResponse(msg, choice.path("finish_reason").asText(), usage);
        } catch (IOException e) {
            throw new ModelException("unparseable model response: " + body, e);
        }
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
