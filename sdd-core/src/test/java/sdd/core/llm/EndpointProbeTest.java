package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;

import java.net.http.HttpClient;
import java.time.Duration;
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
