package sdd.core.diagnostics;

import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.AtlassianTls;

import java.util.HashSet;
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
 * <p>Never throws and never includes a value that did not actually resolve — an unset {@code
 * ${VAR}} token (the deferred-credential idiom {@code AtlassianSite}/{@code AtlassianTls} both use)
 * leaves {@code token()}/{@code password()} null, and a null cannot leak, so it is simply omitted
 * rather than collected as some placeholder.
 */
public final class DiagnosticsSecrets {
    private DiagnosticsSecrets() {
    }

    public static Set<String> collect(AtlassianConfig config) {
        Set<String> secrets = new HashSet<>();
        if (config == null) {
            return secrets;
        }
        addToken(secrets, config.jira());
        addToken(secrets, config.confluence());
        if (config.bitbucket() != null) {
            addToken(secrets, config.bitbucket().site());
        }
        addTruststorePassword(secrets, config.tls());
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
}
