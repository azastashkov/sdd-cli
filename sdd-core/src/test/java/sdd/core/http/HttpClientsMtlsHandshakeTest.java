package sdd.core.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The centrepiece of Phase 1's definition of done: a real mTLS handshake against a WireMock
 * server configured with {@code needClientAuth(true)}, proving the unencrypted-PKCS#8 path
 * (the format the real corporate gateway key is confirmed to use, per the plan) actually works —
 * not merely that the key parses. {@link CertFixtures} generates the whole throwaway PKI (root
 * CA, server leaf, client leaf in every PEM key form, and a separate leaf-signed-by-intermediate
 * chain) into {@code @TempDir} with {@code openssl}/{@code keytool} so nothing here is a
 * checked-in certificate that silently expires later.
 *
 * <p>{@code @RegisterExtension WireMockExtension} is deliberately NOT used here: that field would
 * be initialised in a static initializer, before {@code @TempDir} has anything to inject, but the
 * server's keystore/truststore paths depend on fixtures generated fresh per test into that
 * directory. A plain {@link WireMockServer}, started in {@link #startServer()} after
 * {@link CertFixtures#generate()} has already run, avoids that ordering problem entirely.
 */
class HttpClientsMtlsHandshakeTest {
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
        wm.stubFor(get("/ping").willReturn(ok("pong")));
    }

    @AfterEach
    void stopServer() {
        if (wm != null) {
            wm.stop();
        }
    }

    private TlsConfig tlsConfig(Path clientCert, Path clientKey, List<String> protocols) {
        return new TlsConfig(fixtures.caTrustStoreP12(), new String(CertFixtures.TRUSTSTORE_PASSWORD), null,
                clientCert, clientKey, null, null, protocols);
    }

    private HttpResponse<String> ping(HttpClient client) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(wm.baseUrl() + "/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUnencryptedPkcs8ClientKeyCompletesARealMtlsHandshake() throws Exception {
        HttpClient client = HttpClients.buildClient(
                tlsConfig(fixtures.clientCertPem(), fixtures.clientKeyPkcs8Unencrypted(), List.of()), null);

        HttpResponse<String> response = ping(client);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("pong");
    }

    @Test
    void aRequestWithNoClientCertIsRejectedByAClientAuthRequiringServer() {
        HttpClient client = HttpClients.buildClient(tlsConfig(null, null, List.of()), null);

        assertThatThrownBy(() -> ping(client)).isInstanceOf(IOException.class);
    }

    @Test
    void tls12PinningIsActuallyNegotiated() throws Exception {
        HttpClient client = HttpClients.buildClient(
                tlsConfig(fixtures.clientCertPem(), fixtures.clientKeyPkcs8Unencrypted(), List.of("TLSv1.2")), null);

        HttpResponse<String> response = ping(client);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.sslSession()).isPresent();
        assertThat(response.sslSession().get().getProtocol()).isEqualTo("TLSv1.2");
    }

    @Test
    void withoutPinningTheNegotiatedProtocolIsNotForcedToTls12() throws Exception {
        // Establishes the baseline this whole feature exists to override: left to its own
        // defaults, the JDK does not necessarily land on TLSv1.2 against a server that (like
        // WireMock's Jetty listener here) also offers TLSv1.3.
        HttpClient client = HttpClients.buildClient(
                tlsConfig(fixtures.clientCertPem(), fixtures.clientKeyPkcs8Unencrypted(), List.of()), null);

        HttpResponse<String> response = ping(client);

        assertThat(response.sslSession()).isPresent();
        assertThat(response.sslSession().get().getProtocol()).isNotEqualTo("TLSv1.2");
    }

    @Test
    void aMultiCertificateClientPemPresentsTheWholeChainAndCompletesTheHandshake() throws Exception {
        // WireMock's client-trust store holds ONLY the root; the chain client's leaf is signed by
        // a separate intermediate, so this only succeeds if the whole bundle (leaf + intermediate)
        // is presented — live-handshake proof, not just "the parser counted two certs".
        HttpClient client = HttpClients.buildClient(
                tlsConfig(fixtures.chainClientCertAndIntermediate(), fixtures.chainClientKey(), List.of()), null);

        HttpResponse<String> response = ping(client);

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
