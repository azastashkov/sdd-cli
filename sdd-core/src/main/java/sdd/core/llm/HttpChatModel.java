package sdd.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sdd.core.config.ConfigException;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.Backoff;
import sdd.core.http.HttpClients;
import sdd.core.http.Multipart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HttpChatModel implements ChatModel, AttachmentStore {
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    // Retry/backoff math lives in sdd.core.http.Backoff (Task 2), shared with RestClient. Kept as
    // a same-valued alias here rather than calling Backoff.DEFAULT_MAX_ATTEMPTS directly at the
    // point of use below, so this class's own retry contract (6 attempts by default) reads as a
    // fact about HttpChatModel, not as "whatever Backoff happens to default to today".
    private static final int MAX_ATTEMPTS = Backoff.DEFAULT_MAX_ATTEMPTS;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The only value the documented enum takes. Not "vision" — that spelling is invented, and
     *  believing it cost a round trip on a closed network. */
    private static final String UPLOAD_PURPOSE = "general";
    /** Marks an id this class invented for a reply that carried none — see
     *  {@link #readLegacyFunctionCall}. Distinct so a transcript reader can see it. */
    public static final String SYNTHETIC_CALL_ID_PREFIX = "sdd-synthesized-";

    /** {@code functions} is here for the same reason {@code tools} is: on the GigaChat wire it is
     *  the key the declarations go out under, and an extra_body entry overwriting it would strip
     *  every tool from the request while looking like a configuration nicety. */
    private static final Set<String> PROTECTED_BODY_KEYS =
            Set.of("model", "messages", "tools", "functions", "max_tokens", "temperature", "stream");

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

    /**
     * Put an image in the endpoint's own file store and return its id, for a later user turn's
     * {@code attachments}. Uses this model's client, certificate, bearer token and timeout, because
     * the file store is the same host as {@code /chat/completions} and configuring it twice is how
     * the two drift apart.
     *
     * <p>Refuses outright on a wire with no file store rather than sending and reading the 404 —
     * the caller is about to pay for a download and an upload, and should be told before it does.
     */
    @Override
    public String upload(byte[] image, String filename, String contentType) throws ModelException {
        if (!endpoint.wire().uploadsAttachments()) {
            throw new ModelException("models.<name>.wire " + endpoint.wire()
                    + " has no file store — an image can only be uploaded on the gigachat wire", 0);
        }
        Multipart form = new Multipart()
                .field("purpose", UPLOAD_PURPOSE)
                .file("file", filename, contentType, image);
        HttpResponse<String> response = attempt("upload " + filename,
                URI.create(endpoint.baseUrl() + "/files"), form.contentType(), form.publisher());
        JsonNode id;
        try {
            id = JSON.readTree(response.body()).path("id");
        } catch (Exception e) {
            throw new ModelException("upload of " + filename + " returned an unparseable body: "
                    + response.body().substring(0, Math.min(300, response.body().length())),
                    response.statusCode());
        }
        if (id.isMissingNode() || id.asText("").isBlank()) {
            throw new ModelException("upload of " + filename + " returned no file id: "
                    + response.body().substring(0, Math.min(300, response.body().length())),
                    response.statusCode());
        }
        return id.asText();
    }

    /**
     * Best effort by contract, and deliberately so: the caller has already got its description, and
     * the store is a courtesy to clean up rather than part of the result. A live store was observed
     * holding 99 files, so not calling this is not an option either — it is logged, not thrown.
     */
    @Override
    public void delete(String fileId) {
        try {
            attempt("delete " + fileId, URI.create(endpoint.baseUrl() + "/files/" + fileId + "/delete"),
                    "application/json", HttpRequest.BodyPublishers.noBody());
        } catch (RuntimeException e) {
            // Total, not just ModelException: the caller already holds the description this file
            // was uploaded for, and no cleanup failure may turn that into a failed run.
            if (dump != null) {
                dump.recordFailure(endpoint.baseUrl() + "/files/" + fileId + "/delete", "",
                        e.getMessage());
            }
        }
    }

    /** {@link #complete}'s retry loop, for the requests that are not completions. */
    private HttpResponse<String> attempt(String what, URI uri, String contentType,
            HttpRequest.BodyPublisher publisher) throws ModelException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = send(uri, contentType, publisher);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response;
                }
                if (response.statusCode() != 429 && response.statusCode() < 500) {
                    throw new ModelException(what + " failed: HTTP " + response.statusCode() + " "
                            + response.body().substring(0, Math.min(300, response.body().length())),
                            response.statusCode());
                }
                backoff(attempt, retryAfterMillis(response));
            } catch (IOException e) {
                last = e;
                backoff(attempt, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException(what + " interrupted", 0);
            }
        }
        throw new ModelException(what + " failed after " + maxAttempts + " attempts"
                + (last == null ? "" : ": " + last), 0);
    }

    private String completionsUrl() {
        return endpoint.baseUrl() + "/chat/completions";
    }

    /**
     * One request, bounded end to end by {@code timeout_seconds}.
     *
     * <p><b>{@link HttpRequest.Builder#timeout} is not enough, and believing it was cost a hung
     * run.</b> That timeout bounds the wait for the RESPONSE; once the status line and headers
     * arrive it stops applying, so a gateway that answers {@code 200} immediately and then spends
     * ten minutes generating is not bounded by it at all. Measured: with
     * {@code timeout_seconds: 3} against a server that sent headers at once and the body 25
     * seconds later, the call SUCCEEDED after 25 seconds. An agent turn that stalls this way
     * cannot be told from a hang, and the one setting an operator reaches for does nothing.
     *
     * <p>{@code sendAsync} plus {@link CompletableFuture#orTimeout} bounds the whole exchange,
     * body included. The request-level timeout is kept as well: it fails faster and with a
     * clearer exception when the stall really is before the headers.
     *
     * <p>A timeout is surfaced as {@link HttpTimeoutException} — an {@code IOException} — so the
     * retry loop treats it exactly as it already treats a transport error, with backoff, rather
     * than needing a second failure path.
     */
    private HttpResponse<String> send(String body) throws IOException, InterruptedException {
        return send(URI.create(completionsUrl()), "application/json",
                HttpRequest.BodyPublishers.ofString(body));
    }

    /**
     * The same bounded exchange for any endpoint on this host — {@code /chat/completions} and
     * {@code /files} both. Generalised rather than copied so the file store inherits the whole-body
     * timeout above, the client certificate, and the bearer header; a second hand-rolled sender
     * would have re-earned the hung run that javadoc describes.
     */
    private HttpResponse<String> send(URI uri, String contentType, HttpRequest.BodyPublisher publisher)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(endpoint.timeout())
                .header("Content-Type", contentType)
                .POST(publisher);
        if (endpoint.apiKey() != null) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        CompletableFuture<HttpResponse<String>> pending =
                client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        try {
            return pending.orTimeout(endpoint.timeout().toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            // Cancel so a stalled exchange does not keep a connection and a body reader alive for
            // the rest of the run; every attempt after this one would inherit the leak.
            pending.cancel(true);
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                throw new HttpTimeoutException("no complete reply within "
                        + endpoint.timeout().toSeconds() + "s (models.<name>.timeout_seconds) — the "
                        + "endpoint may have sent headers and then stalled mid-body");
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof InterruptedException interrupted) {
                throw interrupted;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        }
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
            // Ids of files already in the endpoint's own store. Gated on the wire rather than on
            // emptiness alone: sending the key to a wire that does not know it is how a request
            // becomes a 400 that names JSON syntax and nothing else useful.
            if (wire.uploadsAttachments() && !m.attachments().isEmpty()) {
                ArrayNode attachments = msg.putArray("attachments");
                m.attachments().forEach(attachments::add);
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
            // Two spellings of the same declaration. OpenAI nests it under {"type":"function",
            // "function":{...}} in a `tools` array; GigaChat takes the inner object directly in a
            // `functions` array. A gateway that reads only the latter accepts the former, answers
            // 200, and hands the model NOTHING — see WireFormat.declaresFunctions for the
            // measurement, and note that nothing about the failure says which happened.
            boolean asFunctions = wire.declaresFunctions();
            ArrayNode declarations = root.putArray(asFunctions ? "functions" : "tools");
            for (ToolSpec t : req.tools()) {
                ObjectNode fn;
                if (asFunctions) {
                    fn = declarations.addObject();
                } else {
                    ObjectNode tool = declarations.addObject();
                    tool.put("type", "function");
                    fn = tool.putObject("function");
                }
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
        // An assistant turn that CALLS something must not also carry prose on this wire.
        //
        // Measured, and it is a hard 500 rather than a refusal that names a rule: the same
        // conversation sent twice, differing only in this field, returned 3/3 with content ""
        // and 0/3 [500,500,500] with the model's own 109-character preamble beside the call.
        // The model routinely produces both -- "Let me start by understanding the landscape"
        // AND function_call: list_repos -- so a real second turn hit it every time while the
        // first turn, which carries no assistant history yet, was fine.
        //
        // Empty rather than omitted: this wire requires the key to be PRESENT, and "" is the
        // value the passing variant used. The prose is dropped, which costs a little of the
        // model's own commentary on the next turn and buys a conversation the gateway accepts.
        if (wire.assistantContentAlwaysPresent() && "assistant".equals(m.role())
                && !m.toolCalls().isEmpty()) {
            msg.put("content", "");
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
     * "unparseable" far from its cause. Non-text parts are skipped rather than rendered: sdd SENDS
     * images by upload-and-reference rather than as a part (see {@link
     * WireFormat#uploadsAttachments()}), so a part arriving in a REPLY is something it did not ask
     * for, and inventing a placeholder for one would be a fabricated fact.
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
