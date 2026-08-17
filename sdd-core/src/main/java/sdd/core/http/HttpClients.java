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
     *  here than one more overload of {@code build}. {@code DoctorCommand} prints a
     *  {@link ConfigException} from this method verbatim, and needs {@code atlassian.tls.truststore}
     *  named in it, not a generic key no {@code sdd.yml} actually has — {@link #toTlsConfig} is
     *  what supplies that, by stamping {@code "atlassian.tls"} onto the {@link TlsConfig#configPath}
     *  of the value built here, so no re-labelling step is needed after the fact (see that method's
     *  javadoc for why the earlier re-labelling shim was deleted rather than kept alongside this). */
    public static HttpClient build(AtlassianTls tls, AtlassianProxy proxy) {
        return buildClient(toTlsConfig(tls), toProxyConfig(proxy));
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
     * <p>Error messages here are built from {@code tls.configPath()} — {@code "atlassian.tls"} for
     * the one Atlassian truststore, {@code "models.<name>.tls"} for a model endpoint, {@code null}
     * (falling back to the bare {@code tls} prefix — see {@link TlsConfig}'s javadoc) for a
     * {@link TlsConfig} built with no known namespace, e.g. directly in a test — rather than a
     * hardcoded literal, since this method is shared by every caller regardless of which
     * differently-namespaced key actually owns the block. This used to be a hardcoded
     * {@code "tls.truststore"} that was correct for neither caller on its own, re-labelled back to
     * {@code atlassian.tls.truststore} after the fact for the Atlassian one alone by a now-deleted
     * {@code relabelForAtlassian} shim — see this class's javadoc for why that shim was removed
     * rather than given a model-side twin.
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
        String truststoreKey = truststoreConfigKey(tls.configPath());
        if (!Files.isRegularFile(path)) {
            throw new ConfigException(truststoreKey + " " + path + " does not exist");
        }
        char[] password = tls.truststorePassword() == null ? new char[0] : tls.truststorePassword().toCharArray();
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance(truststoreType(path));
            keyStore.load(in, password);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            return tmf.getTrustManagers();
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigException("cannot load " + truststoreKey + " " + path + ": " + e.getMessage(), e);
        }
    }

    /** {@link #trustManagers(TlsConfig)}, callable with the Atlassian shape directly — every
     *  existing Atlassian call site ({@code RemoteGit}, {@code DiagnosticHeader},
     *  {@code HttpClientsTest}) keeps compiling and behaving exactly as before, since a real
     *  {@code AtlassianTls} always converts to a {@link TlsConfig} with a non-null
     *  {@code truststore} (the one branch above this class's javadoc calls out never triggers
     *  here) AND a non-null {@code configPath} of {@code "atlassian.tls"} (set by
     *  {@link #toTlsConfig(AtlassianTls)}), so {@link #trustManagers(TlsConfig)} already names
     *  {@code atlassian.tls.truststore} on its own — no re-labelling step needed after the fact. */
    public static TrustManager[] trustManagers(AtlassianTls tls) {
        return trustManagers(toTlsConfig(tls));
    }

    /** The {@code <configPath>.truststore} config key a truststore error should name —
     *  {@code configPath + ".truststore"}, or the bare {@code "tls.truststore"} {@link TlsConfig}'s
     *  javadoc documents as the fallback when {@code configPath} is null. Shared by
     *  {@link #trustManagers(TlsConfig)} and {@link #modelTlsFailureMessage(String, Path, Throwable,
     *  String)} so the two truststore-naming call sites can never drift apart on the fallback rule. */
    private static String truststoreConfigKey(String configPath) {
        return (configPath == null ? "tls" : configPath) + ".truststore";
    }

    /** {@code <configPath>.cert}/{@code <configPath>.key} — {@link #truststoreConfigKey}'s
     *  counterparts for {@link #keyManagers(TlsConfig)}'s two config-key-naming errors. */
    private static String certConfigKey(String configPath) {
        return (configPath == null ? "tls" : configPath) + ".cert";
    }

    private static String keyConfigKey(String configPath) {
        return (configPath == null ? "tls" : configPath) + ".key";
    }

    /**
     * The client-certificate {@link KeyManager}s for {@code tls.clientCert()}/
     * {@code tls.clientKey()} — null when neither is configured (the common case; Atlassian never
     * sets these, so {@link #trustManagers(AtlassianTls)}'s callers never exercise this branch
     * either). Exactly one of the pair set is a config error: a cert without a key (or the
     * reverse) can never produce a working {@link SSLContext}, and failing fast here with a plain
     * message is better than an opaque {@code NullPointerException} three calls deeper. The dotted
     * config path in that message is {@code tls.configPath()} — {@code ConfigLoader.parseModelTls}
     * already validates this same pairing eagerly, at load time, with its own {@code
     * models.<name>.tls.cert}-prefixed message (so this branch rarely fires for a real {@code
     * sdd.yml}), but a {@link TlsConfig} can also reach this class built some other way — directly
     * in a test, for instance — so this class names its own key too rather than assuming that
     * earlier check always ran first.
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
            throw new ConfigException(certConfigKey(tls.configPath()) + " and " + keyConfigKey(tls.configPath())
                    + " must both be configured, or neither");
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
            throw new ConfigException(certConfigKey(tls.configPath()) + " " + certPath + " does not exist");
        }
        if (!Files.isRegularFile(keyPath)) {
            throw new ConfigException(keyConfigKey(tls.configPath()) + " " + keyPath + " does not exist");
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
     * The parsed certificate chain {@code tls.clientCert()} names — a public passthrough to
     * {@link PemKeyLoader#certificateChain}, which is package-private, for {@code sdd doctor}'s
     * pre-flight validation and diagnostics (Phase 3, {@code sdd.cli.DoctorCommand}): both need the
     * leaf certificate's subject and expiry, which {@link #keyManagers} parses internally but never
     * returns (it only needs a {@link KeyManager}, not the certificate objects themselves). The
     * leaf is parsed a second time as a result — once inside {@link #keyManagers} to build the
     * handshake keystore, once here purely to read {@code subject}/{@code notAfter} off it — rather
     * than changing {@link #keyManagers}' return type, which every existing caller and test already
     * depends on as {@code KeyManager[]}. A deliberate, disclosed duplication: parsing a small PEM
     * file twice costs microseconds and is far cheaper than widening a tested public contract.
     */
    public static List<X509Certificate> clientCertificateChain(Path certPath) {
        return PemKeyLoader.certificateChain(certPath);
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

    /** The Atlassian adapter from {@code AtlassianTls} to this class's neutral {@link TlsConfig} —
     *  {@code configPath} is stamped {@code "atlassian.tls"} here, the one and only place an
     *  {@code AtlassianTls} ever becomes a {@link TlsConfig}, so every error {@link
     *  #trustManagers(TlsConfig)} builds from the result already names {@code atlassian.tls.truststore}
     *  without any re-labelling step afterward. */
    private static TlsConfig toTlsConfig(AtlassianTls tls) {
        if (tls == null) {
            return null;
        }
        return new TlsConfig(tls.truststore(), tls.password(), tls.passwordError(),
                null, null, null, null, List.of(), "atlassian.tls");
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
     * {@link #tlsFailureMessage}, extended with the explanation the plan's "The failure this will
     * most likely hit first" section calls out by name: curl trusts the OS certificate store
     * (macOS keychain; {@code /etc/ssl/certs} on Linux), the JDK trusts only its own {@code
     * cacerts}, so a corporate CA installed system-wide but never imported into the JDK produces
     * exactly this handshake failure while {@code curl -v} against the same URL succeeds — from
     * which a reasonable operator concludes {@code sdd} is broken rather than that trust is
     * misconfigured. Reuses {@link #tlsFailureMessage} for the host/truststore-naming half (the
     * assertion in {@code HttpClientsTlsConfigTest} that this method's output {@code startsWith}
     * the base message is what pins that this is genuinely reused, not a second implementation)
     * and appends the "curl succeeding proves nothing here" sentence plus the two fixes
     * ({@code tls.truststore}, or importing the CA into {@code cacerts}) — never a third,
     * independent message. {@code AtlassianProbe}/{@code RestClient} deliberately keep calling the
     * un-extended {@link #tlsFailureMessage} — Atlassian's failure table in
     * {@code docs/runbook.md} and two tests ({@code HttpClientsTest},
     * {@code AtlassianProbeTest}) already pin that exact byte-for-byte text, and this is a new,
     * separate entry point precisely so extending the model-endpoint message never touches it.
     *
     * <p>Delegates to the 4-argument overload below with a null {@code configPath} — the generic
     * {@code tls.truststore} remedy, same fallback {@link TlsConfig#configPath()} documents — kept
     * as its own 3-argument overload so every existing caller of this exact signature (this class's
     * own tests included) keeps compiling and behaving unchanged.
     */
    public static String modelTlsFailureMessage(String host, Path truststore, Throwable cause) {
        return modelTlsFailureMessage(host, truststore, cause, null);
    }

    /** {@link #modelTlsFailureMessage(String, Path, Throwable)}, naming {@code configPath}'s own
     *  truststore key ({@code "models.<name>.tls.truststore"}) in the remedy sentence instead of
     *  the generic {@code tls.truststore} — {@link EndpointProbe} is the one caller with an actual
     *  endpoint namespace to give it, via {@code ep.tls().configPath()}. */
    public static String modelTlsFailureMessage(String host, Path truststore, Throwable cause, String configPath) {
        return tlsFailureMessage(host, truststore, cause) + " — a working \"curl\" to this same URL "
                + "does not mean the JDK trusts this certificate chain: curl trusts the OS certificate "
                + "store, the JDK trusts only its own cacerts. Fix by setting " + truststoreConfigKey(configPath)
                + " in sdd.yml to the corporate CA chain, or by importing that CA into "
                + "$JAVA_HOME/lib/security/cacerts.";
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
