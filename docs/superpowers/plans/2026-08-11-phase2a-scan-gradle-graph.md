# Phase 2A — Workspace Scan + Gradle Dependency Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `sdd index` real for the Gradle layer: scan a workspace of git checkouts, extract each repo's modules and dependencies via the Gradle Tooling API (with a static-parse fallback), classify consumption modes, link internal artifacts across repos, and print a per-repo status table — producing the queryable cross-repo dependency graph in `.sdd/index.db`.

**Architecture:** All extraction logic lives in `sdd-index` (packages `sdd.index.scan`, `sdd.index.gradle`, `sdd.index.store`); `sdd-cli` gains an `index` subcommand that orchestrates scan → extract (→ fallback) → persist → link. Deterministic-first: no model calls anywhere in this plan. Tooling API drives each repo's own wrapper; failures degrade per repo, never sink the run.

**Tech Stack:** Existing Phase-1 stack + `org.gradle:gradle-tooling-api:8.10.2` (from `https://repo.gradle.org/gradle/libs-releases`), `org.tomlj:tomlj:1.1.1`, JGit (now also a main dependency of sdd-index).

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` → "Component 1 — Knowledge layer". Phase-2 entry criteria: bottom of `docs/superpowers/plans/2026-08-10-phase1-skeleton.md` — items 1–4 are Tasks 1–3 here; item 5 (IdentifierWords digit splitting) belongs to Plan 2B where FTS rows are written.

**Plan series:** 2A (this) → 2B (Java source extraction) → 2C (link passes, repo cards, curation report, golden files). The consolidated golden-file suite arrives in 2C when all tables are populated; 2A's integration test asserts table contents directly.

## Global Constraints

- Java 21 toolchain; never push; never commit secrets; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` after a blank line.
- Deterministic-first: NO model calls in this plan; all tests offline except `@Tag("gradle-it")` ones, which may download Gradle distributions and Maven Central artifacts (cached in `~/.gradle`).
- Consumption mode enum values exactly: `PINNED | SNAPSHOT | DYNAMIC | COMPOSITE | BOM_MANAGED`; `declared_via` values exactly: `DIRECT | CATALOG | BOM` (spec: Component 1).
- Repo/phase status values exactly: `OK | DEGRADED | STALE_OK | FAILED` (spec: Component 1, incremental).
- Statuses degrade per repo — `sdd index` exits 0 with a summary table unless zero repos were indexed (spec: error handling).
- Workspace state stays under `<workspace>/.sdd/`; the KB schema is owned by `V1__init.sql` (pre-release: extend V1 in place, no V2).
- JDK map rule (spec): wrapper `>= 8.5 → 21`, `>= 7.3 → 17`, else `→ 11`, homes from `sdd.yml jdk_homes`; missing home → run on the current JVM and record the risk in the repo's error column on failure.
- `--no-configuration-cache` passed only when wrapper version `>= 6.6` (flag doesn't exist earlier).

---

### Task 1: Database hardening — transactional migrations + busy_timeout

**Files:**
- Modify: `sdd-core/src/main/java/sdd/core/db/Database.java`
- Test: `sdd-core/src/test/java/sdd/core/db/DatabaseTest.java` (add tests)

**Interfaces:**
- Consumes: existing `Database.open(Path)`.
- Produces: same public API; each migration file now applies atomically (all statements + version bump in one transaction); SQLite `busy_timeout` set to 5000 ms on every connection.

- [ ] **Step 1: Write the failing tests** (append to `DatabaseTest`)

```java
@Test
void migrationIsAtomic() throws Exception {
    // Corrupt path: a migration that fails half-way must leave no trace.
    // Simulate by opening a db, then attempting a second migrate with a bad script
    // via the package-visible seam.
    try (Database db = Database.open(ws)) {
        assertThat(db.schemaVersion()).isEqualTo(1);
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            Database.applyMigrationForTest(ws, "CREATE TABLE t_ok(id INTEGER);\n;\nCREATE BROKEN SYNTAX"))
            .isInstanceOf(Exception.class);
    try (Database db = Database.open(ws)) {
        List<String> tables = db.jdbi().withHandle(h ->
                h.createQuery("SELECT name FROM sqlite_master WHERE type='table'").mapTo(String.class).list());
        assertThat(tables).doesNotContain("t_ok"); // first statement rolled back with the failure
    }
}

@Test
void busyTimeoutIsConfigured() throws Exception {
    try (Database db = Database.open(ws)) {
        Integer timeout = db.jdbi().withHandle(h ->
                h.createQuery("PRAGMA busy_timeout").mapTo(Integer.class).one());
        assertThat(timeout).isEqualTo(5000);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.db.*'`
Expected: FAIL — `applyMigrationForTest` doesn't exist; busy_timeout is 0.

- [ ] **Step 3: Implement**

In `Database.java`:
1. Add `config.setBusyTimeout(5000);` next to the existing `SQLiteConfig` setup.
2. Make each migration atomic — replace the migration loop body with a transaction, and extract a package-visible seam used by both the loop and the test:

```java
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
            applyMigration(jdbi, v, readResource("/sdd/db/" + MIGRATIONS.get(v - 1)));
        }
        return MIGRATIONS.size();
    }

    private static void applyMigration(Jdbi jdbi, int version, String script) {
        jdbi.useTransaction(h -> {
            for (String statement : script.split("\\n;\\n")) {
                if (!statement.isBlank()) {
                    h.execute(statement);
                }
            }
            h.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            h.execute("INSERT INTO meta(key, value) VALUES ('schema_version', ?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value", version);
        });
    }

    static void applyMigrationForTest(Path workspace, String script) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + workspace.resolve(".sdd/index.db"));
        applyMigration(Jdbi.create(ds), 999, script);
    }
```

(Note: SQLite supports transactional DDL, so rollback of `CREATE TABLE` works. The `CREATE TABLE IF NOT EXISTS meta` inside the transaction keeps V1 bootstrap working since `meta` is also created by the script — `IF NOT EXISTS` makes both orders safe.)

Also fix the stale comment on `close()` from Phase 1's deferred list: replace `/* pooled per-call connections; nothing to close */` with `/* one physical connection per Jdbi handle, closed per call; nothing pooled to release */`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.db.*'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: transactional migrations and sqlite busy_timeout"
```

---

### Task 2: Config hardening — strict excludes + artifact_overrides

**Files:**
- Modify: `sdd-core/src/main/java/sdd/core/config/SddConfig.java`
- Modify: `sdd-core/src/main/java/sdd/core/config/ConfigLoader.java`
- Test: `sdd-core/src/test/java/sdd/core/config/ConfigLoaderTest.java` (add tests)

**Interfaces:**
- Consumes: existing loader.
- Produces: `SddConfig` gains a new LAST component: `Map<String,String> artifactOverrides` (key `"group:name"`, value repo name; default empty). Record becomes `SddConfig(Path workspace, String retrieval, Map<String,ModelEndpoint> models, Map<Integer,Path> jdkHomes, List<String> excludes, Map<String,String> artifactOverrides)`. Non-list `excludes` now throws `ConfigException` instead of silently degrading. **All existing constructions/tests must be updated for the new component.**

- [ ] **Step 1: Write the failing tests** (append to `ConfigLoaderTest`)

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.config.*'`
Expected: FAIL — no `artifactOverrides()` accessor; non-list excludes currently degrades silently.

- [ ] **Step 3: Implement**

In `SddConfig.java` add the sixth component `Map<String, String> artifactOverrides`. In `ConfigLoader.load(...)`:

```java
        Object excludesNode = root.get("excludes");
        List<String> excludes;
        if (excludesNode == null) {
            excludes = List.of();
        } else if (excludesNode instanceof List<?> l) {
            excludes = l.stream().map(String::valueOf).toList();
        } else {
            throw new ConfigException("excludes must be a list, got: " + excludesNode);
        }

        Map<String, String> artifactOverrides = new LinkedHashMap<>();
        if (root.get("artifact_overrides") instanceof Map<?, ?> am) {
            for (Map.Entry<?, ?> e : am.entrySet()) {
                artifactOverrides.put(String.valueOf(e.getKey()),
                        str(e.getValue(), env, "artifact_overrides"));
            }
        }

        return new SddConfig(workspace, retrieval, Map.copyOf(models), Map.copyOf(jdkHomes),
                excludes, Map.copyOf(artifactOverrides));
```

Fix all compile errors from the record change (existing tests construct `SddConfig` only via the loader, so only the loader changes).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.config.*'` then `./gradlew build`
Expected: config tests PASS (12); whole build green.

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: strict excludes validation and artifact_overrides config"
```

---

### Task 3: FtsSymbolWriter — enforce the words invariant

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/retrieve/FtsSymbolWriter.java`
- Test: `sdd-core/src/test/java/sdd/core/retrieve/FtsSymbolWriterTest.java`

**Interfaces:**
- Consumes: `Database` (Task-1 hardened), `IdentifierWords.split(String)`.
- Produces: `final class FtsSymbolWriter` with `static void insert(org.jdbi.v3.core.Handle handle, long moduleId, String identifier, String fqcn)` — computes `words = IdentifierWords.split(identifier)`, rejects null/blank identifier or fqcn with `IllegalArgumentException`, and `static void deleteForModule(Handle handle, long moduleId)`. Plan 2B populates FTS exclusively through this class.

- [ ] **Step 1: Write the failing test**

```java
package sdd.core.retrieve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class FtsSymbolWriterTest {
    @TempDir Path ws;

    @Test
    void insertsWithSplitWordsAndSearchFinds() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h ->
                    FtsSymbolWriter.insert(h, 7L, "LoyaltyTier", "com.acme.pricing.LoyaltyTier"));
            List<Hit> hits = new FtsRetriever(db.jdbi()).search("loyalty", 10);
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).moduleId()).isEqualTo(7L);
        }
    }

    @Test
    void deleteForModuleRemovesRows() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                FtsSymbolWriter.insert(h, 7L, "A", "p.A");
                FtsSymbolWriter.insert(h, 8L, "B", "p.B");
                FtsSymbolWriter.deleteForModule(h, 7L);
            });
            Integer count = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM fts_symbol").mapTo(Integer.class).one());
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void rejectsNullAndBlank() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                assertThatThrownBy(() -> FtsSymbolWriter.insert(h, 1L, null, "p.A"))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> FtsSymbolWriter.insert(h, 1L, " ", "p.A"))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> FtsSymbolWriter.insert(h, 1L, "A", null))
                        .isInstanceOf(IllegalArgumentException.class);
            });
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.retrieve.FtsSymbolWriterTest'`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement**

```java
package sdd.core.retrieve;

import org.jdbi.v3.core.Handle;

/** Sole write path for fts_symbol — enforces the words-column invariant. */
public final class FtsSymbolWriter {
    private FtsSymbolWriter() {}

    public static void insert(Handle handle, long moduleId, String identifier, String fqcn) {
        if (identifier == null || identifier.isBlank() || fqcn == null || fqcn.isBlank()) {
            throw new IllegalArgumentException(
                    "identifier and fqcn must be non-blank (identifier=" + identifier + ", fqcn=" + fqcn + ")");
        }
        handle.createUpdate("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) "
                        + "VALUES (:id, :fqcn, :words, :mod)")
                .bind("id", identifier)
                .bind("fqcn", fqcn)
                .bind("words", IdentifierWords.split(identifier))
                .bind("mod", moduleId)
                .execute();
    }

    public static void deleteForModule(Handle handle, long moduleId) {
        handle.createUpdate("DELETE FROM fts_symbol WHERE module_id = :mod")
                .bind("mod", moduleId).execute();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdd-core:test --tests 'sdd.core.retrieve.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: FtsSymbolWriter enforcing the words-column invariant"
```

---

### Task 4: Build wiring + FixtureGradleRepo harness

**Files:**
- Modify: `settings.gradle.kts` (add gradle libs-releases repository)
- Modify: `gradle/libs.versions.toml` (add tooling API + tomlj)
- Modify: `sdd-index/build.gradle.kts`
- Create: `sdd-index/src/testFixtures/java/sdd/index/testing/FixtureGradleRepo.java`
- Test: `sdd-index/src/test/java/sdd/index/testing/FixtureGradleRepoTest.java`

**Interfaces:**
- Consumes: `FixtureRepo` (sdd-core testFixtures).
- Produces:
  - Catalog aliases `libs.gradle.tooling`, `libs.tomlj`.
  - `final class FixtureGradleRepo` — builds a **buildable** tiny Gradle repo: `static FixtureGradleRepo in(Path parentDir, String name, String gradleVersion)` copies `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` from THIS project's root (wrapper bootstrap jar is version-independent) and writes `gradle-wrapper.properties` pinning `gradleVersion`; `withSettings(String)`, `withBuildFile(String)`, `withFile(String, String)` (fluent), `commit()` finalizes via `FixtureRepo` (git init + commit) and returns `Path` repo root.

- [ ] **Step 1: Build wiring**

`settings.gradle.kts` — extend repositories:
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}
```

`gradle/libs.versions.toml` — add under `[versions]`: `gradle-tooling = "8.10.2"`, `tomlj = "1.1.1"`; under `[libraries]`:
```toml
gradle-tooling = { module = "org.gradle:gradle-tooling-api", version.ref = "gradle-tooling" }
tomlj = { module = "org.tomlj:tomlj", version.ref = "tomlj" }
```

`sdd-index/build.gradle.kts` becomes:
```kotlin
plugins {
    `java-library`
    `java-test-fixtures`
}
dependencies {
    api(project(":sdd-core"))
    implementation(libs.gradle.tooling)
    implementation(libs.tomlj)
    implementation(libs.jgit)
    implementation(libs.jackson)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
    testFixturesApi(testFixtures(project(":sdd-core")))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.launcher)
}
```

Run: `./gradlew :sdd-index:build --quiet` — must succeed (resolves the tooling API from the new repository).

- [ ] **Step 2: Write the failing test**

```java
package sdd.index.testing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureGradleRepoTest {
    @TempDir Path tmp;

    @Test
    void buildsRepoWithPinnedWrapper() throws Exception {
        Path repo = FixtureGradleRepo.in(tmp, "lib-a", "8.10.2")
                .withSettings("rootProject.name = 'lib-a'\n")
                .withBuildFile("plugins { id 'java-library' }\n")
                .commit();
        assertThat(Files.isExecutable(repo.resolve("gradlew"))).isTrue();
        assertThat(Files.readString(repo.resolve("gradle/wrapper/gradle-wrapper.properties")))
                .contains("gradle-8.10.2-bin.zip");
        assertThat(repo.resolve(".git")).exists();
        assertThat(repo.resolve("gradle/wrapper/gradle-wrapper.jar")).exists();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :sdd-index:test`
Expected: FAIL — class doesn't exist.

- [ ] **Step 4: Implement**

```java
package sdd.index.testing;

import sdd.core.testing.FixtureRepo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/** Builds tiny, actually-buildable Gradle git repos for integration tests. */
public final class FixtureGradleRepo {
    private final FixtureRepo repo;
    private final Path root;

    private FixtureGradleRepo(FixtureRepo repo) {
        this.repo = repo;
        this.root = repo.path();
    }

    public static FixtureGradleRepo in(Path parentDir, String name, String gradleVersion) {
        FixtureRepo base = FixtureRepo.in(parentDir, name);
        FixtureGradleRepo g = new FixtureGradleRepo(base);
        Path projectRoot = locateSddProjectRoot();
        try {
            Files.createDirectories(g.root.resolve("gradle/wrapper"));
            Files.copy(projectRoot.resolve("gradlew"), g.root.resolve("gradlew"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(projectRoot.resolve("gradle/wrapper/gradle-wrapper.jar"),
                    g.root.resolve("gradle/wrapper/gradle-wrapper.jar"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.getFileAttributeView(g.root.resolve("gradlew"),
                            java.nio.file.attribute.PosixFileAttributeView.class)
                    .setPermissions(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
            Files.writeString(g.root.resolve("gradle/wrapper/gradle-wrapper.properties"), """
                    distributionBase=GRADLE_USER_HOME
                    distributionPath=wrapper/dists
                    distributionUrl=https\\://services.gradle.org/distributions/gradle-%s-bin.zip
                    zipStoreBase=GRADLE_USER_HOME
                    zipStorePath=wrapper/dists
                    """.formatted(gradleVersion));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return g;
    }

    private static Path locateSddProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("gradle/wrapper/gradle-wrapper.jar"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("cannot locate sdd project root with wrapper jar");
        }
        return dir;
    }

    public FixtureGradleRepo withSettings(String content) { return withFile("settings.gradle", content); }

    public FixtureGradleRepo withBuildFile(String content) { return withFile("build.gradle", content); }

    public FixtureGradleRepo withFile(String relPath, String content) {
        repo.file(relPath, content);
        return this;
    }

    public Path commit() {
        repo.commit("fixture");
        return root;
    }
}
```

- [ ] **Step 5: Run test to verify it passes, then commit**

Run: `./gradlew :sdd-index:test`
Expected: PASS.

```bash
git add settings.gradle.kts gradle/libs.versions.toml sdd-index
git commit -m "feat: sdd-index build wiring and FixtureGradleRepo harness"
```

---

### Task 5: WorkspaceScanner

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/scan/RepoScan.java`
- Create: `sdd-index/src/main/java/sdd/index/scan/WorkspaceScanner.java`
- Test: `sdd-index/src/test/java/sdd/index/scan/WorkspaceScannerTest.java`

**Interfaces:**
- Consumes: `FixtureRepo` (tests), JGit.
- Produces:
  - `record RepoScan(String name, Path path, String headCommit, String branch, String dirtyHash)` — `dirtyHash` is `""` for a clean tree, else SHA-256 hex of the working-tree diff; convenience method `String fingerprint()` returning `headCommit + ":" + dirtyHash`.
  - `final class WorkspaceScanner` with `static List<RepoScan> scan(Path workspace, List<String> excludes)` — first-level children containing `.git`, sorted by name, excludes filtered by directory name.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceScannerTest {
    @TempDir Path ws;

    @Test
    void findsGitReposSortedAndSkipsNonReposAndExcludes() throws Exception {
        FixtureRepo.in(ws, "svc-b").file("a.txt", "x").commit("init");
        FixtureRepo.in(ws, "lib-a").file("a.txt", "x").commit("init");
        FixtureRepo.in(ws, "sandbox").file("a.txt", "x").commit("init");
        Files.createDirectories(ws.resolve("not-a-repo"));

        List<RepoScan> scans = WorkspaceScanner.scan(ws, List.of("sandbox"));

        assertThat(scans).extracting(RepoScan::name).containsExactly("lib-a", "svc-b");
        RepoScan libA = scans.get(0);
        assertThat(libA.headCommit()).hasSize(40);
        assertThat(libA.branch()).isEqualTo("main");
        assertThat(libA.dirtyHash()).isEmpty();
        assertThat(libA.fingerprint()).isEqualTo(libA.headCommit() + ":");
    }

    @Test
    void dirtyTreeChangesFingerprint() throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, "r").file("a.txt", "one").commit("init");
        String cleanFp = WorkspaceScanner.scan(ws, List.of()).get(0).fingerprint();

        Files.writeString(repo.path().resolve("a.txt"), "two");
        RepoScan dirty = WorkspaceScanner.scan(ws, List.of()).get(0);

        assertThat(dirty.dirtyHash()).isNotEmpty();
        assertThat(dirty.fingerprint()).isNotEqualTo(cleanFp);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.scan.*'`
Expected: FAIL — classes don't exist.

- [ ] **Step 3: Implement**

`RepoScan.java`:
```java
package sdd.index.scan;

import java.nio.file.Path;

public record RepoScan(String name, Path path, String headCommit, String branch, String dirtyHash) {
    public String fingerprint() {
        return headCommit + ":" + dirtyHash;
    }
}
```

`WorkspaceScanner.java`:
```java
package sdd.index.scan;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

public final class WorkspaceScanner {
    private WorkspaceScanner() {}

    public static List<RepoScan> scan(Path workspace, List<String> excludes) {
        List<RepoScan> result = new ArrayList<>();
        try (Stream<Path> children = Files.list(workspace)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(".git")))
                    .filter(dir -> !excludes.contains(dir.getFileName().toString()))
                    .sorted()
                    .forEach(dir -> result.add(scanRepo(dir)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static RepoScan scanRepo(Path dir) {
        try (Git git = Git.open(dir.toFile())) {
            Repository repo = git.getRepository();
            ObjectId head = repo.resolve("HEAD");
            String headSha = head == null ? "" : head.name();
            String branch = repo.getBranch();
            boolean clean = git.status().call().isClean();
            String dirtyHash = clean || head == null ? "" : hashWorkingTreeDiff(repo, head);
            return new RepoScan(dir.getFileName().toString(), dir, headSha, branch, dirtyHash);
        } catch (Exception e) {
            throw new IllegalStateException("cannot scan git repo " + dir, e);
        }
    }

    private static String hashWorkingTreeDiff(Repository repo, ObjectId head) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter fmt = new DiffFormatter(out); RevWalk walk = new RevWalk(repo)) {
            fmt.setRepository(repo);
            fmt.format(fmt.scan(walk.parseCommit(head).getTree(), new FileTreeIterator(repo)));
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.scan.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: workspace scanner with git state fingerprints"
```

---

### Task 6: Gradle extraction model + JSON parsing

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/gradle/GradleModel.java` (all records in one file as nested types)
- Create: `sdd-index/src/main/java/sdd/index/gradle/ExtractJsonParser.java`
- Test: `sdd-index/src/test/java/sdd/index/gradle/ExtractJsonParserTest.java`

**Interfaces:**
- Consumes: Jackson.
- Produces (consumed by Tasks 7, 9, 10, 11):

```java
public final class GradleModel {
    public record Extract(List<Project> projects, List<Path> includedBuilds) {}
    public record Project(String path, String name, String group, String version, Path projectDir,
                          List<String> plugins, boolean hasBootJarTask,
                          List<Publication> publications, Map<String, DepConfig> configurations) {}
    public record Publication(String groupId, String artifactId) {}
    public record DepConfig(List<DeclaredDep> declared, List<ResolvedDep> resolved, List<String> unresolved) {}
    public record DeclaredDep(String group, String name, String version) {}          // version may be null
    public record ResolvedDep(String group, String name, String version, List<Path> files) {}
}
```
  - `final class ExtractJsonParser` with `static GradleModel.Extract parse(String projectsJson, String settingsJson)` — `settingsJson` may be `null` (no included builds).

- [ ] **Step 1: Write the failing test**

```java
package sdd.index.gradle;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractJsonParserTest {
    private static final String PROJECTS_JSON = """
            {"projects":[{
              "path":":","name":"lib-a","group":"com.acme","version":"1.2.0",
              "projectDir":"/tmp/lib-a",
              "plugins":["java-library","maven-publish"],
              "hasBootJarTask":false,
              "publications":[{"groupId":"com.acme","artifactId":"lib-a"}],
              "configurations":{"compileClasspath":{
                "declared":[{"group":"com.acme","name":"lib-core","version":"2.0.0"},
                            {"group":"org.apache.commons","name":"commons-lang3","version":null}],
                "resolved":[{"group":"org.apache.commons","name":"commons-lang3","version":"3.14.0",
                             "files":["/cache/commons-lang3-3.14.0.jar"]}],
                "unresolved":["com.acme:lib-core:2.0.0"]}}}]}
            """;
    private static final String SETTINGS_JSON = """
            {"includedBuilds":["/tmp/lib-core"]}
            """;

    @Test
    void parsesProjectsAndSettings() {
        GradleModel.Extract e = ExtractJsonParser.parse(PROJECTS_JSON, SETTINGS_JSON);
        assertThat(e.includedBuilds()).containsExactly(Path.of("/tmp/lib-core"));
        GradleModel.Project p = e.projects().get(0);
        assertThat(p.name()).isEqualTo("lib-a");
        assertThat(p.plugins()).contains("maven-publish");
        assertThat(p.publications().get(0).artifactId()).isEqualTo("lib-a");
        GradleModel.DepConfig cc = p.configurations().get("compileClasspath");
        assertThat(cc.declared()).hasSize(2);
        assertThat(cc.declared().get(1).version()).isNull();
        assertThat(cc.resolved().get(0).version()).isEqualTo("3.14.0");
        assertThat(cc.unresolved()).containsExactly("com.acme:lib-core:2.0.0");
    }

    @Test
    void nullSettingsMeansNoIncludedBuilds() {
        assertThat(ExtractJsonParser.parse(PROJECTS_JSON, null).includedBuilds()).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`GradleModel.java` exactly as in the Interfaces block (package `sdd.index.gradle`, plus imports `java.nio.file.Path`, `java.util.List`, `java.util.Map`; private constructor).

`ExtractJsonParser.java`:
```java
package sdd.index.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExtractJsonParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractJsonParser() {}

    public static GradleModel.Extract parse(String projectsJson, String settingsJson) {
        try {
            List<GradleModel.Project> projects = new ArrayList<>();
            for (JsonNode p : JSON.readTree(projectsJson).path("projects")) {
                projects.add(parseProject(p));
            }
            List<Path> included = new ArrayList<>();
            if (settingsJson != null) {
                for (JsonNode b : JSON.readTree(settingsJson).path("includedBuilds")) {
                    included.add(Path.of(b.asText()));
                }
            }
            return new GradleModel.Extract(List.copyOf(projects), List.copyOf(included));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GradleModel.Project parseProject(JsonNode p) {
        List<String> plugins = new ArrayList<>();
        p.path("plugins").forEach(n -> plugins.add(n.asText()));
        List<GradleModel.Publication> pubs = new ArrayList<>();
        for (JsonNode pub : p.path("publications")) {
            pubs.add(new GradleModel.Publication(pub.path("groupId").asText(), pub.path("artifactId").asText()));
        }
        Map<String, GradleModel.DepConfig> configs = new LinkedHashMap<>();
        p.path("configurations").properties().forEach(e ->
                configs.put(e.getKey(), parseConfig(e.getValue())));
        return new GradleModel.Project(
                p.path("path").asText(), p.path("name").asText(),
                textOrNull(p, "group"), textOrNull(p, "version"),
                Path.of(p.path("projectDir").asText()),
                List.copyOf(plugins), p.path("hasBootJarTask").asBoolean(false),
                List.copyOf(pubs), configs);
    }

    private static GradleModel.DepConfig parseConfig(JsonNode c) {
        List<GradleModel.DeclaredDep> declared = new ArrayList<>();
        for (JsonNode d : c.path("declared")) {
            declared.add(new GradleModel.DeclaredDep(
                    textOrNull(d, "group"), d.path("name").asText(), textOrNull(d, "version")));
        }
        List<GradleModel.ResolvedDep> resolved = new ArrayList<>();
        for (JsonNode r : c.path("resolved")) {
            List<Path> files = new ArrayList<>();
            r.path("files").forEach(f -> files.add(Path.of(f.asText())));
            resolved.add(new GradleModel.ResolvedDep(
                    r.path("group").asText(), r.path("name").asText(),
                    textOrNull(r, "version"), List.copyOf(files)));
        }
        List<String> unresolved = new ArrayList<>();
        c.path("unresolved").forEach(u -> unresolved.add(u.asText()));
        return new GradleModel.DepConfig(List.copyOf(declared), List.copyOf(resolved), List.copyOf(unresolved));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: gradle extraction model and JSON parser"
```

---

### Task 7: Init script + GradleExtractor (Tooling API driver)

**Files:**
- Create: `sdd-index/src/main/resources/sdd/gradle/sdd-init.gradle`
- Create: `sdd-index/src/main/java/sdd/index/gradle/GradleExtractor.java`
- Create: `sdd-index/src/main/java/sdd/index/gradle/ExtractionException.java`
- Test: `sdd-index/src/test/java/sdd/index/gradle/GradleExtractorIT.java` (`@Tag("gradle-it")`)

**Interfaces:**
- Consumes: `GradleModel`, `ExtractJsonParser`, `FixtureGradleRepo` (tests).
- Produces:
  - `final class GradleExtractor` with constructor `GradleExtractor(Map<Integer, Path> jdkHomes)` and `GradleModel.Extract extract(Path repoDir)` throwing `ExtractionException` (message carries stderr tail) on any Tooling API failure or timeout (10 min).
  - `static String wrapperVersion(Path repoDir)` — parses `gradle/wrapper/gradle-wrapper.properties`, returns e.g. `"8.10.2"` or `null` when absent.
  - `static int jdkMajorFor(String wrapperVersion)` — `>= 8.5 → 21`, `>= 7.3 → 17`, else `11`; `null` version → `21`.
  - `class ExtractionException extends RuntimeException`.

- [ ] **Step 1: Write the init script** at `sdd-index/src/main/resources/sdd/gradle/sdd-init.gradle` (Groovy, Gradle 5.6-safe — no config-cache assumptions, no Kotlin):

```groovy
import groovy.json.JsonOutput

settingsEvaluated { settings ->
    def out = settings.startParameter.projectProperties['sddSettingsOut']
    if (out != null) {
        def included = settings.gradle.includedBuilds.collect { it.projectDir.absolutePath }
        new File(out).text = JsonOutput.toJson([includedBuilds: included])
    }
}

rootProject { root ->
    root.tasks.register('sddExtract') {
        doLast {
            def result = [projects: []]
            root.allprojects { p ->
                def entry = [
                        path        : p.path,
                        name        : p.name,
                        group       : p.group?.toString(),
                        version     : p.version?.toString(),
                        projectDir  : p.projectDir.absolutePath,
                        plugins     : [],
                        hasBootJarTask: p.tasks.findByName('bootJar') != null,
                        publications: [],
                        configurations: [:]
                ]
                ['org.springframework.boot', 'java', 'java-library', 'application', 'maven-publish'].each { id ->
                    if (p.pluginManager.hasPlugin(id)) entry.plugins << id
                }
                def publishing = p.extensions.findByName('publishing')
                if (publishing != null) {
                    try {
                        publishing.publications.each { pub ->
                            if (pub.hasProperty('groupId') && pub.hasProperty('artifactId')) {
                                entry.publications << [groupId: pub.groupId?.toString(), artifactId: pub.artifactId?.toString()]
                            }
                        }
                    } catch (Exception ignored) { }
                }
                ['compileClasspath', 'runtimeClasspath'].each { cfgName ->
                    def cfg = p.configurations.findByName(cfgName)
                    if (cfg == null) return
                    def declared = []
                    cfg.allDependencies.each { d ->
                        if (d instanceof org.gradle.api.artifacts.ExternalDependency) {
                            declared << [group: d.group, name: d.name, version: d.version]
                        }
                    }
                    def resolvedList = []
                    def unresolved = []
                    try {
                        def lenient = cfg.resolvedConfiguration.lenientConfiguration
                        lenient.allModuleDependencies.each { d ->
                            def files = []
                            try {
                                d.moduleArtifacts.each { a -> files << a.file.absolutePath }
                            } catch (Exception ignored) { }
                            resolvedList << [group: d.moduleGroup, name: d.moduleName,
                                             version: d.moduleVersion, files: files]
                        }
                        lenient.unresolvedModuleDependencies.each { u ->
                            unresolved << u.selector.toString()
                        }
                    } catch (Exception e) {
                        unresolved << ("configuration-failed: " + e.message).toString()
                    }
                    entry.configurations[cfgName] = [declared: declared, resolved: resolvedList, unresolved: unresolved]
                }
                result.projects << entry
            }
            def outPath = root.findProperty('sddOut')
            if (outPath != null) {
                new File(outPath).text = JsonOutput.toJson(result)
            }
        }
    }
}
```

- [ ] **Step 2: Write the failing integration test**

```java
package sdd.index.gradle;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.testing.FixtureGradleRepo;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@Tag("gradle-it")
class GradleExtractorIT {
    @TempDir Path tmp;

    @Test
    void extractsProjectsDepsAndBootMarker() {
        Path repo = FixtureGradleRepo.in(tmp, "svc-orders", "8.10.2")
                .withSettings("rootProject.name = 'svc-orders'\n")
                .withBuildFile("""
                        plugins { id 'java' }
                        group = 'com.acme'
                        version = '0.1.0'
                        repositories { mavenCentral() }
                        dependencies {
                            implementation 'org.apache.commons:commons-lang3:3.14.0'
                            implementation 'com.acme:lib-core:2.0.0'
                        }
                        """)
                .withFile("src/main/java/A.java", "public class A {}\n")
                .commit();

        GradleModel.Extract extract = new GradleExtractor(Map.of()).extract(repo);

        assertThat(extract.projects()).hasSize(1);
        GradleModel.Project p = extract.projects().get(0);
        assertThat(p.name()).isEqualTo("svc-orders");
        assertThat(p.group()).isEqualTo("com.acme");
        assertThat(p.plugins()).contains("java");
        assertThat(p.hasBootJarTask()).isFalse();
        GradleModel.DepConfig cc = p.configurations().get("compileClasspath");
        assertThat(cc.declared()).extracting(GradleModel.DeclaredDep::name)
                .contains("commons-lang3", "lib-core");
        // commons-lang3 resolves from Maven Central; internal lib-core does not exist remotely
        assertThat(cc.resolved()).extracting(GradleModel.ResolvedDep::name).contains("commons-lang3");
        assertThat(cc.unresolved()).anySatisfy(u -> assertThat(u).contains("lib-core"));
    }

    @Test
    void brokenSettingsThrowsExtractionException() {
        Path repo = FixtureGradleRepo.in(tmp, "broken", "8.10.2")
                .withSettings("throw new GradleException('intentionally broken')\n")
                .commit();
        assertThatThrownBy(() -> new GradleExtractor(Map.of()).extract(repo))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void wrapperVersionAndJdkMapping() {
        Path repo = FixtureGradleRepo.in(tmp, "v", "8.10.2").withSettings("").commit();
        assertThat(GradleExtractor.wrapperVersion(repo)).isEqualTo("8.10.2");
        assertThat(GradleExtractor.jdkMajorFor("8.10.2")).isEqualTo(21);
        assertThat(GradleExtractor.jdkMajorFor("8.5")).isEqualTo(21);
        assertThat(GradleExtractor.jdkMajorFor("7.6.4")).isEqualTo(17);
        assertThat(GradleExtractor.jdkMajorFor("6.9")).isEqualTo(11);
        assertThat(GradleExtractor.jdkMajorFor(null)).isEqualTo(21);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.GradleExtractorIT'`
Expected: FAIL — classes don't exist. (First green run later downloads the Gradle 8.10.2 distribution — allow minutes.)

- [ ] **Step 4: Implement**

`ExtractionException.java`:
```java
package sdd.index.gradle;

public class ExtractionException extends RuntimeException {
    public ExtractionException(String message) { super(message); }
    public ExtractionException(String message, Throwable cause) { super(message, cause); }
}
```

`GradleExtractor.java`:
```java
package sdd.index.gradle;

import org.gradle.tooling.CancellationTokenSource;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GradleExtractor {
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Pattern DIST_VERSION = Pattern.compile("gradle-([0-9][0-9.]*)-(?:bin|all)\\.zip");

    private final Map<Integer, Path> jdkHomes;

    public GradleExtractor(Map<Integer, Path> jdkHomes) {
        this.jdkHomes = jdkHomes;
    }

    public GradleModel.Extract extract(Path repoDir) {
        String version = wrapperVersion(repoDir);
        Path out = null;
        Path settingsOut = null;
        Path initScript = null;
        try {
            out = Files.createTempFile("sdd-extract", ".json");
            settingsOut = Files.createTempFile("sdd-settings", ".json");
            initScript = materializeInitScript();
            runBuild(repoDir, version, initScript, out, settingsOut);
            String projectsJson = Files.readString(out);
            String settingsJson = Files.size(settingsOut) == 0 ? null : Files.readString(settingsOut);
            return ExtractJsonParser.parse(projectsJson, settingsJson);
        } catch (IOException e) {
            throw new ExtractionException("io failure extracting " + repoDir + ": " + e.getMessage(), e);
        } finally {
            deleteQuietly(out);
            deleteQuietly(settingsOut);
            deleteQuietly(initScript);
        }
    }

    private void runBuild(Path repoDir, String version, Path initScript, Path out, Path settingsOut) {
        GradleConnector connector = GradleConnector.newConnector()
                .forProjectDirectory(repoDir.toFile())
                .useBuildDistribution();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CancellationTokenSource cancel = GradleConnector.newCancellationTokenSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProjectConnection connection = connector.connect()) {
            List<String> args = new ArrayList<>(List.of(
                    "--init-script", initScript.toString(),
                    "-PsddOut=" + out,
                    "-PsddSettingsOut=" + settingsOut));
            if (versionAtLeast(version, 6, 6)) {
                args.add("--no-configuration-cache");
            }
            var build = connection.newBuild()
                    .forTasks("sddExtract")
                    .withArguments(args)
                    .setStandardError(stderr)
                    .withCancellationToken(cancel.token());
            Path jdk = jdkHomes.get(jdkMajorFor(version));
            if (jdk != null) {
                build.setJavaHome(jdk.toFile());
            }
            Future<?> run = executor.submit(build::run);
            run.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancel.cancel();
            throw new ExtractionException("gradle extraction timed out after " + TIMEOUT + " in " + repoDir);
        } catch (Exception e) {
            String tail = stderrTail(stderr);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ExtractionException(
                    "gradle extraction failed in " + repoDir + ": " + cause.getMessage()
                            + (tail.isEmpty() ? "" : "\nstderr: " + tail), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    public static String wrapperVersion(Path repoDir) {
        Path props = repoDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(props)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            String url = p.getProperty("distributionUrl", "");
            Matcher m = DIST_VERSION.matcher(url);
            return m.find() ? m.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static int jdkMajorFor(String wrapperVersion) {
        if (wrapperVersion == null) {
            return 21;
        }
        if (versionAtLeast(wrapperVersion, 8, 5)) {
            return 21;
        }
        return versionAtLeast(wrapperVersion, 7, 3) ? 17 : 11;
    }

    private static boolean versionAtLeast(String version, int major, int minor) {
        if (version == null) {
            return true;
        }
        String[] parts = version.split("\\.");
        int maj = Integer.parseInt(parts[0]);
        int min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return maj > major || (maj == major && min >= minor);
    }

    private static Path materializeInitScript() throws IOException {
        Path script = Files.createTempFile("sdd-init", ".gradle");
        try (InputStream in = GradleExtractor.class.getResourceAsStream("/sdd/gradle/sdd-init.gradle")) {
            if (in == null) {
                throw new IllegalStateException("missing resource /sdd/gradle/sdd-init.gradle");
            }
            Files.write(script, in.readAllBytes());
        }
        return script;
    }

    private static String stderrTail(ByteArrayOutputStream stderr) {
        String s = stderr.toString(StandardCharsets.UTF_8);
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // temp files; best effort
            }
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.GradleExtractorIT'`
Expected: PASS (3 tests; first run downloads the distribution).

- [ ] **Step 6: Commit**

```bash
git add sdd-index/src
git commit -m "feat: gradle tooling-api extractor with injected init script"
```

---

### Task 8: Consumption-mode classifier + version-catalog reader

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/gradle/ConsumptionMode.java`
- Create: `sdd-index/src/main/java/sdd/index/gradle/ModeClassifier.java`
- Create: `sdd-index/src/main/java/sdd/index/gradle/CatalogReader.java`
- Test: `sdd-index/src/test/java/sdd/index/gradle/ModeClassifierTest.java`
- Test: `sdd-index/src/test/java/sdd/index/gradle/CatalogReaderTest.java`

**Interfaces:**
- Consumes: tomlj.
- Produces:
  - `enum ConsumptionMode { PINNED, SNAPSHOT, DYNAMIC, COMPOSITE, BOM_MANAGED }`
  - `final class ModeClassifier`:
    - `static ConsumptionMode classify(String declaredVersion, boolean producerInIncludedBuilds)` — composite wins over everything; then `-SNAPSHOT` suffix → SNAPSHOT; then dynamic patterns (`+`, `latest.release`, `latest.integration`, or starts with `[`/`(`) → DYNAMIC; then null declared → BOM_MANAGED; else PINNED.
    - `static String declaredVia(String declaredVersion, boolean inCatalog)` — null → `"BOM"`; inCatalog → `"CATALOG"`; else `"DIRECT"`.
  - `final class CatalogReader` with `static Set<String> internalGAs(Path repoDir)` — parses `gradle/libs.versions.toml` if present, returns the set of `"group:name"` coordinates declared in `[libraries]`; empty set when file absent or unparseable.

- [ ] **Step 1: Write the failing tests**

`ModeClassifierTest.java` (table-driven):
```java
package sdd.index.gradle;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ModeClassifierTest {
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "2.3.0,          false, PINNED",
            "2.4.0-SNAPSHOT, false, SNAPSHOT",
            "2.+,            false, DYNAMIC",
            "latest.release, false, DYNAMIC",
            "'[2.0,3.0)',    false, DYNAMIC",
            "NULL,           false, BOM_MANAGED",
            "2.3.0,          true,  COMPOSITE",
            "NULL,           true,  COMPOSITE",
    })
    void classifies(String declared, boolean composite, ConsumptionMode expected) {
        assertThat(ModeClassifier.classify(declared, composite)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "2.3.0, false, DIRECT",
            "2.3.0, true,  CATALOG",
            "NULL,  false, BOM",
            "NULL,  true,  BOM",
    })
    void declaredVia(String declared, boolean inCatalog, String expected) {
        assertThat(ModeClassifier.declaredVia(declared, inCatalog)).isEqualTo(expected);
    }
}
```

`CatalogReaderTest.java`:
```java
package sdd.index.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogReaderTest {
    @TempDir Path repo;

    @Test
    void readsLibraryCoordinates() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [versions]
                core = "2.3.0"
                [libraries]
                lib-core = { module = "com.acme:lib-core", version.ref = "core" }
                commons = { group = "org.apache.commons", name = "commons-lang3", version = "3.14.0" }
                """);
        assertThat(CatalogReader.internalGAs(repo))
                .containsExactlyInAnyOrder("com.acme:lib-core", "org.apache.commons:commons-lang3");
    }

    @Test
    void absentCatalogYieldsEmpty() {
        assertThat(CatalogReader.internalGAs(repo)).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.ModeClassifierTest' --tests 'sdd.index.gradle.CatalogReaderTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ConsumptionMode.java`:
```java
package sdd.index.gradle;

public enum ConsumptionMode { PINNED, SNAPSHOT, DYNAMIC, COMPOSITE, BOM_MANAGED }
```

`ModeClassifier.java`:
```java
package sdd.index.gradle;

public final class ModeClassifier {
    private ModeClassifier() {}

    public static ConsumptionMode classify(String declaredVersion, boolean producerInIncludedBuilds) {
        if (producerInIncludedBuilds) {
            return ConsumptionMode.COMPOSITE;
        }
        if (declaredVersion == null) {
            return ConsumptionMode.BOM_MANAGED;
        }
        if (declaredVersion.endsWith("-SNAPSHOT")) {
            return ConsumptionMode.SNAPSHOT;
        }
        if (declaredVersion.contains("+")
                || declaredVersion.startsWith("latest.")
                || declaredVersion.startsWith("[")
                || declaredVersion.startsWith("(")) {
            return ConsumptionMode.DYNAMIC;
        }
        return ConsumptionMode.PINNED;
    }

    public static String declaredVia(String declaredVersion, boolean inCatalog) {
        if (declaredVersion == null) {
            return "BOM";
        }
        return inCatalog ? "CATALOG" : "DIRECT";
    }
}
```

`CatalogReader.java`:
```java
package sdd.index.gradle;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class CatalogReader {
    private CatalogReader() {}

    public static Set<String> internalGAs(Path repoDir) {
        Path catalog = repoDir.resolve("gradle/libs.versions.toml");
        if (!Files.isRegularFile(catalog)) {
            return Set.of();
        }
        try {
            TomlParseResult toml = Toml.parse(catalog);
            TomlTable libraries = toml.getTable("libraries");
            if (libraries == null) {
                return Set.of();
            }
            Set<String> gas = new HashSet<>();
            for (String key : libraries.keySet()) {
                TomlTable lib = libraries.getTable(key);
                if (lib == null) {
                    continue;
                }
                String module = lib.getString("module");
                if (module != null && module.contains(":")) {
                    gas.add(module);
                } else {
                    String group = lib.getString("group");
                    String name = lib.getString("name");
                    if (group != null && name != null) {
                        gas.add(group + ":" + name);
                    }
                }
            }
            return Set.copyOf(gas);
        } catch (Exception e) {
            return Set.of();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: consumption-mode classifier and version-catalog reader"
```

---

### Task 9: Static fallback parser (degraded mode)

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/gradle/StaticGradleParser.java`
- Test: `sdd-index/src/test/java/sdd/index/gradle/StaticGradleParserTest.java`

**Interfaces:**
- Consumes: `GradleModel`, `CatalogReader`.
- Produces: `final class StaticGradleParser` with `static GradleModel.Extract parse(Path repoDir)` — scans `build.gradle`/`build.gradle.kts` in the repo root AND every first-level subdirectory (multi-module), extracting declared dependencies only (`declared` populated; `resolved`/`unresolved` empty; `configurations` keyed `"compileClasspath"`); recognizes quoted GAV strings, Groovy map syntax, and catalog references `libs.foo.bar` (alias resolved via the catalog file: alias `foo-bar` ↔ accessor `libs.foo.bar`); plugins detected from the `plugins {}` block ids; never executes Groovy/Kotlin.

- [ ] **Step 1: Write the failing test**

```java
package sdd.index.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StaticGradleParserTest {
    @TempDir Path repo;

    @Test
    void parsesQuotedMapAndCatalogDependencies() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [versions]
                core = "2.3.0"
                [libraries]
                lib-core = { module = "com.acme:lib-core", version.ref = "core" }
                """);
        Files.writeString(repo.resolve("settings.gradle"), "rootProject.name = 'svc-x'\n");
        Files.writeString(repo.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.2.0'
                }
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.14.0'
                    api group: 'com.acme', name: 'lib-events', version: '1.0.0-SNAPSHOT'
                    implementation(libs.lib.core)
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.3'
                }
                """);

        GradleModel.Extract e = StaticGradleParser.parse(repo);

        assertThat(e.projects()).hasSize(1);
        GradleModel.Project p = e.projects().get(0);
        assertThat(p.plugins()).contains("java", "org.springframework.boot");
        GradleModel.DepConfig cc = p.configurations().get("compileClasspath");
        assertThat(cc.declared()).extracting(GradleModel.DeclaredDep::name)
                .contains("commons-lang3", "lib-events", "lib-core")
                .doesNotContain("junit-jupiter"); // test-only configs are not compileClasspath
        assertThat(cc.declared()).filteredOn(d -> d.name().equals("lib-core"))
                .first().satisfies(d -> {
                    assertThat(d.group()).isEqualTo("com.acme");
                    assertThat(d.version()).isEqualTo("2.3.0");
                });
        assertThat(cc.resolved()).isEmpty();
    }

    @Test
    void scansFirstLevelSubmodules() throws Exception {
        Files.writeString(repo.resolve("settings.gradle"), "include 'app'\n");
        Files.createDirectories(repo.resolve("app"));
        Files.writeString(repo.resolve("app/build.gradle.kts"), """
                plugins { id("java-library") }
                dependencies { implementation("com.acme:lib-core:2.0.0") }
                """);

        GradleModel.Extract e = StaticGradleParser.parse(repo);
        assertThat(e.projects()).extracting(GradleModel.Project::path).contains(":app");
        assertThat(e.projects()).filteredOn(p -> p.path().equals(":app")).first()
                .satisfies(p -> assertThat(p.configurations().get("compileClasspath").declared())
                        .extracting(GradleModel.DeclaredDep::name).contains("lib-core"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.StaticGradleParserTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package sdd.index.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Declared-only fallback when the Tooling API build fails. Never executes build logic. */
public final class StaticGradleParser {
    // implementation 'group:name:version'  /  implementation("group:name:version")
    private static final Pattern GAV = Pattern.compile(
            "^\\s*(implementation|api|compileOnly|runtimeOnly)\\s*[\\(\\s]\\s*['\"]([\\w.\\-]+):([\\w.\\-]+)(?::([^'\"]+))?['\"]");
    // api group: 'g', name: 'n', version: 'v'
    private static final Pattern MAP_SYNTAX = Pattern.compile(
            "^\\s*(implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*group:\\s*['\"]([\\w.\\-]+)['\"]\\s*,\\s*name:\\s*['\"]([\\w.\\-]+)['\"]\\s*(?:,\\s*version:\\s*['\"]([^'\"]+)['\"])?");
    // implementation(libs.foo.bar) / implementation libs.foo.bar
    private static final Pattern CATALOG_REF = Pattern.compile(
            "^\\s*(implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*libs((?:\\.[A-Za-z0-9]+)+)\\)?");
    private static final Pattern PLUGIN_ID = Pattern.compile("id\\s*\\(?['\"]([\\w.\\-]+)['\"]\\)?");

    private StaticGradleParser() {}

    public static GradleModel.Extract parse(Path repoDir) {
        Map<String, CatalogEntry> catalog = readCatalog(repoDir);
        List<GradleModel.Project> projects = new ArrayList<>();
        parseModule(repoDir, ":", repoDir, catalog, projects);
        try (Stream<Path> children = Files.list(repoDir)) {
            children.filter(Files::isDirectory).sorted().forEach(sub ->
                    parseModule(repoDir, ":" + sub.getFileName(), sub, catalog, projects));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GradleModel.Extract(List.copyOf(projects), List.of());
    }

    private static void parseModule(Path repoDir, String projectPath, Path moduleDir,
                                    Map<String, CatalogEntry> catalog, List<GradleModel.Project> out) {
        Path buildFile = Files.isRegularFile(moduleDir.resolve("build.gradle"))
                ? moduleDir.resolve("build.gradle")
                : moduleDir.resolve("build.gradle.kts");
        if (!Files.isRegularFile(buildFile)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(buildFile);
        } catch (IOException e) {
            return;
        }
        List<String> plugins = new ArrayList<>();
        List<GradleModel.DeclaredDep> declared = new ArrayList<>();
        for (String line : lines) {
            Matcher plugin = PLUGIN_ID.matcher(line);
            while (plugin.find()) {
                plugins.add(plugin.group(1));
            }
            Matcher gav = GAV.matcher(line);
            if (gav.find()) {
                declared.add(new GradleModel.DeclaredDep(gav.group(2), gav.group(3), gav.group(4)));
                continue;
            }
            Matcher map = MAP_SYNTAX.matcher(line);
            if (map.find()) {
                declared.add(new GradleModel.DeclaredDep(map.group(2), map.group(3), map.group(4)));
                continue;
            }
            Matcher cat = CATALOG_REF.matcher(line);
            if (cat.find()) {
                String alias = cat.group(2).substring(1).replace('.', '-').toLowerCase(Locale.ROOT);
                CatalogEntry entry = catalog.get(alias);
                if (entry != null) {
                    declared.add(new GradleModel.DeclaredDep(entry.group, entry.name, entry.version));
                }
            }
        }
        String name = projectPath.equals(":") ? repoDir.getFileName().toString() : moduleDir.getFileName().toString();
        out.add(new GradleModel.Project(projectPath, name, null, null, moduleDir,
                List.copyOf(plugins), false, List.of(),
                Map.of("compileClasspath",
                        new GradleModel.DepConfig(List.copyOf(declared), List.of(), List.of()))));
    }

    private record CatalogEntry(String group, String name, String version) {}

    private static Map<String, CatalogEntry> readCatalog(Path repoDir) {
        Path file = repoDir.resolve("gradle/libs.versions.toml");
        Map<String, CatalogEntry> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            org.tomlj.TomlParseResult toml = org.tomlj.Toml.parse(file);
            org.tomlj.TomlTable versions = toml.getTable("versions");
            org.tomlj.TomlTable libraries = toml.getTable("libraries");
            if (libraries == null) {
                return out;
            }
            for (String key : libraries.keySet()) {
                org.tomlj.TomlTable lib = libraries.getTable(key);
                if (lib == null) {
                    continue;
                }
                String group;
                String name;
                String module = lib.getString("module");
                if (module != null && module.contains(":")) {
                    group = module.substring(0, module.indexOf(':'));
                    name = module.substring(module.indexOf(':') + 1);
                } else {
                    group = lib.getString("group");
                    name = lib.getString("name");
                }
                if (group == null || name == null) {
                    continue;
                }
                String version = lib.getString("version");
                if (version == null) {
                    String ref = lib.getString("version.ref");
                    if (ref != null && versions != null) {
                        version = versions.getString(ref);
                    }
                }
                out.put(key.toLowerCase(Locale.ROOT), new CatalogEntry(group, name, version));
            }
        } catch (Exception ignored) {
            // fallback parser is best-effort by definition
        }
        return out;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.StaticGradleParserTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: static declared-only gradle fallback parser"
```

---

### Task 10: Persistence — modules, artifacts, dep edges, statuses

**Files:**
- Modify: `sdd-core/src/main/resources/sdd/db/V1__init.sql` (repo table gains `included_builds TEXT`; pre-release edit, no V2)
- Create: `sdd-index/src/main/java/sdd/index/store/IndexPersistence.java`
- Test: `sdd-index/src/test/java/sdd/index/store/IndexPersistenceTest.java`

**Interfaces:**
- Consumes: `Database`, `RepoScan`, `GradleModel`, `ModeClassifier`, `CatalogReader`.
- Produces: `final class IndexPersistence` with:
  - `static void persistRepo(org.jdbi.v3.core.Jdbi jdbi, RepoScan scan, GradleModel.Extract extract, String gradleStatus, String error)` — one transaction: upsert `repo` row (name, path, head_commit, branch, dirty_hash, kind rollup, gradle_status, error, indexed_at ISO-8601 UTC, included_builds as JSON array of absolute paths); delete the repo's old `module` rows (cascade clears artifacts/dep_edges); insert modules with classification, artifacts (from publications, else `group`+`name` when group present), and dep_edges (declared+resolved merged per GA: declared_version, resolved_version, configuration, mode via `ModeClassifier.classify(declared, false)` — composite re-check happens in Task 11's linker — declared_via via catalog membership).
  - Module classification (spec): `org.springframework.boot` plugin or bootJar task → `SERVICE`; else `maven-publish` plugin or non-empty publications → `LIBRARY`; else `java`/`java-library` → `UNKNOWN`; no java at all → `UNKNOWN`. Repo kind rollup: any SERVICE and any LIBRARY → `MIXED`; any SERVICE → `SERVICE`; any LIBRARY → `LIBRARY`; else `UNKNOWN`.
  - `static void markStale(Jdbi jdbi, String repoName, String error)` — sets `gradle_status='STALE_OK'`, error, leaves data intact.

- [ ] **Step 1: Extend V1 schema**

In `V1__init.sql`, change the `repo` table to add one column after `dirty_hash`:
```sql
  included_builds TEXT,
```
(Existing `DatabaseTest` table assertions don't enumerate columns; pre-release schema edits are sanctioned — same precedent as the `words` column.)

- [ ] **Step 2: Write the failing test**

```java
package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.gradle.GradleModel;
import sdd.index.scan.RepoScan;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexPersistenceTest {
    @TempDir Path ws;

    private static GradleModel.Extract serviceExtract() {
        return new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", "svc-orders", "com.acme", "0.1.0", Path.of("/w/svc-orders"),
                List.of("java", "org.springframework.boot"), true,
                List.of(),
                Map.of("compileClasspath", new GradleModel.DepConfig(
                        List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "2.3.0"),
                                new GradleModel.DeclaredDep("com.acme", "lib-events", "1.0.0-SNAPSHOT"),
                                new GradleModel.DeclaredDep("com.acme", "lib-bom-managed", null)),
                        List.of(new GradleModel.ResolvedDep("com.acme", "lib-core", "2.3.0", List.of())),
                        List.of())))),
                List.of(Path.of("/w/lib-included")));
    }

    @Test
    void persistsRepoModulesEdgesAndStatuses() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);

            Map<String, Object> repo = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT kind, gradle_status, included_builds FROM repo WHERE name='svc-orders'")
                            .mapToMap().one());
            assertThat(repo.get("kind")).isEqualTo("SERVICE");
            assertThat(repo.get("gradle_status")).isEqualTo("OK");
            assertThat((String) repo.get("included_builds")).contains("lib-included");

            List<Map<String, Object>> edges = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT to_name, declared_version, resolved_version, mode, declared_via "
                            + "FROM dep_edge ORDER BY to_name").mapToMap().list());
            assertThat(edges).hasSize(3);
            assertThat(edges.get(1)).containsEntry("to_name", "lib-core")
                    .containsEntry("mode", "PINNED").containsEntry("declared_via", "DIRECT")
                    .containsEntry("resolved_version", "2.3.0");
            assertThat(edges.get(0)).containsEntry("to_name", "lib-bom-managed")
                    .containsEntry("mode", "BOM_MANAGED").containsEntry("declared_via", "BOM");
            assertThat(edges.get(2)).containsEntry("to_name", "lib-events")
                    .containsEntry("mode", "SNAPSHOT");
        }
    }

    @Test
    void reindexReplacesOldRowsAndMarkStalePreservesThem() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            Integer modules = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM module").mapTo(Integer.class).one());
            assertThat(modules).isEqualTo(1); // replaced, not duplicated

            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            Map<String, Object> repo = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT gradle_status, error FROM repo WHERE name='svc-orders'")
                            .mapToMap().one());
            assertThat(repo.get("gradle_status")).isEqualTo("STALE_OK");
            assertThat(db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM dep_edge").mapTo(Integer.class).one())).isEqualTo(3);
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.*'`
Expected: FAIL.

- [ ] **Step 4: Implement**

```java
package sdd.index.store;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.index.gradle.CatalogReader;
import sdd.index.gradle.GradleModel;
import sdd.index.gradle.ModeClassifier;
import sdd.index.scan.RepoScan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class IndexPersistence {
    private IndexPersistence() {}

    public static void persistRepo(Jdbi jdbi, RepoScan scan, GradleModel.Extract extract,
                                   String gradleStatus, String error) {
        Set<String> catalogGAs = CatalogReader.internalGAs(scan.path());
        jdbi.useTransaction(h -> {
            String includedJson = extract.includedBuilds().stream()
                    .map(p -> '"' + p.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"')
                    .collect(Collectors.joining(",", "[", "]"));
            String repoKind = rollupKind(extract);
            h.createUpdate("""
                            INSERT INTO repo(name, path, kind, head_commit, branch, dirty_hash,
                                             included_builds, gradle_status, error, indexed_at)
                            VALUES (:name, :path, :kind, :head, :branch, :dirty, :included, :status, :error, :at)
                            ON CONFLICT(name) DO UPDATE SET
                              path=excluded.path, kind=excluded.kind, head_commit=excluded.head_commit,
                              branch=excluded.branch, dirty_hash=excluded.dirty_hash,
                              included_builds=excluded.included_builds, gradle_status=excluded.gradle_status,
                              error=excluded.error, indexed_at=excluded.indexed_at""")
                    .bind("name", scan.name()).bind("path", scan.path().toString())
                    .bind("kind", repoKind).bind("head", scan.headCommit())
                    .bind("branch", scan.branch()).bind("dirty", scan.dirtyHash())
                    .bind("included", includedJson).bind("status", gradleStatus)
                    .bind("error", error).bind("at", Instant.now().toString())
                    .execute();
            long repoId = h.createQuery("SELECT id FROM repo WHERE name=:n")
                    .bind("n", scan.name()).mapTo(Long.class).one();
            h.createUpdate("DELETE FROM module WHERE repo_id=:r").bind("r", repoId).execute();
            for (GradleModel.Project p : extract.projects()) {
                insertModule(h, repoId, p, catalogGAs);
            }
        });
    }

    private static void insertModule(Handle h, long repoId, GradleModel.Project p, Set<String> catalogGAs) {
        String kind = moduleKind(p);
        h.createUpdate("INSERT INTO module(repo_id, gradle_path, grp, name, version, kind) "
                        + "VALUES (:r, :path, :grp, :name, :ver, :kind)")
                .bind("r", repoId).bind("path", p.path()).bind("grp", p.group())
                .bind("name", p.name()).bind("ver", p.version()).bind("kind", kind)
                .execute();
        long moduleId = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();

        if (!p.publications().isEmpty()) {
            for (GradleModel.Publication pub : p.publications()) {
                insertArtifact(h, moduleId, pub.groupId(), pub.artifactId());
            }
        } else if (p.group() != null && !p.group().isBlank()) {
            insertArtifact(h, moduleId, p.group(), p.name());
        }

        Map<String, MergedDep> merged = new LinkedHashMap<>();
        p.configurations().forEach((cfgName, cfg) -> {
            for (GradleModel.DeclaredDep d : cfg.declared()) {
                merged.computeIfAbsent(d.group() + ":" + d.name(),
                        k -> new MergedDep(d.group(), d.name(), cfgName)).declaredVersion = d.version();
            }
            for (GradleModel.ResolvedDep r : cfg.resolved()) {
                merged.computeIfAbsent(r.group() + ":" + r.name(),
                        k -> new MergedDep(r.group(), r.name(), cfgName)).resolvedVersion = r.version();
            }
        });
        for (MergedDep d : merged.values()) {
            boolean inCatalog = catalogGAs.contains(d.group + ":" + d.name);
            h.createUpdate("""
                            INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration,
                                                 declared_version, resolved_version, declared_via, mode)
                            VALUES (:m, :g, :n, :cfg, :dv, :rv, :via, :mode)""")
                    .bind("m", moduleId).bind("g", d.group).bind("n", d.name).bind("cfg", d.configuration)
                    .bind("dv", d.declaredVersion).bind("rv", d.resolvedVersion)
                    .bind("via", ModeClassifier.declaredVia(d.declaredVersion, inCatalog))
                    .bind("mode", ModeClassifier.classify(d.declaredVersion, false).name())
                    .execute();
        }
    }

    private static void insertArtifact(Handle h, long moduleId, String grp, String name) {
        h.createUpdate("INSERT INTO artifact(grp, name, module_id) VALUES (:g, :n, :m) "
                        + "ON CONFLICT(grp, name) DO UPDATE SET module_id=excluded.module_id")
                .bind("g", grp).bind("n", name).bind("m", moduleId).execute();
    }

    private static String moduleKind(GradleModel.Project p) {
        boolean boot = p.plugins().contains("org.springframework.boot") || p.hasBootJarTask();
        if (boot) {
            return "SERVICE";
        }
        if (p.plugins().contains("maven-publish") || !p.publications().isEmpty()) {
            return "LIBRARY";
        }
        return "UNKNOWN";
    }

    private static String rollupKind(GradleModel.Extract extract) {
        boolean svc = false;
        boolean lib = false;
        for (GradleModel.Project p : extract.projects()) {
            String k = moduleKind(p);
            svc |= k.equals("SERVICE");
            lib |= k.equals("LIBRARY");
        }
        if (svc && lib) {
            return "MIXED";
        }
        if (svc) {
            return "SERVICE";
        }
        return lib ? "LIBRARY" : "UNKNOWN";
    }

    public static void markStale(Jdbi jdbi, String repoName, String error) {
        jdbi.useHandle(h -> h.createUpdate(
                        "UPDATE repo SET gradle_status='STALE_OK', error=:e WHERE name=:n")
                .bind("e", error).bind("n", repoName).execute());
    }

    private static final class MergedDep {
        final String group;
        final String name;
        final String configuration;
        String declaredVersion;
        String resolvedVersion;

        MergedDep(String group, String name, String configuration) {
            this.group = group;
            this.name = name;
            this.configuration = configuration;
        }
    }
}
```

Note for the implementer: `repo.name` needs a UNIQUE constraint for `ON CONFLICT(name)` — V1 already declares `name TEXT NOT NULL UNIQUE`. `Instant.now()` is fine here (production code, not a Workflow script).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.*'` then `./gradlew :sdd-core:test`
Expected: PASS (schema edit must not break sdd-core tests).

- [ ] **Step 6: Commit**

```bash
git add sdd-core/src sdd-index/src
git commit -m "feat: index persistence with module classification and mode-annotated edges"
```

---

### Task 11: ArtifactLinker — internal-edge marking, composite re-check, conflicts

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/store/ArtifactLinker.java`
- Test: `sdd-index/src/test/java/sdd/index/store/ArtifactLinkerTest.java`

**Interfaces:**
- Consumes: persisted tables from Task 10.
- Produces: `final class ArtifactLinker` with `record LinkReport(int internalEdges, List<String> conflicts, List<String> orphanArtifacts)` and `static LinkReport link(Jdbi jdbi, Map<String,String> artifactOverrides)`:
  1. Apply overrides: for each `"grp:name" → repoName`, point the artifact row at that repo's root module (`gradle_path=':'`), creating the artifact row if absent; unknown repo name → conflict entry, skipped.
  2. Mark internal edges: `UPDATE dep_edge SET is_internal=1, to_module_id=(SELECT a.module_id FROM artifact a WHERE a.grp=dep_edge.to_grp AND a.name=dep_edge.to_name) WHERE EXISTS(...)`; also reset previously-internal edges whose artifact vanished.
  3. Composite re-check: for internal edges where the consumer repo's `included_builds` JSON contains the producer repo's path → `mode='COMPOSITE'`.
  4. Report: count internal edges; conflicts (same GA claimed by modules of different repos is prevented by the UNIQUE constraint — instead report overrides that failed); orphans = internal library artifacts consumed by zero edges (spec sanity check "every internal library GA is consumed by ≥ 1 module or warn").

- [ ] **Step 1: Write the failing test**

```java
package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.gradle.GradleModel;
import sdd.index.scan.RepoScan;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactLinkerTest {
    @TempDir Path ws;
    private Database db;

    private static GradleModel.Project project(String name, String grp, List<String> plugins,
                                               List<GradleModel.DeclaredDep> deps) {
        return new GradleModel.Project(":", name, grp, "1.0", Path.of("/w/" + name),
                plugins, false, List.of(),
                Map.of("compileClasspath",
                        new GradleModel.DepConfig(deps, List.of(), List.of())));
    }

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        // lib-core: publishes com.acme:lib-core
        IndexPersistence.persistRepo(db.jdbi(),
                new RepoScan("lib-core", Path.of("/w/lib-core"), "b".repeat(40), "main", ""),
                new GradleModel.Extract(List.of(project("lib-core", "com.acme",
                        List.of("java-library", "maven-publish"), List.of())), List.of()),
                "OK", null);
        // svc-orders: depends on lib-core (pinned) and lib-included (composite via includedBuilds)
        IndexPersistence.persistRepo(db.jdbi(),
                new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", ""),
                new GradleModel.Extract(List.of(project("svc-orders", "com.acme",
                        List.of("java", "org.springframework.boot"),
                        List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "1.0"),
                                new GradleModel.DeclaredDep("com.acme", "lib-included", "1.0"),
                                new GradleModel.DeclaredDep("org.apache.commons", "commons-lang3", "3.14.0")))),
                        List.of(Path.of("/w/lib-included"))),
                "OK", null);
        // lib-included: composite producer
        IndexPersistence.persistRepo(db.jdbi(),
                new RepoScan("lib-included", Path.of("/w/lib-included"), "c".repeat(40), "main", ""),
                new GradleModel.Extract(List.of(project("lib-included", "com.acme",
                        List.of("java-library", "maven-publish"), List.of())), List.of()),
                "OK", null);
    }

    @Test
    void marksInternalEdgesAndCompositeAndReportsOrphans() {
        ArtifactLinker.LinkReport report = ArtifactLinker.link(db.jdbi(), Map.of());

        assertThat(report.internalEdges()).isEqualTo(2);
        List<Map<String, Object>> internal = db.jdbi().withHandle(h ->
                h.createQuery("SELECT to_name, mode, is_internal FROM dep_edge WHERE is_internal=1 ORDER BY to_name")
                        .mapToMap().list());
        assertThat(internal).hasSize(2);
        assertThat(internal.get(0)).containsEntry("to_name", "lib-core").containsEntry("mode", "PINNED");
        assertThat(internal.get(1)).containsEntry("to_name", "lib-included").containsEntry("mode", "COMPOSITE");
        // commons-lang3 stays external
        Integer external = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM dep_edge WHERE to_name='commons-lang3' AND is_internal=0")
                .mapTo(Integer.class).one());
        assertThat(external).isEqualTo(1);
        assertThat(report.orphanArtifacts()).isEmpty();
        assertThat(report.conflicts()).isEmpty();
    }

    @Test
    void overridesRemapAndUnknownRepoIsConflict() {
        ArtifactLinker.LinkReport report = ArtifactLinker.link(db.jdbi(),
                Map.of("org.apache.commons:commons-lang3", "lib-core",
                       "com.acme:ghost", "no-such-repo"));

        Integer remapped = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM dep_edge WHERE to_name='commons-lang3' AND is_internal=1")
                .mapTo(Integer.class).one());
        assertThat(remapped).isEqualTo(1);
        assertThat(report.conflicts()).anySatisfy(c -> assertThat(c).contains("no-such-repo"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.ArtifactLinkerTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package sdd.index.store;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ArtifactLinker {
    public record LinkReport(int internalEdges, List<String> conflicts, List<String> orphanArtifacts) {}

    private ArtifactLinker() {}

    public static LinkReport link(Jdbi jdbi, Map<String, String> artifactOverrides) {
        List<String> conflicts = new ArrayList<>();
        int internalEdges = jdbi.inTransaction(h -> {
            for (Map.Entry<String, String> e : artifactOverrides.entrySet()) {
                String[] ga = e.getKey().split(":", 2);
                Optional<Long> rootModule = h.createQuery("""
                                SELECT m.id FROM module m JOIN repo r ON r.id = m.repo_id
                                WHERE r.name = :repo AND m.gradle_path = ':'""")
                        .bind("repo", e.getValue()).mapTo(Long.class).findOne();
                if (rootModule.isEmpty()) {
                    conflicts.add("override " + e.getKey() + " -> unknown repo '" + e.getValue() + "'");
                    continue;
                }
                h.createUpdate("INSERT INTO artifact(grp, name, module_id) VALUES (:g, :n, :m) "
                                + "ON CONFLICT(grp, name) DO UPDATE SET module_id=excluded.module_id")
                        .bind("g", ga[0]).bind("n", ga.length > 1 ? ga[1] : "")
                        .bind("m", rootModule.get()).execute();
            }

            h.execute("UPDATE dep_edge SET is_internal=0, to_module_id=NULL");
            h.execute("""
                    UPDATE dep_edge SET is_internal=1,
                      to_module_id=(SELECT a.module_id FROM artifact a
                                    WHERE a.grp=dep_edge.to_grp AND a.name=dep_edge.to_name)
                    WHERE EXISTS(SELECT 1 FROM artifact a
                                 WHERE a.grp=dep_edge.to_grp AND a.name=dep_edge.to_name)""");
            h.execute("""
                    UPDATE dep_edge SET mode='COMPOSITE'
                    WHERE is_internal=1 AND EXISTS(
                      SELECT 1 FROM module cm JOIN repo cr ON cr.id=cm.repo_id,
                                   module pm JOIN repo pr ON pr.id=pm.repo_id
                      WHERE cm.id=dep_edge.from_module_id AND pm.id=dep_edge.to_module_id
                        AND cr.included_builds LIKE '%"' || pr.path || '"%')""");
            return h.createQuery("SELECT count(*) FROM dep_edge WHERE is_internal=1")
                    .mapTo(Integer.class).one();
        });

        List<String> orphans = jdbi.withHandle(h -> h.createQuery("""
                        SELECT a.grp || ':' || a.name FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        WHERE m.kind = 'LIBRARY'
                          AND NOT EXISTS(SELECT 1 FROM dep_edge e
                                         WHERE e.to_grp = a.grp AND e.to_name = a.name AND e.is_internal = 1)""")
                .mapTo(String.class).list());
        return new LinkReport(internalEdges, List.copyOf(conflicts), List.copyOf(orphans));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: artifact linker with overrides, composite re-check, orphan report"
```

---

### Task 12: IndexService + `sdd index` command + end-to-end integration test

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/IndexService.java`
- Create: `sdd-cli/src/main/java/sdd/cli/IndexCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/SddCli.java` (register subcommand)
- Modify: `sdd-cli/build.gradle.kts` (add `implementation(project(":sdd-index"))` and `testImplementation(testFixtures(project(":sdd-index")))`)
- Test: `sdd-index/src/test/java/sdd/index/IndexServiceIT.java` (`@Tag("gradle-it")`)

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `final class IndexService` with `record RepoResult(String repo, String status, int modules, int internalDeps, boolean skipped, String error)` and `List<RepoResult> run(SddConfig config, Database db)`:
    1. `WorkspaceScanner.scan(config.workspace(), config.excludes())`.
    2. Per repo: skip when stored fingerprint (`head_commit || ':' || dirty_hash`) matches AND `gradle_status='OK'` (result `skipped=true`, status `OK`). Else `GradleExtractor.extract`; on `ExtractionException` → `StaticGradleParser.parse` with status `DEGRADED` (status `FAILED` with `markStale` if the repo already has rows; `FAILED` with error row upsert if brand new and even the fallback throws).
    3. `IndexPersistence.persistRepo(...)` with the achieved status.
    4. After all repos: `ArtifactLinker.link(db.jdbi(), config.artifactOverrides())`.
    5. `internalDeps` per repo counted from `dep_edge` joined via module.
  - `sdd index [--workspace <dir>]` — runs the service, prints one line per repo: `<repo>  <status>  modules=<n> internal-deps=<n><skipped marker><error tail>`, then the linker summary (`internal edges`, `conflicts`, `orphan artifacts`); exit 0 unless every repo FAILED (then 1).

- [ ] **Step 1: Write the failing integration test**

```java
package sdd.index;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.testing.FixtureGradleRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("gradle-it")
class IndexServiceIT {
    @TempDir Path ws;

    private SddConfig config() {
        return new SddConfig(ws, "fts", Map.of(), Map.of(), List.of(), Map.of());
    }

    private void buildFixtureEstate() {
        FixtureGradleRepo.in(ws, "lib-core", "8.10.2")
                .withSettings("rootProject.name = 'lib-core'\n")
                .withBuildFile("""
                        plugins { id 'java-library'; id 'maven-publish' }
                        group = 'com.acme'
                        version = '2.3.0'
                        publishing { publications { maven(MavenPublication) { from components.java } } }
                        """)
                .withFile("src/main/java/C.java", "public class C {}\n")
                .commit();
        FixtureGradleRepo.in(ws, "svc-orders", "8.10.2")
                .withSettings("rootProject.name = 'svc-orders'\n")
                .withBuildFile("""
                        plugins { id 'java' }
                        group = 'com.acme'
                        version = '0.1.0'
                        repositories { mavenCentral() }
                        dependencies { implementation 'com.acme:lib-core:2.3.0' }
                        """)
                .withFile("src/main/java/A.java", "public class A {}\n")
                .commit();
        FixtureGradleRepo.in(ws, "broken-build", "8.10.2")
                .withSettings("throw new GradleException('kaput')\n")
                .withFile("build.gradle", """
                        plugins { id 'java' }
                        dependencies { implementation 'com.acme:lib-core:2.3.0' }
                        """)
                .commit();
    }

    @Test
    void indexesEstateWithDegradedFallbackAndIncrementalSkip() {
        buildFixtureEstate();
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService();
            List<IndexService.RepoResult> first = service.run(config(), db);

            assertThat(first).extracting(IndexService.RepoResult::repo)
                    .containsExactly("broken-build", "lib-core", "svc-orders");
            assertThat(first).filteredOn(r -> r.repo().equals("broken-build")).first()
                    .satisfies(r -> assertThat(r.status()).isEqualTo("DEGRADED"));
            assertThat(first).filteredOn(r -> r.repo().equals("svc-orders")).first()
                    .satisfies(r -> {
                        assertThat(r.status()).isEqualTo("OK");
                        assertThat(r.internalDeps()).isEqualTo(1);
                    });

            // internal edge svc-orders -> lib-core is marked and PINNED
            Map<String, Object> edge = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT is_internal, mode FROM dep_edge WHERE to_name='lib-core' AND is_internal=1")
                    .mapToMap().one());
            assertThat(edge).containsEntry("mode", "PINNED");
            // broken-build's declared-only edge also links internally
            Integer internalCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM dep_edge WHERE is_internal=1").mapTo(Integer.class).one());
            assertThat(internalCount).isEqualTo(2);

            // second run: clean repos skip (fingerprint unchanged); DEGRADED repo retries
            List<IndexService.RepoResult> second = service.run(config(), db);
            assertThat(second).filteredOn(r -> r.repo().equals("svc-orders")).first()
                    .satisfies(r -> assertThat(r.skipped()).isTrue());
            assertThat(second).filteredOn(r -> r.repo().equals("broken-build")).first()
                    .satisfies(r -> assertThat(r.skipped()).isFalse());
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.IndexServiceIT'`
Expected: FAIL — `IndexService` doesn't exist.

- [ ] **Step 3: Implement `IndexService`**

```java
package sdd.index;

import org.jdbi.v3.core.Jdbi;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.gradle.ExtractionException;
import sdd.index.gradle.GradleExtractor;
import sdd.index.gradle.GradleModel;
import sdd.index.gradle.StaticGradleParser;
import sdd.index.scan.RepoScan;
import sdd.index.scan.WorkspaceScanner;
import sdd.index.store.ArtifactLinker;
import sdd.index.store.IndexPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class IndexService {
    public record RepoResult(String repo, String status, int modules, int internalDeps,
                             boolean skipped, String error) {}

    private ArtifactLinker.LinkReport lastLinkReport;

    public List<RepoResult> run(SddConfig config, Database db) {
        List<RepoScan> scans = WorkspaceScanner.scan(config.workspace(), config.excludes());
        GradleExtractor extractor = new GradleExtractor(config.jdkHomes());
        List<RepoResult> results = new ArrayList<>();
        for (RepoScan scan : scans) {
            results.add(indexRepo(db.jdbi(), extractor, scan));
        }
        lastLinkReport = ArtifactLinker.link(db.jdbi(), config.artifactOverrides());
        return results.stream().map(r -> withCounts(db.jdbi(), r)).toList();
    }

    public ArtifactLinker.LinkReport lastLinkReport() {
        return lastLinkReport;
    }

    private RepoResult indexRepo(Jdbi jdbi, GradleExtractor extractor, RepoScan scan) {
        Optional<String> stored = jdbi.withHandle(h -> h.createQuery(
                        "SELECT head_commit || ':' || dirty_hash FROM repo WHERE name=:n AND gradle_status='OK'")
                .bind("n", scan.name()).mapTo(String.class).findOne());
        if (stored.isPresent() && stored.get().equals(scan.fingerprint())) {
            return new RepoResult(scan.name(), "OK", 0, 0, true, null);
        }
        try {
            GradleModel.Extract extract = extractor.extract(scan.path());
            IndexPersistence.persistRepo(jdbi, scan, extract, "OK", null);
            return new RepoResult(scan.name(), "OK", extract.projects().size(), 0, false, null);
        } catch (ExtractionException gradleFailure) {
            try {
                GradleModel.Extract fallback = StaticGradleParser.parse(scan.path());
                IndexPersistence.persistRepo(jdbi, scan, fallback, "DEGRADED", gradleFailure.getMessage());
                return new RepoResult(scan.name(), "DEGRADED", fallback.projects().size(), 0, false,
                        gradleFailure.getMessage());
            } catch (RuntimeException fallbackFailure) {
                boolean hasRows = jdbi.withHandle(h -> h.createQuery(
                                "SELECT count(*) FROM repo WHERE name=:n").bind("n", scan.name())
                        .mapTo(Integer.class).one()) > 0;
                if (hasRows) {
                    IndexPersistence.markStale(jdbi, scan.name(), fallbackFailure.getMessage());
                    return new RepoResult(scan.name(), "STALE_OK", 0, 0, false, fallbackFailure.getMessage());
                }
                IndexPersistence.persistRepo(jdbi, scan,
                        new GradleModel.Extract(List.of(), List.of()), "FAILED", fallbackFailure.getMessage());
                return new RepoResult(scan.name(), "FAILED", 0, 0, false, fallbackFailure.getMessage());
            }
        }
    }

    private RepoResult withCounts(Jdbi jdbi, RepoResult r) {
        int modules = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM module m JOIN repo rp ON rp.id=m.repo_id WHERE rp.name=:n""")
                .bind("n", r.repo()).mapTo(Integer.class).one());
        int internal = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM dep_edge e JOIN module m ON m.id=e.from_module_id
                        JOIN repo rp ON rp.id=m.repo_id WHERE rp.name=:n AND e.is_internal=1""")
                .bind("n", r.repo()).mapTo(Integer.class).one());
        return new RepoResult(r.repo(), r.status(), modules, internal, r.skipped(), r.error());
    }
}
```

- [ ] **Step 4: Run the integration test**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.IndexServiceIT'`
Expected: PASS.

- [ ] **Step 5: Implement the CLI command**

`sdd-cli/build.gradle.kts` — add to dependencies: `implementation(project(":sdd-index"))`.

`IndexCommand.java`:
```java
package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.IndexService;
import sdd.index.store.ArtifactLinker;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "index", description = "Build or refresh the knowledge base for a workspace")
public final class IndexCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        SddConfig config = ConfigLoader.load(workspace);
        try (Database db = Database.open(workspace)) {
            IndexService service = new IndexService();
            List<IndexService.RepoResult> results = service.run(config, db);
            for (IndexService.RepoResult r : results) {
                out.printf("%-28s %-9s modules=%-3d internal-deps=%-3d%s%s%n",
                        r.repo(), r.status(), r.modules(), r.internalDeps(),
                        r.skipped() ? " (unchanged, skipped)" : "",
                        r.error() == null ? "" : "  ! " + firstLine(r.error()));
            }
            ArtifactLinker.LinkReport link = service.lastLinkReport();
            out.printf("link: %d internal edges, %d conflicts, %d orphan artifacts%n",
                    link.internalEdges(), link.conflicts().size(), link.orphanArtifacts().size());
            link.conflicts().forEach(c -> out.println("  conflict: " + c));
            link.orphanArtifacts().forEach(o -> out.println("  orphan: " + o));
            boolean allFailed = !results.isEmpty()
                    && results.stream().allMatch(r -> r.status().equals("FAILED"));
            return allFailed ? 1 : 0;
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
```

In `SddCli.java` change the subcommands to `subcommands = {DoctorCommand.class, IndexCommand.class}`.

- [ ] **Step 6: Full build + manual smoke**

Run: `./gradlew build` — everything green.
Then: `./gradlew :sdd-cli:installDist --quiet` and run `sdd-cli/build/install/sdd/bin/sdd index --workspace <a-temp-dir-with-one-fixture-repo>` manually (create one with two tiny repos or reuse a test temp dir) — table prints, exit 0.

- [ ] **Step 7: Commit**

```bash
git add sdd-cli sdd-index
git commit -m "feat: sdd index command with incremental skip and degraded fallback"
```

---

## Self-Review (completed at write time)

1. **Spec coverage (2A scope):** workspace scan + per-module classification → Tasks 5, 10; Tooling API + init script + JDK map + `--no-configuration-cache` gate + timeout → Task 7; static fallback → Task 9; five consumption modes + declared_via incl. catalog detection → Task 8 (composite re-check in Task 11); artifact→module mapping with loud conflicts + `sdd.yml artifactOverrides` + orphan sanity check → Tasks 2, 11; incremental fingerprint skip + keep-last-good statuses → Tasks 5, 10, 12; summary-table exit-code contract → Task 12; entry criteria 1–4 → Tasks 1–3 (busy_timeout in 1). Deferred to 2B/2C by design: source extraction, FTS population (via Task 3's writer), REST/Kafka links, repo cards, curation report, golden files, spring_app_name/context_path columns.
2. **Placeholder scan:** all code steps carry complete code; no TBDs. The one external variable (Gradle distribution download time in gradle-it tests) is documented, not hand-waved.
3. **Type consistency:** `RepoScan(name, path, headCommit, branch, dirtyHash)` + `fingerprint()` used identically in Tasks 5, 10, 12; `GradleModel.Extract/Project/DepConfig/DeclaredDep/ResolvedDep/Publication` shapes match across Tasks 6, 7, 9, 10, 11, 12; `ConsumptionMode` five values everywhere; `SddConfig` six-component shape (Task 2) used in Task 12's test and `IndexService.run`; `IndexPersistence.persistRepo(Jdbi, RepoScan, Extract, String, String)` and `markStale(Jdbi, String, String)` consistent between Tasks 10 and 12; `ArtifactLinker.link(Jdbi, Map<String,String>)` consistent between Tasks 11 and 12.

---

## Execution outcome (2026-08-11)

All 12 tasks complete via subagent-driven execution; final whole-branch review "With fixes"; fix wave landed and re-review verified (95 tests green incl. real-Gradle ITs). Key approved deviations beyond the plan text: init script captures includedBuilds in `projectsLoaded` (settingsEvaluated fires too early — plan bug); `dep_edge.to_module_id ON DELETE SET NULL` (producer re-index after link no longer FK-crashes); **dep_edge rows are declared-deps-only** (resolved fills versions; transitives excluded — plan's merge created phantom BOM_MANAGED edges); scan failures degrade per repo (never sink the run); near-empty fallback prefers STALE_OK over wiping rows; GA cross-repo conflicts recorded in repo.error.

**Carry into Plan 2B/2C:**
1. 2B reads the full resolved classpath from the extractor output (NOT dep_edge — declared-only by contract now); `GradleModel.ResolvedDep.files` carries the jars for the symbol solver.
2. First 2B tests: real-includeBuild composite IT; mixed-wrapper fixture (6.9/7.6) exercising setJavaHome + config-cache gate; markStale and timeout paths currently unit/statically verified only.
3. 2C curation report must surface: GA conflicts (parked — repo.error column only today, and markStale overwrites it), linker orphans, DYNAMIC edges, scan-failure repos; consider static includeBuild scrape in fallback parser (DEGRADED currently writes included_builds=[] flipping COMPOSITE→PINNED on relink).
4. Unify StaticGradleParser's duplicate catalog TOML parsing with CatalogReader (+ hasErrors guard, underscore alias normalization).
5. `sdd index` prints per-repo lines only at the end; stream them per-repo for 40-repo cold runs; empty workspace prints bare link line (exit 0) — improve messaging.
6. Deferred minors list: see .superpowers ledger content preserved in this section's review history (LIKE-escape in composite match, executor timeout thread, latest. prefix breadth, first-dep-per-line, block-comment bodies).
