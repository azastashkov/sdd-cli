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
 */
public record TlsConfig(Path truststore, String truststorePassword, String truststorePasswordError,
                         Path clientCert, Path clientKey,
                         String keyPassword, String keyPasswordError,
                         List<String> protocols) {
    public TlsConfig {
        protocols = protocols == null ? List.of() : List.copyOf(protocols);
    }
}
