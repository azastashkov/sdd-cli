package sdd.core.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianTls;
import sdd.core.config.ConfigException;

import javax.net.ssl.SSLHandshakeException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class HttpClientsTest {
    @TempDir Path dir;

    private Path emptyKeystore(String filename, String type) throws Exception {
        Path path = dir.resolve(filename);
        KeyStore ks = KeyStore.getInstance(type);
        ks.load(null, "changeit".toCharArray());
        try (var out = Files.newOutputStream(path)) {
            ks.store(out, "changeit".toCharArray());
        }
        return path;
    }

    @Test
    void withNeitherTlsNorProxyReturnsAPlainHttpClientUsingTheJdkDefaultSslContext() {
        HttpClient client = HttpClients.build(null, null);
        assertThat(client).isNotNull();
        // Same instance HttpClient.newHttpClient() itself would use (SSLContext.getDefault() is a
        // JVM-wide cached singleton) — a custom truststore-backed context would NOT be this same
        // instance, so this actually distinguishes "plain" from "custom" rather than merely
        // asserting non-null, which a custom context would satisfy too.
        assertThat(client.sslContext()).isSameAs(HttpClient.newHttpClient().sslContext());
    }

    @Test
    void loadsJksTruststoreByExtension() throws Exception {
        Path jks = emptyKeystore("corp-ca.jks", "JKS");
        HttpClient client = HttpClients.build(new AtlassianTls(jks, "changeit", null), null);
        assertThat(client).isNotNull();
        assertThat(client.sslContext()).isNotNull();
    }

    @Test
    void loadsPkcs12TruststoreByExtension() throws Exception {
        Path p12 = emptyKeystore("corp-ca.p12", "PKCS12");
        HttpClient client = HttpClients.build(new AtlassianTls(p12, "changeit", null), null);
        assertThat(client).isNotNull();
    }

    @Test
    void unrecognisedExtensionDefaultsToPkcs12() throws Exception {
        // No extension at all: PKCS12 is the JDK default keystore type, and the brief is explicit
        // that an unrecognised extension should fall back to it rather than error out.
        Path noExt = emptyKeystore("corp-ca.trust", "PKCS12");
        HttpClient client = HttpClients.build(new AtlassianTls(noExt, "changeit", null), null);
        assertThat(client).isNotNull();
    }

    @Test
    void missingTruststorePathIsAnErrorNeverAQuietFallback() {
        Path missing = dir.resolve("does-not-exist.jks");
        assertThatThrownBy(() -> HttpClients.build(new AtlassianTls(missing, "changeit", null), null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void aDeferredTruststorePasswordErrorSurfacesAtThePointHttpClientsActuallyOpensTheFile() throws Exception {
        Path jks = emptyKeystore("corp-ca.jks", "JKS");
        AtlassianTls tls = new AtlassianTls(jks, null,
                "atlassian.tls.truststore_password: environment variable CORP_TRUSTSTORE_PASSWORD is not set");

        assertThatThrownBy(() -> HttpClients.build(tls, null))
                .isInstanceOf(ConfigException.class)
                .hasMessage("atlassian.tls.truststore_password: environment variable "
                        + "CORP_TRUSTSTORE_PASSWORD is not set");
    }

    @Test
    void proxySelectorReturnsTheProxyByDefault() {
        AtlassianProxy proxy = new AtlassianProxy("proxy.corp.local", 8080, List.of("corp.local"));
        ProxySelector selector = HttpClients.proxySelector(proxy);

        List<Proxy> chosen = selector.select(URI.create("https://some-external-host.example.com/x"));

        assertThat(chosen).hasSize(1);
        assertThat(chosen.get(0).address()).isEqualTo(InetSocketAddress.createUnresolved("proxy.corp.local", 8080));
    }

    @Test
    void proxySelectorBypassesAnExactNoProxyHost() {
        AtlassianProxy proxy = new AtlassianProxy("proxy.corp.local", 8080, List.of("jira.corp.local"));
        ProxySelector selector = HttpClients.proxySelector(proxy);

        assertThat(selector.select(URI.create("https://jira.corp.local/rest/api/2/myself")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void proxySelectorBypassesADottedSuffixMatch() {
        AtlassianProxy proxy = new AtlassianProxy("proxy.corp.local", 8080, List.of("corp.local"));
        ProxySelector selector = HttpClients.proxySelector(proxy);

        assertThat(selector.select(URI.create("https://jira.corp.local/x"))).containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void proxySelectorNoProxyMatchIsCaseInsensitive() {
        AtlassianProxy proxy = new AtlassianProxy("proxy.corp.local", 8080, List.of("CORP.LOCAL"));
        ProxySelector selector = HttpClients.proxySelector(proxy);

        assertThat(selector.select(URI.create("https://JIRA.corp.local/x"))).containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void proxySelectorDoesNotBypassAnUnrelatedHostThatMerelyContainsTheSuffix() {
        // "notcorp.local" ends with "corp.local" as a raw substring but not as a dotted suffix —
        // must NOT be treated as a match, or a no_proxy entry could accidentally cover more hosts
        // than intended.
        AtlassianProxy proxy = new AtlassianProxy("proxy.corp.local", 8080, List.of("corp.local"));
        ProxySelector selector = HttpClients.proxySelector(proxy);

        List<Proxy> chosen = selector.select(URI.create("https://notcorp.local/x"));
        assertThat(chosen).doesNotContain(Proxy.NO_PROXY);
    }

    @Test
    void tlsFailureMessageNamesHostAndTruststore() {
        String msg = HttpClients.tlsFailureMessage("jira.corp.local", Path.of("/etc/ssl/corp-ca.jks"),
                new SSLHandshakeException("PKIX path building failed"));

        assertThat(msg).isEqualTo(
                "TLS handshake with jira.corp.local failed using truststore /etc/ssl/corp-ca.jks: "
                        + "PKIX path building failed");
    }

    @Test
    void tlsFailureMessageNamesJdkDefaultWhenNoTruststoreConfigured() {
        String msg = HttpClients.tlsFailureMessage("jira.corp.local", null,
                new SSLHandshakeException("PKIX path building failed"));

        assertThat(msg).isEqualTo(
                "TLS handshake with jira.corp.local failed using (JDK default truststore): "
                        + "PKIX path building failed");
    }
}
