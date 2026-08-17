package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.TlsConfig;

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
}
