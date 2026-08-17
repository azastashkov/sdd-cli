package sdd.core.llm;

import sdd.core.config.ConfigException;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.HttpClients;
import sdd.core.http.UrlHosts;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

public final class EndpointProbe {
    /**
     * {@code negotiatedProtocol}: the TLS protocol version this probe's handshake actually landed
     * on (e.g. {@code "TLSv1.2"}), read off the same {@link HttpResponse#sslSession()} the probe
     * request already produced — never a second connection just to observe it. Null for a plain
     * HTTP endpoint (no TLS session at all) or when the probe failed before completing a handshake.
     * Phase 3 diagnostics ({@code sdd.cli.DoctorCommand}) is the one consumer.
     *
     * <p>A 2-arg canonical-shaped constructor is kept alongside the 3-arg one — the same delegating
     * pattern {@code ModelEndpoint} already uses for {@code apiKeyError}/{@code tls} — so every
     * pre-existing call site (this class's own four {@code new ProbeResult(ok, detail)} sites below,
     * and {@code ImplementCommandWaitEndpointTest}'s two direct constructions) keeps compiling with
     * {@code negotiatedProtocol} defaulting to null, unchanged.
     */
    public record ProbeResult(boolean ok, String detail, String negotiatedProtocol) {
        public ProbeResult(boolean ok, String detail) {
            this(ok, detail, null);
        }
    }

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private EndpointProbe() {}

    /**
     * Phase 2: {@code HttpClient.newHttpClient()} was inlined here, which could never see a model
     * endpoint's {@code tls} block. Routed through {@link HttpClients#buildClient} instead — a
     * no-op ({@code HttpClient.newHttpClient()}, byte-identical to before) for every endpoint that
     * predates this feature, since {@code endpoint.tls()} is null there. Unlike
     * {@link #probe(ModelEndpoint, HttpClient)}'s internal {@code apiKeyError} handling, a TLS
     * misconfiguration (missing cert file, unset {@code key_password}, cert without key) is caught
     * here and turned into a failed {@link ProbeResult} rather than thrown — {@code doctor}'s
     * per-tier probe loop must keep reporting every other endpoint even when one tier's client
     * certificate is broken, exactly the contract {@code apiKeyError} already established below.
     */
    public static ProbeResult probe(ModelEndpoint ep) {
        HttpClient client;
        try {
            client = HttpClients.buildClient(ep.tls(), null);
        } catch (ConfigException e) {
            return new ProbeResult(false, e.getMessage());
        }
        return probe(ep, client);
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
            String protocol = resp.sslSession().map(SSLSession::getProtocol).orElse(null);
            return new ProbeResult(status >= 200 && status < 300, "HTTP " + status, protocol);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "interrupted");
        } catch (SSLException e) {
            // "The failure this will most likely hit first" (plan): curl succeeding against this
            // same URL is not evidence the JDK trusts the chain — see
            // HttpClients.modelTlsFailureMessage's javadoc. Only reachable for a tls-configured
            // endpoint (api-key-only endpoints never build an SSLContext beyond the JDK default, so
            // this is exactly as likely to fire there as it always was — i.e. essentially never).
            Path truststore = ep.tls() != null ? ep.tls().truststore() : null;
            return new ProbeResult(false, HttpClients.modelTlsFailureMessage(UrlHosts.hostOf(ep.baseUrl()),
                    truststore, e));
        } catch (Exception e) {
            return new ProbeResult(false, String.valueOf(e.getMessage()));
        }
    }
}
