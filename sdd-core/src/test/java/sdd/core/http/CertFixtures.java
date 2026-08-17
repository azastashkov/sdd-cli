package sdd.core.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates a throwaway CA + server + client PKI into a {@code @TempDir} using {@code openssl}
 * and {@code keytool} (both available; no checked-in certificate that silently expires and
 * breaks the build years from now — the same approach {@code HttpClientsTest} already
 * established for the Atlassian truststore tests, extended here to a full mTLS handshake).
 *
 * <p>One root CA signs both the server leaf cert (for WireMock's HTTPS listener) and the client
 * leaf cert (the thing under test) directly; a second, separate intermediate hierarchy exists
 * purely for {@link #chainClientKey}/{@link #chainClientCertAndIntermediate} — WireMock's
 * client-trust store is loaded with the ROOT only, so a client that sends the leaf without the
 * intermediate cannot build a trusted path, which is exactly what proves "every certificate in
 * the file, in order" rather than merely the first one.
 *
 * <p>Not a {@code @Test} class itself — a plain helper other test classes call from a
 * {@code @TempDir}. Public (Phase 2, model-mTLS): {@code sdd.core.llm}'s tests need the same
 * generated PKI to prove a {@code ModelEndpoint.tls()}-configured {@code HttpChatModel}/
 * {@code EndpointProbe} completes a real handshake, and duplicating this generator into a second
 * package is exactly the "two places to get certificate handling wrong" this whole feature's
 * design otherwise avoids — see {@code HttpClients}' class javadoc.
 */
public final class CertFixtures {
    private final Path dir;

    public CertFixtures(Path dir) {
        this.dir = dir;
    }

    public Path caCert() { return dir.resolve("ca.crt"); }
    public Path caTrustStoreP12() { return dir.resolve("ca-truststore.p12"); }
    public static final char[] TRUSTSTORE_PASSWORD = "changeit".toCharArray();

    public Path serverKeystoreP12() { return dir.resolve("server.p12"); }
    public static final String SERVER_KEYSTORE_PASSWORD = "changeit";

    public Path clientCertPem() { return dir.resolve("client.crt"); }
    public Path clientKeyPkcs8Unencrypted() { return dir.resolve("client_pkcs8.key"); }
    public Path clientKeyPkcs8Encrypted() { return dir.resolve("client_pkcs8_enc.key"); }
    public static final String CLIENT_KEY_PASSWORD = "test-key-pass-1234";
    public Path clientKeyPkcs1() { return dir.resolve("client_pkcs1.key"); }
    public Path clientKeyEc() { return dir.resolve("client_ec.key"); }
    public Path clientKeyLegacyEncrypted() { return dir.resolve("client_pkcs1_legacy_enc.key"); }
    public static final String LEGACY_KEY_PASSWORD = "legacy-pass-1234";

    public Path chainClientKey() { return dir.resolve("chain_client.key"); }
    public Path chainClientCertAndIntermediate() { return dir.resolve("chain_client_bundle.pem"); }
    public Path chainClientCertLeafOnly() { return dir.resolve("chain_client_leaf.crt"); }

    /** Builds every fixture this class exposes. Idempotent within one {@code @TempDir}. */
    public void generate() throws IOException, InterruptedException {
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str("ca.key"), "-out", str("ca.crt"), "-days", "3", "-subj", "/CN=sdd-test Root CA");

        // Server leaf, signed directly by the root, SAN=localhost so hostname verification passes.
        run("openssl", "req", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str("server.key"), "-out", str("server.csr"), "-subj", "/CN=localhost");
        Files.writeString(dir.resolve("server.ext"), "subjectAltName=DNS:localhost,IP:127.0.0.1\n");
        run("openssl", "x509", "-req", "-in", str("server.csr"), "-CA", str("ca.crt"), "-CAkey", str("ca.key"),
                "-CAcreateserial", "-out", str("server.crt"), "-days", "3", "-extfile", str("server.ext"));
        run("openssl", "pkcs12", "-export", "-in", str("server.crt"), "-inkey", str("server.key"),
                "-out", str("server.p12"), "-passout", "pass:" + SERVER_KEYSTORE_PASSWORD, "-name", "server");

        // Client leaf, signed directly by the root — the key material under test in every format.
        run("openssl", "req", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str("client_pkcs8.key"), "-out", str("client.csr"), "-subj", "/CN=sdd-test-client");
        run("openssl", "x509", "-req", "-in", str("client.csr"), "-CA", str("ca.crt"), "-CAkey", str("ca.key"),
                "-CAcreateserial", "-out", str("client.crt"), "-days", "3");

        run("openssl", "pkcs8", "-topk8", "-in", str("client_pkcs8.key"), "-out", str("client_pkcs8_enc.key"),
                "-passout", "pass:" + CLIENT_KEY_PASSWORD);
        run("openssl", "rsa", "-in", str("client_pkcs8.key"), "-traditional", "-out", str("client_pkcs1.key"));
        run("openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str("client_ec.key"));
        run("openssl", "rsa", "-in", str("client_pkcs1.key"), "-des3", "-traditional",
                "-out", str("client_pkcs1_legacy_enc.key"), "-passout", "pass:" + LEGACY_KEY_PASSWORD);

        // A separate intermediate hierarchy for the multi-cert-chain fixture.
        run("openssl", "req", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str("inter.key"), "-out", str("inter.csr"), "-subj", "/CN=sdd-test Intermediate CA");
        Files.writeString(dir.resolve("inter.ext"), "basicConstraints=critical,CA:true\nkeyUsage=critical,keyCertSign,cRLSign\n");
        run("openssl", "x509", "-req", "-in", str("inter.csr"), "-CA", str("ca.crt"), "-CAkey", str("ca.key"),
                "-CAcreateserial", "-out", str("inter.crt"), "-days", "3", "-extfile", str("inter.ext"));
        run("openssl", "req", "-newkey", "rsa:2048", "-nodes",
                "-keyout", str("chain_client.key"), "-out", str("chain_client.csr"), "-subj", "/CN=sdd-test-chain-client");
        run("openssl", "x509", "-req", "-in", str("chain_client.csr"), "-CA", str("inter.crt"), "-CAkey", str("inter.key"),
                "-CAcreateserial", "-out", str("chain_client_leaf.crt"), "-days", "3");
        // leaf THEN intermediate, in that order, mirroring the order a real bundle is handed out in.
        String leaf = Files.readString(dir.resolve("chain_client_leaf.crt"));
        String intermediate = Files.readString(dir.resolve("inter.crt"));
        Files.writeString(dir.resolve("chain_client_bundle.pem"), leaf + intermediate);

        // Root-only truststore: trusts the server leaf (signed directly by root) and validates
        // the chain-client leaf only when the intermediate is presented alongside it.
        run("keytool", "-importcert", "-alias", "ca", "-file", str("ca.crt"),
                "-keystore", str("ca-truststore.p12"), "-storetype", "PKCS12",
                "-storepass", new String(TRUSTSTORE_PASSWORD), "-noprompt");
    }

    private String str(String name) { return dir.resolve(name).toString(); }

    private void run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("fixture command failed: " + List.of(command) + "\n" + output);
        }
    }
}
