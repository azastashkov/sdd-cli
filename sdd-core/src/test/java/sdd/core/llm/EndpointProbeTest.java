package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointProbeTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private ModelEndpoint ep(String key) {
        return new ModelEndpoint(wm.baseUrl() + "/v1", "m", key, 256, 0.0, Duration.ofSeconds(5));
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
                256, 0.0, Duration.ofSeconds(1));
        EndpointProbe.ProbeResult r = EndpointProbe.probe(dead);
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isNotBlank();
    }
}
