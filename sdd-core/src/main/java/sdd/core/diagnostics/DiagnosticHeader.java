package sdd.core.diagnostics;

import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianSite;
import sdd.core.config.AtlassianTls;
import sdd.core.config.BitbucketSite;
import sdd.core.http.HttpClients;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * B3's header block — written once, at the top of every diagnostic file, by whichever command
 * opened it. This is what makes a pasted file self-contained without a second round trip: enough
 * about the environment (versions, OS), the invocation (command line, credentials elided), and the
 * Atlassian configuration (which sites, which env vars, whether they resolved — never a value) that
 * a remote reader can rule out a whole class of causes before reading a single HTTP entry below it.
 *
 * <p>Deliberately a pure function of its arguments — no file I/O of its own beyond the one
 * best-effort truststore load {@link #truststoreSection} performs to report whether it loads and
 * how many trust anchors it contains (the same information {@code sdd doctor}'s own diagnostic
 * would need to compute anyway; doing it once here means the header can state it directly rather
 * than the reader having to infer it from whether later HTTP entries show TLS failures). Every
 * other fact is handed in already resolved, which is what keeps this class trivially testable
 * without a real Atlassian instance, a real truststore, or a real git checkout.
 */
public final class DiagnosticHeader {
    // Flags whose VALUE is elided from the printed command line — this repo's own commands never
    // take a secret directly (tokens come from ${VAR} env references inside sdd.yml, not CLI
    // arguments), so this is defensive rather than presently load-bearing: a future option named
    // anything credential-shaped is still caught by name, not by an enumerated allowlist of today's
    // flags.
    private static final Pattern SENSITIVE_FLAG =
            Pattern.compile("(?i)^--?(token|password|secret|api[-_]?key|credential)s?$");

    private DiagnosticHeader() {
    }

    /**
     * @param commandLine the exact argv {@code sdd} was invoked with, INCLUDING the subcommand name
     *                     at index 0 (e.g. {@code ["doctor", "--report"]}) — the caller's {@code
     *                     args} array is not itself prefixed with "sdd", so this method does not
     *                     assume one.
     * @param atlassian    the loaded config's {@code atlassian:} block, or null when {@code sdd.yml}
     *                     has none (or could not be loaded at all) — every Atlassian-specific
     *                     section then reports every site as not configured, matching {@code sdd
     *                     doctor}'s own "a missing atlassian: block changes nothing" rule.
     * @param sddVersion   best-effort, "unknown" when not resolvable (see {@code RuntimeInfo}).
     * @param gitCommit    best-effort, "unknown" when not resolvable.
     */
    public static String render(List<String> commandLine, AtlassianConfig atlassian, String sddVersion,
            String gitCommit) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== sdd diagnostics ===\n");
        sb.append("This file is safe to share: known secret values (Atlassian tokens, the TLS\n");
        sb.append("truststore password) are redacted by construction. Internal hostnames, Bitbucket\n");
        sb.append("project keys and Jira/Confluence issue keys WILL appear unredacted — they are\n");
        sb.append("necessary to diagnose anything — so redact them yourself first if your sharing\n");
        sb.append("policy requires it.\n");
        sb.append("Retention: the ").append(DiagnosticsDir.MAX_FILES)
                .append(" most recent diagnostic files under .sdd/diagnostics/ are kept; older ones\n");
        sb.append("are deleted automatically the next time a command writes a new one.\n");
        sb.append("Unverified: every Jira/Confluence/Bitbucket Data Center API shape this build uses\n");
        sb.append("was checked against Atlassian's documentation but never against a live instance —\n");
        sb.append("see scripts/atlassian-dc/README.md's \"least-certain API shapes\" section for\n");
        sb.append("exactly which behaviours that covers.\n");
        sb.append('\n');
        sb.append("sdd version: ").append(sddVersion).append('\n');
        sb.append("git commit: ").append(gitCommit).append('\n');
        sb.append("java: ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("os: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        sb.append("command: ").append(String.join(" ", redactArgs(commandLine))).append('\n');
        sb.append('\n');
        sb.append("--- config summary ---\n");
        siteSection(sb, "jira", atlassian == null ? null : atlassian.jira());
        siteSection(sb, "confluence", atlassian == null ? null : atlassian.confluence());
        BitbucketSite bb = atlassian == null ? null : atlassian.bitbucket();
        siteSection(sb, "bitbucket", bb == null ? null : bb.site());
        truststoreSection(sb, atlassian == null ? null : atlassian.tls());
        proxySection(sb, atlassian == null ? null : atlassian.proxy());
        sb.append("write_back=").append(atlassian == null ? "NONE" : atlassian.writeBack()).append('\n');
        sb.append("pull_requests=").append(atlassian != null && atlassian.pullRequests()).append('\n');
        return sb.toString();
    }

    private static void siteSection(StringBuilder sb, String name, AtlassianSite site) {
        if (site == null) {
            sb.append(name).append(": not configured\n");
            return;
        }
        boolean resolved = site.token() != null && site.tokenError() == null;
        sb.append(name).append(": configured host=").append(hostOf(site.baseUrl()))
                .append(" token-env-var=").append(site.tokenVar() == null ? "(literal, not a ${VAR})" : site.tokenVar())
                .append(" resolved=").append(resolved).append('\n');
    }

    private static void truststoreSection(StringBuilder sb, AtlassianTls tls) {
        if (tls == null) {
            sb.append("truststore: not configured (JDK default)\n");
            return;
        }
        try {
            TrustManager[] tms = HttpClients.trustManagers(tls);
            int anchors = 0;
            for (TrustManager tm : tms) {
                if (tm instanceof X509TrustManager x509) {
                    anchors += x509.getAcceptedIssuers().length;
                }
            }
            sb.append("truststore: ").append(tls.truststore()).append(" loaded=true trust-anchors=")
                    .append(anchors).append('\n');
        } catch (RuntimeException e) {
            sb.append("truststore: ").append(tls.truststore()).append(" loaded=false error=")
                    .append(e.getMessage()).append('\n');
        }
    }

    private static void proxySection(StringBuilder sb, AtlassianProxy proxy) {
        if (proxy == null) {
            sb.append("proxy: not configured\n");
            return;
        }
        sb.append("proxy: ").append(proxy.host()).append(':').append(proxy.port())
                .append(" no_proxy=").append(proxy.noProxy()).append('\n');
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null ? host : baseUrl;
        } catch (IllegalArgumentException | NullPointerException e) {
            return String.valueOf(baseUrl);
        }
    }

    /** Elides the value immediately following a flag whose name looks credential-shaped — see
     *  {@link #SENSITIVE_FLAG}'s javadoc for why this is defensive rather than presently
     *  load-bearing for this repo's actual command set. */
    static List<String> redactArgs(List<String> args) {
        List<String> out = new java.util.ArrayList<>(args.size());
        boolean redactNext = false;
        for (String arg : args) {
            if (redactNext) {
                out.add("<redacted>");
                redactNext = false;
                continue;
            }
            out.add(arg);
            if (SENSITIVE_FLAG.matcher(arg.toLowerCase(Locale.ROOT)).matches()) {
                redactNext = true;
            }
        }
        return out;
    }
}
