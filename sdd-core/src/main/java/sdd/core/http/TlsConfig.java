package sdd.core.http;

import java.nio.file.Path;
import java.util.List;

/**
 * The transport-neutral TLS shape {@link HttpClients} actually needs, generalised out of
 * {@code sdd.core.config.AtlassianTls} so one {@code SSLContext} builder can serve both the
 * Atlassian clients and (starting with a model endpoint's {@code tls:} block) the model clients —
 * see {@code HttpClients}' class javadoc for why forking the truststore/key-manager logic instead
 * would mean two places to get certificate handling wrong.
 *
 * <p>{@code truststore}/{@code truststorePassword}/{@code truststorePasswordError} are exactly
 * {@code AtlassianTls}'s three fields, renamed only to make room for the client-certificate
 * counterpart alongside them. {@code clientCert}/{@code clientKey} are the {@code --cert}/
 * {@code --key} PEM pair {@link HttpClients#keyManagers} loads; both null means "no client
 * certificate" (the common case — Atlassian never sets these), exactly one set is a config error
 * {@link HttpClients#keyManagers} raises. {@code keyPassword}/{@code keyPasswordError} follow the
 * same deferred-credential idiom as {@code truststorePassword}/{@code truststorePasswordError} —
 * an unset {@code ${VAR}} must not fail config loading for a command that never opens the key, so
 * the message is captured at load time and raised byte-identically at the point the key is
 * actually read.
 *
 * <p>{@code protocols} is the {@code --tlsv1.2 --tls-max 1.2} pin, applied via
 * {@code SSLParameters.setProtocols}; empty (the default via the compact constructor below, so a
 * caller passing {@code null} never has to null-check it back) leaves the JDK's own protocol
 * negotiation alone.
 *
 * <p>{@code configPath} is the dotted {@code sdd.yml} prefix that owns this block —
 * {@code "atlassian.tls"} for the one Atlassian truststore, {@code "models.<name>.tls"} for a
 * model endpoint, set by {@code ConfigLoader.parseModelTls} and wherever {@code AtlassianTls}
 * converts to a {@link TlsConfig} ({@code HttpClients.toTlsConfig}). {@link HttpClients} builds
 * every config-key-naming error message (a missing truststore/cert/key file, or a cert configured
 * without its key) from this field instead of a hardcoded literal, so each caller's error names its
 * own real {@code sdd.yml} key rather than a generic one nothing in the file actually matches —
 * see {@code HttpClients}' class javadoc for why a loader shared by two differently-namespaced
 * callers needed this in the first place, and why the older fix (re-labelling the Atlassian
 * messages after the fact, string-by-string, in {@code HttpClients.relabelForAtlassian}) was
 * deleted rather than extended to the model side: a second copy of that same shim, one per
 * namespace, is more code to keep in sync than one field the loader already has every field it
 * needs to set correctly at construction time.
 *
 * <p>Null — never {@code ""}: the two are not the same in this class's error messages, since a
 * message built from {@code "" + ".truststore"} would print a nonsensical leading dot — is the
 * "caller has no endpoint namespace to give" case. Every constructor below defaults it to null via
 * the 8-argument compatibility shape, and {@link HttpClients} falls back to the bare {@code tls}
 * prefix used before this field existed (unqualified {@code tls.truststore}, not {@code
 * null.tls.cert}) for exactly this case — a {@link TlsConfig} built directly in a test, with no
 * config file and no namespace behind it, still gets a message that at least names a plausible key
 * shape rather than crashing on a null concatenation or printing the literal word {@code "null"}.
 */
public record TlsConfig(Path truststore, String truststorePassword, String truststorePasswordError,
                         Path clientCert, Path clientKey,
                         String keyPassword, String keyPasswordError,
                         List<String> protocols, String configPath) {
    public TlsConfig {
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
    }

    /** Pre-{@code configPath} 8-argument shape, kept so every existing construction site (main and
     *  test) keeps compiling untouched: {@code configPath} defaults to null, i.e. no known
     *  namespace — {@link HttpClients} falls back to the generic {@code tls.*} prefix for these,
     *  exactly as it always has. Same pattern as {@code ModelEndpoint}'s pre-{@code tls}
     *  8-argument overload. */
    public TlsConfig(Path truststore, String truststorePassword, String truststorePasswordError,
                      Path clientCert, Path clientKey,
                      String keyPassword, String keyPasswordError,
                      List<String> protocols) {
        this(truststore, truststorePassword, truststorePasswordError, clientCert, clientKey,
                keyPassword, keyPasswordError, protocols, null);
    }
}
