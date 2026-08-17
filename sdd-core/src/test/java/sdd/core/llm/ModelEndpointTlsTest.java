package sdd.core.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.CertFixtures;
import sdd.core.http.TlsConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2's centrepiece test: a {@link ModelEndpoint} carrying a {@code tls} block, built through
 * the ordinary {@code ConfigLoader} shape (no injected {@code HttpClient}), completes a real mTLS
 * handshake through {@link HttpChatModel}'s and {@link EndpointProbe}'s DEFAULT client-building
 * constructors — not merely that a hand-built {@code TlsConfig} works in isolation (Phase 1's
 * {@code HttpClientsMtlsHandshakeTest} already proved the mechanics); this proves the two model
 * call sites are actually wired to it. Reuses Phase 1's generated-PKI WireMock mTLS setup
 * ({@link CertFixtures}, now public for exactly this reuse) rather than a second implementation of
 * the same throwaway-CA machinery.
 */
class ModelEndpointTlsTest {
    @TempDir Path dir;
    private CertFixtures fixtures;
    private WireMockServer wm;

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1}}
            """;

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
    }

    @AfterEach
    void stopServer() {
        if (wm != null) {
            wm.stop();
        }
    }

    private ModelEndpoint endpoint() {
        TlsConfig tls = new TlsConfig(fixtures.caTrustStoreP12(), new String(CertFixtures.TRUSTSTORE_PASSWORD), null,
                fixtures.clientCertPem(), fixtures.clientKeyPkcs8Unencrypted(), null, null, List.of());
        return new ModelEndpoint(wm.baseUrl(), "corp-test", null, 256, 0.0, Duration.ofSeconds(5),
                Map.of(), null, tls);
    }

    @Test
    void aCertConfiguredEndpointsDefaultClientChatModelCompletesARealMtlsHandshake() {
        wm.stubFor(post("/chat/completions").willReturn(okJson(OK_BODY)));

        ChatResponse resp = new HttpChatModel(endpoint())
                .complete(new ChatRequest("corp-test", List.of(ChatMessage.user("hi")), List.of(), 256, 0.0));

        assertThat(resp.message().content()).isEqualTo("hello");
    }

    @Test
    void aCertConfiguredEndpointsDefaultClientProbeCompletesARealMtlsHandshake() {
        wm.stubFor(get("/models").willReturn(okJson("{\"data\":[]}")));

        EndpointProbe.ProbeResult r = EndpointProbe.probe(endpoint());

        assertThat(r.ok()).isTrue();
    }
}
