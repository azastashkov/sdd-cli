package sdd.core.http;

import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianTls;
import sdd.core.config.ConfigException;

import javax.net.ssl.SSLContext;
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
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.Locale;

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
     *  configured — the common case for every command that never touches Atlassian. */
    public static HttpClient build(AtlassianTls tls, AtlassianProxy proxy) {
        if (tls == null && proxy == null) {
            return HttpClient.newHttpClient();
        }
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (tls != null) {
            builder.sslContext(sslContext(tls));
        }
        if (proxy != null) {
            builder.proxy(proxySelector(proxy));
        }
        return builder.build();
    }

    /**
     * Loads {@code tls.truststore()} into an {@link SSLContext} trusting only what it contains.
     * The keystore type is inferred from the file extension — {@code .jks} is JKS, everything
     * else (including no extension) is PKCS12, the JDK's own default keystore type, so an
     * unlabelled corporate export still loads without the operator having to know or care which
     * binary format it happens to be in.
     *
     * <p>A configured path that does not exist is a {@link ConfigException}, never a silent
     * fallback to the JDK default truststore — see this class's javadoc. This is the same rule
     * {@code node_home} follows for the same reason: a typo here must not silently change which
     * trust anchors are in play.
     */
    private static SSLContext sslContext(AtlassianTls tls) {
        Path path = tls.truststore();
        if (!Files.isRegularFile(path)) {
            throw new ConfigException("atlassian.tls.truststore " + path + " does not exist");
        }
        char[] password = tls.password() == null ? new char[0] : tls.password().toCharArray();
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance(truststoreType(path));
            keyStore.load(in, password);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (IOException | GeneralSecurityException e) {
            throw new ConfigException("cannot load atlassian.tls.truststore " + path + ": " + e.getMessage(), e);
        }
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
}
