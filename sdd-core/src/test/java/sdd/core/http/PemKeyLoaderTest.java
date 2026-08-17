package sdd.core.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.ConfigException;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
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

    // Re-review Fix 2: a truncated copy, a mangled paste or a corrupted transfer is the failure
    // mode most likely to actually happen to a real key file — far more likely than an operator
    // hand-typing an unsupported PEM header — and it must come back naming the file, not as a bare
    // IllegalArgumentException escaping from Base64.getDecoder().decode(...) with a stack trace
    // and no file name attached, on a network where nobody can attach a debugger.

    @Test
    void aCorruptedUnencryptedPkcs8KeyIsAConfigExceptionNamingTheFileNotARawIllegalArgumentException() throws Exception {
        Path key = writeRawPem("corrupted_pkcs8.key", "PRIVATE KEY", "not-valid-base64-@@@@");

        assertThatThrownBy(() -> PemKeyLoader.privateKey(key, null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(key.toString());
    }

    @Test
    void aCorruptedEncryptedPkcs8KeyIsAConfigExceptionNamingTheFileNotARawIllegalArgumentException() throws Exception {
        Path key = writeRawPem("corrupted_pkcs8_enc.key", "ENCRYPTED PRIVATE KEY", "not-valid-base64-@@@@");

        assertThatThrownBy(() -> PemKeyLoader.privateKey(key, "irrelevant-password".toCharArray()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(key.toString());
    }

    // Re-review Fix 3: keyFromSpec's final "not a recognised RSA or EC PKCS#8 key" message
    // interpolates InvalidKeySpecException#getMessage() — almost certainly safe (it's the JDK's
    // own structural ASN.1 error text), but encryptedPkcs8KeyFailsUsefullyWithTheWrongPassword
    // above already PROVES the wrong-password path never echoes the password, rather than assuming
    // it. These two do the same for this path: a real DSA key (an algorithm PemKeyLoader
    // deliberately never tries, per KEY_ALGORITHMS) is valid, parseable PKCS#8 DER that reaches
    // exactly this final branch — unencrypted, and (the case that matters most here) successfully
    // decrypted with the CORRECT password, so the password variable is genuinely in scope right up
    // until this message is built.

    private byte[] dsaPkcs8() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("DSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair().getPrivate().getEncoded();
    }

    private Path writePem(String filename, String header, byte[] der) throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return writeRawPem(filename, header, base64);
    }

    private Path writeRawPem(String filename, String header, String body) throws Exception {
        Path path = dir.resolve(filename);
        Files.writeString(path, "-----BEGIN " + header + "-----\n" + body + "\n-----END " + header + "-----\n");
        return path;
    }

    @Test
    void anUnrecognisedAlgorithmPkcs8KeyNamesTheFileNotTheKeyMaterial() throws Exception {
        Path key = writePem("dsa_pkcs8.key", "PRIVATE KEY", dsaPkcs8());

        assertThatThrownBy(() -> PemKeyLoader.privateKey(key, null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(key.toString())
                .hasMessageContaining("not a recognised RSA or EC");
    }

    @Test
    void anUnrecognisedAlgorithmEncryptedPkcs8KeyNeverEchoesThePasswordEvenAfterASuccessfulDecryption() throws Exception {
        String password = "dsa-guard-pass-1234";
        // Builds a real, valid ENCRYPTED PRIVATE KEY PEM (PBES2, the same scheme openssl produces)
        // wrapping a real DSA key — plain javax.crypto APIs only, no hand-rolled ASN.1 (Ruling M3
        // applies here too): SecretKeyFactory/Cipher do the real PBES2 encryption, and
        // EncryptedPrivateKeyInfo(AlgorithmParameters, byte[]) builds the correct DER around it.
        String algName = "PBEWithHmacSHA256AndAES_256";
        SecretKeyFactory skf = SecretKeyFactory.getInstance(algName);
        SecretKey pbeKey = skf.generateSecret(new PBEKeySpec(password.toCharArray()));
        Cipher cipher = Cipher.getInstance(algName);
        cipher.init(Cipher.ENCRYPT_MODE, pbeKey);
        AlgorithmParameters params = AlgorithmParameters.getInstance("1.2.840.113549.1.5.13"); // PBES2 OID
        params.init(cipher.getParameters().getEncoded());
        byte[] encrypted = cipher.doFinal(dsaPkcs8());
        byte[] der = new EncryptedPrivateKeyInfo(params, encrypted).getEncoded();
        Path key = writePem("dsa_pkcs8_enc.key", "ENCRYPTED PRIVATE KEY", der);

        assertThatThrownBy(() -> PemKeyLoader.privateKey(key, password.toCharArray()))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining(key.toString())
                .hasMessageContaining("not a recognised RSA or EC")
                .hasMessageNotContaining(password);
    }
}
