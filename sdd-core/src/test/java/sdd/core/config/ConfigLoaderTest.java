package sdd.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.http.TlsConfig;

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

    // --- models.<name>.tls (mutual-TLS client-certificate auth) --------------------------------

    @Test
    void tlsIsNullWhenNoTlsBlockIsConfigured() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL), ENV);
        assertThat(c.models().get("planner").tls()).isNull();
        assertThat(c.models().get("coder").tls()).isNull();
    }

    @Test
    void parsesTlsBlockWithEveryKeyAndResolvesEnvRefs() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: ${MODEL_CERT_PATH}
                      key: ${MODEL_KEY_PATH}
                      key_password: ${MODEL_KEY_PASSWORD}
                      protocols: [TLSv1.2]
                      truststore: /etc/ssl/corp-ca.p12
                """), k -> switch (k) {
                    case "MODEL_CERT_PATH" -> "/certs/client.crt";
                    case "MODEL_KEY_PATH" -> "/certs/client.key";
                    case "MODEL_KEY_PASSWORD" -> "s3cret";
                    default -> ENV.apply(k);
                });

        TlsConfig tls = c.models().get("corp").tls();
        assertThat(tls).isNotNull();
        assertThat(tls.clientCert()).isEqualTo(Path.of("/certs/client.crt"));
        assertThat(tls.clientKey()).isEqualTo(Path.of("/certs/client.key"));
        assertThat(tls.keyPassword()).isEqualTo("s3cret");
        assertThat(tls.keyPasswordError()).isNull();
        assertThat(tls.protocols()).containsExactly("TLSv1.2");
        assertThat(tls.truststore()).isEqualTo(Path.of("/etc/ssl/corp-ca.p12"));
    }

    @Test
    void tlsBlockDefaultsKeyPasswordProtocolsAndTruststoreWhenOmitted() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: /certs/client.crt
                      key: /certs/client.key
                """), ENV);

        TlsConfig tls = c.models().get("corp").tls();
        assertThat(tls.keyPassword()).isNull();
        assertThat(tls.keyPasswordError()).isNull();
        assertThat(tls.protocols()).isEmpty();
        assertThat(tls.truststore()).isNull();
    }

    // Fix 1 (Gate re-review): models.<name>.tls had no truststore_password key at all, so the
    // documented remedy for the plan's #1 predicted failure (curl trusts the OS store, the JDK
    // trusts only its own cacerts — set tls.truststore) could never actually be satisfied by a
    // keytool-produced store, since keytool itself refuses to create a password-less one
    // ("Keystore password must be at least 6 characters"). These mirror the equivalent
    // atlassian.tls.truststore_password tests above exactly.

    @Test
    void truststorePasswordDefaultsNullWhenOmitted() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: /certs/client.crt
                      key: /certs/client.key
                      truststore: /etc/ssl/corp-ca.p12
                """), ENV);

        TlsConfig tls = c.models().get("corp").tls();
        assertThat(tls.truststorePassword()).isNull();
        assertThat(tls.truststorePasswordError()).isNull();
    }

    @Test
    void unsetTruststorePasswordEnvVarDoesNotFailModelConfigLoadingAndCarriesTheDeferredError() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: /certs/client.crt
                      key: /certs/client.key
                      truststore: /etc/ssl/corp-ca.p12
                      truststore_password: ${MODEL_TRUSTSTORE_PASSWORD}
                """), k -> null);   // no env var resolves — including api_key's own DEEPSEEK_API_KEY,
                                     // which is fine, exactly as unsetKeyPasswordEnvVarLoadsSuccessfullyAndCarriesTheErrorOnTls
                                     // above already relies on.

        TlsConfig tls = c.models().get("corp").tls();
        assertThat(tls.truststorePassword()).isNull();
        assertThat(tls.truststorePasswordError()).isEqualTo("models.corp.tls.truststore_password: "
                + "environment variable MODEL_TRUSTSTORE_PASSWORD is not set");
    }

    @Test
    void truststorePasswordParsedFromConfigActuallyOpensAPasswordProtectedTruststore() throws Exception {
        // The regression this whole fix closes: ConfigLoader used to hardcode `null, null` for the
        // truststore password here, so HttpClients.trustManagers always opened a model truststore
        // with an empty password — which works only for a password-less store, and keytool refuses
        // to create one. This builds a REAL password-protected PKCS12 store (6+ character password,
        // same as keytool would insist on), parses it through the ordinary ConfigLoader.load path,
        // and proves HttpClients can actually open it — not merely that a string got parsed.
        Path p12 = ws.resolve("corp-ca.p12");
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        ks.load(null, "s3cr3t-pw".toCharArray());
        try (var out = Files.newOutputStream(p12)) {
            ks.store(out, "s3cr3t-pw".toCharArray());
        }

        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: /certs/client.crt
                      key: /certs/client.key
                      truststore: %s
                      truststore_password: ${MODEL_TRUSTSTORE_PASSWORD}
                """.formatted(p12)),
                k -> "MODEL_TRUSTSTORE_PASSWORD".equals(k) ? "s3cr3t-pw" : ENV.apply(k));

        TlsConfig tls = c.models().get("corp").tls();
        assertThat(tls.truststorePassword()).isEqualTo("s3cr3t-pw");
        assertThat(tls.truststorePasswordError()).isNull();
        assertThat(sdd.core.http.HttpClients.trustManagers(tls)).isNotEmpty();
    }

    @Test
    void certWithoutKeyFailsNamingBothDottedKeys() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: /certs/client.crt
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("models.corp.tls.cert")
                .hasMessageContaining("models.corp.tls.key");
    }

    @Test
    void keyWithoutCertFailsNamingBothDottedKeys() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      key: /certs/client.key
                """), ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("models.corp.tls.cert")
                .hasMessageContaining("models.corp.tls.key");
    }

    // The regression that matters most for key_password specifically: sdd status/index/clean never
    // open a model's client key, so an unset ${MODEL_KEY_PASSWORD} must not fail config load —
    // exactly the deferred-credential idiom api_key already follows above.
    @Test
    void unsetKeyPasswordEnvVarLoadsSuccessfullyAndCarriesTheErrorOnTls() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    tls:
                      cert: /certs/client.crt
                      key: /certs/client.key
                      key_password: ${MODEL_KEY_PASSWORD}
                """), k -> null);   // no env var resolves — including api_key's own DEEPSEEK_API_KEY,
                                     // which is fine: MINIMAL's coder has no api_key and planner's
                                     // is deferred too, so nothing here fails config load eagerly.

        TlsConfig tls = c.models().get("corp").tls();
        assertThat(tls.keyPassword()).isNull();
        assertThat(tls.keyPasswordError())
                .isEqualTo("models.corp.tls.key_password: environment variable MODEL_KEY_PASSWORD is not set");
    }

    @Test
    void anEndpointMayCarryBothApiKeyAndTls() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                  corp:
                    base_url: https://corp-ift.example/v1
                    model: DeepSeek-V4-Flash
                    api_key: ${DEEPSEEK_API_KEY}
                    tls:
                      cert: /certs/client.crt
                      key: /certs/client.key
                """), ENV);

        ModelEndpoint corp = c.models().get("corp");
        assertThat(corp.apiKey()).isEqualTo("sk-test-123");
        assertThat(corp.tls()).isNotNull();
        assertThat(corp.tls().clientCert()).isEqualTo(Path.of("/certs/client.crt"));
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
        assertThat(ac.tls().passwordError()).isNull();
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

    // Fix 1 (review): an unset truststore_password ${VAR} must not fail loading either — it is
    // shared by every configured site, but sdd index/status/clean never open an Atlassian
    // connection at all and must not be blocked by a credential none of them will ever use.
    @Test
    void unsetTruststorePasswordEnvVarDoesNotFailLoadingAndCarriesTheDeferredError() throws Exception {
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  tls:
                    truststore: /etc/ssl/corp-ca.jks
                    truststore_password: ${CORP_TRUSTSTORE_PASSWORD}
                  jira:
                    base_url: https://jira.corp.local
                """), k -> null);   // no env var resolves

        AtlassianTls tls = c.atlassian().tls();
        assertThat(tls.password()).isNull();
        assertThat(tls.passwordError()).isEqualTo(
                "atlassian.tls.truststore_password: environment variable CORP_TRUSTSTORE_PASSWORD is not set");
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
    void anEnvVarReferenceInProxyPortIsNeverResolvedIntoTheErrorMessage() throws Exception {
        // Gate re-review Fix 2: atlassian.proxy.port used to run the raw node through str(...,
        // env, ...) — which expands a ${VAR} reference — BEFORE parseInt saw it, so a
        // `port: ${JIRA_PAT}` config error printed the REAL RESOLVED TOKEN ("jira-token-abc") in
        // "... must be an integer, got '...'" — worse than the literal-node echo I4 fixed. The raw
        // "${JIRA_PAT}" reference itself is fine to appear (it is not a secret), but the resolved
        // value must never reach this message.
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  proxy:
                    host: proxy.corp.local
                    port: ${JIRA_PAT}
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.proxy.port must be an integer")
                .hasMessageNotContaining("jira-token-abc");
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
    void nonIntegerMaxPagesFailsNamingKeyAndValue() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    base_url: https://jira.corp.local
                  max_pages: many
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.max_pages must be an integer, got 'many'");
    }

    @Test
    void nonIntegerMaxLinkedIssuesFailsNamingKeyAndValue() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  jira:
                    base_url: https://jira.corp.local
                  max_linked_issues: many
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.max_linked_issues must be an integer, got 'many'");
    }

    @Test
    void nonListNoProxyFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  proxy:
                    host: proxy.corp.local
                    port: 8080
                    no_proxy: oops
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.proxy.no_proxy");
    }

    @Test
    void nonListDefaultReviewersFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + """
                atlassian:
                  bitbucket:
                    base_url: https://bitbucket.corp.local
                    project: TRADING
                    default_reviewers: oops
                """), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.bitbucket.default_reviewers");
    }

    @Test
    void nonMappingJiraFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "atlassian:\n  jira: oops\n"), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.jira");
    }

    @Test
    void aMalformedAtlassianJiraBlockContainingALiteralTokenDoesNotEchoItInTheErrorMessage() throws Exception {
        // Gate review I4: sdd.yml permits a LITERAL token (AtlassianSite.tokenVar() is then null),
        // so "atlassian.jira must be a mapping, got: <node>" printed that literal token straight to
        // stdout the moment a human pasted a raw PAT where a mapping belonged. The message must
        // name the problem without ever echoing the offending value.
        assertThatThrownBy(() -> ConfigLoader.load(
                write(MINIMAL + "atlassian:\n  jira: super-secret-jira-pat-xyz\n"), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.jira")
                .hasMessageNotContaining("super-secret-jira-pat-xyz");
    }

    @Test
    void nonMappingBitbucketFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "atlassian:\n  bitbucket: oops\n"), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.bitbucket");
    }

    @Test
    void nonMappingTlsFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "atlassian:\n  tls: oops\n"), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.tls");
    }

    @Test
    void nonMappingProxyFails() throws Exception {
        assertThatThrownBy(() -> ConfigLoader.load(write(MINIMAL + "atlassian:\n  proxy: oops\n"), ATLASSIAN_ENV))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("atlassian.proxy");
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
