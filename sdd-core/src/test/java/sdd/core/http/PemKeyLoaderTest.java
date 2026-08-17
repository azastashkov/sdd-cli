package sdd.core.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.ConfigException;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-level coverage of {@link PemKeyLoader}'s format detection: one test per row of the plan's
 * PEM-header table, each pinning the exact conversion hint a real operator will read. The
 * strongest proof that the unencrypted-PKCS#8 path actually works end to end (not merely parses)
 * lives in {@code HttpClientsMtlsHandshakeTest} against a real client-auth-requiring server —
 * this class is the fast, hermetic complement covering the error paths that test can't reach.
 */
class PemKeyLoaderTest {
    @TempDir Path dir;
    private CertFixtures fixtures;

    @BeforeEach
    void generateFixtures() throws Exception {
        fixtures = new CertFixtures(dir);
        fixtures.generate();
    }

    @Test
    void unencryptedPkcs8KeyLoads() {
        PrivateKey key = PemKeyLoader.privateKey(fixtures.clientKeyPkcs8Unencrypted(), null);
        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void encryptedPkcs8KeyLoadsWithTheCorrectPassword() {
        PrivateKey key = PemKeyLoader.privateKey(fixtures.clientKeyPkcs8Encrypted(),
                CertFixtures.CLIENT_KEY_PASSWORD.toCharArray());
        assertThat(key).isNotNull();
        assertThat(key.getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void encryptedPkcs8KeyFailsUsefullyWithTheWrongPassword() {
        assertThatThrownBy(() -> PemKeyLoader.privateKey(fixtures.clientKeyPkcs8Encrypted(), "wrong-password".toCharArray()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(fixtures.clientKeyPkcs8Encrypted().toString())
                .hasMessageNotContaining(CertFixtures.CLIENT_KEY_PASSWORD)
                .hasMessageNotContaining("wrong-password");
    }

    @Test
    void encryptedPkcs8KeyFailsUsefullyWithNoPasswordAtAll() {
        assertThatThrownBy(() -> PemKeyLoader.privateKey(fixtures.clientKeyPkcs8Encrypted(), null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("ENCRYPTED PRIVATE KEY")
                .hasMessageContaining("key_password");
    }

    @Test
    void pkcs1RsaKeyNamesTheHeaderFoundAndTheConversionCommand() {
        Path keyPath = fixtures.clientKeyPkcs1();
        assertThatThrownBy(() -> PemKeyLoader.privateKey(keyPath, null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("RSA PRIVATE KEY")
                .hasMessageContaining("PRIVATE KEY")
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt -in " + keyPath + " -out " + keyPath + ".pk8.pem");
    }

    @Test
    void sec1EcKeyNamesTheHeaderFoundAndTheConversionCommand() {
        Path keyPath = fixtures.clientKeyEc();
        assertThatThrownBy(() -> PemKeyLoader.privateKey(keyPath, null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("EC PRIVATE KEY")
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt -in " + keyPath + " -out " + keyPath + ".pk8.pem");
    }

    @Test
    void legacyDekInfoEncryptedKeyNamesTheLegacyHeaderAndTheConversionCommand() {
        Path keyPath = fixtures.clientKeyLegacyEncrypted();
        assertThatThrownBy(() -> PemKeyLoader.privateKey(keyPath, CertFixtures.LEGACY_KEY_PASSWORD.toCharArray()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("DEK-Info")
                .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt -in " + keyPath + " -out " + keyPath + ".pk8.pem");
    }

    @Test
    void aMultiCertificatePemPresentsTheWholeChainInFileOrder() {
        List<X509Certificate> chain = PemKeyLoader.certificateChain(fixtures.chainClientCertAndIntermediate());
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getSubjectX500Principal().getName()).contains("sdd-test-chain-client");
        assertThat(chain.get(1).getSubjectX500Principal().getName()).contains("sdd-test Intermediate CA");
    }

    @Test
    void aSingleCertificatePemLoadsJustTheOneCert() {
        List<X509Certificate> chain = PemKeyLoader.certificateChain(fixtures.clientCertPem());
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).getSubjectX500Principal().getName()).contains("sdd-test-client");
    }
}
