package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianSite;
import sdd.core.config.AtlassianTls;
import sdd.core.config.BitbucketSite;
import sdd.core.config.WriteBack;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DiagnosticsSecrets} collects every value {@link DiagnosticWriter}'s {@link Redactor}
 * backstop must never emit, from the WHOLE {@link AtlassianConfig} — not just the one site a
 * particular {@code RestClient} happens to be built for — so a writer shared across a command's
 * Jira, Confluence and Bitbucket calls redacts all three regardless of which one is talking.
 */
class DiagnosticsSecretsTest {
    private AtlassianSite site(String baseUrl, String token, String tokenVar) {
        return new AtlassianSite(baseUrl, token, tokenVar, Duration.ofSeconds(5), null);
    }

    @Test
    void collectsEveryResolvedSiteTokenAndTheTruststorePassword() {
        AtlassianTls tls = new AtlassianTls(Path.of("/etc/ssl/corp.p12"), "trust-pass-123", null);
        AtlassianConfig config = new AtlassianConfig(tls, null,
                site("https://jira.corp.local", "jira-tok-1", "JIRA_API_KEY"),
                site("https://confluence.corp.local", "conf-tok-2", "CONFLUENCE_API_KEY"),
                new BitbucketSite(site("https://bb.corp.local", "bb-tok-3", "BITBUCKET_API_KEY"), "TRADING", List.of()),
                1, 20, 10, WriteBack.NONE, false);

        Set<String> secrets = DiagnosticsSecrets.collect(config);

        assertThat(secrets).containsExactlyInAnyOrder("jira-tok-1", "conf-tok-2", "bb-tok-3", "trust-pass-123");
    }

    @Test
    void skipsSitesThatAreNotConfiguredOrWhoseTokenDidNotResolve() {
        AtlassianConfig config = new AtlassianConfig(null, null,
                site("https://jira.corp.local", null, "JIRA_API_KEY"),   // unresolved (deferred error, not reached here)
                null, null, 1, 20, 10, WriteBack.NONE, false);

        Set<String> secrets = DiagnosticsSecrets.collect(config);

        assertThat(secrets).isEmpty();
    }

    @Test
    void nullConfigCollectsNoSecretsRatherThanThrowing() {
        assertThat(DiagnosticsSecrets.collect(null)).isEmpty();
    }
}
