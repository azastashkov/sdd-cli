package sdd.core.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianTls;
import sdd.core.config.ConfigException;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Covers {@link HttpClients}' generalised, neutral-typed surface ({@link TlsConfig}/
 * {@link ProxyConfig}) added on top of the existing {@code AtlassianTls}/{@code AtlassianProxy}
 * one, which {@link HttpClientsTest} already pins and which this class never touches (Ruling
 * M1). The live client-certificate-authenticated handshake — the strongest evidence the
 * unencrypted-PKCS#8 path actually works — is in {@code HttpClientsMtlsHandshakeTest}; this
 * class covers the surrounding config-shaped edges a live server can't exercise.
 */
class HttpClientsTlsConfigTest {
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
    void buildClientWithNeitherTlsNorProxyIsThePlainJdkDefaultClient() {
        HttpClient client = HttpClients.buildClient(null, null);
        assertThat(client.sslContext()).isSameAs(HttpClient.newHttpClient().sslContext());
    }

    @Test
    void buildClientLoadsATlsConfigTruststoreJustLikeTheAtlassianOverloadDoes() throws Exception {
        Path p12 = emptyKeystore("corp-ca.p12", "PKCS12");
        TlsConfig tls = new TlsConfig(p12, "changeit", null, null, null, null, null, List.of());
        HttpClient client = HttpClients.buildClient(tls, null);
        assertThat(client.sslContext()).isNotNull();
    }

    @Test
    void trustManagersWithNoTruststoreConfiguredReturnsNullMeaningJdkDefaultTrust() {
        TlsConfig tls = new TlsConfig(null, null, null, null, null, null, null, List.of());
        assertThat(HttpClients.trustManagers(tls)).isNull();
    }

    @Test
    void keyManagersWithNeitherCertNorKeyConfiguredReturnsNull() {
        TlsConfig tls = new TlsConfig(null, null, null, null, null, null, null, List.of());
        assertThat(HttpClients.keyManagers(tls)).isNull();
    }

    @Test
    void keyManagersWithCertButNoKeyIsAConfigError() {
        TlsConfig tls = new TlsConfig(null, null, null, dir.resolve("client.crt"), null, null, null, List.of());
        assertThatThrownBy(() -> HttpClients.keyManagers(tls)).isInstanceOf(ConfigException.class);
    }

    @Test
    void keyManagersWithKeyButNoCertIsAConfigError() {
        TlsConfig tls = new TlsConfig(null, null, null, null, dir.resolve("client.key"), null, null, List.of());
        assertThatThrownBy(() -> HttpClients.keyManagers(tls)).isInstanceOf(ConfigException.class);
    }

    @Test
    void aDeferredKeyPasswordErrorSurfacesAtThePointKeyManagersActuallyLoadsTheKey() throws Exception {
        // Mirrors HttpClientsTest's aDeferredTruststorePasswordErrorSurfacesAtThePointHttpClientsActuallyOpensTheFile
        // — same idiom, now proven for tls.key_password too.
        CertFixtures fixtures = new CertFixtures(dir);
        fixtures.generate();
        TlsConfig tls = new TlsConfig(null, null, null, fixtures.clientCertPem(), fixtures.clientKeyPkcs8Encrypted(),
                null, "models.corp.tls.key_password: environment variable MODEL_KEY_PASSWORD is not set",
                List.of());

        assertThatThrownBy(() -> HttpClients.keyManagers(tls))
                .isInstanceOf(ConfigException.class)
                .hasMessage("models.corp.tls.key_password: environment variable MODEL_KEY_PASSWORD is not set");
    }

    @Test
    void keyManagersLoadsARealClientCertificateAndKeyIntoAKeyManager() throws Exception {
        CertFixtures fixtures = new CertFixtures(dir);
        fixtures.generate();
        TlsConfig tls = new TlsConfig(null, null, null, fixtures.clientCertPem(),
                fixtures.clientKeyPkcs8Unencrypted(), null, null, List.of());

        assertThat(HttpClients.keyManagers(tls)).isNotEmpty();
    }

    @Test
    void keyFilePermissionWarningFlagsAWorldReadableKey() throws Exception {
        Path key = dir.resolve("world-readable.key");
        Files.writeString(key, "not a real key");
        Files.setPosixFilePermissions(key, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OTHERS_READ));

        assertThat(HttpClients.keyFilePermissionWarning(key))
                .startsWith("  warn: ")
                .contains(key.toString());
    }

    @Test
    void keyFilePermissionWarningIsSilentForAnOwnerOnlyKey() throws Exception {
        Path key = dir.resolve("owner-only.key");
        Files.writeString(key, "not a real key");
        Files.setPosixFilePermissions(key, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        assertThat(HttpClients.keyFilePermissionWarning(key)).isNull();
    }

    // Re-review Fix 1: the neutral TlsConfig loader's error text ("tls.truststore ...") must not
    // leak, unqualified, into what an Atlassian operator sees — DoctorCommand prints
    // HttpClients.build(AtlassianTls, AtlassianProxy)'s exception message verbatim, and
    // "tls.truststore" alone names no key that actually exists in sdd.yml. These pin the exact
    // prefix each of the three Atlassian-facing entry points restores, and the generic one the
    // neutral TlsConfig-typed entry points keep instead — a distinction HttpClientsTest (pinned,
    // unmodified) never had to assert because the prefix never varied before this generalisation.

    @Test
    void buildWithAMissingAtlassianTruststoreNamesTheAtlassianConfigKey() {
        Path missing = dir.resolve("does-not-exist.jks");
        AtlassianTls tls = new AtlassianTls(missing, "changeit", null);

        assertThatThrownBy(() -> HttpClients.build(tls, null))
                .isInstanceOf(ConfigException.class)
                .hasMessage("atlassian.tls.truststore " + missing + " does not exist");
    }

    @Test
    void buildWithAnAtlassianTruststoreAndProxyBothConfiguredStillNamesTheAtlassianConfigKey() {
        // Same as above, through the overload that also wires a proxy — the try/catch that
        // restores the prefix wraps the whole buildClient call, not just the TLS half.
        Path missing = dir.resolve("does-not-exist.jks");
        AtlassianTls tls = new AtlassianTls(missing, "changeit", null);
        AtlassianProxy proxy = new AtlassianProxy("proxy.corp.local", 8080, List.of());

        assertThatThrownBy(() -> HttpClients.build(tls, proxy))
                .isInstanceOf(ConfigException.class)
                .hasMessage("atlassian.tls.truststore " + missing + " does not exist");
    }

    @Test
    void trustManagersWithAMissingAtlassianTruststoreNamesTheAtlassianConfigKey() {
        Path missing = dir.resolve("does-not-exist.jks");
        AtlassianTls tls = new AtlassianTls(missing, "changeit", null);

        assertThatThrownBy(() -> HttpClients.trustManagers(tls))
                .isInstanceOf(ConfigException.class)
                .hasMessage("atlassian.tls.truststore " + missing + " does not exist");
    }

    @Test
    void buildClientWithAMissingTlsConfigTruststoreStaysGenericAndNeverClaimsAnAtlassianConfigKey() {
        Path missing = dir.resolve("does-not-exist.jks");
        TlsConfig tls = new TlsConfig(missing, "changeit", null, null, null, null, null, List.of());

        assertThatThrownBy(() -> HttpClients.buildClient(tls, null))
                .isInstanceOf(ConfigException.class)
                .hasMessage("tls.truststore " + missing + " does not exist");
    }

    @Test
    void trustManagersWithAMissingTlsConfigTruststoreStaysGenericAndNeverClaimsAnAtlassianConfigKey() {
        Path missing = dir.resolve("does-not-exist.jks");
        TlsConfig tls = new TlsConfig(missing, "changeit", null, null, null, null, null, List.of());

        assertThatThrownBy(() -> HttpClients.trustManagers(tls))
                .isInstanceOf(ConfigException.class)
                .hasMessage("tls.truststore " + missing + " does not exist");
    }

    @Test
    void trustManagersWithACorruptAtlassianTruststoreFileNamesTheAtlassianConfigKey() throws Exception {
        // The other error branch trustManagers(TlsConfig) can throw — a file that exists but fails
        // to load ("cannot load tls.truststore ...") — must also come back re-labelled.
        Path corrupt = dir.resolve("corrupt.jks");
        Files.writeString(corrupt, "not a real keystore");
        AtlassianTls tls = new AtlassianTls(corrupt, "changeit", null);

        assertThatThrownBy(() -> HttpClients.trustManagers(tls))
                .isInstanceOf(ConfigException.class)
                .hasMessageStartingWith("cannot load atlassian.tls.truststore " + corrupt);
    }

    @Test
    void aDeferredAtlassianTruststorePasswordErrorIsNeverDoublePrefixed() {
        // The deferred passwordError text ConfigLoader would have produced already says
        // "atlassian.tls.truststore_password" — relabelForAtlassian must pass it through
        // unchanged, not turn it into "atlassian.atlassian.tls...".
        Path jks = dir.resolve("corp-ca.jks");
        AtlassianTls tls = new AtlassianTls(jks, null,
                "atlassian.tls.truststore_password: environment variable CORP_TRUSTSTORE_PASSWORD is not set");

        assertThatThrownBy(() -> HttpClients.build(tls, null))
                .isInstanceOf(ConfigException.class)
                .hasMessage("atlassian.tls.truststore_password: environment variable "
                        + "CORP_TRUSTSTORE_PASSWORD is not set");
    }
}
