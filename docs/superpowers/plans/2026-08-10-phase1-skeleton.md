# Phase 1 — Project Skeleton & Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the runnable foundation of the `sdd` CLI: Gradle multi-module project, config loading, SQLite knowledge-base bootstrap, the OpenAI-compatible model client, the Retriever seam, `sdd doctor`, the fixture-repo test harness, and the local-model runtime setup.

**Architecture:** Java 21 Gradle multi-module build (`sdd-core` for shared infra — a deliberate addition to the spec's four modules so `index`/`plan`/`agent` don't depend on each other for plumbing — plus `sdd-index`, `sdd-plan`, `sdd-agent`, `sdd-cli`). Everything model-facing goes through the `ChatModel` interface; everything fuzzy-retrieval goes through `Retriever`. Workspace state lives in `<workspace>/.sdd/index.db`.

**Tech Stack:** Java 21, Gradle 8.10.2 (Kotlin DSL + version catalog), picocli 4.7.6, snakeyaml 2.2, sqlite-jdbc 3.46.1.3, jdbi3 3.45.4, jackson-databind 2.17.2, JGit 6.10, JUnit 5 + AssertJ, WireMock 3.9.1, mlx-lm (Python, serving only).

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md`. This is plan 1 of 5; Phases 2–5 (`index`, `plan`, `implement`, `review`) get their own plans after this one lands.

## Global Constraints

- Java **21** toolchain for every module; code may use records, sealed types, virtual threads.
- Secrets: `DEEPSEEK_API_KEY` only ever read from the environment; `.env` is git-ignored; **no key value appears in any committed file or in this plan**.
- Deterministic-first: no live model calls from tests; all HTTP tested against WireMock.
- Models config keys are exactly `planner`, `coder`, `embeddings` (spec: Model routing).
- Retrieval flag values are exactly `fts` and `embeddings`, default `fts` (spec: Retrieval).
- Workspace state path is exactly `<workspace>/.sdd/index.db` (spec: System shape).
- TDD for every code task; one commit per task minimum; commit messages `feat:`/`test:`/`chore:` style.
- No git pushes ever.

---

### Task 1: Gradle multi-module skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `sdd-core/build.gradle.kts`, `sdd-index/build.gradle.kts`, `sdd-plan/build.gradle.kts`, `sdd-agent/build.gradle.kts`, `sdd-cli/build.gradle.kts`
- Create: Gradle wrapper (`gradlew`, `gradle/wrapper/*`)

**Interfaces:**
- Consumes: nothing.
- Produces: the module layout and version catalog aliases (`libs.picocli`, `libs.snakeyaml`, `libs.sqlite.jdbc`, `libs.jdbi3`, `libs.jackson`, `libs.jgit`, `libs.wiremock`, `libs.bundles.test`) every later task depends on.

- [ ] **Step 1: Write the build files**

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "sdd"
include("sdd-core", "sdd-index", "sdd-plan", "sdd-agent", "sdd-cli")
```

`build.gradle.kts` (root):
```kotlin
subprojects {
    apply(plugin = "java")
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}
```

`gradle/libs.versions.toml`:
```toml
[versions]
picocli = "4.7.6"
snakeyaml = "2.2"
sqlite-jdbc = "3.46.1.3"
jdbi3 = "3.45.4"
jackson = "2.17.2"
jgit = "6.10.0.202406032230-r"
junit = "5.10.3"
assertj = "3.26.3"
wiremock = "3.9.1"

[libraries]
picocli = { module = "info.picocli:picocli", version.ref = "picocli" }
snakeyaml = { module = "org.yaml:snakeyaml", version.ref = "snakeyaml" }
sqlite-jdbc = { module = "org.xerial:sqlite-jdbc", version.ref = "sqlite-jdbc" }
jdbi3 = { module = "org.jdbi:jdbi3-core", version.ref = "jdbi3" }
jackson = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
jgit = { module = "org.eclipse.jgit:org.eclipse.jgit", version.ref = "jgit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }
wiremock = { module = "org.wiremock:wiremock", version.ref = "wiremock" }

[bundles]
test = ["junit-jupiter", "assertj"]
```

`sdd-core/build.gradle.kts`:
```kotlin
plugins {
    `java-library`
    `java-test-fixtures`
}
dependencies {
    api(libs.jdbi3)
    implementation(libs.snakeyaml)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jackson)
    testFixturesApi(libs.jgit)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.wiremock)
}
```

`sdd-cli/build.gradle.kts`:
```kotlin
plugins {
    application
}
dependencies {
    implementation(project(":sdd-core"))
    implementation(libs.picocli)
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.wiremock)
    testImplementation(testFixtures(project(":sdd-core")))
}
application { mainClass.set("sdd.cli.SddCli") }
```

`sdd-index/build.gradle.kts`, `sdd-plan/build.gradle.kts`, `sdd-agent/build.gradle.kts` (identical for now):
```kotlin
plugins { `java-library` }
dependencies {
    api(project(":sdd-core"))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}
```

- [ ] **Step 2: Generate the wrapper**

Run: `gradle wrapper --gradle-version 8.10.2` (if `gradle` is missing: `brew install gradle`, then rerun).
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` created.

- [ ] **Step 3: Verify the build is green**

Run: `./gradlew build --quiet`
Expected: BUILD SUCCESSFUL (all modules compile; zero tests is fine at this point).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: gradle multi-module skeleton (core/index/plan/agent/cli)"
```

---

### Task 2: Config loading (`sdd.yml`)

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/config/SddConfig.java`
- Create: `sdd-core/src/main/java/sdd/core/config/ModelEndpoint.java`
- Create: `sdd-core/src/main/java/sdd/core/config/ConfigException.java`
- Create: `sdd-core/src/main/java/sdd/core/config/ConfigLoader.java`
- Test: `sdd-core/src/test/java/sdd/core/config/ConfigLoaderTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `SddConfig ConfigLoader.load(Path workspace)` and `SddConfig ConfigLoader.load(Path workspace, Function<String,String> env)`; throws `ConfigException` (message includes the problem and, for env vars, the variable name).
  - `record SddConfig(Path workspace, String retrieval, Map<String,ModelEndpoint> models, Map<Integer,Path> jdkHomes, List<String> excludes)`
  - `record ModelEndpoint(String baseUrl, String model, String apiKey, int maxTokens, double temperature, Duration timeout)` — `apiKey` is `null` when absent.

- [ ] **Step 1: Write the failing tests**

`ConfigLoaderTest.java`:
```java
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
        SddConfig c = ConfigLoader.load(write(MINIMAL + """
                retrieval: embeddings
                models_extra_ignored: {}
                jdk_homes:
                  17: /opt/jdk17
                  21: /opt/jdk21
                excludes: [sandbox-repo]
                """.replace("models_extra_ignored: {}\n", "")
                // embeddings retrieval requires an embeddings endpoint:
                + """
                """), envWithEmbeddings());
        assertThat(c.retrieval()).isEqualTo("embeddings");
        assertThat(c.jdkHomes()).containsEntry(17, Path.of("/opt/jdk17"));
        assertThat(c.excludes()).containsExactly("sandbox-repo");
    }

    private Function<String, String> envWithEmbeddings() { return ENV; }

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
}
```

Note for the `parsesOptionalSections` test: the `retrieval: embeddings` line there needs an `embeddings` model in the YAML — add this block to that test's YAML string so it passes validation:

```yaml
  embeddings:
    base_url: http://127.0.0.1:8080/v1
    model: some-embedding-model
```
(i.e. build the YAML for that test as `MINIMAL` with the `embeddings:` entry appended under `models:` — write it as one literal string in the test rather than concatenating fragments if that is clearer.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.config.*'`
Expected: FAIL — classes don't exist / compilation error.

- [ ] **Step 3: Implement**

`ConfigException.java`:
```java
package sdd.core.config;

public class ConfigException extends RuntimeException {
    public ConfigException(String message) { super(message); }
    public ConfigException(String message, Throwable cause) { super(message, cause); }
}
```

`ModelEndpoint.java`:
```java
package sdd.core.config;

import java.time.Duration;

public record ModelEndpoint(
        String baseUrl,
        String model,
        String apiKey,
        int maxTokens,
        double temperature,
        Duration timeout) {}
```

`SddConfig.java`:
```java
package sdd.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record SddConfig(
        Path workspace,
        String retrieval,
        Map<String, ModelEndpoint> models,
        Map<Integer, Path> jdkHomes,
        List<String> excludes) {}
```

`ConfigLoader.java`:
```java
package sdd.core.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final double DEFAULT_TEMPERATURE = 0.15;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);

    private ConfigLoader() {}

    public static SddConfig load(Path workspace) {
        return load(workspace, System::getenv);
    }

    public static SddConfig load(Path workspace, Function<String, String> env) {
        Path file = workspace.resolve("sdd.yml");
        if (!Files.isRegularFile(file)) {
            throw new ConfigException("sdd.yml not found in " + workspace);
        }
        Map<String, Object> root = parseYaml(file);

        String retrieval = str(root.getOrDefault("retrieval", "fts"), env, "retrieval");
        if (!retrieval.equals("fts") && !retrieval.equals("embeddings")) {
            throw new ConfigException("retrieval must be 'fts' or 'embeddings', got '" + retrieval + "'");
        }

        Map<String, ModelEndpoint> models = new LinkedHashMap<>();
        Object modelsNode = root.get("models");
        if (modelsNode instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                models.put(String.valueOf(e.getKey()),
                        endpoint(String.valueOf(e.getKey()), e.getValue(), env));
            }
        }
        for (String required : List.of("planner", "coder")) {
            if (!models.containsKey(required)) {
                throw new ConfigException("models." + required + " is required");
            }
        }
        if (retrieval.equals("embeddings") && !models.containsKey("embeddings")) {
            throw new ConfigException("retrieval=embeddings requires a models.embeddings endpoint");
        }

        Map<Integer, Path> jdkHomes = new LinkedHashMap<>();
        if (root.get("jdk_homes") instanceof Map<?, ?> jm) {
            for (Map.Entry<?, ?> e : jm.entrySet()) {
                jdkHomes.put(Integer.parseInt(String.valueOf(e.getKey())),
                        Path.of(str(e.getValue(), env, "jdk_homes")));
            }
        }

        List<String> excludes = root.get("excludes") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList()
                : List.of();

        return new SddConfig(workspace, retrieval, Map.copyOf(models), Map.copyOf(jdkHomes), excludes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(Path file) {
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(Files.readString(file));
            if (!(parsed instanceof Map)) {
                throw new ConfigException("sdd.yml must be a YAML mapping");
            }
            return (Map<String, Object>) parsed;
        } catch (IOException e) {
            throw new ConfigException("cannot read " + file, e);
        }
    }

    private static ModelEndpoint endpoint(String name, Object node, Function<String, String> env) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new ConfigException("models." + name + " must be a mapping");
        }
        String baseUrl = required(m, "base_url", name, env);
        String model = required(m, "model", name, env);
        Object rawKey = m.get("api_key");
        String apiKey = rawKey == null ? null : str(rawKey, env, "models." + name + ".api_key");
        int maxTokens = m.get("max_tokens") == null
                ? DEFAULT_MAX_TOKENS : Integer.parseInt(String.valueOf(m.get("max_tokens")));
        double temperature = m.get("temperature") == null
                ? DEFAULT_TEMPERATURE : Double.parseDouble(String.valueOf(m.get("temperature")));
        Duration timeout = m.get("timeout_seconds") == null
                ? DEFAULT_TIMEOUT : Duration.ofSeconds(Long.parseLong(String.valueOf(m.get("timeout_seconds"))));
        return new ModelEndpoint(baseUrl, model, apiKey, maxTokens, temperature, timeout);
    }

    private static String required(Map<?, ?> m, String key, String endpointName, Function<String, String> env) {
        Object v = m.get(key);
        if (v == null) {
            throw new ConfigException("models." + endpointName + "." + key + " is required");
        }
        return str(v, env, "models." + endpointName + "." + key);
    }

    private static String str(Object value, Function<String, String> env, String where) {
        String s = String.valueOf(value);
        Matcher matcher = ENV_REF.matcher(s);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String var = matcher.group(1);
            String resolved = env.apply(var);
            if (resolved == null) {
                throw new ConfigException(where + ": environment variable " + var + " is not set");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.config.*'`
Expected: PASS (all 7 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: sdd.yml config loading with env interpolation and validation"
```

---

### Task 3: SQLite knowledge base bootstrap + V1 schema

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/db/Database.java`
- Create: `sdd-core/src/main/resources/sdd/db/V1__init.sql`
- Test: `sdd-core/src/test/java/sdd/core/db/DatabaseTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `Database Database.open(Path workspace)` — creates `<workspace>/.sdd/index.db`, WAL mode, foreign keys ON, applies pending migrations; `Jdbi jdbi()`, `int schemaVersion()`, `void close()`. `Database` implements `AutoCloseable`.
  - The V1 schema: tables `repo, module, artifact, dep_edge, java_type, api_member, api_usage, file_ref, rest_endpoint, rest_client, rest_call_edge, kafka_topic, kafka_role, config_property, repo_card, meta`, FTS5 table `fts_symbol`, view `v_repo_dep_edge`. Phase 2 (indexer) fills them; Phase 3 (planner) queries them.

- [ ] **Step 1: Write the failing tests**

`DatabaseTest.java`:
```java
package sdd.core.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTest {
    @TempDir Path ws;

    @Test
    void createsDbFileAndAppliesSchema() throws Exception {
        try (Database db = Database.open(ws)) {
            assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isTrue();
            assertThat(db.schemaVersion()).isEqualTo(1);
            List<String> tables = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')")
                            .mapTo(String.class).list());
            assertThat(tables).contains("repo", "module", "artifact", "dep_edge",
                    "java_type", "api_member", "api_usage", "file_ref",
                    "rest_endpoint", "rest_client", "rest_call_edge",
                    "kafka_topic", "kafka_role", "config_property", "repo_card",
                    "meta", "fts_symbol", "v_repo_dep_edge");
        }
    }

    @Test
    void reopenIsIdempotent() throws Exception {
        try (Database first = Database.open(ws)) {
            first.jdbi().withHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('r1', '/x', 'SERVICE')"));
        }
        try (Database again = Database.open(ws)) {
            assertThat(again.schemaVersion()).isEqualTo(1);
            Integer count = again.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM repo").mapTo(Integer.class).one());
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void foreignKeysAreEnforced() throws Exception {
        try (Database db = Database.open(ws)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    db.jdbi().withHandle(h -> h.execute(
                            "INSERT INTO module(repo_id, gradle_path, kind) VALUES (999, ':', 'LIBRARY')")))
                    .hasMessageContaining("FOREIGN KEY");
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.db.*'`
Expected: FAIL — `Database` doesn't exist.

- [ ] **Step 3: Implement**

`sdd-core/src/main/resources/sdd/db/V1__init.sql` (statements separated by `;` on a line of its own — the runner splits on that):
```sql
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
;
CREATE TABLE repo(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  path TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'UNKNOWN',
  head_commit TEXT,
  branch TEXT,
  dirty_hash TEXT,
  gradle_status TEXT,
  parse_status TEXT,
  error TEXT,
  indexed_at TEXT);
;
CREATE TABLE module(
  id INTEGER PRIMARY KEY,
  repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  gradle_path TEXT NOT NULL,
  grp TEXT,
  name TEXT,
  version TEXT,
  kind TEXT NOT NULL DEFAULT 'UNKNOWN',
  spring_app_name TEXT,
  context_path TEXT);
;
CREATE TABLE artifact(
  id INTEGER PRIMARY KEY,
  grp TEXT NOT NULL,
  name TEXT NOT NULL,
  module_id INTEGER REFERENCES module(id) ON DELETE CASCADE,
  UNIQUE(grp, name));
;
CREATE TABLE dep_edge(
  id INTEGER PRIMARY KEY,
  from_module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  to_grp TEXT NOT NULL,
  to_name TEXT NOT NULL,
  configuration TEXT,
  declared_version TEXT,
  resolved_version TEXT,
  declared_via TEXT,
  mode TEXT,
  is_internal INTEGER NOT NULL DEFAULT 0,
  to_module_id INTEGER REFERENCES module(id));
;
CREATE INDEX ix_dep_to ON dep_edge(to_module_id) WHERE is_internal = 1;
;
CREATE TABLE java_type(
  id INTEGER PRIMARY KEY,
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  fqcn TEXT NOT NULL,
  kind TEXT,
  is_api INTEGER NOT NULL DEFAULT 0,
  file_path TEXT,
  signature_hash TEXT,
  api_confidence TEXT,
  annotations TEXT);
;
CREATE INDEX ix_type_fqcn ON java_type(fqcn);
;
CREATE TABLE api_member(
  id INTEGER PRIMARY KEY,
  type_id INTEGER NOT NULL REFERENCES java_type(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  signature TEXT,
  return_type TEXT,
  synthesized_by TEXT);
;
CREATE TABLE api_usage(
  from_module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  target_fqcn TEXT NOT NULL,
  target_module_id INTEGER REFERENCES module(id),
  ref_kind TEXT);
;
CREATE INDEX ix_usage_target ON api_usage(target_module_id, target_fqcn);
;
CREATE TABLE file_ref(
  repo_id INTEGER NOT NULL REFERENCES repo(id) ON DELETE CASCADE,
  src_file TEXT NOT NULL,
  dst_file TEXT NOT NULL,
  ref_count INTEGER NOT NULL DEFAULT 1);
;
CREATE INDEX ix_file_ref_src ON file_ref(repo_id, src_file);
;
CREATE TABLE rest_endpoint(
  id INTEGER PRIMARY KEY,
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  class_fqcn TEXT,
  method_name TEXT,
  http_method TEXT,
  path_template TEXT,
  norm_path TEXT,
  request_type TEXT,
  response_type TEXT,
  profile TEXT);
;
CREATE TABLE rest_client(
  id INTEGER PRIMARY KEY,
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  kind TEXT,
  class_fqcn TEXT,
  method_or_site TEXT,
  http_method TEXT,
  uri_template TEXT,
  norm_path TEXT,
  target_hint TEXT,
  resolution TEXT,
  raw_expr TEXT);
;
CREATE TABLE rest_call_edge(
  client_id INTEGER NOT NULL REFERENCES rest_client(id) ON DELETE CASCADE,
  endpoint_id INTEGER NOT NULL REFERENCES rest_endpoint(id) ON DELETE CASCADE,
  confidence TEXT,
  matched_by TEXT);
;
CREATE TABLE kafka_topic(
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  resolution TEXT);
;
CREATE TABLE kafka_role(
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  topic_id INTEGER NOT NULL REFERENCES kafka_topic(id),
  role TEXT NOT NULL,
  class_fqcn TEXT,
  group_id TEXT,
  payload_type TEXT);
;
CREATE INDEX ix_kafka ON kafka_role(topic_id, role);
;
CREATE TABLE config_property(
  module_id INTEGER NOT NULL REFERENCES module(id) ON DELETE CASCADE,
  key TEXT NOT NULL,
  value TEXT,
  profile TEXT,
  source_file TEXT);
;
CREATE TABLE repo_card(
  repo_id INTEGER PRIMARY KEY REFERENCES repo(id) ON DELETE CASCADE,
  card_md TEXT,
  card_line TEXT,
  model TEXT,
  input_hash TEXT,
  created_at TEXT);
;
CREATE VIRTUAL TABLE fts_symbol USING fts5(
  identifier,
  fqcn,
  module_id UNINDEXED,
  tokenize = "unicode61 tokenchars '_$'");
;
CREATE VIEW v_repo_dep_edge AS
  SELECT DISTINCT mf.repo_id AS from_repo_id,
                  mt.repo_id AS to_repo_id,
                  e.mode     AS mode,
                  e.declared_via AS declared_via
  FROM dep_edge e
  JOIN module mf ON mf.id = e.from_module_id
  JOIN module mt ON mt.id = e.to_module_id
  WHERE e.is_internal = 1 AND mf.repo_id <> mt.repo_id;
```

`Database.java`:
```java
package sdd.core.db;

import org.jdbi.v3.core.Jdbi;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Database implements AutoCloseable {
    private static final List<String> MIGRATIONS = List.of("V1__init.sql");

    private final Jdbi jdbi;
    private final int schemaVersion;

    private Database(Jdbi jdbi, int schemaVersion) {
        this.jdbi = jdbi;
        this.schemaVersion = schemaVersion;
    }

    public static Database open(Path workspace) {
        try {
            Files.createDirectories(workspace.resolve(".sdd"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + workspace.resolve(".sdd/index.db"));
        Jdbi jdbi = Jdbi.create(ds);
        int version = migrate(jdbi);
        return new Database(jdbi, version);
    }

    private static int migrate(Jdbi jdbi) {
        int current = jdbi.withHandle(h -> {
            boolean hasMeta = !h.createQuery(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")
                    .mapTo(String.class).list().isEmpty();
            if (!hasMeta) {
                return 0;
            }
            return h.createQuery("SELECT value FROM meta WHERE key='schema_version'")
                    .mapTo(Integer.class).findOne().orElse(0);
        });
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            String script = readResource("/sdd/db/" + MIGRATIONS.get(v - 1));
            int version = v;
            jdbi.useHandle(h -> {
                for (String statement : script.split("\\n;\\n")) {
                    if (!statement.isBlank()) {
                        h.execute(statement);
                    }
                }
                h.execute("INSERT INTO meta(key, value) VALUES ('schema_version', ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", version);
            });
        }
        return MIGRATIONS.size();
    }

    private static String readResource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing migration resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Jdbi jdbi() { return jdbi; }

    public int schemaVersion() { return schemaVersion; }

    @Override
    public void close() { /* pooled per-call connections; nothing to close */ }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.db.*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: sqlite knowledge base bootstrap with V1 schema and migrations"
```

---

### Task 4: ChatModel API + ScriptedChatModel test double

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/llm/ChatMessage.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/ToolCall.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/ToolSpec.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/ChatRequest.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/Usage.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/ChatResponse.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/ChatModel.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/ModelException.java`
- Create: `sdd-core/src/testFixtures/java/sdd/core/testing/ScriptedChatModel.java`
- Test: `sdd-core/src/test/java/sdd/core/llm/ScriptedChatModelTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (the seam every model interaction in Phases 2–5 goes through):
  - `interface ChatModel { ChatResponse complete(ChatRequest req) throws ModelException; }`
  - `record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId)` + statics `system(String)`, `user(String)`, `assistant(String)`, `tool(String toolCallId, String content)`
  - `record ToolCall(String id, String name, String argumentsJson)`
  - `record ToolSpec(String name, String description, String parametersSchemaJson)`
  - `record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools, Integer maxTokens, Double temperature)`
  - `record Usage(int promptTokens, int completionTokens)`
  - `record ChatResponse(ChatMessage message, String finishReason, Usage usage)`
  - `class ModelException extends RuntimeException` with `int statusCode()` (0 for transport errors)
  - Test fixture `ScriptedChatModel implements ChatModel`: constructor takes `List<ChatResponse>`; serves them in order; `List<ChatRequest> requests()` records every call; throws `IllegalStateException` when exhausted.

- [ ] **Step 1: Write the failing test**

`ScriptedChatModelTest.java`:
```java
package sdd.core.llm;

import org.junit.jupiter.api.Test;
import sdd.core.testing.ScriptedChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ScriptedChatModelTest {
    @Test
    void servesResponsesInOrderAndRecordsRequests() {
        ChatResponse first = new ChatResponse(ChatMessage.assistant("one"), "stop", new Usage(10, 2));
        ChatResponse second = new ChatResponse(ChatMessage.assistant("two"), "stop", new Usage(12, 3));
        ScriptedChatModel model = new ScriptedChatModel(List.of(first, second));

        ChatRequest req = new ChatRequest("m", List.of(ChatMessage.user("hi")), List.of(), 100, 0.0);
        assertThat(model.complete(req)).isSameAs(first);
        assertThat(model.complete(req)).isSameAs(second);
        assertThat(model.requests()).hasSize(2);
        assertThatThrownBy(() -> model.complete(req)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void messageFactoriesSetRoles() {
        assertThat(ChatMessage.system("s").role()).isEqualTo("system");
        assertThat(ChatMessage.user("u").role()).isEqualTo("user");
        assertThat(ChatMessage.assistant("a").role()).isEqualTo("assistant");
        ChatMessage tool = ChatMessage.tool("call-1", "result");
        assertThat(tool.role()).isEqualTo("tool");
        assertThat(tool.toolCallId()).isEqualTo("call-1");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.*'`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement**

`ChatMessage.java`:
```java
package sdd.core.llm;

import java.util.List;

public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public static ChatMessage system(String content) { return new ChatMessage("system", content, List.of(), null); }
    public static ChatMessage user(String content) { return new ChatMessage("user", content, List.of(), null); }
    public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content, List.of(), null); }
    public static ChatMessage tool(String toolCallId, String content) { return new ChatMessage("tool", content, List.of(), toolCallId); }
}
```

`ToolCall.java`:
```java
package sdd.core.llm;

public record ToolCall(String id, String name, String argumentsJson) {}
```

`ToolSpec.java`:
```java
package sdd.core.llm;

public record ToolSpec(String name, String description, String parametersSchemaJson) {}
```

`ChatRequest.java`:
```java
package sdd.core.llm;

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools,
                          Integer maxTokens, Double temperature) {}
```

`Usage.java`:
```java
package sdd.core.llm;

public record Usage(int promptTokens, int completionTokens) {}
```

`ChatResponse.java`:
```java
package sdd.core.llm;

public record ChatResponse(ChatMessage message, String finishReason, Usage usage) {}
```

`ChatModel.java`:
```java
package sdd.core.llm;

public interface ChatModel {
    ChatResponse complete(ChatRequest req) throws ModelException;
}
```

`ModelException.java`:
```java
package sdd.core.llm;

public class ModelException extends RuntimeException {
    private final int statusCode;

    public ModelException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int statusCode() { return statusCode; }
}
```

`ScriptedChatModel.java` (in `src/testFixtures/java/sdd/core/testing/`):
```java
package sdd.core.testing;

import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ScriptedChatModel implements ChatModel {
    private final Deque<ChatResponse> script;
    private final List<ChatRequest> requests = new ArrayList<>();

    public ScriptedChatModel(List<ChatResponse> responses) {
        this.script = new ArrayDeque<>(responses);
    }

    @Override
    public ChatResponse complete(ChatRequest req) {
        requests.add(req);
        ChatResponse next = script.poll();
        if (next == null) {
            throw new IllegalStateException("ScriptedChatModel exhausted after " + requests.size() + " calls");
        }
        return next;
    }

    public List<ChatRequest> requests() { return List.copyOf(requests); }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: ChatModel API records and ScriptedChatModel test fixture"
```

---

### Task 5: HttpChatModel (OpenAI-compatible client)

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/llm/HttpChatModel.java`
- Test: `sdd-core/src/test/java/sdd/core/llm/HttpChatModelTest.java`

**Interfaces:**
- Consumes: `ModelEndpoint` (Task 2), ChatModel records (Task 4).
- Produces:
  - `new HttpChatModel(ModelEndpoint endpoint)` — production constructor.
  - `new HttpChatModel(ModelEndpoint endpoint, HttpClient client, Sleeper sleeper)` — test constructor.
  - `interface HttpChatModel.Sleeper { void sleep(long millis) throws InterruptedException; }`
  - Behavior contract: retries 5xx/IO up to 6 attempts (250 ms base, ×2, jitter, 60 s cap); honors `Retry-After` on 429; throws `ModelException` with status code for other 4xx **without retrying**; maps `choices[0].message` (content + `tool_calls`), `finish_reason`, `usage`.

- [ ] **Step 1: Write the failing tests**

`HttpChatModelTest.java`:
```java
package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

class HttpChatModelTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private static final String OK_BODY = """
            {"choices":[{"message":{"role":"assistant","content":"hello",
              "tool_calls":[{"id":"c1","type":"function",
                "function":{"name":"read_file","arguments":"{\\"path\\":\\"A.java\\"}"}}]},
              "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":42,"completion_tokens":7}}
            """;

    private HttpChatModel model() {
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
                256, 0.0, Duration.ofSeconds(5));
        return new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { });
    }

    private static ChatRequest request() {
        return new ChatRequest("test-model", List.of(ChatMessage.user("hi")), List.of(), 256, 0.0);
    }

    @Test
    void parsesContentToolCallsUsageAndFinishReason() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        ChatResponse resp = model().complete(request());

        assertThat(resp.message().content()).isEqualTo("hello");
        assertThat(resp.message().toolCalls()).hasSize(1);
        assertThat(resp.message().toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(resp.finishReason()).isEqualTo("tool_calls");
        assertThat(resp.usage().promptTokens()).isEqualTo(42);

        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("256"))));
    }

    @Test
    void retriesOn5xxThenSucceeds() {
        wm.stubFor(post("/v1/chat/completions").inScenario("flaky")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(serverError()).willSetStateTo("second"));
        wm.stubFor(post("/v1/chat/completions").inScenario("flaky")
                .whenScenarioStateIs("second").willReturn(okJson(OK_BODY)));

        assertThat(model().complete(request()).message().content()).isEqualTo("hello");
        wm.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void doesNotRetry400AndCarriesStatus() {
        wm.stubFor(post("/v1/chat/completions").willReturn(badRequest().withBody("context too long")));

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class)
                .satisfies(e -> assertThat(((ModelException) e).statusCode()).isEqualTo(400))
                .hasMessageContaining("context too long");
        wm.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void failsAfterSixAttemptsOnPersistent5xx() {
        wm.stubFor(post("/v1/chat/completions").willReturn(serverError()));

        assertThatThrownBy(() -> model().complete(request()))
                .isInstanceOf(ModelException.class);
        wm.verify(6, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void omitsAuthorizationHeaderWhenNoApiKey() {
        wm.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", null,
                256, 0.0, Duration.ofSeconds(5));
        new HttpChatModel(ep, HttpClient.newHttpClient(), millis -> { }).complete(request());

        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withoutHeader("Authorization"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.HttpChatModelTest'`
Expected: FAIL — `HttpChatModel` doesn't exist.

- [ ] **Step 3: Implement**

`HttpChatModel.java`:
```java
package sdd.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sdd.core.config.ModelEndpoint;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class HttpChatModel implements ChatModel {
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private static final int MAX_ATTEMPTS = 6;
    private static final long BASE_BACKOFF_MILLIS = 250;
    private static final long MAX_BACKOFF_MILLIS = 60_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ModelEndpoint endpoint;
    private final HttpClient client;
    private final Sleeper sleeper;

    public HttpChatModel(ModelEndpoint endpoint) {
        this(endpoint, HttpClient.newHttpClient(), Thread::sleep);
    }

    public HttpChatModel(ModelEndpoint endpoint, HttpClient client, Sleeper sleeper) {
        this.endpoint = endpoint;
        this.client = client;
        this.sleeper = sleeper;
    }

    @Override
    public ChatResponse complete(ChatRequest req) {
        String body = toJson(req);
        ModelException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = send(body);
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return parse(resp.body());
                }
                if (status == 429) {
                    last = new ModelException("HTTP 429: " + resp.body(), status);
                    backoff(attempt, retryAfterMillis(resp));
                    continue;
                }
                if (status >= 500) {
                    last = new ModelException("HTTP " + status + ": " + resp.body(), status);
                    backoff(attempt, null);
                    continue;
                }
                throw new ModelException("HTTP " + status + ": " + resp.body(), status);
            } catch (IOException e) {
                last = new ModelException("transport error: " + e.getMessage(), e);
                backoff(attempt, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException("interrupted", e);
            }
        }
        throw last;
    }

    private HttpResponse<String> send(String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.baseUrl() + "/chat/completions"))
                .timeout(endpoint.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (endpoint.apiKey() != null) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void backoff(int attempt, Long retryAfterMillis) {
        if (attempt >= MAX_ATTEMPTS) {
            return;
        }
        long delay = retryAfterMillis != null
                ? retryAfterMillis
                : Math.min(MAX_BACKOFF_MILLIS,
                        BASE_BACKOFF_MILLIS * (1L << (attempt - 1))
                                + ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MILLIS));
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("interrupted during backoff", e);
        }
    }

    private static Long retryAfterMillis(HttpResponse<String> resp) {
        return resp.headers().firstValue("Retry-After")
                .map(v -> Long.parseLong(v.trim()) * 1000L).orElse(null);
    }

    private String toJson(ChatRequest req) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", req.model());
        ArrayNode messages = root.putArray("messages");
        for (ChatMessage m : req.messages()) {
            ObjectNode msg = messages.addObject();
            msg.put("role", m.role());
            if (m.content() != null) {
                msg.put("content", m.content());
            }
            if (m.toolCallId() != null) {
                msg.put("tool_call_id", m.toolCallId());
            }
            if (!m.toolCalls().isEmpty()) {
                ArrayNode calls = msg.putArray("tool_calls");
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", c.id());
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", c.name());
                    fn.put("arguments", c.argumentsJson());
                }
            }
        }
        if (!req.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolSpec t : req.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                try {
                    fn.set("parameters", JSON.readTree(t.parametersSchemaJson()));
                } catch (IOException e) {
                    throw new ModelException("bad tool schema for " + t.name(), e);
                }
            }
        }
        if (req.maxTokens() != null) {
            root.put("max_tokens", req.maxTokens());
        }
        if (req.temperature() != null) {
            root.put("temperature", req.temperature());
        }
        return root.toString();
    }

    private ChatResponse parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            List<ToolCall> toolCalls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                toolCalls.add(new ToolCall(
                        call.path("id").asText(),
                        call.path("function").path("name").asText(),
                        call.path("function").path("arguments").asText()));
            }
            ChatMessage msg = new ChatMessage("assistant",
                    message.path("content").isNull() ? null : message.path("content").asText(),
                    List.copyOf(toolCalls), null);
            Usage usage = new Usage(
                    root.path("usage").path("prompt_tokens").asInt(),
                    root.path("usage").path("completion_tokens").asInt());
            return new ChatResponse(msg, choice.path("finish_reason").asText(), usage);
        } catch (IOException e) {
            throw new ModelException("unparseable model response: " + body, e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.HttpChatModelTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: OpenAI-compatible HttpChatModel with retry/backoff and tool-call parsing"
```

---

### Task 6: Retriever seam + FtsRetriever

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/retrieve/Retriever.java`
- Create: `sdd-core/src/main/java/sdd/core/retrieve/Hit.java`
- Create: `sdd-core/src/main/java/sdd/core/retrieve/FtsRetriever.java`
- Test: `sdd-core/src/test/java/sdd/core/retrieve/FtsRetrieverTest.java`

**Interfaces:**
- Consumes: `Database` (Task 3).
- Produces:
  - `interface Retriever { List<Hit> search(String query, int limit); }`
  - `record Hit(String identifier, String fqcn, long moduleId, double score)`
  - `new FtsRetriever(Jdbi jdbi)` — queries `fts_symbol`; free text is tokenized on non-alphanumerics, each token double-quoted, joined with `OR` (never raw user text into FTS MATCH). Empty/blank query returns an empty list. The Phase-2 indexer populates `fts_symbol`; the embeddings alternative arrives in a later plan behind this same interface.

- [ ] **Step 1: Write the failing tests**

`FtsRetrieverTest.java`:
```java
package sdd.core.retrieve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FtsRetrieverTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, module_id) VALUES "
                    + "('PriceCalculator', 'com.acme.pricing.PriceCalculator', 1)");
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, module_id) VALUES "
                    + "('LoyaltyTier', 'com.acme.pricing.LoyaltyTier', 1)");
            h.execute("INSERT INTO fts_symbol(identifier, fqcn, module_id) VALUES "
                    + "('OrderController', 'com.acme.orders.OrderController', 2)");
        });
    }

    @Test
    void findsByIdentifierToken() {
        List<Hit> hits = new FtsRetriever(db.jdbi()).search("loyalty tier pricing", 10);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).fqcn()).isEqualTo("com.acme.pricing.LoyaltyTier");
    }

    @Test
    void limitIsRespected() {
        assertThat(new FtsRetriever(db.jdbi()).search("com.acme", 1)).hasSize(1);
    }

    @Test
    void blankQueryReturnsEmpty() {
        assertThat(new FtsRetriever(db.jdbi()).search("   ", 10)).isEmpty();
    }

    @Test
    void punctuationOnlyQueryReturnsEmptyInsteadOfThrowing() {
        assertThat(new FtsRetriever(db.jdbi()).search("...///:::", 10)).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.retrieve.*'`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement**

`Retriever.java`:
```java
package sdd.core.retrieve;

import java.util.List;

public interface Retriever {
    List<Hit> search(String query, int limit);
}
```

`Hit.java`:
```java
package sdd.core.retrieve;

public record Hit(String identifier, String fqcn, long moduleId, double score) {}
```

`FtsRetriever.java`:
```java
package sdd.core.retrieve;

import org.jdbi.v3.core.Jdbi;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class FtsRetriever implements Retriever {
    private final Jdbi jdbi;

    public FtsRetriever(Jdbi jdbi) { this.jdbi = jdbi; }

    @Override
    public List<Hit> search(String query, int limit) {
        String match = Arrays.stream(query.split("[^A-Za-z0-9_$]+"))
                .filter(t -> !t.isBlank())
                .map(t -> '"' + t + '"')
                .collect(Collectors.joining(" OR "));
        if (match.isEmpty()) {
            return List.of();
        }
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT identifier, fqcn, module_id, bm25(fts_symbol) AS score
                        FROM fts_symbol WHERE fts_symbol MATCH :match
                        ORDER BY score LIMIT :limit""")
                .bind("match", match)
                .bind("limit", limit)
                .map((rs, ctx) -> new Hit(
                        rs.getString("identifier"),
                        rs.getString("fqcn"),
                        rs.getLong("module_id"),
                        rs.getDouble("score")))
                .list());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.retrieve.*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: Retriever seam with FTS5 implementation"
```

---

### Task 7: Endpoint probe

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/llm/EndpointProbe.java`
- Test: `sdd-core/src/test/java/sdd/core/llm/EndpointProbeTest.java`

**Interfaces:**
- Consumes: `ModelEndpoint` (Task 2).
- Produces:
  - `record EndpointProbe.ProbeResult(boolean ok, String detail)`
  - `static ProbeResult EndpointProbe.probe(ModelEndpoint ep)` — `GET {baseUrl}/models` with `Authorization` header when the endpoint has a key; 10 s timeout; 2xx ⇒ `ok=true, detail="HTTP <code>"`; non-2xx ⇒ `ok=false, detail="HTTP <code>"`; transport failure ⇒ `ok=false, detail=<exception message>`. Never throws.

- [ ] **Step 1: Write the failing tests**

`EndpointProbeTest.java`:
```java
package sdd.core.llm;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import sdd.core.config.ModelEndpoint;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointProbeTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    private ModelEndpoint ep(String key) {
        return new ModelEndpoint(wm.baseUrl() + "/v1", "m", key, 256, 0.0, Duration.ofSeconds(5));
    }

    @Test
    void reachableEndpointIsOkAndSendsAuth() {
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        EndpointProbe.ProbeResult r = EndpointProbe.probe(ep("sk-x"));
        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).isEqualTo("HTTP 200");
        wm.verify(getRequestedFor(urlEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer sk-x")));
    }

    @Test
    void non2xxIsNotOk() {
        wm.stubFor(get("/v1/models").willReturn(unauthorized()));
        assertThat(EndpointProbe.probe(ep("bad")).ok()).isFalse();
    }

    @Test
    void unreachableHostIsNotOkAndDoesNotThrow() {
        ModelEndpoint dead = new ModelEndpoint("http://127.0.0.1:1/v1", "m", null,
                256, 0.0, Duration.ofSeconds(1));
        EndpointProbe.ProbeResult r = EndpointProbe.probe(dead);
        assertThat(r.ok()).isFalse();
        assertThat(r.detail()).isNotBlank();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.EndpointProbeTest'`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement**

`EndpointProbe.java`:
```java
package sdd.core.llm;

import sdd.core.config.ModelEndpoint;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class EndpointProbe {
    public record ProbeResult(boolean ok, String detail) {}

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private EndpointProbe() {}

    public static ProbeResult probe(ModelEndpoint ep) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(ep.baseUrl() + "/models"))
                    .timeout(PROBE_TIMEOUT)
                    .GET();
            if (ep.apiKey() != null) {
                builder.header("Authorization", "Bearer " + ep.apiKey());
            }
            HttpResponse<Void> resp = HttpClient.newHttpClient()
                    .send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            return new ProbeResult(status >= 200 && status < 300, "HTTP " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "interrupted");
        } catch (Exception e) {
            return new ProbeResult(false, String.valueOf(e.getMessage()));
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.EndpointProbeTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: model endpoint probe for doctor checks"
```

---

### Task 8: CLI root + `sdd doctor`

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/SddCli.java`
- Create: `sdd-cli/src/main/java/sdd/cli/DoctorCommand.java`
- Test: `sdd-cli/src/test/java/sdd/cli/DoctorCommandTest.java`

**Interfaces:**
- Consumes: `ConfigLoader`, `Database`, `EndpointProbe` (Tasks 2, 3, 7).
- Produces:
  - `sdd` root command (picocli, `mixinStandardHelpOptions`), subcommand `doctor`.
  - `sdd doctor [--workspace <dir>]` (default: current directory). Checks, one line each: Java runtime ≥ 21; `sdd.yml` loads; `.sdd/index.db` opens (prints schema version); each configured model endpoint probes OK. Exit code **0** when every check passes, **1** otherwise. Output format per line: `[ OK ]` or `[FAIL]` + check name + detail.

- [ ] **Step 1: Write the failing tests**

`DoctorCommandTest.java`:
```java
package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class DoctorCommandTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: %s/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: %s/v1
                    model: qwen
                """.formatted(wm.baseUrl(), wm.baseUrl());
    }

    private record Run(int exitCode, String out) {}

    private Run doctor(Path workspace) {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("doctor", "--workspace", workspace.toString());
        return new Run(code, sw.toString());
    }

    @Test
    void allChecksPassExitsZero() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] java")
                .contains("[ OK ] config")
                .contains("[ OK ] database")
                .contains("[ OK ] model:planner")
                .contains("[ OK ] model:coder");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void unreachableModelEndpointFails() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(serverError()));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[FAIL] model:planner");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void missingConfigFailsButStillReportsJava() {
        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] java").contains("[FAIL] config");
        assertThat(run.exitCode()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-cli:test`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement**

`SddCli.java`:
```java
package sdd.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "sdd",
        description = "Spec-Driven Development pipeline for multi-repo estates",
        mixinStandardHelpOptions = true,
        version = "sdd 0.1.0",
        subcommands = {DoctorCommand.class})
public final class SddCli {
    public static void main(String[] args) {
        System.exit(new CommandLine(new SddCli()).execute(args));
    }
}
```

`DoctorCommand.java`:
```java
package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.EndpointProbe;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Check that sdd's environment is ready")
public final class DoctorCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Spec CommandSpec spec;

    private boolean allOk = true;

    @Override
    public Integer call() {
        int javaMajor = Runtime.version().feature();
        report(javaMajor >= 21, "java", "runtime " + javaMajor);

        SddConfig config = null;
        try {
            config = ConfigLoader.load(workspace);
            report(true, "config", workspace.resolve("sdd.yml").toString());
        } catch (RuntimeException e) {
            report(false, "config", e.getMessage());
        }

        try (Database db = Database.open(workspace)) {
            report(true, "database", ".sdd/index.db schema v" + db.schemaVersion());
        } catch (RuntimeException e) {
            report(false, "database", e.getMessage());
        }

        if (config != null) {
            for (Map.Entry<String, ModelEndpoint> entry : config.models().entrySet()) {
                EndpointProbe.ProbeResult result = EndpointProbe.probe(entry.getValue());
                report(result.ok(), "model:" + entry.getKey(),
                        entry.getValue().baseUrl() + " → " + result.detail());
            }
        }
        return allOk ? 0 : 1;
    }

    private void report(boolean ok, String check, String detail) {
        if (!ok) {
            allOk = false;
        }
        spec.commandLine().getOut().printf("[%s] %s — %s%n", ok ? " OK " : "FAIL", check, detail);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-cli:test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: sdd CLI with doctor command"
```

---

### Task 9: FixtureRepo test harness

**Files:**
- Create: `sdd-core/src/testFixtures/java/sdd/core/testing/FixtureRepo.java`
- Test: `sdd-core/src/test/java/sdd/core/testing/FixtureRepoTest.java`

**Interfaces:**
- Consumes: JGit (already on the testFixtures classpath from Task 1).
- Produces (the builder every fixture-estate test in Phases 2–5 uses):
  - `static FixtureRepo FixtureRepo.in(Path parentDir, String name)` — creates `<parentDir>/<name>` and `git init` with initial branch `main`.
  - `FixtureRepo file(String relPath, String content)` — writes a file (creating parent dirs), stages nothing yet.
  - `FixtureRepo commit(String message)` — `git add -A` + commit as `sdd-fixture <fixture@sdd.local>`.
  - `Path path()`, `String headSha()`.

- [ ] **Step 1: Write the failing test**

`FixtureRepoTest.java`:
```java
package sdd.core.testing;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureRepoTest {
    @TempDir Path tmp;

    @Test
    void buildsCommittedGitRepo() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib-a")
                .file("settings.gradle", "rootProject.name = 'lib-a'\n")
                .file("src/main/java/A.java", "public class A {}\n")
                .commit("init");

        assertThat(Files.readString(repo.path().resolve("settings.gradle")))
                .contains("lib-a");
        assertThat(repo.headSha()).hasSize(40);
        try (Git git = Git.open(repo.path().toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo("main");
            assertThat(git.status().call().isClean()).isTrue();
        }
    }

    @Test
    void multipleCommitsAdvanceHead() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "r").file("a.txt", "1").commit("c1");
        String first = repo.headSha();
        repo.file("a.txt", "2").commit("c2");
        assertThat(repo.headSha()).isNotEqualTo(first);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.testing.*'`
Expected: FAIL — `FixtureRepo` doesn't exist.

- [ ] **Step 3: Implement**

`FixtureRepo.java`:
```java
package sdd.core.testing;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FixtureRepo {
    private static final PersonIdent AUTHOR = new PersonIdent("sdd-fixture", "fixture@sdd.local");

    private final Path root;

    private FixtureRepo(Path root) { this.root = root; }

    public static FixtureRepo in(Path parentDir, String name) {
        Path root = parentDir.resolve(name);
        try {
            Files.createDirectories(root);
            Git.init().setDirectory(root.toFile()).setInitialBranch("main").call().close();
        } catch (Exception e) {
            throw new IllegalStateException("cannot init fixture repo " + root, e);
        }
        return new FixtureRepo(root);
    }

    public FixtureRepo file(String relPath, String content) {
        try {
            Path target = root.resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    public FixtureRepo commit(String message) {
        try (Git git = Git.open(root.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message).setAuthor(AUTHOR).setCommitter(AUTHOR).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot commit in " + root, e);
        }
        return this;
    }

    public Path path() { return root; }

    public String headSha() {
        try (Git git = Git.open(root.toFile())) {
            return git.getRepository().resolve("HEAD").name();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.testing.*'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: FixtureRepo git test harness"
```

---

### Task 10: Runtime assets — serve-qwen script, config examples, README, smoke test

**Files:**
- Create: `scripts/serve-qwen.sh`
- Create: `.env.example`
- Create: `sdd.yml.example`
- Create: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: the operator-facing setup: local Qwen serving, DeepSeek key wiring, quickstart docs.

- [ ] **Step 1: Write `scripts/serve-qwen.sh`**

```bash
#!/usr/bin/env bash
# Serve Qwen3.6-35B-A3B-8bit locally via mlx-lm with an OpenAI-compatible API.
# Usage: scripts/serve-qwen.sh   (env overrides: QWEN_MODEL_REPO, QWEN_PORT)
set -euo pipefail

MODEL_REPO="${QWEN_MODEL_REPO:-mlx-community/Qwen3.6-35B-A3B-8bit}"
PORT="${QWEN_PORT:-8080}"
VENV="$(cd "$(dirname "$0")/.." && pwd)/.venv-mlx"

if ! command -v python3 >/dev/null; then
  echo "python3 is required (brew install python)" >&2
  exit 1
fi

if [ ! -d "$VENV" ]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install --quiet --upgrade mlx-lm "huggingface_hub[cli]"

echo "Downloading $MODEL_REPO (tens of GB on first run; cached afterwards)..."
huggingface-cli download "$MODEL_REPO" >/dev/null

echo "Serving $MODEL_REPO on http://127.0.0.1:$PORT/v1 ..."
exec mlx_lm.server --model "$MODEL_REPO" --port "$PORT"
```

Then: `chmod +x scripts/serve-qwen.sh` and verify syntax with `bash -n scripts/serve-qwen.sh` (expected: no output).

Note: if the exact HuggingFace repo id for the 8-bit MLX quant differs (e.g. a different org than `mlx-community`), override with `QWEN_MODEL_REPO=<org>/<repo>` — do not edit the script.

- [ ] **Step 2: Write `.env.example`**

```
# Copy to .env, fill in the real key, and `source .env` (or use direnv).
# .env is git-ignored — NEVER commit the real key.
export DEEPSEEK_API_KEY=sk-REPLACE_ME
```

- [ ] **Step 3: Write `sdd.yml.example`**

```yaml
# Copy to <workspace>/sdd.yml — the directory containing all estate checkouts.
retrieval: fts            # fts | embeddings (embeddings needs a models.embeddings endpoint)

models:
  planner:                # whole-estate synthesis: impact seeding, plan drafting, escalation
    base_url: https://api.deepseek.com/v1
    model: deepseek-v4-flash
    api_key: ${DEEPSEEK_API_KEY}
    max_tokens: 16384
  coder:                  # local precise work: repo cards, coding agent
    base_url: http://127.0.0.1:8080/v1
    model: mlx-community/Qwen3.6-35B-A3B-8bit
    max_tokens: 4096

# Gradle daemon JDKs for old wrappers (filled per machine):
# jdk_homes:
#   11: /Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home
#   17: /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
#   21: /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

excludes: []              # workspace directories to skip
```

- [ ] **Step 4: Write `README.md`**

```markdown
# sdd — Spec-Driven Development pipeline for multi-repo estates

Turns a structured feature spec into coordinated, human-gated code changes
across a 40+-repo Gradle/Spring estate, using context-limited local models.
Design: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md`.

## Quickstart

1. **Serve the local coder model** (Apple Silicon, ~40 GB disk):
   `scripts/serve-qwen.sh`
2. **Configure the DeepSeek key**: `cp .env.example .env`, paste the real key,
   `source .env`. Never commit `.env`.
3. **Configure the workspace**: copy `sdd.yml.example` to `<workspace>/sdd.yml`
   (the directory containing all estate checkouts) and adjust.
4. **Check the environment**: `./gradlew :sdd-cli:installDist` then
   `sdd-cli/build/install/sdd/bin/sdd doctor --workspace <workspace>`
5. Pipeline commands (`index`, `plan`, `implement`, `review`) arrive in
   Phases 2–5.

## Development

- Java 21, Gradle. `./gradlew build` runs all tests.
- Modules: `sdd-core` (config, db, model client, retrieval), `sdd-index`,
  `sdd-plan`, `sdd-agent`, `sdd-cli`.
```

- [ ] **Step 5: Create the real `.env` (NOT committed)**

Copy `.env.example` to `.env` and replace the placeholder with the DeepSeek API key the user provided in conversation. Verify it is ignored: `git status --short` must NOT list `.env`.

- [ ] **Step 6: Smoke-test the installed CLI**

Run:
```bash
./gradlew :sdd-cli:installDist --quiet
sdd-cli/build/install/sdd/bin/sdd --version
sdd-cli/build/install/sdd/bin/sdd doctor --workspace . || true
```
Expected: version prints `sdd 0.1.0`; doctor prints the check table (model checks may FAIL if endpoints aren't up — that's correct behavior, the command itself must not crash).

- [ ] **Step 7: Full build green + commit**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, all tests pass.

```bash
git add scripts .env.example sdd.yml.example README.md
git commit -m "chore: model runtime setup, config examples, README"
```

---

## Self-Review (completed at write time)

1. **Spec coverage (Phase 1 scope):** Gradle multi-module skeleton → Task 1; `sdd.yml` single config with env interpolation → Task 2; `.sdd/index.db` + full V1 schema incl. `file_ref`, FTS, `v_repo_dep_edge` → Task 3; `ChatModel` seam + test double → Task 4; hand-rolled OpenAI-compatible client with retry/backoff → Task 5; `Retriever` fts/embeddings seam (fts implemented, embeddings later per spec's deferred list) → Task 6; `sdd doctor` endpoint checks → Tasks 7–8; fixture-estate harness → Task 9; `scripts/serve-qwen.sh`, `DEEPSEEK_API_KEY` wiring, README → Task 10. Not in Phase 1 by design: indexer, planner, agent loop, review (Phases 2–5); `sqlite-vec` table (arrives with the embeddings Retriever); jtokkit (arrives with the token budgeter in Phase 3).
2. **Placeholder scan:** every code step contains complete, compilable code; no TBD/TODO. The one intentional external unknown (exact HF org for the MLX quant) is handled by a documented env override, not a placeholder.
3. **Type consistency:** `ModelEndpoint(baseUrl, model, apiKey, maxTokens, temperature, timeout)` is used identically in Tasks 2, 5, 7, 8; `ChatMessage`/`ChatRequest`/`ChatResponse` shapes match between Tasks 4, 5; `Database.open(Path)` + `jdbi()` used identically in Tasks 3, 6, 8; `FixtureRepo.in(...).file(...).commit(...)` consistent in Task 9.

---

## Execution outcome (2026-08-10)

Executed via subagent-driven development on branch `feature/phase1-skeleton`; all 10 tasks complete; final whole-branch review returned "With fixes", fix wave landed and re-review verified clean. 40 tests green.

**Approved deviations from this plan:**
- `fts_symbol` gained an indexed `words` column + `IdentifierWords` splitter (FTS5 unicode61 cannot split camelCase — plan defect found in Task 6).
- `applicationName = "sdd"` on sdd-cli so the documented `bin/sdd` path is real.
- ConfigLoader wraps numeric-parse errors in ConfigException; Retry-After sleeps capped at 60 s; score contract documented on Retriever/Hit (final-review fixes).

**Phase-2 entry criteria (carry into the Phase-2 plan):**
1. Make `Database.migrate()` transactional BEFORE authoring any V2 migration.
2. Add an `FtsSymbolWriter` insert helper that enforces the `words = IdentifierWords.split(identifier)` invariant (never null input) instead of relying on convention.
3. Fix ConfigLoader silent degradation of non-list `excludes` before the indexer honors excludes.
4. Set an explicit SQLite `busy_timeout` when concurrent per-repo write transactions arrive.
5. Revisit `IdentifierWords` letter–digit splitting (`OrderV2Handler` → `order v 2 handler` hurts version-token recall) before baking millions of rows.
6. Known deferred minors (accepted for v1): unknown `models.*` keys accepted; `schemaVersion()` returns code constant; `base_url` trailing slash unnormalized; doctor test gaps; FixtureRepo doesn't stage deletions; serve-qwen.sh uses deprecated `huggingface-cli` entry point with unpinned `huggingface_hub`.
