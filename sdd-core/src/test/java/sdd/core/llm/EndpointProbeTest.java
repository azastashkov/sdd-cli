package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.TlsConfig;

import javax.net.ssl.SSLHandshakeException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointProbeTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private ModelEndpoint ep(String key) {
        return new ModelEndpoint(wm.baseUrl() + "/v1", "m", key, 256, 0.0, Duration.ofSeconds(5), Map.of());
    }

    @Test
    void reachableEndpointIsOkAndSendsAuth() {
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep("sk-x"));
        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).isEqualTo("HTTP 200");
        wm.verify(getRequestedFor(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer sk-x")));
    }

    @Test
    void non2xxIsNotOk() {
        wm.stubFor(get("/v1/models").willReturn(unauthorized()));
        assertThat(EndpointProbe.probe(ep("bad")).ok()).isFalse();
    }

    @Test
    void unreachableHostIsNotOkAndDoesNotThrow() {
        ModelEndpoint dead = new ModelEndpoint("http://127.0.0.1:1/v1", "m", null,
                256, 0.0, Duration.ofSeconds(1), Map.of());
        EndpointProbe.ProbeResult r = EndpointProbe.probe(dead);
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isNotBlank();
    }

    @Test
    void anApiKeyErrorSurfacesAsAFailedProbeWithTheExactDeferredMessage() {
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "m", null, 256, 0.0,
                Duration.ofSeconds(5), Map.of(),
                "models.flash.api_key: environment variable ROUTER_AI_API_KEY is not set");

        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep);

        assertThat(r.ok()).isFalse();
        assertThat(r.detail())
                .isEqualTo("models.flash.api_key: environment variable ROUTER_AI_API_KEY is not set");
        wm.verify(0, getRequestedFor(urlEqualTo("/v1/models")));   // failed before any call
    }

    // Phase 2 (model mTLS): the default-client probe(ModelEndpoint) overload now builds via
    // HttpClients.buildClient(ep.tls(), null), which can throw ConfigException for a broken tls
    // block (here, a client cert file that does not exist). doctor's per-tier probe loop must keep
    // reporting every other endpoint even when one tier's certificate is misconfigured — the exact
    // contract the apiKeyError test above already established for a bad credential — so this must
    // surface as a failed ProbeResult, never an uncaught exception.
    @Test
    void aBrokenTlsConfigSurfacesAsAFailedProbeInsteadOfThrowing() {
        TlsConfig brokenTls = new TlsConfig(null, null, null,
                Path.of("/does/not/exist/client.crt"), Path.of("/does/not/exist/client.key"),
                null, null, List.of());
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "m", null, 256, 0.0,
                Duration.ofSeconds(5), Map.of(), null, brokenTls);

        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep);

        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).contains("does not exist");
        wm.verify(0, getRequestedFor(urlEqualTo("/v1/models")));   // failed before any call
    }

    // Task 2: EndpointProbe.probe(ModelEndpoint) inlined HttpClient.newHttpClient() and could not
    // have TLS/proxy settings applied, or be pointed at a WireMock server that requires them. The
    // injectable overload keeps the existing single-arg signature working (see the two tests
    // above, both still calling it) while making the client swappable.
    @Test
    void injectedClientOverloadIsUsedInsteadOfANewDefaultClient() {
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep("sk-x"), HttpClient.newHttpClient());
        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).isEqualTo("HTTP 200");
    }

    // Pre-existing 2-arg call sites (ImplementCommandWaitEndpointTest constructs ProbeResult
    // directly) must keep compiling untouched once a 3rd component (negotiatedProtocol, Phase 3
    // diagnostics) is added — this is the same delegating-constructor pattern ModelEndpoint already
    // uses for apiKeyError/tls.
    @Test
    void theTwoArgProbeResultConstructorLeavesNegotiatedProtocolNull() {
        EndpointProbe.ProbeResult r = new EndpointProbe.ProbeResult(true, "HTTP 200");
        assertThat(r.negotiatedProtocol()).isNull();
    }

    // Phase 3: "The failure this will most likely hit first" — an SSL handshake failure against a
    // model endpoint must name the host and truststore, and say plainly that a working curl to the
    // same URL is not evidence the JDK trusts the chain (HttpClients.modelTlsFailureMessage), not a
    // bare "PKIX path building failed" a reasonable person reads as "sdd is broken". Mirrors
    // AtlassianProbeTest's anSslHandshakeFailureSurfacesTheHostAndTruststoreDiagnostic fake-client
    // pattern exactly, since this is the same failure mode one layer down.
    @Test
    void anSslHandshakeFailureSurfacesTheExtendedHostAndTruststoreDiagnostic() {
        HttpClient sslRefusing = new HttpClient() {
            @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
            @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest req,
                    java.net.http.HttpResponse.BodyHandler<T> h) throws java.io.IOException {
                throw new SSLHandshakeException("PKIX path building failed");
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h,
                    java.net.http.HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }
        };
        TlsConfig tls = new TlsConfig(Path.of("/etc/ssl/corp-ca.p12"), null, null,
                Path.of("/does/not/matter/client.crt"), Path.of("/does/not/matter/client.key"), null, null,
                List.of());
        ModelEndpoint ep = new ModelEndpoint("https://corp-ift.example/v1", "m", null,
                256, 0.0, Duration.ofSeconds(5), Map.of(), null, tls);

        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep, sslRefusing);

        assertThat(r.ok()).isFalse();
        assertThat(r.detail())
                .startsWith("TLS handshake with corp-ift.example failed using "
                        + "truststore /etc/ssl/corp-ca.p12: PKIX path building failed")
                .contains("curl").contains("cacerts");
    }

    // Follow-up fix: the remedy sentence above ("Fix by setting tls.truststore in sdd.yml...") used
    // to name no real config key for a model endpoint. With this endpoint's TlsConfig.configPath
    // threaded through (set by ConfigLoader.parseModelTls, same as every other field on this
    // record), it now names this endpoint's own dotted key instead of the generic one the test
    // above pins deliberately (that TlsConfig carries no configPath, mirroring a caller with no
    // endpoint namespace to give).
    @Test
    void anSslHandshakeFailureNamesThisEndpointsOwnTruststoreConfigKeyWhenConfigPathIsKnown() {
        HttpClient sslRefusing = new HttpClient() {
            @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest req,
                    java.net.http.HttpResponse.BodyHandler<T> h) throws java.io.IOException {
                throw new SSLHandshakeException("PKIX path building failed");
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }
            @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                    java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h,
                    java.net.http.HttpResponse.PushPromiseHandler<T> p) {
                throw new UnsupportedOperationException();
            }
        };
        TlsConfig tls = new TlsConfig(Path.of("/etc/ssl/corp-ca.p12"), null, null,
                Path.of("/does/not/matter/client.crt"), Path.of("/does/not/matter/client.key"), null, null,
                List.of(), "models.corp.tls");
        ModelEndpoint ep = new ModelEndpoint("https://corp-ift.example/v1", "m", null,
                256, 0.0, Duration.ofSeconds(5), Map.of(), null, tls);

        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep, sslRefusing);

        assertThat(r.detail()).contains("models.corp.tls.truststore").doesNotContain(" tls.truststore");
    }
}
