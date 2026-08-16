package sdd.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class ConfigLoaderTest {
    @TempDir Path ws;

    private static final Function<String, String> ENV =
            Map.of("DEEPSEEK_API_KEY", "sk-test-123")::get;

    private Path write(String yaml) throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml);
        return ws;
    }

    private static final String MINIMAL = """
            models:
              planner:
                base_url: https://api.deepseek.com/v1
                model: deepseek-v4-flash
                api_key: ${DEEPSEEK_API_KEY}
                max_tokens: 16384
              coder:
                base_url: http://127.0.0.1:8080/v1
                model: mlx-community/Qwen3.6-35B-A3B-8bit
            """;

    @Test
    void loadsMinimalConfigWithDefaults() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL), ENV);
        assertThat(c.workspace()).isEqualTo(ws);
        assertThat(c.models()).containsOnlyKeys("planner", "coder");
        ModelEndpoint planner = c.models().get("planner");
        assertThat(planner.apiKey()).isEqualTo("sk-test-123");
        assertThat(planner.maxTokens()).isEqualTo(16384);
        ModelEndpoint coder = c.models().get("coder");
        assertThat(coder.apiKey()).isNull();
        assertThat(coder.maxTokens()).isEqualTo(4096);
        assertThat(coder.temperature()).isEqualTo(0.15);
        assertThat(coder.timeout()).isEqualTo(Duration.ofSeconds(600));
        assertThat(c.excludes()).isEmpty();
        assertThat(c.jdkHomes()).isEmpty();
    }

    @Test
    void parsesOptionalSections() throws Exception {
        SddConfig c = ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: deepseek-v4-flash
                    api_key: ${DEEPSEEK_API_KEY}
                    max_tokens: 16384
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                jdk_homes:
                  17: /opt/jdk17
                  21: /opt/jdk21
                excludes: [sandbox-repo]
                """), ENV);
        assertThat(c.jdkHomes()).containsEntry(17, Path.of("/opt/jdk17"));
        assertThat(c.excludes()).containsExactly("sandbox-repo");
    }

    @Test
    void embeddingsRetrievalIsRejectedAsNotImplemented() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "retrieval: embeddings\n"), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("not implemented")
                .hasMessageContaining("retrieval: fts");

        // Pin the old escape hatch shut: declaring a valid models.embeddings endpoint alongside
        // retrieval: embeddings must still fail. This is the single most likely future regression —
        // someone "fixing" the rejection by re-adding the requires-an-endpoint check it replaced.
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                  embeddings:
                    base_url: http://127.0.0.1:8080/v1
                    model: some-embedding-model
                retrieval: embeddings
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("not implemented");
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(() -> ConfigLoader.load(ws, ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("sdd.yml");
    }

    // Was against MINIMAL, whose only ${VAR} sits in api_key — exactly the field Fix 1 (below)
    // intentionally stops failing eagerly on, since a read-only command may never touch a model at
    // all. Moved to base_url, which stays structural and must still fail eagerly, to keep this
    // test's original intent (unset var eager-fails, naming the var) honest.
    @Test
    void missingEnvVarFailsWithVarName() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: ${DEEPSEEK_API_KEY}
                    model: deepseek-v4-flash
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                """), k -> null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    // --- unset api_key env var defers to point-of-use (read-only Gate-2 commands must not need
    // credentials they will never touch) --------------------------------------------------------

    @Test
    void unsetApiKeyEnvVarLoadsSuccessfullyAndCarriesTheErrorOnTheEndpoint() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL), k -> null);   // no env var resolves
        ModelEndpoint planner = c.models().get("planner");
        assertThat(planner.apiKey()).isNull();
        assertThat(planner.apiKeyError())
                .isEqualTo("models.planner.api_key: environment variable DEEPSEEK_API_KEY is not set");
    }

    @Test
    void unsetEnvVarInModelStillFailsEagerly() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: ${MISSING_MODEL}
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                """), k -> null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("MISSING_MODEL");
    }

    @Test
    void resolvedApiKeyLeavesTheErrorNull() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL), ENV);
        assertThat(c.models().get("planner").apiKey()).isEqualTo("sk-test-123");
        assertThat(c.models().get("planner").apiKeyError()).isNull();
    }

    @Test
    void absentApiKeyLeavesBothApiKeyAndErrorNull() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL), ENV);
        ModelEndpoint coder = c.models().get("coder");   // MINIMAL's coder has no api_key at all
        assertThat(coder.apiKey()).isNull();
        assertThat(coder.apiKeyError()).isNull();
    }

    @Test
    void missingRequiredModelFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: deepseek-v4-flash
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("coder");
    }

    @Test
    void invalidRetrievalValueFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "retrieval: vector\n"), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("retrieval")
                .hasMessageNotContaining("embeddings");
    }

    @Test
    void explicitFtsRetrievalStillLoads() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + "retrieval: fts\n"), ENV);
        assertThat(c.models()).containsOnlyKeys("planner", "coder");
    }

    @Test
    void nonNumericMaxTokensFailsWithConfigExceptionNamingKeyAndValue() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: deepseek-v4-flash
                    api_key: ${DEEPSEEK_API_KEY}
                    max_tokens: 16384
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                    max_tokens: many
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("models.coder.max_tokens")
                .hasMessageContaining("many");
    }

    @Test
    void nonNumericJdkHomesKeyFailsWithConfigException() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                jdk_homes:
                  x: /opt/jdkx
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("jdk_homes");
    }

    @Test
    void nonListExcludesFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "excludes: oops\n"), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("excludes");
    }

    @Test
    void parsesArtifactOverrides() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                artifact_overrides:
                  com.acme:legacy-lib: platform-repo
                """), ENV);
        assertThat(c.artifactOverrides()).containsEntry("com.acme:legacy-lib", "platform-repo");
    }

    @Test
    void artifactOverridesDefaultEmpty() throws Exception {
        assertThat(ConfigLoader.load(write(MINIMAL), ENV).artifactOverrides()).isEmpty();
    }

    @Test
    void parsesManualEdges() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                manual_edges:
                  - client_repo: svc-orders
                    http_method: POST
                    path: /pay/charge
                    provider_repo: billing-service
                """), ENV);
        assertThat(c.manualEdges()).containsExactly(
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service"));
    }

    @Test
    void manualEdgeMissingKeyFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                manual_edges:
                  - client_repo: svc-orders
                    path: /x
                    provider_repo: y
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("manual_edges").hasMessageContaining("http_method");
    }

    @Test
    void manualEdgesDefaultEmpty() throws Exception {
        assertThat(ConfigLoader.load(write(MINIMAL), ENV).manualEdges()).isEmpty();
    }

    @Test
    void nonListManualEdgesFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "manual_edges: oops\n"), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("manual_edges");
    }

    @Test
    void httpMethodNormalizedToUppercase() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                manual_edges:
                  - client_repo: svc-orders
                    http_method: post
                    path: /pay/charge
                    provider_repo: billing-service
                """), ENV);
        assertThat(c.manualEdges()).containsExactly(
                new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service"));
    }

    @Test
    void parsesTheRunSection() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  gradle_workers: 4
                  model_concurrency: 1
                  token_budget: 5000000
                  agent_turns: 20
                  agent_tokens: 750000
                """);
        SddConfig config = ConfigLoader.load(ws);
        assertThat(config.run()).isEqualTo(
                new RunSettings(4, 1, 5_000_000L, 20, 750_000L, java.util.List.of("coder", "planner")));
    }

    @Test
    void runSectionDefaultsWhenAbsentOrPartial() throws Exception {
        Path absent = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                """);
        assertThat(ConfigLoader.load(absent).run()).isEqualTo(RunSettings.defaults());
        assertThat(RunSettings.defaults()).isEqualTo(
                new RunSettings(2, 2, 30_000_000L, 40, 1_500_000L, java.util.List.of("coder", "planner")));

        Path partial = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  gradle_workers: 8
                """);
        assertThat(ConfigLoader.load(partial).run()).isEqualTo(
                new RunSettings(8, 2, 30_000_000L, 40, 1_500_000L, java.util.List.of("coder", "planner")));
    }

    @Test
    void parsesTheEscalationLadder() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                  reviewer: { base_url: http://z/v1, model: r }
                run:
                  escalation_ladder: [coder, reviewer, planner]
                """);
        SddConfig config = ConfigLoader.load(ws);
        assertThat(config.run().escalationLadder()).containsExactly("coder", "reviewer", "planner");
    }

    @Test
    void escalationLadderDefaultsToCoderThenPlannerWhenAbsent() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                """);
        assertThat(ConfigLoader.load(ws).run().escalationLadder()).containsExactly("coder", "planner");
    }

    @Test
    void escalationLadderNamingUnknownModelFails() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  escalation_ladder: [coder, ghost]
                """);
        assertThatThrownBy(() -> ConfigLoader.load(ws))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("escalation_ladder")
                .hasMessageContaining("ghost");
    }

    @Test
    void emptyEscalationLadderFails() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  escalation_ladder: []
                """);
        assertThatThrownBy(() -> ConfigLoader.load(ws))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("escalation_ladder");
    }

    @Test
    void duplicateEscalationLadderEntryFails() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  escalation_ladder: [coder, coder]
                """);
        assertThatThrownBy(() -> ConfigLoader.load(ws))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("escalation_ladder")
                .hasMessageContaining("coder");
    }

    @Test
    void nonNumericTokenBudgetFails() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  token_budget: lots
                """);
        assertThatThrownBy(() -> ConfigLoader.load(ws))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("token_budget");
    }

    @Test
    void nonPositiveRunValuesFail() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  gradle_workers: 0
                """);
        assertThatThrownBy(() -> ConfigLoader.load(ws))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("gradle_workers");
    }

    @Test
    void nonPositiveAgentTurnsFails() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  agent_turns: 0
                """);
        assertThatThrownBy(() -> ConfigLoader.load(ws))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("agent_turns");
    }

    @Test
    void nonPositiveAgentTokensFails() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  agent_tokens: 0
                """);
        assertThatThrownBy(() -> ConfigLoader.load(ws))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("agent_tokens");
    }

    @Test
    void parsesNestedExtraBody() throws Exception {
        SddConfig c = ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: deepseek-v4-flash
                    api_key: ${DEEPSEEK_API_KEY}
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                    extra_body:
                      chat_template_kwargs:
                        enable_thinking: false
                """), ENV);
        assertThat(c.models().get("coder").extraBody()).isEqualTo(
                Map.of("chat_template_kwargs", Map.of("enable_thinking", false)));
    }

    @Test
    void extraBodyDefaultsEmpty() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL), ENV);
        assertThat(c.models().get("coder").extraBody()).isEmpty();
    }

    @Test
    void nonMappingExtraBodyFailsNamingKey() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: deepseek-v4-flash
                    api_key: ${DEEPSEEK_API_KEY}
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                    extra_body: oops
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("models.coder.extra_body");
    }

    @Test
    void parsesVerificationExclusions() throws Exception {
        Path ws = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                verification_exclusions:
                  legacy-service: [test, check]
                """);
        SddConfig config = ConfigLoader.load(ws);
        assertThat(config.verificationExclusions())
                .containsEntry("legacy-service", java.util.List.of("test", "check"));

        Path absent = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                """);
        assertThat(ConfigLoader.load(absent).verificationExclusions()).isEmpty();
    }

    // --- atlassian: block --------------------------------------------------------------------

    private static final Function<String, String> ATLASSIAN_ENV = Map.of(
            "DEEPSEEK_API_KEY", "sk-test-123",
            "JIRA_PAT", "jira-token-abc",
            "CORP_TRUSTSTORE_PASSWORD", "trust-pw")::get;

    @Test
    void absentAtlassianBlockLeavesTheFieldNull() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL), ENV);
        assertThat(c.atlassian()).isNull();
    }

    @Test
    void nonMappingAtlassianFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "atlassian: oops\n"), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian");
    }

    @Test
    void parsesEveryAtlassianKeyIncludingDefaults() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  tls:
                    truststore: /etc/ssl/corp-ca.jks
                    truststore_password: ${CORP_TRUSTSTORE_PASSWORD}
                  proxy:
                    host: proxy.corp.local
                    port: 8080
                    no_proxy: [jira.corp.local, confluence.corp.local, bitbucket.corp.local]
                  jira:
                    base_url: https://jira.corp.local
                    token: ${JIRA_PAT}
                    timeout_seconds: 45
                  bitbucket:
                    base_url: https://bitbucket.corp.local
                    token: literal-bb-token
                    project: TRADING
                    default_reviewers: [alice, bob]
                  follow_depth: 2
                  max_pages: 5
                  max_linked_issues: 3
                  write_back: comment
                  pull_requests: true
                """), ATLASSIAN_ENV);

        AtlassianConfig ac = c.atlassian();
        assertThat(ac).isNotNull();
        assertThat(ac.tls().truststore()).isEqualTo(Path.of("/etc/ssl/corp-ca.jks"));
        assertThat(ac.tls().password()).isEqualTo("trust-pw");
        assertThat(ac.proxy().host()).isEqualTo("proxy.corp.local");
        assertThat(ac.proxy().port()).isEqualTo(8080);
        assertThat(ac.proxy().noProxy()).containsExactly(
                "jira.corp.local", "confluence.corp.local", "bitbucket.corp.local");

        assertThat(ac.jira().baseUrl()).isEqualTo("https://jira.corp.local");
        assertThat(ac.jira().token()).isEqualTo("jira-token-abc");
        assertThat(ac.jira().tokenVar()).isEqualTo("JIRA_PAT");
        assertThat(ac.jira().tokenError()).isNull();
        assertThat(ac.jira().timeout()).isEqualTo(Duration.ofSeconds(45));

        assertThat(ac.confluence()).isNull();   // independently optional, not configured here

        assertThat(ac.bitbucket().site().baseUrl()).isEqualTo("https://bitbucket.corp.local");
        assertThat(ac.bitbucket().site().token()).isEqualTo("literal-bb-token");
        assertThat(ac.bitbucket().site().tokenVar()).isNull();   // literal, not a ${VAR} reference
        assertThat(ac.bitbucket().site().timeout()).isEqualTo(Duration.ofSeconds(30));   // default
        assertThat(ac.bitbucket().project()).isEqualTo("TRADING");
        assertThat(ac.bitbucket().defaultReviewers()).containsExactly("alice", "bob");

        assertThat(ac.followDepth()).isEqualTo(2);
        assertThat(ac.maxPages()).isEqualTo(5);
        assertThat(ac.maxLinkedIssues()).isEqualTo(3);
        assertThat(ac.writeBack()).isEqualTo(WriteBack.COMMENT);
        assertThat(ac.pullRequests()).isTrue();
    }

    @Test
    void atlassianDefaultsWhenOnlyOneSiteConfigured() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  confluence:
                    base_url: https://confluence.corp.local
                """), ATLASSIAN_ENV);

        AtlassianConfig ac = c.atlassian();
        assertThat(ac.jira()).isNull();
        assertThat(ac.bitbucket()).isNull();
        assertThat(ac.tls()).isNull();
        assertThat(ac.proxy()).isNull();
        assertThat(ac.confluence().baseUrl()).isEqualTo("https://confluence.corp.local");
        assertThat(ac.confluence().token()).isNull();
        assertThat(ac.confluence().tokenVar()).isNull();
        assertThat(ac.confluence().tokenError()).isNull();
        assertThat(ac.confluence().timeout()).isEqualTo(Duration.ofSeconds(30));

        assertThat(ac.followDepth()).isEqualTo(1);
        assertThat(ac.maxPages()).isEqualTo(20);
        assertThat(ac.maxLinkedIssues()).isEqualTo(10);
        assertThat(ac.writeBack()).isEqualTo(WriteBack.NONE);
        assertThat(ac.pullRequests()).isFalse();
    }

    @Test
    void jiraBaseUrlIsRequired() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    token: ${JIRA_PAT}
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.jira.base_url is required");
    }

    @Test
    void bitbucketProjectIsRequired() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  bitbucket:
                    base_url: https://bitbucket.corp.local
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.bitbucket.project is required");
    }

    @Test
    void unsetTokenEnvVarDoesNotFailLoadingAndCarriesTheDeferredError() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    base_url: https://jira.corp.local
                    token: ${JIRA_PAT}
                """), k -> null);   // no env var resolves

        AtlassianSite jira = c.atlassian().jira();
        assertThat(jira.token()).isNull();
        assertThat(jira.tokenVar()).isEqualTo("JIRA_PAT");
        assertThat(jira.tokenError()).isEqualTo("atlassian.jira.token: environment variable JIRA_PAT is not set");
    }

    @Test
    void unsetJiraBaseUrlEnvVarFailsEagerlyNamingTheVar() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    base_url: ${JIRA_BASE_URL}
                """), k -> null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("JIRA_BASE_URL");
    }

    @Test
    void writeBackRejectsUnknownValue() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    base_url: https://jira.corp.local
                  write_back: delete
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("write_back");
    }

    @Test
    void proxyHostRequiredWhenProxyBlockPresent() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  proxy:
                    port: 8080
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.proxy.host is required");
    }

    @Test
    void proxyPortRequiredWhenProxyBlockPresent() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  proxy:
                    host: proxy.corp.local
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.proxy.port is required");
    }

    @Test
    void tlsTruststoreRequiredWhenTlsBlockPresent() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  tls:
                    truststore_password: ${CORP_TRUSTSTORE_PASSWORD}
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.tls.truststore is required");
    }

    @Test
    void nonIntegerFollowDepthFailsNamingKeyAndValue() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    base_url: https://jira.corp.local
                  follow_depth: x
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.follow_depth must be an integer, got 'x'");
    }

    @Test
    void nonBooleanPullRequestsFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    base_url: https://jira.corp.local
                  pull_requests: maybe
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.pull_requests");
    }

    @Test
    void rejectsNullValueInExtraBody() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write("""
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: deepseek-v4-flash
                    api_key: ${DEEPSEEK_API_KEY}
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                    extra_body:
                      some_key:
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("extra_body")
                .hasMessageContaining("null");
    }
}
