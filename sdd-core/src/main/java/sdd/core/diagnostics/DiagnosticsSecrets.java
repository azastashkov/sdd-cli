package sdd.core.diagnostics;

import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.AtlassianTls;
import sdd.core.config.ModelEndpoint;
import sdd.core.http.TlsConfig;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * B4: "collect the set of known secret values at construction (every resolved Atlassian token, and
 * the truststore password)". Deliberately collects across the WHOLE {@link AtlassianConfig} —
 * Jira, Confluence and Bitbucket together — rather than one call site handing {@link
 * DiagnosticWriter} only the single site's token it happens to be using: a command's one {@link
 * DiagnosticWriter} is shared across every Atlassian call it makes (B2's "one file per command
 * invocation"), so its {@link Redactor} backstop has to know about every secret any of those calls
 * COULD touch, not just the one the caller building the writer happened to have in scope.
 *
 * <p><b>Phase 3 (model mTLS), defence in depth.</b> {@code sdd doctor} is the one command that ever
 * writes a {@code model-tls} diagnostic line ({@code DoctorCommand.recordModelTlsDiagnostics}), and
 * that line is built to never interpolate a model endpoint's {@code tls.key_password}/{@code
 * tls.truststore_password} in the first place — see that method's javadoc. {@link #collect(AtlassianConfig,
 * Map)} adds both secrets to the same redaction set anyway, for the same reason the Atlassian
 * truststore password is collected even though every Atlassian call site is equally careful never
 * to interpolate it: "never construct the interpolation" and "the redaction pass would catch it
 * anyway" are two independent guarantees, and a future change to either the model-tls line or some
 * other write path only has to fail ONE of them, not both, for a secret to leak. Cheap here because
 * both values are already fully resolved by {@code ConfigLoader} (or deliberately absent).
 *
 * <p>Never throws and never includes a value that did not actually resolve — an unset {@code
 * ${VAR}} token (the deferred-credential idiom {@code AtlassianSite}/{@code AtlassianTls}/
 * {@link TlsConfig} all use) leaves {@code token()}/{@code password()}/{@code keyPassword()}/
 * {@code truststorePassword()} null, and a null cannot leak, so it is simply omitted rather than
 * collected as some placeholder.
 */
public final class DiagnosticsSecrets {
    private DiagnosticsSecrets() {
    }

    /** {@link #collect(AtlassianConfig, Map)} with no model endpoints — every call site other than
     *  {@code DoctorCommand} (the only one that can ever write a model-tls diagnostic line), kept
     *  as its own overload so none of those call sites has to thread a {@code Map.of()} through. */
    public static Set<String> collect(AtlassianConfig config) {
        return collect(config, Map.of());
    }

    public static Set<String> collect(AtlassianConfig config, Map<String, ModelEndpoint> models) {
        Set<String> secrets = new HashSet<>();
        if (config != null) {
            addToken(secrets, config.jira());
            addToken(secrets, config.confluence());
            if (config.bitbucket() != null) {
                addToken(secrets, config.bitbucket().site());
            }
            addTruststorePassword(secrets, config.tls());
        }
        addModelTlsSecrets(secrets, models);
        return secrets;
    }

    private static void addToken(Set<String> secrets, AtlassianSite site) {
        if (site != null && site.token() != null) {
            secrets.add(site.token());
        }
    }

    private static void addTruststorePassword(Set<String> secrets, AtlassianTls tls) {
        if (tls != null && tls.password() != null) {
            secrets.add(tls.password());
        }
    }

    private static void addModelTlsSecrets(Set<String> secrets, Map<String, ModelEndpoint> models) {
        if (models == null) {
            return;
        }
        for (ModelEndpoint endpoint : models.values()) {
            TlsConfig tls = endpoint.tls();
            if (tls == null) {
                continue;
            }
            if (tls.keyPassword() != null) {
                secrets.add(tls.keyPassword());
            }
            if (tls.truststorePassword() != null) {
                secrets.add(tls.truststorePassword());
            }
        }
    }
}
