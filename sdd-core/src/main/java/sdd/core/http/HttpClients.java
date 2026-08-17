package sdd.core.http;

import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianTls;
import sdd.core.config.ConfigException;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the {@link HttpClient} every Jira/Confluence/Bitbucket call goes through, wiring in the
 * corporate TLS truststore and forward proxy from {@code atlassian.tls}/{@code atlassian.proxy}
 * when configured. This is the whole reason Task 2 exists before any Jira/Confluence/Bitbucket
 * client does: {@code sdd} is destined for a closed network where the JDK's bundled
 * {@code cacerts} does not know the corporate CA and outbound traffic has to leave through a
 * proxy — a client built with {@code HttpClient.newHttpClient()} works on a laptop and fails
 * there with a bare {@code PKIX path building failed}.
 *
 * <p>Deliberately absent: any verification-disabling flag. There is no {@code --insecure} and no
 * all-trusting {@code TrustManager}. A closed network is exactly where such a switch gets turned
 * on "temporarily" during an incident and never turned back off; the fix for a handshake failure
 * is fixing the truststore, and {@link #tlsFailureMessage} exists so the failure says how.
 */
public final class HttpClients {
    private HttpClients() {}

    /** {@code HttpClient.newHttpClient()} when neither {@code tls} nor {@code proxy} is
     *  configured — the common case for every command that never touches Atlassian. Delegates to
     *  {@link #buildClient(TlsConfig, ProxyConfig)} via {@link #toTlsConfig}/{@link #toProxyConfig}
     *  so the Atlassian and model-endpoint transports share one implementation; kept as its own
     *  method, under its own name, rather than an overload of {@code buildClient} — {@code
     *  build(null, null)} is called with bare {@code null} literals by existing tests, and two
     *  same-arity overloads with unrelated parameter types make that call ambiguous at compile
     *  time (neither {@code AtlassianTls} nor {@code TlsConfig} is more specific than the other),
     *  which would break {@link HttpClientsTest} — a name that can never collide is worth more
     *  here than one more overload of {@code build}. Re-labels a {@code tls.truststore}-prefixed
     *  {@link ConfigException} back to {@code atlassian.tls.truststore} via
     *  {@link #relabelForAtlassian} — {@code DoctorCommand} prints this message verbatim, and the
     *  generic prefix {@link #trustManagers(TlsConfig)} produces names no config key that actually
     *  exists in {@code sdd.yml}. */
    public static HttpClient build(AtlassianTls tls, AtlassianProxy proxy) {
        try {
            return buildClient(toTlsConfig(tls), toProxyConfig(proxy));
        } catch (ConfigException e) {
            throw relabelForAtlassian(e);
        }
    }

    /** The neutral-typed counterpart to {@link #build(AtlassianTls, AtlassianProxy)} — what a
     *  model endpoint's {@code tls}/{@code proxy} config builds its {@link HttpClient} from. Same
     *  contract: {@code HttpClient.newHttpClient()} when both are null, otherwise an
     *  {@link SSLContext} (trust managers, and now key managers for client-certificate auth) and/or
     *  a {@link ProxySelector} wired onto the builder. {@code tls.protocols()} — the {@code
     *  --tlsv1.2 --tls-max 1.2} pin — is applied via {@link SSLParameters#setProtocols} directly on
     *  the {@link HttpClient.Builder}, not the {@link SSLContext}: that is the layer the JDK
     *  actually reads protocol restrictions from for an {@code HttpClient}. Left empty, the JDK's
     *  own default negotiation is untouched. */
    public static HttpClient buildClient(TlsConfig tls, ProxyConfig proxy) {
        if (tls == null && proxy == null) {
            return HttpClient.newHttpClient();
        }
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (tls != null) {
            builder.sslContext(sslContext(tls));
            if (!tls.protocols().isEmpty()) {
                SSLParameters params = new SSLParameters();
                params.setProtocols(tls.protocols().toArray(new String[0]));
                builder.sslParameters(params);
            }
        }
        if (proxy != null) {
            builder.proxy(proxySelector(proxy));
        }
        return builder.build();
    }

    /**
     * Loads {@code tls.truststore()} (trust) and {@code tls.clientCert()}/{@code tls.clientKey()}
     * (client-certificate auth, when configured) into one {@link SSLContext}. The keystore type
     * for the truststore is inferred from the file extension — {@code .jks} is JKS, everything
     * else (including no extension) is PKCS12, the JDK's own default keystore type, so an
     * unlabelled corporate export still loads without the operator having to know or care which
     * binary format it happens to be in.
     *
     * <p>A configured truststore path that does not exist is a {@link ConfigException}, never a
     * silent fallback to the JDK default truststore — see this class's javadoc. This is the same
     * rule {@code node_home} follows for the same reason: a typo here must not silently change
     * which trust anchors are in play.
     */
    private static SSLContext sslContext(TlsConfig tls) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers(tls), trustManagers(tls), null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new ConfigException("cannot initialize TLS context: " + e.getMessage(), e);
        }
    }

    /**
     * {@code tls.truststore()}'s trust anchors alone, without wrapping them in an
     * {@link SSLContext} — the one extra thing a plain {@link #build} caller never needs but
     * {@code sdd.cli.review.RemoteGit} does: JGit's {@code HttpConnectionFactory} configures a
     * connection via raw {@link TrustManager}s ({@code JDKHttpConnection.configure}), not an
     * {@code SSLContext}. Extracted out of {@link #sslContext} so the corporate truststore file is
     * read and parsed in exactly one place — Task 5's brief is explicit that the CA must be
     * "configured once, not twice" between the REST client and JGit's push transport.
     *
     * <p>Returns null — "use the JDK's own default trust anchors" — when {@code truststore()} is
     * null, unlike a missing FILE at a configured path, which is still an error below. This is the
     * one place model-endpoint {@link TlsConfig} and Atlassian {@code AtlassianTls} genuinely
     * differ: every real {@code AtlassianTls} instance always has a truststore path (only the
     * whole {@code AtlassianTls} reference is ever null), but a model endpoint's {@code tls.cert}/
     * {@code tls.key} legitimately come with no {@code tls.truststore} at all — "the JDK already
     * trusts this gateway's CA" per the plan's example config — so this branch exists for the
     * model path without changing a single byte of the Atlassian one, which never exercises it.
     *
     * <p>Error messages here use the generic {@code tls.truststore} config-key name — this method
     * is the one shared by both an Atlassian truststore and a model endpoint's, and it has no way
     * to know which dotted path a given {@link TlsConfig} actually came from. {@code sdd doctor}
     * prints an Atlassian truststore failure's message verbatim to an operator who needs to know
     * to edit {@code atlassian.tls.truststore} specifically, not a generic {@code tls.truststore}
     * that names no real config key in that file — so {@link #trustManagers(AtlassianTls)} restores
     * the exact {@code "atlassian.tls.truststore"} prefix these two error paths produced before
     * this generalisation, by re-labelling the {@link ConfigException} this method threw rather
     * than duplicating the load logic.
     */
    public static TrustManager[] trustManagers(TlsConfig tls) {
        // Deferred from ConfigLoader: an unset truststore_password ${VAR} does not fail config
        // loading (sdd index/status/clean never open a truststore), so it is raised here instead —
        // the earliest point something is actually about to open the truststore — with the exact
        // message ConfigLoader would have thrown eagerly before that was fixed.
        if (tls.truststorePasswordError() != null) {
            throw new ConfigException(tls.truststorePasswordError());
        }
        Path path = tls.truststore();
        if (path == null) {
            return null;
        }
        if (!Files.isRegularFile(path)) {
            throw new ConfigException("tls.truststore " + path + " does not exist");
        }
        char[] password = tls.truststorePassword() == null ? new char[0] : tls.truststorePassword().toCharArray();
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance(truststoreType(path));
            keyStore.load(in, password);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            return tmf.getTrustManagers();
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigException("cannot load tls.truststore " + path + ": " + e.getMessage(), e);
        }
    }

    /** {@link #trustManagers(TlsConfig)}, callable with the Atlassian shape directly — every
     *  existing Atlassian call site ({@code RemoteGit}, {@code DiagnosticHeader},
     *  {@code HttpClientsTest}) keeps compiling and behaving exactly as before, since a real
     *  {@code AtlassianTls} always converts to a {@link TlsConfig} with a non-null
     *  {@code truststore} (the one branch above this class's javadoc calls out never triggers
     *  here). Re-labels the {@code tls.truststore}-prefixed messages {@link #trustManagers(TlsConfig)}
     *  produces back to {@code atlassian.tls.truststore} — the exact prefix {@code DoctorCommand}
     *  has always printed verbatim to an operator, and the only config key that actually exists for
     *  them to edit — WITHOUT re-implementing the file-existence/load logic a second time; only the
     *  message text of the two errors that name a config key is re-labelled, never the deferred
     *  {@code truststorePasswordError} message (that one is {@code ConfigLoader}'s own text,
     *  already correctly prefixed at the source, and must be echoed byte-identically). */
    public static TrustManager[] trustManagers(AtlassianTls tls) {
        try {
            return trustManagers(toTlsConfig(tls));
        } catch (ConfigException e) {
            throw relabelForAtlassian(e);
        }
    }

    /** Re-labels the generic {@code tls.truststore ...} text {@link #trustManagers(TlsConfig)}
     *  produces (for a file that does not exist, or one that fails to load) back to
     *  {@code atlassian.tls.truststore ...} — the exact prefix both error paths produced before
     *  {@link TlsConfig} generalised them, and the only one naming a real {@code sdd.yml} config
     *  key an Atlassian operator can act on. The deferred {@code truststorePasswordError} message
     *  is never touched here: that text is {@code ConfigLoader}'s own, already correctly prefixed
     *  at the source, and both {@link #build(AtlassianTls, AtlassianProxy)} and
     *  {@link #trustManagers(AtlassianTls)} must still echo it byte-identically (it never contains
     *  the literal {@code "tls.truststore "} this method looks for, so it passes through
     *  untouched). */
    private static ConfigException relabelForAtlassian(ConfigException e) {
        String message = e.getMessage();
        if (message != null && message.contains("tls.truststore ")) {
            return new ConfigException(message.replace("tls.truststore ", "atlassian.tls.truststore "), e.getCause());
        }
        return e;
    }

    /**
     * The client-certificate {@link KeyManager}s for {@code tls.clientCert()}/
     * {@code tls.clientKey()} — null when neither is configured (the common case; Atlassian never
     * sets these, so {@link #trustManagers(AtlassianTls)}'s callers never exercise this branch
     * either). Exactly one of the pair set is a config error: a cert without a key (or the
     * reverse) can never produce a working {@link SSLContext}, and failing fast here with a plain
     * message is better than an opaque {@code NullPointerException} three calls deeper. The dotted
     * config path in that message belongs to {@code ConfigLoader} (Phase 2), which alone knows
     * whether this came from {@code models.corp.tls} or another endpoint entirely; this class
     * only knows the file paths themselves.
     *
     * <p>Certificate and key parsing is {@link PemKeyLoader}'s job — see its class javadoc for the
     * exact PEM-header handling and Ruling M3 (no PKCS#1 DER wrap). The parsed chain and key are
     * assembled into a throwaway in-memory {@link KeyStore} purely as the vehicle
     * {@link KeyManagerFactory} requires; nothing about that keystore is ever written to disk or
     * reused, so its password is a fixed empty array, not a secret worth threading through.
     */
    public static KeyManager[] keyManagers(TlsConfig tls) {
        if (tls.clientCert() == null && tls.clientKey() == null) {
            return null;
        }
        if (tls.clientCert() == null || tls.clientKey() == null) {
            throw new ConfigException("tls.cert and tls.key must both be configured, or neither");
        }
        // Deferred from ConfigLoader: an unset key_password ${VAR} does not fail config loading
        // for a command that never opens the key, so it is raised here instead — the earliest
        // point the key is actually about to be read — with the exact message ConfigLoader would
        // have thrown eagerly before that was fixed. Mirrors truststorePasswordError above.
        if (tls.keyPasswordError() != null) {
            throw new ConfigException(tls.keyPasswordError());
        }
        Path certPath = tls.clientCert();
        Path keyPath = tls.clientKey();
        if (!Files.isRegularFile(certPath)) {
            throw new ConfigException("tls.cert " + certPath + " does not exist");
        }
        if (!Files.isRegularFile(keyPath)) {
            throw new ConfigException("tls.key " + keyPath + " does not exist");
        }
        char[] keyPassword = tls.keyPassword() == null ? null : tls.keyPassword().toCharArray();
        List<X509Certificate> chain = PemKeyLoader.certificateChain(certPath);
        PrivateKey privateKey = PemKeyLoader.privateKey(keyPath, keyPassword);
        try {
            char[] empty = new char[0];
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, empty);
            keyStore.setKeyEntry("client", privateKey, empty, chain.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, empty);
            return kmf.getKeyManagers();
        } catch (GeneralSecurityException | IOException e) {
            throw new ConfigException("cannot build client key store from " + certPath + " / " + keyPath
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@code "  warn: "}-prefixed (the two-space prefix every {@code sdd} warning uses) when
     * {@code keyPath} is group- or world-readable, null otherwise. A PEM private key is a
     * plaintext secret on disk, and this is the environment where that matters — but
     * {@code sdd-core} has no writer (see this package's other classes: errors are exceptions,
     * warnings are returned data), so this returns the message rather than printing it; the
     * caller — {@code sdd doctor} or wherever a model endpoint's key is first loaded — prints it.
     * Never throws: a non-POSIX filesystem (Windows) has nothing to check, so this is silently
     * null there rather than a spurious failure on a platform this warning doesn't apply to.
     */
    public static String keyFilePermissionWarning(Path keyPath) {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(keyPath);
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
        boolean loose = permissions.contains(PosixFilePermission.GROUP_READ)
                || permissions.contains(PosixFilePermission.OTHERS_READ);
        return loose ? "  warn: client key " + keyPath + " is group- or world-readable" : null;
    }

    private static TlsConfig toTlsConfig(AtlassianTls tls) {
        if (tls == null) {
            return null;
        }
        return new TlsConfig(tls.truststore(), tls.password(), tls.passwordError(),
                null, null, null, null, List.of());
    }

    private static ProxyConfig toProxyConfig(AtlassianProxy proxy) {
        return proxy == null ? null : new ProxyConfig(proxy.host(), proxy.port(), proxy.noProxy());
    }

    private static String truststoreType(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jks") ? "JKS" : "PKCS12";
    }

    /**
     * A {@link ProxySelector} that routes every host through {@code proxy} except those matching
     * {@code proxy.noProxy()} — the Atlassian hosts themselves are usually reachable directly even
     * when the proxy is required for everything else, and forcing them through it anyway is a
     * common way for a self-hosted-Jira setup to mysteriously stop working.
     *
     * <p>A {@code no_proxy} entry matches a host that equals it, or ends with {@code "." + entry}
     * (so {@code corp.local} covers {@code jira.corp.local} without also covering
     * {@code notcorp.local}, which merely contains the same characters). Comparison is
     * case-insensitive, since DNS names are.
     */
    public static ProxySelector proxySelector(AtlassianProxy proxy) {
        return proxySelector(toProxyConfig(proxy));
    }

    /** {@link #proxySelector(AtlassianProxy)}'s neutral-typed counterpart, for a model endpoint's
     *  {@code proxy} config — same match rule, same implementation, reached by every existing
     *  Atlassian call site through the overload above rather than duplicated. */
    public static ProxySelector proxySelector(ProxyConfig proxy) {
        Proxy target = new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(proxy.host(), proxy.port()));
        List<String> noProxy = proxy.noProxy().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                String host = uri.getHost();
                if (host != null && bypasses(host.toLowerCase(Locale.ROOT), noProxy)) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(target);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                // Nothing to record; HttpClient/RestClient surface the failure themselves.
            }
        };
    }

    private static boolean bypasses(String host, List<String> noProxy) {
        for (String entry : noProxy) {
            if (host.equals(entry) || host.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The diagnostic message every SSL handshake failure against a configured Atlassian site must
     * produce: which host, and which truststore was actually in play — {@code "(JDK default
     * truststore)"} when {@code truststore} is null. A bare {@code PKIX path building failed} is
     * exactly the failure mode this exists to prevent, because {@code sdd doctor} is the first
     * thing anyone runs on a closed network and it has to say what to fix, not just that
     * something is broken.
     */
    public static String tlsFailureMessage(String host, Path truststore, Throwable cause) {
        String using = truststore != null ? "truststore " + truststore : "(JDK default truststore)";
        String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return "TLS handshake with " + host + " failed using " + using + ": " + detail;
    }

    /**
     * The effective proxy {@code host} would actually go through given {@code proxy} — Fix 5 (Task
     * 8 review): a private CA and a corporate proxy are the two most likely failure causes on the
     * closed network this runs in, and a reader debugging remotely, with no ability to re-run,
     * should not have to hand-correlate a raw connect-timeout message against the header block's
     * proxy section themselves. Reuses {@link #bypasses}, the exact match rule {@link
     * #proxySelector} itself applies, rather than re-deriving it — so this can never disagree with
     * what a real request actually did.
     */
    public static String describeEffectiveProxy(AtlassianProxy proxy, String host) {
        if (proxy == null) {
            return "no proxy configured";
        }
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        List<String> noProxy = proxy.noProxy().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        return bypasses(normalizedHost, noProxy)
                ? "direct (no_proxy matches " + host + ")"
                : "proxy " + proxy.host() + ":" + proxy.port();
    }
}
