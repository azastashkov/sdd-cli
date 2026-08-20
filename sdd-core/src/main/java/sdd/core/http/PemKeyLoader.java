package sdd.core.http;

import sdd.core.config.ConfigException;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code --cert}/{@code --key} PEM pair a model endpoint's {@code tls.cert}/
 * {@code tls.key} name, per the plan's PEM-header table. JDK-only, deliberately: a closed
 * corporate network cannot resolve BouncyCastle, so every format handled here goes through
 * {@link CertificateFactory}/{@link KeyFactory}/{@link EncryptedPrivateKeyInfo} alone.
 *
 * <p><b>No PKCS#1 DER wrap, no ASN.1 parser (Ruling M3).</b> The real corporate key is confirmed
 * unencrypted PKCS#8 ({@code head -1} on it returns {@code -----BEGIN PRIVATE KEY-----}), which
 * {@link PKCS8EncodedKeySpec} loads with zero transformation. Writing a PKCS#1-to-PKCS#8 DER
 * wrapper for a format nobody in this environment has would be speculative code shipped untested
 * against the one real gateway this integration talks to — see the plan's "Deliberately not
 * built" section. PKCS#1 ({@code BEGIN RSA PRIVATE KEY}), SEC1 ({@code BEGIN EC PRIVATE KEY}) and
 * the legacy {@code Proc-Type: 4,ENCRYPTED}/{@code DEK-Info} PEM encryption therefore all take the
 * actionable-error path below: name the header found, the header expected, and the exact
 * {@code openssl pkcs8 -topk8 -nocrypt} command that converts it — turning a confusing handshake
 * failure into a one-line fix, revisitable with a real key of that shape if one ever shows up.
 */
final class PemKeyLoader {
    private PemKeyLoader() {}

    private static final Pattern HEADER = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----");

    /** RSA is the corporate key's actual algorithm; EC is tried second only because a bare
     *  {@code PKCS8EncodedKeySpec} carries no algorithm tag of its own to switch on without
     *  parsing the ASN.1 {@code AlgorithmIdentifier} ourselves (the parser Ruling M3 rules out) —
     *  {@link KeyFactory#generatePrivate} is what actually inspects the DER, so trying the two
     *  JDK-standard algorithms in turn is a couple of lines, not a parser. SEC1 (EC) keys are out
     *  of scope for the standalone {@code BEGIN EC PRIVATE KEY} format (see the plan's
     *  "Deliberately not built"), but a PKCS#8-wrapped EC key is plain JDK either way, so trying
     *  it costs nothing and covers a possible future EC client cert for free. */
    private static final List<String> KEY_ALGORITHMS = List.of("RSA", "EC");

    /** Every certificate in {@code certPath}, in file order — not just the first. A corporate
     *  bundle commonly carries an intermediate alongside the leaf, and a gateway that requires the
     *  full chain fails opaquely (a bare handshake alert) when only the leaf is presented.
     *  {@link CertificateFactory#generateCertificates} already parses a whole concatenated PEM
     *  file in one call, so there is no bespoke multi-cert splitting to get wrong here. */
    static List<X509Certificate> certificateChain(Path certPath) {
        return certificates(certPath, "client certificate " + certPath);
    }

    /**
     * {@link #certificateChain} with the thing being loaded named by the caller.
     *
     * <p>A PEM file full of certificates is a client chain in one place and a CA trust bundle in
     * another ({@code HttpClients.trustManagers}), and the two must not report each other's name:
     * "cannot load client certificate /etc/ssl/corp-ca.pem" sends an operator to the wrong
     * {@code sdd.yml} key. The parsing is identical, so only the label is a parameter.
     */
    static List<X509Certificate> certificates(Path path, String what) {
        try (InputStream in = Files.newInputStream(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            var certs = cf.generateCertificates(in);
            if (certs.isEmpty()) {
                throw new ConfigException("no certificates found in " + path);
            }
            return certs.stream().map(X509Certificate.class::cast).toList();
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigException("cannot load " + what + ": " + e.getMessage(), e);
        }
    }

    /** Loads {@code keyPath} by its PEM header — see this class's javadoc for which headers are
     *  supported directly and which take the conversion-hint error path. {@code password} is only
     *  consulted for {@code BEGIN ENCRYPTED PRIVATE KEY}; null everywhere else. */
    static PrivateKey privateKey(Path keyPath, char[] password) {
        String pem;
        try {
            pem = Files.readString(keyPath, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new ConfigException("cannot read client key " + keyPath + ": " + e.getMessage(), e);
        }
        Matcher m = HEADER.matcher(pem);
        if (!m.find()) {
            throw new ConfigException("client key " + keyPath + " has no PEM header; expected "
                    + "\"-----BEGIN PRIVATE KEY-----\" (unencrypted PKCS#8)");
        }
        String header = m.group(1).trim();
        return switch (header) {
            case "PRIVATE KEY" -> keyFromSpec(keyPath, new PKCS8EncodedKeySpec(decodedBody(keyPath, pem)));
            case "ENCRYPTED PRIVATE KEY" -> decryptPkcs8(keyPath, pem, password);
            case "RSA PRIVATE KEY" -> throw conversionRequired(keyPath, pem, header, "PKCS#1 (RSA)");
            case "EC PRIVATE KEY" -> throw conversionRequired(keyPath, pem, header, "SEC1 (EC)");
            default -> throw new ConfigException("client key " + keyPath + " has unsupported PEM header \""
                    + header + "\"; expected \"PRIVATE KEY\" (unencrypted PKCS#8) or \"ENCRYPTED PRIVATE KEY\" "
                    + "(encrypted PKCS#8)");
        };
    }

    /** Names the header actually found (including the legacy {@code Proc-Type}/{@code DEK-Info}
     *  case, which wraps the same {@code BEGIN RSA PRIVATE KEY} header a plain PKCS#1 key uses but
     *  needs its own name in the message since the fix — decrypting first — differs), the header
     *  expected, and the exact conversion command. This is the single message every PKCS#1/SEC1/
     *  legacy-encrypted key produces (Ruling M3: no code path actually loads any of them). */
    private static ConfigException conversionRequired(Path keyPath, String pem, String header, String formatName) {
        boolean legacyEncrypted = pem.contains("Proc-Type: 4,ENCRYPTED") && pem.contains("DEK-Info:");
        String found = "\"" + header + "\" (" + formatName + ")"
                + (legacyEncrypted ? ", legacy PEM-encrypted (Proc-Type: 4,ENCRYPTED / DEK-Info header)" : "");
        return new ConfigException("client key " + keyPath + " has PEM header " + found
                + "; expected \"PRIVATE KEY\" (unencrypted PKCS#8). Convert it with: "
                + conversionCommand(keyPath));
    }

    private static String conversionCommand(Path keyPath) {
        return "openssl pkcs8 -topk8 -nocrypt -in " + keyPath + " -out " + keyPath + ".pk8.pem";
    }

    private static PrivateKey decryptPkcs8(Path keyPath, String pem, char[] password) {
        if (password == null) {
            throw new ConfigException("client key " + keyPath + " is encrypted (BEGIN ENCRYPTED PRIVATE KEY) "
                    + "but no tls.key_password is configured");
        }
        try {
            EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(decodedBody(keyPath, pem));
            Cipher cipher = Cipher.getInstance(info.getAlgName());
            SecretKeyFactory skf = SecretKeyFactory.getInstance(info.getAlgName());
            Key pbeKey = skf.generateSecret(new PBEKeySpec(password));
            cipher.init(Cipher.DECRYPT_MODE, pbeKey, info.getAlgParameters());
            return keyFromSpec(keyPath, info.getKeySpec(cipher));
        } catch (GeneralSecurityException | IOException e) {
            // Never the password, never the key material — only the file name and the JDK's own
            // exception text (which for a wrong password is a bare "bad padding"/"unpad" message,
            // not anything derived from the password itself).
            throw new ConfigException("cannot decrypt client key " + keyPath
                    + " (wrong tls.key_password, or an unsupported cipher): " + e.getMessage(), e);
        }
    }

    private static PrivateKey keyFromSpec(Path keyPath, PKCS8EncodedKeySpec spec) {
        InvalidKeySpecException last = null;
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException e) {
                last = e;
            } catch (GeneralSecurityException e) {
                throw new ConfigException("cannot load client key " + keyPath + ": " + e.getMessage(), e);
            }
        }
        throw new ConfigException("client key " + keyPath + " is not a recognised RSA or EC PKCS#8 key"
                + (last != null && last.getMessage() != null ? ": " + last.getMessage() : ""));
    }

    /** {@link #body}, wrapped so a truncated copy, a mangled paste or a corrupted transfer — the
     *  most likely real-world way a key file actually gets broken, far more likely than an
     *  operator hand-typing an unsupported PEM header — comes back as a {@link ConfigException}
     *  naming {@code keyPath}, not a bare {@code IllegalArgumentException} stack trace with no
     *  file name attached, on a closed network where nobody can attach a debugger to find out
     *  which file it even was. */
    private static byte[] decodedBody(Path keyPath, String pem) {
        try {
            return body(pem);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("cannot decode client key " + keyPath
                    + ": not valid base64 (" + e.getMessage() + ")", e);
        }
    }

    /** Strips the {@code -----BEGIN/END-----} delimiters and any header lines (a legacy
     *  encrypted key's {@code Proc-Type}/{@code DEK-Info} lines, recognised here only so this
     *  method never mis-decodes them as base64 — those keys are rejected before reaching this
     *  method regardless) and base64-decodes what remains. */
    private static byte[] body(String pem) {
        StringBuilder base64 = new StringBuilder();
        for (String line : pem.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-----") || trimmed.contains(":")) {
                continue;
            }
            base64.append(trimmed);
        }
        return Base64.getDecoder().decode(base64.toString());
    }
}
