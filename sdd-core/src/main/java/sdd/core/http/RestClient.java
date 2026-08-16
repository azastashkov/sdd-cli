package sdd.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String siteName;
    private final String baseUrl;
    private final String token;
    private final String tokenVar;
    private final Duration timeout;
    private final int maxAttempts;
    private final HttpClient client;
    private final Sleeper sleeper;

    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            HttpClient client) {
        this(siteName, baseUrl, token, tokenVar, timeout, Backoff.DEFAULT_MAX_ATTEMPTS, client, Thread::sleep);
    }

    public RestClient(String siteName, String baseUrl, String token, String tokenVar, Duration timeout,
            int maxAttempts, HttpClient client, Sleeper sleeper) {
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
    }

    public JsonNode get(String path) {
        return send("GET", path, null, true);
    }

    public JsonNode post(String path, JsonNode body) {
        return send("POST", path, body, true);
    }

    public JsonNode put(String path, JsonNode body) {
        return send("PUT", path, body, true);
    }

    /** For endpoints that reply 204 No Content (or a body the caller does not need) — same retry
     *  and error handling as {@link #post}, without trying to parse a response that may be empty. */
    public void postExpectingNoContent(String path, JsonNode body) {
        send("POST", path, body, false);
    }

    private JsonNode send(String method, String path, JsonNode body, boolean parseResponse) {
        AtlassianException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> resp = execute(method, path, body);
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return parseResponse ? parse(resp.body()) : null;
                }
                if (status == 401 || status == 403) {
                    throw new AtlassianException(rejectedTokenMessage(status));
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
                throw new AtlassianException(siteOrGeneric() + " HTTP " + status + ": " + resp.body());
            } catch (IOException e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                last = new AtlassianException("transport error talking to " + siteOrGeneric() + ": " + detail, e);
                backoff(attempt, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AtlassianException("interrupted", e);
            }
        }
        throw last;
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
