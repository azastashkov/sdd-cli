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
        assertThat(c.retrieval()).isEqualTo("fts");
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
                retrieval: embeddings
                models:
                  planner:
                    base_url: https://api.deepseek.com/v1
                    model: deepseek-v4-flash
                    api_key: ${DEEPSEEK_API_KEY}
                    max_tokens: 16384
                  coder:
                    base_url: http://127.0.0.1:8080/v1
                    model: mlx-community/Qwen3.6-35B-A3B-8bit
                  embeddings:
                    base_url: http://127.0.0.1:8080/v1
                    model: some-embedding-model
                jdk_homes:
                  17: /opt/jdk17
                  21: /opt/jdk21
                excludes: [sandbox-repo]
                """), ENV);
        assertThat(c.retrieval()).isEqualTo("embeddings");
        assertThat(c.jdkHomes()).containsEntry(17, Path.of("/opt/jdk17"));
        assertThat(c.excludes()).containsExactly("sandbox-repo");
    }

    @Test
    void embeddingsRetrievalRequiresEmbeddingsEndpoint() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "retrieval: embeddings\n"), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("embeddings");
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(() -> ConfigLoader.load(ws, ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("sdd.yml");
    }

    @Test
    void missingEnvVarFailsWithVarName() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL), k -> null))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("DEEPSEEK_API_KEY");
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
                .hasMessageContaining("retrieval");
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
                """);
        SddConfig config = ConfigLoader.load(ws);
        assertThat(config.run()).isEqualTo(new RunSettings(4, 1, 5_000_000L, 20));
    }

    @Test
    void runSectionDefaultsWhenAbsentOrPartial() throws Exception {
        Path absent = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                """);
        assertThat(ConfigLoader.load(absent).run()).isEqualTo(RunSettings.defaults());
        assertThat(RunSettings.defaults()).isEqualTo(new RunSettings(2, 2, 30_000_000L, 40));

        Path partial = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  gradle_workers: 8
                """);
        assertThat(ConfigLoader.load(partial).run()).isEqualTo(new RunSettings(8, 2, 30_000_000L, 40));
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
}
