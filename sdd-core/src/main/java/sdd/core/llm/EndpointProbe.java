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
import java.util.List;

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
     *
     * <p>{@code connected}: whether an HTTP response came back AT ALL, whatever its status — as
     * opposed to {@code ok}, which means that response was 2xx. The two differ for exactly one
     * reason, and it matters: this probe asks for {@code /models}, and a gateway is under no
     * obligation to serve an OpenAI model-listing route. Such a gateway answers 404 while
     * {@code /chat/completions} works perfectly, and {@code sdd doctor} gates its {@code --tools}
     * and {@code --completion} probes on this field rather than on {@code ok} so the checks that
     * actually predict a run still get to run.
     *
     * <p>{@code ok} is deliberately NOT redefined to mean this. A 404 on {@code /models} from an
     * ordinary OpenAI endpoint means {@code base_url} is wrong — usually the {@code
     * /chat/completions} suffix left on, the misconfiguration {@code sdd.yml.example} and the
     * runbook both warn about. Reporting that green to make one gateway look tidy would hide the
     * commonest configuration error this project has. The older constructors default
     * {@code connected} to {@code ok}, which is exactly right for them: every one is either a 2xx
     * or a failure with no response.
     */
    public record ProbeResult(boolean ok, String detail, String negotiatedProtocol,
                             boolean connected, List<String> models) {
        public ProbeResult(boolean ok, String detail, String negotiatedProtocol,
                           boolean connected) {
            this(ok, detail, negotiatedProtocol, connected, List.of());
        }

        public ProbeResult(boolean ok, String detail, String negotiatedProtocol) {
            this(ok, detail, negotiatedProtocol, ok, List.of());
        }

        public ProbeResult(boolean ok, String detail) {
            this(ok, detail, null);
        }
    }

    /** How many ids are worth naming before the list is just noise. */
    private static final int MAX_MODEL_IDS = 40;

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
            // The body was discarded here until a live run spent a round trip on
            // {"status":404,"message":"No such model"} while THIS request had just returned 200
            // with the list of models the gateway actually serves. Keeping it turns "no such
            // model" into "it offers these; you configured that".
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            String protocol = resp.sslSession().map(SSLSession::getProtocol).orElse(null);
            boolean ok = status >= 200 && status < 300;
            // The response arrived, so the host, the TLS handshake and the routing all worked.
            // That is worth reporting separately from the status — see ProbeResult's javadoc.
            return new ProbeResult(ok, detail(status, ok), protocol, true,
                    ok ? modelIds(resp.body()) : List.of());
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
            String configPath = ep.tls() != null ? ep.tls().configPath() : null;
            return new ProbeResult(false, HttpClients.modelTlsFailureMessage(UrlHosts.hostOf(ep.baseUrl()),
                    truststore, e, configPath));
        } catch (Exception e) {
            return new ProbeResult(false, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Model ids out of an OpenAI-shaped {@code /models} listing, or empty for anything else.
     *
     * <p>Best-effort by design: this is a diagnostic aid, and a gateway that answers /models in its
     * own shape must not turn into an error on a path that was working. Empty simply means the
     * hint cannot be offered.
     */
    private static List<String> modelIds(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            com.fasterxml.jackson.databind.JsonNode data = root.path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<String> ids = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode entry : data) {
                String id = entry.path("id").asText(null);
                if (id != null && !id.isBlank() && ids.size() < MAX_MODEL_IDS) {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * What a status means, when the difference changes the operator's next move.
     *
     * <p>404/405 on {@code /models} has two readings that need opposite fixes, and neither is
     * guessable from {@code HTTP 404} alone: the gateway serves no model listing (fine — the real
     * check is {@code --tools}), or {@code base_url} is wrong. Naming both is the only honest
     * option, since nothing observable here distinguishes them.
     */
    private static String detail(int status, boolean ok) {
        if (ok || (status != 404 && status != 405)) {
            return "HTTP " + status;
        }
        return "HTTP " + status + " — reachable, but no /models listing here. Either this gateway "
                + "does not serve one (run --tools to check what matters), or base_url is wrong "
                + "(it must NOT include /chat/completions)";
    }
}
