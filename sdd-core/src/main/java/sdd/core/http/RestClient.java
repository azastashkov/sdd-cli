package sdd.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import sdd.core.diagnostics.DiagnosticWriter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String siteName;
    private final String baseUrl;
    private final String token;
    private final String tokenVar;
    private final Duration timeout;
    private final int maxAttempts;
    private final HttpClient client;
    private final Sleeper sleeper;
    private final DiagnosticWriter diagnostics;

    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            HttpClient client) {
        this(siteName, baseUrl, token, tokenVar, timeout, Backoff.DEFAULT_MAX_ATTEMPTS, client, Thread::sleep, null);
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
                diagnostics);
    }

    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            int maxAttempts, HttpClient client, Sleeper sleeper) {
        this(siteName, baseUrl, token, tokenVar, timeout, maxAttempts, client, sleeper, null);
    }

    /**
     * Task 8's diagnostics-aware canonical constructor. {@code diagnostics} is nullable and,
     * when null, every diagnostics call below is skipped — {@code RestClient} stays exactly as
     * testable and dependency-free as before Task 8 for any caller that has no writer to give it
     * (every {@link RestClientTest} case, and every real call site until Task 8's callers are
     * wired up one command at a time).
     */
    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            int maxAttempts, HttpClient client, Sleeper sleeper, DiagnosticWriter diagnostics) {
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

    /** Same as {@link #get}, but keeps the response headers alongside the parsed body — see
     *  {@link JsonResponse}. */
    public JsonResponse getWithHeaders(String path) {
        HttpResponse<String> resp = send("GET", path, null);
        return new JsonResponse(parse(resp.body()), resp.headers());
    }

    private HttpResponse<String> send(String method, String path, JsonNode body) {
        AtlassianException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.nanoTime();
            try {
                HttpResponse<String> resp = execute(method, path, body);
                int status = resp.statusCode();
                logRequest(method, path, status, durationMs(start), attempt, resp);
                if (status >= 200 && status < 300) {
                    return resp;
                }
                if (status == 401 || status == 403) {
                    throw logFailure(method, path, new AtlassianException(rejectedTokenMessage(status)));
                }
                if (status == 429) {
                    last = new AtlassianException(siteOrGeneric() + " HTTP 429: " + resp.body());
                    backoff(attempt, Backoff.retryAfterMillis(resp.headers().firstValue("Retry-After")));
                    continue;
                }
                if (status >= 500) {
                    last = new AtlassianException(siteOrGeneric() + " HTTP " + status + ": " + resp.body());
                    backoff(attempt, null);
                    continue;
                }
                throw logFailure(method, path, new AtlassianException(siteOrGeneric() + " HTTP " + status
                        + ": " + resp.body()));
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

    /** Task 8 B3's "per Atlassian HTTP request" line — a no-op when this instance has no
     *  {@link DiagnosticWriter} (every call site that predates Task 8, and every {@link
     *  RestClientTest} case). {@code errorBodySnippet} is only populated on a non-2xx: Atlassian
     *  error bodies "carry the actual reason and are the single most useful thing here" (brief). */
    private void logRequest(String method, String path, int status, long durationMs, int attempt,
            HttpResponse<String> resp) {
        if (diagnostics == null) {
            return;
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse(null);
        String errorBody = (status < 200 || status >= 300) ? resp.body() : null;
        diagnostics.httpRequest(siteOrGeneric(), method, path, status, durationMs, attempt, attempt > 1,
                contentType, errorBody);
    }

    /** Logs {@code ex}'s full cause chain via {@link DiagnosticWriter#failure} and returns it
     *  unchanged, so every throw site can wrap itself as {@code throw logFailure(...)} without a
     *  separate statement — keeping the existing control flow (and every message this class's
     *  tests already pin) untouched. A no-op when there is no writer, or when {@code ex} is null
     *  (the {@code last == null} case is unreachable in practice — {@code maxAttempts >= 1} means
     *  at least one iteration always runs — but this keeps the helper total rather than assuming
     *  that invariant here too). */
    private <T extends Throwable> T logFailure(String method, String path, T ex) {
        if (diagnostics != null && ex != null) {
            diagnostics.failure(siteOrGeneric() + " " + method + " " + path, ex);
        }
        return ex;
    }

    private String siteOrGeneric() {
        return siteName != null ? siteName : "the server";
    }

    /** "Jira rejected the token in $JIRA_PAT (HTTP 401) — reissue it" — PATs expire, so the
     *  message has to say which environment variable to reissue, not just "unauthorized". Falls
     *  back to "the configured token" when the token was a literal rather than a {@code ${VAR}}
     *  reference, since there is then no variable name to give. */
    private String rejectedTokenMessage(int status) {
        String tokenDesc = tokenVar != null ? "the token in $" + tokenVar : "the configured token";
        return siteOrGeneric() + " rejected " + tokenDesc + " (HTTP " + status + ") — reissue it";
    }

    private HttpResponse<String> execute(String method, String path, JsonNode body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json");
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
            throw new AtlassianException("interrupted during backoff", e);
        }
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new AtlassianException("unparseable response: " + body, e);
        }
    }
}
