package sdd.core.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
}
