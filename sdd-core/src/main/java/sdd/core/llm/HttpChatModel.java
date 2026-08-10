package sdd.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sdd.core.config.ModelEndpoint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class HttpChatModel implements ChatModel {
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private static final int MAX_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_MILLIS = 250;
    private static final long MAX_BACKOFF_MILLIS = 60_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ModelEndpoint endpoint;
    private final HttpClient client;
    private final Sleeper sleeper;

    public HttpChatModel(ModelEndpoint endpoint) {
        this(endpoint, HttpClient.newHttpClient(), Thread::sleep);
    }

    public HttpChatModel(ModelEndpoint endpoint, HttpClient client, Sleeper sleeper) {
        this.endpoint = endpoint;
        this.client = client;
        this.sleeper = sleeper;
    }

    @Override
    public ChatResponse complete(ChatRequest req) {
        String body = toJson(req);
        ModelException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
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
                last = new ModelException("transport error: " + e.getMessage(), e);
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
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }
        long delay = retryAfterMillis != null
                ? Math.min(retryAfterMillis, MAX_BACKOFF_MILLIS)
                : Math.min(MAX_BACKOFF_MILLIS,
                        BASE_BACKOFF_MILLIS * (1L << (attempt - 1))
                                + ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MILLIS));
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("interrupted during backoff", e);
        }
    }

    private static Long retryAfterMillis(HttpResponse<String> resp) {
        return resp.headers().firstValue("Retry-After")
                .map(v -> {
                    try {
                        return Long.parseLong(v.trim()) * 1000L;
                    } catch (NumberFormatException e) {
                        // HTTP-date format not supported; fall back to exponential backoff
                        return null;
                    }
                }).orElse(null);
    }

    private String toJson(ChatRequest req) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", req.model());
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : req.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put("role", m.role());
            if (m.content() != null) {
                msg.put("content", m.content());
            }
            if (m.toolCallId() != null) {
                msg.put("tool_call_id", m.toolCallId());
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
        return root.toString();
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
            String content = (contentNode.isMissingNode() || contentNode.isNull()) ? null : contentNode.asText();
            ChatMessage msg = new ChatMessage("assistant", content, List.copyOf(toolCalls), null);
            Usage usage = new Usage(
                    root.path("usage").path("prompt_tokens").asInt(),
                    root.path("usage").path("completion_tokens").asInt());
            return new ChatResponse(msg, choice.path("finish_reason").asText(), usage);
        } catch (IOException e) {
            throw new ModelException("unparseable model response: " + body, e);
        }
    }
}
