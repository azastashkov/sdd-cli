package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianSite;
import sdd.core.config.AtlassianTls;
import sdd.core.config.BitbucketSite;
import sdd.core.config.WriteBack;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DiagnosticHeader} renders B3's header block: the one piece of the diagnostic file
 * guaranteed to be present on every run, and the part that makes a pasted file self-contained
 * without a second round trip. Every assertion here checks for PRESENCE of a required fact, not
 * exact formatting — the brief lists facts, not a template.
 */
class DiagnosticHeaderTest {
    @TempDir
    Path tmp;

    private AtlassianSite site(String baseUrl, String token, String tokenVar) {
        return new AtlassianSite(baseUrl, token, tokenVar, Duration.ofSeconds(5), null);
    }

    /** A real, empty, password-protected PKCS12 keystore — {@link DiagnosticHeader} actually opens
     *  the truststore to count trust anchors, so a non-existent path would only exercise the
     *  loaded=false branch. */
    private Path emptyTruststore(String password) throws IOException, GeneralSecurityException {
        Path file = tmp.resolve("truststore.p12");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password.toCharArray());
        try (OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, password.toCharArray());
        }
        return file;
    }

    @Test
    void includesVersionJavaAndOsFacts() {
        String header = DiagnosticHeader.render(List.of("doctor"), null, "1.2.3", "abc1234");

        assertThat(header).contains("1.2.3").contains("abc1234")
                .contains(System.getProperty("java.version"))
                .contains(System.getProperty("java.vendor"))
                .contains(System.getProperty("os.name"));
    }

    @Test
    void includesTheCommandLineAsInvoked() {
        String header = DiagnosticHeader.render(List.of("review", "--workspace", "/tmp/ws", "run.plan.json"), null,
                "1.0", "unknown");

        assertThat(header).contains("review").contains("--workspace").contains("/tmp/ws").contains("run.plan.json");
    }

    @Test
    void elidesArgumentValuesFollowingASensitiveLookingFlag() {
        String header = DiagnosticHeader.render(
                List.of("doctor", "--token", "sk-should-not-appear", "--workspace", "/tmp/ws"), null, "1.0", "u");

        assertThat(header).doesNotContain("sk-should-not-appear").contains("<redacted>");
    }

    @Test
    void summarizesEachAtlassianSiteWithoutEverPrintingTheTokenValue() {
        AtlassianConfig config = new AtlassianConfig(null, null,
                site("https://jira.corp.local", "jira-tok", "JIRA_API_KEY"),
                site("https://confluence.corp.local", "conf-tok", "CONFLUENCE_API_KEY"),
                new BitbucketSite(site("https://bb.corp.local", "bb-tok", "BITBUCKET_API_KEY"), "TRADING", List.of()),
                1, 20, 10, WriteBack.COMMENT, true);

        String header = DiagnosticHeader.render(List.of("doctor"), config, "1.0", "u");

        assertThat(header).contains("jira.corp.local").contains("JIRA_API_KEY")
                .contains("confluence.corp.local").contains("CONFLUENCE_API_KEY")
                .contains("bb.corp.local").contains("BITBUCKET_API_KEY")
                .contains("resolved=true")
                .doesNotContain("jira-tok").doesNotContain("conf-tok").doesNotContain("bb-tok");
    }

    @Test
    void reportsAnUnconfiguredSiteAsNotConfiguredRatherThanOmittingIt() {
        AtlassianConfig config = new AtlassianConfig(null, null, null, null, null,
                1, 20, 10, WriteBack.NONE, false);

        String header = DiagnosticHeader.render(List.of("doctor"), config, "1.0", "u");

        assertThat(header).contains("jira: not configured").contains("confluence: not configured")
                .contains("bitbucket: not configured");
    }

    @Test
    void reportsAnUnresolvedTokenAsSuchWithoutTheDeferredErrorLeakingAValue() {
        AtlassianSite unresolved = new AtlassianSite("https://jira.corp.local", null, "JIRA_API_KEY",
                Duration.ofSeconds(5), "atlassian.jira.token: environment variable JIRA_API_KEY is not set");
        AtlassianConfig config = new AtlassianConfig(null, null, unresolved, null, null,
                1, 20, 10, WriteBack.NONE, false);

        String header = DiagnosticHeader.render(List.of("doctor"), config, "1.0", "u");

        assertThat(header).contains("resolved=false");
    }

    @Test
    void summarizesTruststoreAndProxyWithoutPrintingTheTruststorePassword() throws Exception {
        Path truststore = emptyTruststore("trust-pass");
        AtlassianTls tls = new AtlassianTls(truststore, "trust-pass", null);
        AtlassianProxy proxy = new AtlassianProxy("proxy.corp.local", 8080, List.of("jira.corp.local"));
        AtlassianConfig config = new AtlassianConfig(tls, proxy, null, null, null,
                1, 20, 10, WriteBack.NONE, false);

        String header = DiagnosticHeader.render(List.of("doctor"), config, "1.0", "u");

        assertThat(header).contains(truststore.toString()).contains("trust-anchor").contains("loaded=true")
                .contains("proxy.corp.local").contains("8080").contains("jira.corp.local")
                .doesNotContain("trust-pass");
    }

    @Test
    void statesWriteBackAndPullRequestsSettings() {
        AtlassianConfig config = new AtlassianConfig(null, null, null, null, null,
                1, 20, 10, WriteBack.COMMENT, true);

        String header = DiagnosticHeader.render(List.of("doctor"), config, "1.0", "u");

        assertThat(header).contains("write_back=COMMENT").contains("pull_requests=true");
    }

    @Test
    void pointsAtTheUnverifiedBehavioursRunbook() {
        String header = DiagnosticHeader.render(List.of("doctor"), null, "1.0", "u");

        assertThat(header).containsIgnoringCase("unverified").contains("docs/runbook.md");
    }

    @Test
    void disclosesThatHostnamesAndKeysWillAppearUnredacted() {
        String header = DiagnosticHeader.render(List.of("doctor"), null, "1.0", "u");

        assertThat(header).containsIgnoringCase("hostnames").containsIgnoringCase("project");
    }

    @Test
    void statesTheRetentionPolicy() {
        String header = DiagnosticHeader.render(List.of("doctor"), null, "1.0", "u");

        assertThat(header).contains(String.valueOf(DiagnosticsDir.MAX_FILES));
    }
}
