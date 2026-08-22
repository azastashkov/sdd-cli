package sdd.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianTls;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.diagnostics.AtlassianWireDump;
import sdd.core.diagnostics.Redactor;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * JSON-over-HTTP with bearer auth, retry and the diagnostics Jira/Confluence/Bitbucket need on a
 * closed corporate network. The generic client Task 3/4/5's {@code JiraClient}/
 * {@code ConfluenceClient}/{@code BitbucketClient} build on — this class knows nothing about any
 * of the three products' resource shapes, only how to talk JSON to a PAT-protected REST API and
 * report failures usefully.
 *
 * <p>Retry/backoff (429 and 5xx, with an {@link IOException} treated the same way) reuses
 * {@link Backoff} — the exact math {@code HttpChatModel} uses, so a flaky Jira and a flaky model
 * router get the same treatment. A 4xx other than 429 throws immediately: retrying a client error
 * a server will never accept just delays the failure.
 *
 * <p>Constructor overloads mirror {@code HttpChatModel}'s test-seam shape (an injectable
 * {@link HttpClient} and {@link Sleeper}, since there is no Mockito in this repo) rather than
 * reusing {@code HttpChatModel.Sleeper} directly — that interface lives in {@code sdd.core.llm}
 * and is about model retries specifically; declaring an equivalent here keeps {@code sdd.core.http}
 * from depending on the LLM package for an unrelated single-method interface.
 */
public final class RestClient {
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    /** A parsed body paired with the raw response headers — for the rare caller (Bitbucket's
     *  {@code X-AUSERNAME}, see {@code AtlassianProbe}) that needs a header {@link #get}/{@link #post}/
     *  {@link #put} otherwise discard. Deliberately minimal: one more field on the normal return
     *  shape, not a parallel client API. */
    public record JsonResponse(JsonNode body, HttpHeaders headers) {}

    /**
     * Task 8 Fix 5: the truststore/proxy this instance's {@link HttpClient} was ALREADY built with
     * ({@code HttpClients.build}'s inputs) — known only by the caller, carried here purely so a TLS
     * or connect-shaped failure's diagnostic entry can name the host+truststore / effective-proxy
     * fact directly, the same enrichment {@code AtlassianProbe} already computes for {@code sdd
     * doctor}'s probes, reused rather than re-derived (see {@link HttpClients#tlsFailureMessage}/
     * {@link HttpClients#describeEffectiveProxy}). {@code RestClient} never uses either field to
     * build anything — the {@link HttpClient} it is handed already has them wired in — so both are
     * nullable exactly like {@code HttpClients.build}'s own null-means-JDK-default/no-proxy
     * contract, and {@link #NONE} is the "nothing configured" instance every non-Task-8 and
     * non-Fix-5 constructor implicitly uses.
     */
    public record TransportContext(Path truststore, AtlassianProxy proxy) {
        public static final TransportContext NONE = new TransportContext(null, null);

        /** Builds a {@link TransportContext} straight from the same {@code atlassian.tls}/{@code
         *  atlassian.proxy} config {@link HttpClients#build} was already given to construct this
         *  instance's {@link HttpClient} — the one-line helper every real call site (Fix 5, Task 8
         *  review) uses instead of each independently null-checking {@code tls} to reach its
         *  truststore path. */
        public static TransportContext of(AtlassianTls tls, AtlassianProxy proxy) {
            return new TransportContext(tls == null ? null : tls.truststore(), proxy);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Gate review minor: mirrors {@code DiagnosticWriter.MAX_BODY_SNIPPET_CHARS} — the terminal
     *  path (an {@link AtlassianException}'s message, which reaches stderr uncaught by any
     *  best-effort catch) used to carry the entire, unscrubbed response body while the diagnostics
     *  file's copy of the same content was already redacted and capped. Not a shared constant with
     *  {@code DiagnosticWriter} (that field is private, and the two files serve different readers)
     *  — kept at the same value on purpose so neither path is treated as "more trustworthy" than
     *  the other. */
    private static final int MAX_BODY_SNIPPET_CHARS = 500;

    private final String siteName;
    private final String baseUrl;
    private final String token;
    private final String tokenVar;
    private final Duration timeout;
    private final int maxAttempts;
    private final HttpClient client;
    private final Sleeper sleeper;
    private final DiagnosticWriter diagnostics;
    private final TransportContext transport;
    /** Gate review minor: redacts (at least) this site's own bearer token — plus, unconditionally,
     *  URL userinfo/Authorization-header/credential-query-param SHAPES regardless of value, per
     *  {@link Redactor}'s rule 2/3 — out of any response body an {@link AtlassianException}
     *  message carries to the caller/terminal. Built from ONLY this instance's token, not the full
     *  cross-site secret set a {@link DiagnosticWriter}'s own {@code Redactor} has: this class has
     *  no visibility into any other configured site's credentials, and does not need it — it only
     *  ever talks to the one site it was constructed for. */
    private final Redactor redactor;
    /**
     * Set only when {@code SDD_ATLASSIAN_DUMP} is configured. A field with a fluent setter rather
     * than a seventh constructor overload: six already exist, every one of them public and used,
     * and this is opt-in diagnostics rather than part of any caller's contract.
     */
    private AtlassianWireDump wireDump;

    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            HttpClient client) {
        this(siteName, baseUrl, token, tokenVar, timeout, Backoff.DEFAULT_MAX_ATTEMPTS, client, Thread::sleep,
                null, TransportContext.NONE);
    }

    /**
     * Task 8: same as the six-argument constructor, plus an optional {@link DiagnosticWriter}
     * (nullable — see this field's javadoc below) that every call this instance makes reports to.
     * A separate overload rather than a nullable-by-default parameter added to the existing one,
     * so every pre-Task-8 call site keeps compiling unchanged.
     */
    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            HttpClient client, DiagnosticWriter diagnostics) {
        this(siteName, baseUrl, token, tokenVar, timeout, Backoff.DEFAULT_MAX_ATTEMPTS, client, Thread::sleep,
                diagnostics, TransportContext.NONE);
    }

    /** Same as the seven-argument diagnostics constructor, plus Fix 5's {@link TransportContext} —
     *  the overload {@code BitbucketClients}/{@code JiraWriteBack}/{@code PlanCommand} use once they
     *  have a truststore/proxy in scope to hand in for failure-message enrichment. */
    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            HttpClient client, DiagnosticWriter diagnostics, TransportContext transport) {
        this(siteName, baseUrl, token, tokenVar, timeout, Backoff.DEFAULT_MAX_ATTEMPTS, client, Thread::sleep,
                diagnostics, transport);
    }

    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            int maxAttempts, HttpClient client, Sleeper sleeper) {
        this(siteName, baseUrl, token, tokenVar, timeout, maxAttempts, client, sleeper, null, TransportContext.NONE);
    }

    /** Same as the eight-argument diagnostics constructor, plus Fix 5's {@link TransportContext}. */
    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            int maxAttempts, HttpClient client, Sleeper sleeper, DiagnosticWriter diagnostics) {
        this(siteName, baseUrl, token, tokenVar, timeout, maxAttempts, client, sleeper, diagnostics,
                TransportContext.NONE);
    }

    /**
     * Task 8's diagnostics-aware canonical constructor. {@code diagnostics} is nullable and,
     * when null, every diagnostics call below is skipped — {@code RestClient} stays exactly as
     * testable and dependency-free as before Task 8 for any caller that has no writer to give it
     * (every {@link RestClientTest} case, and every real call site until Task 8's callers are
     * wired up one command at a time). {@code transport} is Fix 5's addition, {@link
     * TransportContext#NONE} when the caller has nothing to hand in.
     */
    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            int maxAttempts, HttpClient client, Sleeper sleeper, DiagnosticWriter diagnostics,
            TransportContext transport) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.siteName = siteName;
        this.baseUrl = baseUrl;
        this.token = token;
        this.tokenVar = tokenVar;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
        this.client = client;
        this.sleeper = sleeper;
        this.diagnostics = diagnostics;
        this.transport = transport == null ? TransportContext.NONE : transport;
        this.redactor = Redactor.of(token != null ? List.of(token) : List.of());
        this.wireDump = null;
    }

    /**
     * Records every exchange to {@code dump}, or stops recording when null.
     *
     * <p>Wired here rather than inside {@link #execute} reading the environment itself, so a test
     * can attach one without touching process state, and so the secret set the dump redacts is
     * assembled by the caller that knows every configured site's token — not just this client's.
     */
    public RestClient wireDump(AtlassianWireDump dump) {
        this.wireDump = dump;
        return this;
    }

    public JsonNode get(String path) {
        return parse(send("GET", path, null).body());
    }

    public JsonNode post(String path, JsonNode body) {
        return parse(send("POST", path, body).body());
    }

    public JsonNode put(String path, JsonNode body) {
        return parse(send("PUT", path, body).body());
    }

    /** For endpoints that reply 204 No Content (or a body the caller does not need) — same retry
     *  and error handling as {@link #post}, without trying to parse a response that may be empty. */
    public void postExpectingNoContent(String path, JsonNode body) {
        send("POST", path, body);
    }

    /**
     * Fetch a non-JSON resource — the one in sdd being a Confluence image attachment.
     *
     * <p>Two things separate this from {@link #get}, and both are why it exists rather than the
     * caller doing it. It sends a wildcard {@code Accept} header: this class otherwise hard-codes
     * {@code application/json}, and an attachment endpoint is entitled to refuse that. And it
     * follows ONE redirect, because Data Center answers the documented download URL with a 302 to
     * a {@code /download/attachments/...} path, while every other method here treats a 3xx as a
     * hard error — correctly, since for an API call it means the URL is wrong.
     *
     * <p>The hop is bounded at one and must stay on this site. A {@code Location} pointing
     * anywhere else is refused rather than followed: this client attaches a bearer token to every
     * request it makes, and following an off-site redirect would hand that token to whoever the
     * redirect names.
     */
    public byte[] getBytes(String path) {
        HttpResponse<byte[]> response = sendBytes(path);
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            String location = response.headers().firstValue("Location").orElseThrow();
            response = sendBytes(redirectPath(path, location));
            if (response.statusCode() >= 300) {
                throw new AtlassianException(siteOrGeneric() + " redirected " + path
                        + " more than once; sdd follows a single hop");
            }
        }
        return response.body();
    }

    private HttpResponse<byte[]> sendBytes(String path) {
        return send("GET", path, null, "*/*", HttpResponse.BodyHandlers.ofByteArray(),
                bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8), true);
    }

    /**
     * A {@code Location} reduced to a path under this client's base URL, or a refusal.
     *
     * <p>Relative and absolute forms both occur. An absolute one is accepted only when it starts
     * with {@link #baseUrl} — string-prefixed on purpose, because that is exactly the set of URLs
     * this client would have been willing to build itself, and anything broader would be a way to
     * aim a request carrying the site token somewhere the operator never configured.
     */
    private String redirectPath(String from, String location) {
        if (location.startsWith("/")) {
            return location;
        }
        if (location.startsWith(baseUrl + "/")) {
            return location.substring(baseUrl.length());
        }
        throw new AtlassianException(siteOrGeneric() + " redirected " + from + " to " + location
                + ", which is not under " + baseUrl + " — refusing to send the site token there");
    }

    /** Same as {@link #get}, but keeps the response headers alongside the parsed body — see
     *  {@link JsonResponse}. */
    public JsonResponse getWithHeaders(String path) {
        HttpResponse<String> resp = send("GET", path, null);
        return new JsonResponse(parse(resp.body()), resp.headers());
    }

    private HttpResponse<String> send(String method, String path, JsonNode body) {
        return send(method, path, body, "application/json", HttpResponse.BodyHandlers.ofString(),
                text -> text, false);
    }

    /**
     * The same retry, status handling, diagnostics and redaction for a response of any body type.
     *
     * <p>Generified rather than copied for {@link #getBytes}: this loop carries the 401/403
     * reissue-the-token message, the 407 it-is-the-proxy-not-the-site message, the 429/5xx backoff
     * and the {@link #safeBody} scrubbing, and a second hand-written copy of it would drift from
     * all five. {@code asText} exists only so an error body can still be read and scrubbed — on a
     * binary path it is used for the failure message and nothing else.
     *
     * <p>{@code allowRedirect} is off for every pre-existing caller, so a 3xx stays the hard error
     * it has always been here. It is on only for attachment downloads, where Data Center answers
     * the documented URL with a 302 to a different path on the same host.
     */
    private <T> HttpResponse<T> send(String method, String path, JsonNode body, String accept,
            HttpResponse.BodyHandler<T> handler, java.util.function.Function<T, String> asText,
            boolean allowRedirect) {
        AtlassianException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.nanoTime();
            try {
                HttpResponse<T> resp = execute(method, path, body, accept, handler, asText);
                int status = resp.statusCode();
                logRequest(method, path, status, durationMs(start), attempt, resp, asText);
                if (status >= 200 && status < 300) {
                    return resp;
                }
                if (allowRedirect && status >= 300 && status < 400
                        && resp.headers().firstValue("Location").isPresent()) {
                    return resp;
                }
                if (status == 401 || status == 403) {
                    throw logFailure(method, path, new AtlassianException(rejectedTokenMessage(status)));
                }
                if (status == 429) {
                    last = new AtlassianException(siteOrGeneric() + " HTTP 429: "
                            + safeBody(asText.apply(resp.body())));
                    backoff(attempt, Backoff.retryAfterMillis(resp.headers().firstValue("Retry-After")));
                    continue;
                }
                if (status >= 500) {
                    last = new AtlassianException(siteOrGeneric() + " HTTP " + status + ": "
                            + safeBody(asText.apply(resp.body())));
                    backoff(attempt, null);
                    continue;
                }
                if (status == 407) {
                    // A 407 is not the site rejecting us, it is the proxy in front of it -- and the
                    // generic message below sends someone hunting for a Jira permission problem
                    // that does not exist. describeEffectiveProxy already existed and was called
                    // only on the transport-failure path, where a 407 never lands.
                    throw logFailure(method, path, new AtlassianException(
                            "the HTTP proxy demanded authentication (HTTP 407) before "
                            + siteOrGeneric() + " was reached — " + proxyDescription()
                            + ". sdd sends no proxy credentials; configure a proxy that does not "
                            + "require them for this host, or route around it via "
                            + "atlassian.proxy.no_proxy"));
                }
                throw logFailure(method, path, new AtlassianException(siteOrGeneric() + " HTTP " + status
                        + ": " + safeBody(asText.apply(resp.body()))));
            } catch (IOException e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                last = new AtlassianException("transport error talking to " + siteOrGeneric() + ": " + detail, e);
                backoff(attempt, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw logFailure(method, path, new AtlassianException("interrupted", e));
            }
        }
        throw logFailure(method, path, last);
    }

    private static long durationMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Gate review minor: scrub-then-cap, same order as {@code DiagnosticWriter.httpRequest}'s own
     *  fix for the identical straddling-cutoff bug (redact BEFORE truncating, never after) — a
     *  secret that straddles the {@link #MAX_BODY_SNIPPET_CHARS} cutoff must not survive as a
     *  fragment that no longer exact-substring-matches what {@link #redactor} was built from. */
    private String safeBody(String body) {
        String scrubbed = redactor.scrub(body);
        if (scrubbed == null || scrubbed.length() <= MAX_BODY_SNIPPET_CHARS) {
            return scrubbed;
        }
        return scrubbed.substring(0, MAX_BODY_SNIPPET_CHARS)
                + " …[truncated, " + (scrubbed.length() - MAX_BODY_SNIPPET_CHARS) + " more chars]";
    }

    /** Task 8 B3's "per Atlassian HTTP request" line — a no-op when this instance has no
     *  {@link DiagnosticWriter} (every call site that predates Task 8, and every {@link
     *  RestClientTest} case). {@code errorBodySnippet} is only populated on a non-2xx: Atlassian
     *  error bodies "carry the actual reason and are the single most useful thing here" (brief). */
    private <T> void logRequest(String method, String path, int status, long durationMs, int attempt,
            HttpResponse<T> resp, java.util.function.Function<T, String> asText) {
        if (diagnostics == null) {
            return;
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse(null);
        String errorBody = (status < 200 || status >= 300) ? asText.apply(resp.body()) : null;
        diagnostics.httpRequest(siteOrGeneric(), method, path, status, durationMs, attempt, attempt > 1,
                contentType, errorBody);
    }

    /** Logs {@code ex}'s full cause chain via {@link DiagnosticWriter#failure} and returns it
     *  unchanged, so every throw site can wrap itself as {@code throw logFailure(...)} without a
     *  separate statement — keeping the existing control flow (and every message this class's
     *  tests already pin) untouched. A no-op when there is no writer, or when {@code ex} is null
     *  (the {@code last == null} case is unreachable in practice — {@code maxAttempts >= 1} means
     *  at least one iteration always runs — but this keeps the helper total rather than assuming
     *  that invariant here too). Also appends Fix 5's TLS-handshake/effective-proxy enrichment as a
     *  separate {@link DiagnosticWriter#note} line when the cause chain and {@link #transport} make
     *  one available — see {@link #transportEnrichment}. */
    private <T extends Throwable> T logFailure(String method, String path, T ex) {
        if (diagnostics != null && ex != null) {
            diagnostics.failure(siteOrGeneric() + " " + method + " " + path, ex);
            String enrichment = transportEnrichment(ex);
            if (enrichment != null) {
                diagnostics.note(enrichment);
            }
        }
        return ex;
    }

    /**
     * Fix 5 (Task 8 review): "only {@code AtlassianProbe}'s doctor path gets the pre-correlated
     * host+truststore+effective-proxy sentence" — this is that same enrichment for every OTHER
     * {@code RestClient} caller's failures, reusing {@link HttpClients#tlsFailureMessage}/{@link
     * HttpClients#describeEffectiveProxy} rather than a second bespoke implementation. Checked in
     * this order because a TLS failure IS a connect-shaped failure too (an {@link SSLException} is
     * an {@link IOException}) — the truststore fact is the more specific, more actionable one, so it
     * wins when both would otherwise apply. Returns null (nothing appended) when the cause chain
     * carries neither shape, or when {@link #transport} has nothing configured to name.
     */
    private String transportEnrichment(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException ssl) {
                return HttpClients.tlsFailureMessage(UrlHosts.hostOf(baseUrl), transport.truststore(), ssl);
            }
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                String host = UrlHosts.hostOf(baseUrl);
                return "effective proxy for " + host + ": " + HttpClients.describeEffectiveProxy(transport.proxy(), host);
            }
        }
        return null;
    }

    private String siteOrGeneric() {
        return siteName != null ? siteName : "the server";
    }

    /** "Jira rejected the token in $JIRA_API_KEY (HTTP 401) — reissue it" — PATs expire, so the
     *  message has to say which environment variable to reissue, not just "unauthorized". Falls
     *  back to "the configured token" when the token was a literal rather than a {@code ${VAR}}
     *  reference, since there is then no variable name to give. */
    private String rejectedTokenMessage(int status) {
        String tokenDesc = tokenVar != null ? "the token in $" + tokenVar : "the configured token";
        return siteOrGeneric() + " rejected " + tokenDesc + " (HTTP " + status + ") — reissue it";
    }

    /** The proxy actually in effect for this site's host, for a 407's message. */
    private String proxyDescription() {
        String host = UrlHosts.hostOf(baseUrl);
        if (transport.proxy() == null || host == null) {
            return "no atlassian.proxy is configured, so this proxy is one the JVM or the "
                    + "environment supplied";
        }
        return HttpClients.describeEffectiveProxy(transport.proxy(), host);
    }

    private <T> HttpResponse<T> execute(String method, String path, JsonNode body, String accept,
            HttpResponse.BodyHandler<T> handler, java.util.function.Function<T, String> asText)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", accept);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        switch (method) {
            case "GET" -> builder.GET();
            case "POST" -> builder.header("Content-Type", "application/json").POST(publisher);
            case "PUT" -> builder.header("Content-Type", "application/json").PUT(publisher);
            default -> throw new IllegalArgumentException("unsupported method: " + method);
        }
        HttpRequest request = builder.build();
        String requestBody = body == null ? null : body.toString();
        if (wireDump == null) {
            return client.send(request, handler);
        }
        HttpResponse<T> response;
        try {
            response = client.send(request, handler);
        } catch (IOException | InterruptedException e) {
            // A transport failure is exactly the case the dump exists for -- a TLS handshake
            // rejected by a corporate CA, or a proxy refusing to tunnel -- so it must be recorded
            // before the exception continues on its way.
            wireDump.recordFailure(method, baseUrl + path, requestBody, String.valueOf(e));
            throw e;
        }
        // Never the raw body when it is not text: an attachment download is hundreds of kilobytes
        // of PNG, and writing that into a JSONL diagnostics file as mojibake helps nobody while
        // making the file unreadable and enormous. Its size and type are the useful part.
        wireDump.record(method, baseUrl + path, requestBody, response.statusCode(),
                response.headers().map(), dumpableBody(response, asText));
        return response;
    }

    /**
     * What the wire dump should record as this response's body.
     *
     * <p>Text goes in as-is. Anything else is described, not transcribed: an attachment download is
     * hundreds of kilobytes of image, and a JSONL diagnostics file is read by a human on a closed
     * network who is trying to see a request. Bytes would make that file both unreadable and
     * enormous, and the dump exists to be readable.
     */
    private static <T> String dumpableBody(HttpResponse<T> response,
            java.util.function.Function<T, String> asText) {
        T body = response.body();
        if (body instanceof String text) {
            return text;
        }
        int length = body instanceof byte[] bytes ? bytes.length : -1;
        String contentType = response.headers().firstValue("Content-Type").orElse("unknown type");
        return "<" + (length < 0 ? "non-text" : length + " bytes") + " of " + contentType + ">";
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
            throw new AtlassianException("interrupted during backoff", e);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            // Gate re-review Fix 4: the last body-to-terminal hole safeBody's introduction missed —
            // reachable from the 2xx path (get/post/put/getWithHeaders all funnel through here), so
            // an Atlassian endpoint returning malformed "JSON" carried its entire raw, unscrubbed,
            // uncapped body into this exception's message exactly like the three non-2xx sites did
            // before that fix. Routed through the same safeBody(...) for the same reason.
            throw new AtlassianException("unparseable response: " + safeBody(body), e);
        }
    }
}
