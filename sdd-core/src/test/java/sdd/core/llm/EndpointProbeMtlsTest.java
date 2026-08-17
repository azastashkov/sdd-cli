package sdd.core.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.CertFixtures;
import sdd.core.http.HttpClients;
import sdd.core.http.TlsConfig;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link EndpointProbe.ProbeResult#negotiatedProtocol()} carries a REAL negotiated TLS
 * protocol version, read off the same handshake the probe request itself performs — Phase 3
 * diagnostics ({@code sdd.cli.DoctorCommand}) reports this per the plan's "Diagnostics" section.
 * Reuses {@code CertFixtures} (public, in {@code sdd.core.http}, generated fresh into
 * {@code @TempDir} at test time — same class {@code HttpClientsMtlsHandshakeTest} already
 * established this pattern with) rather than a second PKI generator; it is visible here across
 * packages because both live in {@code sdd-core}'s ordinary {@code src/test/java} — no
 * {@code testFixtures} wiring needed for one same-module test-to-test dependency.
 */
class EndpointProbeMtlsTest {
    @TempDir Path dir;
    private CertFixtures fixtures;
    private WireMockServer wm;

    @BeforeEach
    void startServer() throws Exception {
        fixtures = new CertFixtures(dir);
        fixtures.generate();
        wm = new WireMockServer(WireMockConfiguration.wireMockConfig()
                .dynamicHttpsPort()
                .httpDisabled(true)
                .keystorePath(fixtures.serverKeystoreP12().toString())
                .keystorePassword(CertFixtures.SERVER_KEYSTORE_PASSWORD)
                .keyManagerPassword(CertFixtures.SERVER_KEYSTORE_PASSWORD)
                .keystoreType("PKCS12")
                .trustStorePath(fixtures.caTrustStoreP12().toString())
                .trustStorePassword(new String(CertFixtures.TRUSTSTORE_PASSWORD))
                .trustStoreType("PKCS12")
                .needClientAuth(true));
        wm.start();
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
    }

    @AfterEach
    void stopServer() {
        if (wm != null) {
            wm.stop();
        }
    }

    private ModelEndpoint endpoint(List<String> protocols) {
        TlsConfig tls = new TlsConfig(fixtures.caTrustStoreP12(), new String(CertFixtures.TRUSTSTORE_PASSWORD),
                null, fixtures.clientCertPem(), fixtures.clientKeyPkcs8Unencrypted(), null, null, protocols);
        return new ModelEndpoint(wm.baseUrl() + "/v1", "m", null, 256, 0.0, Duration.ofSeconds(5), Map.of(),
                null, tls);
    }

    @Test
    void aSuccessfulProbeRecordsTheRealNegotiatedProtocol() {
        ModelEndpoint ep = endpoint(List.of("TLSv1.2"));
        HttpClient client = HttpClients.buildClient(ep.tls(), null);

        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep, client);

        assertThat(r.ok()).isTrue();
        assertThat(r.negotiatedProtocol()).isEqualTo("TLSv1.2");
    }
}
