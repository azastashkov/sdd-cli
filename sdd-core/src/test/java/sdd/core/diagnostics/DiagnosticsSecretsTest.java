package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianSite;
import sdd.core.config.AtlassianTls;
import sdd.core.config.BitbucketSite;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.WriteBack;
import sdd.core.http.TlsConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

    // Fix 2 (Gate re-review): docs/commands.md claims sdd doctor's model-tls line "still applies
    // the redaction pass ... as a backstop" — true only once a model endpoint's tls.key_password
    // and tls.truststore_password are actually in the collected set, since DiagnosticWriter can
    // only redact a secret it knows about.

    private ModelEndpoint endpointWithTls(String keyPassword, String truststorePassword) {
        TlsConfig tls = new TlsConfig(Path.of("/etc/ssl/corp-ca.p12"), truststorePassword, null,
                Path.of("/certs/client.crt"), Path.of("/certs/client.key"), keyPassword, null, List.of());
        return new ModelEndpoint("https://corp-ift.example/v1", "DeepSeek-V4-Flash", null, 256, 0.0,
                Duration.ofSeconds(5), Map.of(), null, tls);
    }

    @Test
    void collectsEveryConfiguredModelEndpointsKeyAndTruststorePasswords() {
        Map<String, ModelEndpoint> models = Map.of(
                "corp", endpointWithTls("key-pass-1", "trust-pass-1"),
                "backup", endpointWithTls("key-pass-2", "trust-pass-2"));

        Set<String> secrets = DiagnosticsSecrets.collect(null, models);

        assertThat(secrets).containsExactlyInAnyOrder("key-pass-1", "trust-pass-1", "key-pass-2", "trust-pass-2");
    }

    @Test
    void skipsModelEndpointsWithNoTlsBlockOrUnresolvedPasswords() {
        Map<String, ModelEndpoint> models = Map.of(
                "planner", new ModelEndpoint("https://api.deepseek.com/v1", "deepseek-v4-flash", "sk-test", 4096,
                        0.15, Duration.ofSeconds(600), Map.of(), null, null),
                "corp", endpointWithTls(null, null));

        Set<String> secrets = DiagnosticsSecrets.collect(null, models);

        assertThat(secrets).isEmpty();
    }

    @Test
    void collectPlainOverloadIsEquivalentToCollectWithNoModels() {
        AtlassianTls tls = new AtlassianTls(Path.of("/etc/ssl/corp.p12"), "trust-pass-123", null);
        AtlassianConfig config = new AtlassianConfig(tls, null,
                site("https://jira.corp.local", "jira-tok-1", "JIRA_API_KEY"), null, null, 1, 20, 10,
                WriteBack.NONE, false);

        assertThat(DiagnosticsSecrets.collect(config)).isEqualTo(DiagnosticsSecrets.collect(config, Map.of()));
    }

    @Test
    void nullModelsCollectsNoModelSecretsRatherThanThrowing() {
        assertThat(DiagnosticsSecrets.collect(null, null)).isEmpty();
    }
}
