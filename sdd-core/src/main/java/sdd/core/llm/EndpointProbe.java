package sdd.core.llm;

import sdd.core.config.ConfigException;
import sdd.core.config.ModelEndpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class EndpointProbe {
    public record ProbeResult(boolean ok, String detail) {}

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private EndpointProbe() {}

    public static ProbeResult probe(ModelEndpoint ep) {
        return probe(ep, HttpClient.newHttpClient());
    }

    /**
     * Task 2: the single-arg {@link #probe(ModelEndpoint)} used to inline
     * {@code HttpClient.newHttpClient()} and could not be pointed at a client carrying
     * {@code atlassian.tls}/{@code atlassian.proxy} settings, or a WireMock server that requires
     * them. This overload takes the client instead — {@link #probe(ModelEndpoint)} is now a thin
     * wrapper over it, so every existing caller keeps compiling and behaving identically.
     */
    public static ProbeResult probe(ModelEndpoint ep, HttpClient client) {
        try {
            // Deferred from ConfigLoader (Fix 1): raise it here, as early as this class can — the
            // generic catch below turns it into a failed ProbeResult carrying the exact deferred
            // message, which is exactly what sdd doctor wants to show per model tier rather than
            // aborting the whole probe loop.
            if (ep.apiKeyError() != null) {
                throw new ConfigException(ep.apiKeyError());
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(ep.baseUrl() + "/models"))
                    .timeout(PROBE_TIMEOUT)
                    .GET();
            if (ep.apiKey() != null) {
                builder.header("Authorization", "Bearer " + ep.apiKey());
            }
            HttpResponse<Void> resp = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            return new ProbeResult(status >= 200 && status < 300, "HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "interrupted");
        } catch (Exception e) {
            return new ProbeResult(false, String.valueOf(e.getMessage()));
        }
    }
}
