# Phase 4C-2b: MAVEN_LOCAL Fallback + Version-Bump Edits — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdd implement` executes the MAVEN_LOCAL propagation fallback (design line 61): a changed provider on a MAVEN_LOCAL edge publishes to a run-scoped `<runDir>/m2` with its planned version after success, every consumer build resolves from it via an injected init script, and PINNED dependency declarations are deterministically bumped to the provider's planned version at their declaration site before the agent runs.

**Architecture:** All new code lives in `sdd-cli`'s `sdd.cli.implement` package — `sdd-agent` is untouched and `GradleTool.ALLOWED` keeps `publishToMavenLocal` locked out. Deterministic data first: `PlannedVersions` (KB root-module version × plan `version_action` semver policy), `DeclaredDeps` (KB dep_edge query), and `PropagationPlanner` assemble a per-repo `RepoPropagation` (bump edits + publish spec) before the run lock is taken. Execution second: `Orchestrator` applies bump edits after every `startBranch` (both attempts) and, on SUCCESS, runs the orchestrator-only `MavenLocalPublisher` (a `GradleSmokeRunner`-style env-scrubbed subprocess) before declaring the repo SUCCEEDED; consumer builds get `--init-script <runDir>/maven-local-init.gradle` through the existing `RunnerSettings.gradleExtraArgs → GradleTool` channel, so `run_gradle` and the verify gate see the run-scoped repo identically.

**Tech Stack:** Java 21, Jdbi/SQLite (KB reads), ProcessBuilder (publish subprocess), JUnit 5 + AssertJ, FixtureRepo + stub `gradlew` scripts, ScriptedChatModel.

## Global Constraints

- **Scope:** MAVEN_LOCAL fallback end-to-end + PINNED bump edits at DIRECT/CATALOG declaration sites. Explicitly DEFERRED: **BOM_MANAGED cross-repo declaration-site resolution and step-less bump-site widening** — the KB records `declared_version`/`declared_via` but NO declaration file/line (recon-verified: no such column anywhere in V1__init.sql), so locating a BOM's declaring repo/file needs indexer schema work first; also deferred: the `group = "g", name = "n"` two-key catalog form (only `module = "g:n"` and inline `"g:n:v"` are handled), builds using `dependencyResolutionManagement { repositoriesMode = FAIL_ON_PROJECT_REPOS }` (the init script injects project repositories), `m2/` cleanup (Phase 5 `sdd clean`), and real-estate `-Pversion` validation (Phase-0 spike — a build that hard-assigns `version = '…'` in build.gradle overrides `-Pversion`; fixtures here respect it, the spike validates reality).
- **Fixture-only phase:** the trading estate is all-INCLUDE_BUILD; zero real-estate coverage. Everything is proven with FixtureRepo + stub `gradlew` + real SQLite KB rows.
- **Agent guardrails inviolate:** `GradleTool.ALLOWED` is NOT widened; `GradleToolTest.disallowedTaskNeverRuns` (which pins `publishToMavenLocal` as disallowed) must remain untouched and green. Publishing is orchestrator-owned via a new class the model can never reach.
- **Ratified interpretations (flag at review if you disagree):** (a) a MAVEN_LOCAL provider WITHOUT a plan step needs no publish and triggers no bumps — an unchanged artifact keeps resolving from wherever it is already published; (b) PINNED declarations bump on ALL mechanisms (INCLUDE_BUILD substitution ignores the requested version, and the release runbook needs the new pin either way); SNAPSHOT/DYNAMIC declarations never bump; (c) when ANY plan edge has mechanism MAVEN_LOCAL, EVERY repo's Gradle invocations get the init script — init scripts are invocation-global (they reach included builds inside a composite, which per-project injection cannot), and an extra last-position `maven { url }` repo is inert for builds that never request a planned version; (d) publish failure after the checkpoint commit: infra-classified log → `PAUSED_INFRA` (break), otherwise `FAILED` (cascade) — the commit exists but the repo is not SUCCEEDED, and the detail says why; (e) SUCCEEDED is recorded only after a required publish succeeds (crash between commit and publish leaves IN_PROGRESS → `--resume` re-runs the repo); (f) bump edits are re-applied after every `startBranch` (hard reset wipes them) and land in the checkpoint commit via the existing `commitAll`.
- **Lock discipline (4C-3 invariant):** propagation planning (KB queries, problem detection) runs INSIDE each fork branch of `ImplementCommand.call()` BEFORE that branch's lock acquisition (`store.create` fresh / `store.acquireLock` resume), so a planning abort (exit 4) never leaks the lock.
- **plan.json edge direction:** `from_repo` = consumer, `to_repo` = provider (matches `Scheduler.upstreams` and `Propagation.includeBuildArgs`).
- **`MavenLocalPublisher` log shape mirrors `GradleTool`** (`"exit N\n…"` / `"timed out after Ns"` / plain message on IO error) so `InfraClassifier.isInfra` applies unchanged.
- **Zero-test-breaking** outside files a task explicitly edits. The one constructor-arity change (`Orchestrator`, 8→10 args) is confined to `Orchestrator.java`, `OrchestratorTest`, and `ImplementCommand` — Task 4 updates the first two, and Task 4 keeps `ImplementCommand` compiling by passing `Map.of()` + a publisher until Task 5 wires the real planner.
- Commit messages: conventional commits, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Context (verified against the code, 2026-08-13, main @ c5a18a4)

- `module.version` (root module, `gradle_path = ':'`) is the only per-repo current-version source; `artifact` has no version column; `dep_edge` has `to_grp/to_name/declared_version/declared_via/mode/is_internal/to_module_id` but no file path.
- `plan.json` `repos[].version_action` ∈ `none|patch|minor|major` (validated by PlanValidator); no coordinates, no numeric versions anywhere in plan.json.
- `PlanJson.compile` sets `mechanism = "MAVEN_LOCAL"` when the include-build smoke probe fails; nothing on the execution side reads it yet (`Propagation.includeBuildArgs` filters to INCLUDE_BUILD only, and a MAVEN_LOCAL hop deliberately breaks its closure).
- `RepoStepRunner` builds ONE `GradleTool` from `RunnerSettings.gradleExtraArgs` shared by `run_gradle` and the verify gate — new flags ride that channel.
- `Orchestrator` (post-4C-3) hooks: bump-apply goes after each of the two `RunGit.startBranch` calls; publish goes in the `StepResult.SUCCESS` branch after `RunGit.commitAll`. `RunStore.writeAgentEvents` overwrites, so re-calling it with an extended events list is the correct way to append publish events.
- `ImplementCommand` (post-4C-3) has the fresh/resume fork with `activePlan`/`activeSteps` final aliases; `runDir` is reassigned in the fresh branch (NOT effectively final — any new lambda capture needs a fresh final alias, e.g. `activeRunDir`).
- Init-script precedent: `GradleExtractor.materializeInitScript()` + `--init-script <path>`; subprocess precedent: `GradleSmokeRunner` (ProcessBuilder + temp log + hard timeout); env-scrub precedent: `GradleTool.scrubEnvironment` (KEEP_ENV = PATH/HOME/LANG/TMPDIR + JAVA_HOME).
- `Database.open(ws)` in tests gives a real migrated SQLite KB; `FixtureRepo` builds real git repos; stub `gradlew` scripts assert argv via `case "$*" in …)`.

---

### Task 1: PlannedVersions — semver bump policy over KB root-module versions

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/PlannedVersions.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/PlannedVersionsTest.java`

**Interfaces:**
- Produces: `public static Map<String, String> compute(Jdbi jdbi, PlanModel plan)` — for every plan repo with a KB root-module version, maps repo name → planned version (`version_action` applied; `none`/blank/null action keeps the current version verbatim, even a non-semver one). Repos with no KB version, or an unparseable version under a real bump action, are ABSENT from the map — callers that need an entry treat absence as a problem. Also `static String bump(String version, String action)` (package-private, unit-tested): semver triple bump preserving any suffix (`1.2.3-SNAPSHOT` + minor → `1.3.0-SNAPSHOT`); returns `null` for an unparseable version with a real action.
- Consumes: `PlanModel.PlanRepo.versionAction()` (existing, first execution-side reader).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlannedVersionsTest {
    @TempDir Path ws;

    @Test
    void bumpPolicy() {
        assertThat(PlannedVersions.bump("1.2.3", "none")).isEqualTo("1.2.3");
        assertThat(PlannedVersions.bump("1.2.3", null)).isEqualTo("1.2.3");
        assertThat(PlannedVersions.bump("1.2.3", "patch")).isEqualTo("1.2.4");
        assertThat(PlannedVersions.bump("1.2.3", "minor")).isEqualTo("1.3.0");
        assertThat(PlannedVersions.bump("1.2.3", "major")).isEqualTo("2.0.0");
        assertThat(PlannedVersions.bump("1.2.3-SNAPSHOT", "minor")).isEqualTo("1.3.0-SNAPSHOT");
        assertThat(PlannedVersions.bump("2024.10", "patch")).isNull();   // unparseable + real bump
        assertThat(PlannedVersions.bump("2024.10", "none")).isEqualTo("2024.10");
    }

    @Test
    void computeReadsTheRootModuleVersionAndSkipsUnversionedRepos() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, grp, name, version, kind) "
                        + "VALUES (1, ':', 'com.acme', 'lib', '1.2.3', 'LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('nover', '/w/nover', 'LIBRARY')");
            });
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                            new PlanModel.PlanRepo("nover", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("nover")), List.of(), List.of(), List.of());

            Map<String, String> planned = PlannedVersions.compute(db.jdbi(), plan);

            assertThat(planned).containsEntry("lib", "1.3.0").doesNotContainKey("nover");
        }
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.PlannedVersionsTest'`

- [ ] **Step 3: Implement `PlannedVersions.java`:**

```java
package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each plan repo's planned (post-run) version: the KB root-module version bumped by the plan's
 * version_action (design line 61 — {@code publishToMavenLocal -Pversion=<planned>}). Repos absent
 * from the returned map have no computable planned version (no KB root-module version, or a
 * non-semver version under a real bump action) — callers that need one treat absence as a
 * pre-flight problem. Numeric-suffix-preserving: 1.2.3-SNAPSHOT + minor = 1.3.0-SNAPSHOT.
 */
public final class PlannedVersions {
    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(.*)");

    private PlannedVersions() {
    }

    public static Map<String, String> compute(Jdbi jdbi, PlanModel plan) {
        Map<String, String> planned = new LinkedHashMap<>();
        for (PlanModel.PlanRepo repo : plan.repos()) {
            String current = rootVersion(jdbi, repo.name());
            if (current == null || current.isBlank()) {
                continue;
            }
            String next = bump(current, repo.versionAction());
            if (next != null) {
                planned.put(repo.name(), next);
            }
        }
        return planned;
    }

    static String bump(String version, String action) {
        if (action == null || action.isBlank() || "none".equals(action)) {
            return version;
        }
        Matcher m = SEMVER.matcher(version);
        if (!m.matches()) {
            return null;
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = Integer.parseInt(m.group(3));
        String suffix = m.group(4);
        return switch (action) {
            case "patch" -> major + "." + minor + "." + (patch + 1) + suffix;
            case "minor" -> major + "." + (minor + 1) + ".0" + suffix;
            case "major" -> (major + 1) + ".0.0" + suffix;
            default -> version;
        };
    }

    private static String rootVersion(Jdbi jdbi, String repo) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT m.version FROM module m JOIN repo r ON r.id = m.repo_id "
                                + "WHERE r.name = :repo AND m.gradle_path = ':'")
                .bind("repo", repo)
                .mapTo(String.class)
                .findFirst()
                .orElse(null));
    }
}
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.PlannedVersionsTest'`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: compute planned versions from KB root-module versions and version_action"
```

---

### Task 2: DeclaredDeps KB query + VersionBump declaration-site editor

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/DeclaredDeps.java`
- Create: `sdd-cli/src/main/java/sdd/cli/implement/VersionBump.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/DeclaredDepsTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/VersionBumpTest.java`

**Interfaces:**
- Produces: `DeclaredDeps.between(Jdbi, String consumerRepo, String providerRepo)` → `List<DeclaredDeps.Declared>` where `record Declared(String group, String name, String declaredVersion, String declaredVia)` — DISTINCT internal declared dependencies from the consumer's modules onto the provider's modules. `VersionBump.apply(Path repoRoot, String group, String name, String oldVersion, String newVersion)` → `List<Path>` of edited files: rewrites `g:n:old → g:n:new` in every `build.gradle`/`build.gradle.kts` (skipping `.git`/`build` dirs), and in `libs.versions.toml` handles inline `"g:n:old"`, a `module = "g:n"` line with `version = "old"`, and `version.ref` indirection into `[versions]`. Empty list = nothing matched (caller records, never throws).
- Consumes: KB tables `dep_edge`/`module`/`repo` (existing schema).

- [ ] **Step 1: Write the failing tests.** `DeclaredDepsTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeclaredDepsTest {
    @TempDir Path ws;

    @Test
    void returnsDistinctInternalDeclarationsBetweenTwoRepos() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
                // same GA on two configurations -> one DISTINCT row
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1, 'com.acme', 'lib', 'compileClasspath', '1.2.3', 'DIRECT', 'PINNED', 1, 2)");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1, 'com.acme', 'lib', 'runtimeClasspath', '1.2.3', 'DIRECT', 'PINNED', 1, 2)");
                // external edge -> excluded
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal) "
                        + "VALUES (1, 'org.ext', 'thing', 'compileClasspath', '9.0', 'DIRECT', 'PINNED', 0)");
            });

            List<DeclaredDeps.Declared> deps = DeclaredDeps.between(db.jdbi(), "svc", "lib");

            assertThat(deps).containsExactly(
                    new DeclaredDeps.Declared("com.acme", "lib", "1.2.3", "DIRECT"));
            assertThat(DeclaredDeps.between(db.jdbi(), "lib", "svc")).isEmpty();   // direction matters
        }
    }
}
```

`VersionBumpTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionBumpTest {
    @TempDir Path repo;

    @Test
    void bumpsADirectDeclarationInBuildGradle() throws Exception {
        Files.writeString(repo.resolve("build.gradle"),
                "dependencies {\n    implementation \"com.acme:lib:1.2.3\"\n}\n");

        List<Path> edited = VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(edited).containsExactly(repo.resolve("build.gradle"));
        assertThat(Files.readString(repo.resolve("build.gradle")))
                .contains("com.acme:lib:1.3.0").doesNotContain("1.2.3");
    }

    @Test
    void bumpsSubprojectKtsFilesButSkipsBuildAndGitDirs() throws Exception {
        Files.createDirectories(repo.resolve("app"));
        Files.writeString(repo.resolve("app/build.gradle.kts"),
                "dependencies {\n    implementation(\"com.acme:lib:1.2.3\")\n}\n");
        Files.createDirectories(repo.resolve("build"));
        Files.writeString(repo.resolve("build/build.gradle"), "// generated com.acme:lib:1.2.3\n");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/build.gradle"), "com.acme:lib:1.2.3\n");

        List<Path> edited = VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(edited).containsExactly(repo.resolve("app/build.gradle.kts"));
        assertThat(Files.readString(repo.resolve("build/build.gradle"))).contains("1.2.3");
    }

    @Test
    void bumpsAnInlineCatalogCoordinate() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [libraries]
                acme-lib = "com.acme:lib:1.2.3"
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(Files.readString(repo.resolve("gradle/libs.versions.toml")))
                .contains("\"com.acme:lib:1.3.0\"");
    }

    @Test
    void bumpsACatalogVersionRef() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [versions]
                acmeLib = "1.2.3"
                other = "1.2.3"

                [libraries]
                acme-lib = { module = "com.acme:lib", version.ref = "acmeLib" }
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        String toml = Files.readString(repo.resolve("gradle/libs.versions.toml"));
        assertThat(toml).contains("acmeLib = \"1.3.0\"");
        assertThat(toml).contains("other = \"1.2.3\"");   // only the referenced alias moves
    }

    @Test
    void bumpsAModuleLineWithInlineVersionKey() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [libraries]
                acme-lib = { module = "com.acme:lib", version = "1.2.3" }
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(Files.readString(repo.resolve("gradle/libs.versions.toml")))
                .contains("version = \"1.3.0\"");
    }

    @Test
    void unmatchedDeclarationEditsNothing() throws Exception {
        Files.writeString(repo.resolve("build.gradle"),
                "dependencies { implementation \"com.acme:lib:9.9.9\" }\n");

        assertThat(VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0")).isEmpty();
        assertThat(Files.readString(repo.resolve("build.gradle"))).contains("9.9.9");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.DeclaredDepsTest' --tests 'sdd.cli.implement.VersionBumpTest'`

- [ ] **Step 3: Implement.** `DeclaredDeps.java`:

```java
package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;

import java.util.List;

/** KB lookup: the DISTINCT declared internal dependencies from one repo's modules onto another
 *  repo's artifacts (dep_edge is module-level; this collapses to unique GA + declaration facts). */
public final class DeclaredDeps {
    public record Declared(String group, String name, String declaredVersion, String declaredVia) {
    }

    private DeclaredDeps() {
    }

    public static List<Declared> between(Jdbi jdbi, String consumerRepo, String providerRepo) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT e.to_grp AS grp, e.to_name AS name,
                                        e.declared_version AS declared, e.declared_via AS via
                        FROM dep_edge e
                        JOIN module mf ON mf.id = e.from_module_id
                        JOIN module mt ON mt.id = e.to_module_id
                        JOIN repo rf ON rf.id = mf.repo_id
                        JOIN repo rt ON rt.id = mt.repo_id
                        WHERE rf.name = :consumer AND rt.name = :provider AND e.is_internal = 1
                        ORDER BY grp, name""")
                .bind("consumer", consumerRepo)
                .bind("provider", providerRepo)
                .map((rs, ctx) -> new Declared(rs.getString("grp"), rs.getString("name"),
                        rs.getString("declared"), rs.getString("via")))
                .list());
    }
}
```

`VersionBump.java`:

```java
package sdd.cli.implement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic version-bump edit at the declaration site (design line 61: "PINNED/BOM edges also
 * get the version-bump edit at the real declaration site"). DIRECT declarations rewrite
 * {@code g:n:old -> g:n:new} in build.gradle(.kts); CATALOG declarations in libs.versions.toml
 * handle inline {@code "g:n:old"}, a {@code module = "g:n"} line carrying {@code version = "old"},
 * and {@code version.ref} indirection into [versions]. Line-based and format-conservative: an
 * unmatched declaration edits nothing and the caller records that. BOM declaration sites live in
 * repos the KB cannot locate yet — deferred with the step-less widening.
 */
public final class VersionBump {
    private static final Pattern VERSION_REF = Pattern.compile("version\\.ref\\s*=\\s*\"([^\"]+)\"");

    private VersionBump() {
    }

    public static List<Path> apply(Path repoRoot, String group, String name,
                                   String oldVersion, String newVersion) {
        String coordinate = group + ":" + name;
        List<Path> edited = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            List<Path> buildFiles = walk
                    .filter(p -> {
                        String f = p.getFileName().toString();
                        return f.equals("build.gradle") || f.equals("build.gradle.kts")
                                || f.equals("libs.versions.toml");
                    })
                    .filter(p -> !skipped(repoRoot.relativize(p)))
                    .sorted()
                    .toList();
            for (Path file : buildFiles) {
                String content = Files.readString(file);
                String updated = file.getFileName().toString().equals("libs.versions.toml")
                        ? bumpCatalog(content, coordinate, oldVersion, newVersion)
                        : content.replace(coordinate + ":" + oldVersion, coordinate + ":" + newVersion);
                if (!updated.equals(content)) {
                    Files.writeString(file, updated);
                    edited.add(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return edited;
    }

    static String bumpCatalog(String toml, String coordinate, String oldVersion, String newVersion) {
        String inline = toml.replace("\"" + coordinate + ":" + oldVersion + "\"",
                "\"" + coordinate + ":" + newVersion + "\"");
        if (!inline.equals(toml)) {
            return inline;
        }
        String[] lines = toml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("\"" + coordinate + "\"")) {
                continue;   // not this library's module = "g:n" line
            }
            String versionKey = "version = \"" + oldVersion + "\"";
            if (lines[i].contains(versionKey)) {
                lines[i] = lines[i].replace(versionKey, "version = \"" + newVersion + "\"");
                return String.join("\n", lines);
            }
            Matcher ref = VERSION_REF.matcher(lines[i]);
            if (ref.find()) {
                String alias = ref.group(1);
                for (int j = 0; j < lines.length; j++) {
                    String stripped = lines[j].strip();
                    if ((stripped.startsWith(alias + " ") || stripped.startsWith(alias + "="))
                            && lines[j].contains("\"" + oldVersion + "\"")) {
                        lines[j] = lines[j].replace("\"" + oldVersion + "\"", "\"" + newVersion + "\"");
                        return String.join("\n", lines);
                    }
                }
            }
        }
        return toml;
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            String segment = part.toString();
            if (segment.equals(".git") || segment.equals("build")) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.DeclaredDepsTest' --tests 'sdd.cli.implement.VersionBumpTest'`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: KB declared-dependency query and deterministic version-bump editor"
```

---

### Task 3: MavenLocalPublisher, MavenLocalInit, and the invocation-global init-script flag

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/MavenLocalPublisher.java`
- Create: `sdd-cli/src/main/java/sdd/cli/implement/MavenLocalInit.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/Propagation.java` (add `mavenLocalArgs`)
- Test: `sdd-cli/src/test/java/sdd/cli/implement/MavenLocalPublisherTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/MavenLocalInitTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/PropagationTest.java` (add 2 tests)

**Interfaces:**
- Produces: `MavenLocalPublisher` — instance class, `MavenLocalPublisher()` (10-min timeout) / `MavenLocalPublisher(Duration timeout)`; `Result publish(Path repoRoot, Path javaHome, String version, Path m2Dir)` with `record Result(boolean ok, String log)`; runs `./gradlew publishToMavenLocal -Pversion=<version> -Dmaven.repo.local=<m2 abs> --no-configuration-cache --no-daemon -q`, env-scrubbed like GradleTool, creates `m2Dir`, log shaped like GradleTool output. `MavenLocalInit.scriptPath(Path runDir)` → `<runDir>/maven-local-init.gradle`; `MavenLocalInit.write(Path runDir)` → writes the init script injecting `<runDir>/m2` as a maven repository into `allprojects` and returns the path. `Propagation.mavenLocalArgs(List<PlanModel.PlanEdge> edges, Path initScript)` → `["--init-script", <abs path>]` when ANY edge has mechanism MAVEN_LOCAL, else empty.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing tests.** `MavenLocalPublisherTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class MavenLocalPublisherTest {
    @TempDir Path ws;

    private Path repoWith(String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve("lib"));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void publishesWithPlannedVersionAndRunScopedRepo() throws Exception {
        Path repo = repoWith("echo \"$*\" > publish-args; exit 0");
        Path m2 = ws.resolve("run/m2");

        MavenLocalPublisher.Result result = new MavenLocalPublisher().publish(repo, null, "1.3.0", m2);

        assertThat(result.ok()).isTrue();
        String args = Files.readString(repo.resolve("publish-args"));
        assertThat(args).contains("publishToMavenLocal")
                .contains("-Pversion=1.3.0")
                .contains("-Dmaven.repo.local=" + m2.toAbsolutePath())
                .contains("--no-daemon");
        assertThat(Files.isDirectory(m2)).isTrue();   // created up front for the publish
    }

    @Test
    void failureReturnsAGradleShapedLogForInfraClassification() throws Exception {
        Path repo = repoWith("echo 'Could not resolve com.acme:x'; exit 1");

        MavenLocalPublisher.Result result = new MavenLocalPublisher()
                .publish(repo, null, "1.0.0", ws.resolve("m2"));

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).startsWith("exit 1").contains("Could not resolve");
    }

    @Test
    void missingWrapperFailsWithoutRunningAnything() {
        MavenLocalPublisher.Result result = new MavenLocalPublisher()
                .publish(ws.resolve("nowhere"), null, "1.0.0", ws.resolve("m2"));

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("no gradle wrapper");
    }
}
```

`MavenLocalInitTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenLocalInitTest {
    @TempDir Path ws;

    @Test
    void writesAnInitScriptPointingAtTheRunScopedM2() throws Exception {
        Path runDir = Files.createDirectories(ws.resolve("run"));

        Path script = MavenLocalInit.write(runDir);

        assertThat(script).isEqualTo(runDir.resolve("maven-local-init.gradle"));
        assertThat(Files.readString(script))
                .contains("allprojects")
                .contains("maven { url = uri('" + runDir.resolve("m2").toAbsolutePath() + "') }");
    }
}
```

Append to `PropagationTest` (its `EDGES` fixture already contains a MAVEN_LOCAL edge):

```java
    @Test
    void mavenLocalArgsPresentWhenAnyEdgeFellBackToMavenLocal() {
        Path script = Path.of("/run/maven-local-init.gradle");
        assertThat(Propagation.mavenLocalArgs(EDGES, script))
                .containsExactly("--init-script", "/run/maven-local-init.gradle");
    }

    @Test
    void mavenLocalArgsEmptyWhenNoMavenLocalEdges() {
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"));
        assertThat(Propagation.mavenLocalArgs(edges, Path.of("/run/i.gradle"))).isEmpty();
    }
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.MavenLocalPublisherTest' --tests 'sdd.cli.implement.MavenLocalInitTest' --tests 'sdd.cli.implement.PropagationTest'`

- [ ] **Step 3: Implement.** `MavenLocalPublisher.java`:

```java
package sdd.cli.implement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrator-owned, run-scoped Maven-local publish (design line 61):
 * {@code ./gradlew publishToMavenLocal -Pversion=<planned> -Dmaven.repo.local=<runDir>/m2}.
 * Deliberately NOT GradleTool — publishToMavenLocal stays off the agent's allowlist; this runner is
 * never model-reachable (the GradleSmokeRunner privileged-subprocess precedent). Env-scrubbed like
 * GradleTool: a publish needs no ambient credentials. The log mirrors GradleTool's shape
 * ("exit N\n…" / "timed out after Ns") so InfraClassifier patterns apply unchanged.
 */
public final class MavenLocalPublisher {
    private static final List<String> KEEP_ENV = List.of("PATH", "HOME", "LANG", "TMPDIR");
    private static final int MAX_LOG = 200_000;

    private final Duration timeout;

    public MavenLocalPublisher() {
        this(Duration.ofMinutes(10));
    }

    public MavenLocalPublisher(Duration timeout) {
        this.timeout = timeout;
    }

    public record Result(boolean ok, String log) {
    }

    public Result publish(Path repoRoot, Path javaHome, String version, Path m2Dir) {
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, "no gradle wrapper in " + repoRoot);
        }
        Path log = null;
        try {
            Files.createDirectories(m2Dir);
            log = Files.createTempFile("sdd-publish", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", "publishToMavenLocal",
                    "-Pversion=" + version,
                    "-Dmaven.repo.local=" + m2Dir.toAbsolutePath(),
                    "--no-configuration-cache", "--no-daemon", "-q"));
            builder.directory(repoRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            scrub(builder.environment(), javaHome);
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(false, "timed out after " + timeout.toSeconds() + "s");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (output.length() > MAX_LOG) {
                output = output.substring(0, MAX_LOG);
            }
            return new Result(process.exitValue() == 0, "exit " + process.exitValue() + "\n" + output);
        } catch (IOException e) {
            return new Result(false, "publish failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    private static void scrub(Map<String, String> env, Path javaHome) {
        Map<String, String> keep = new HashMap<>();
        for (String name : KEEP_ENV) {
            String value = System.getenv(name);
            if (value != null) {
                keep.put(name, value);
            }
        }
        env.clear();
        env.putAll(keep);
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome.toAbsolutePath().toString());
        }
    }
}
```

`MavenLocalInit.java`:

```java
package sdd.cli.implement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The run-scoped init script that injects {@code <runDir>/m2} as a maven repository into every
 * project of every build in a Gradle invocation. An init script is the one channel that reaches
 * included builds inside a composite too, which per-project injection cannot.
 */
public final class MavenLocalInit {
    static final String FILE_NAME = "maven-local-init.gradle";

    private MavenLocalInit() {
    }

    public static Path scriptPath(Path runDir) {
        return runDir.resolve(FILE_NAME);
    }

    public static Path write(Path runDir) {
        String script = """
                // sdd: run-scoped mavenLocal injection (design line 61). Appended repository, so it
                // only serves artifacts other repositories cannot — the planned versions published
                // into this run's m2.
                allprojects {
                    repositories {
                        maven { url = uri('%s') }
                    }
                }
                """.formatted(runDir.resolve("m2").toAbsolutePath());
        try {
            Files.writeString(scriptPath(runDir), script);
            return scriptPath(runDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

In `Propagation.java`, add (below `includeBuildArgs`, with the class javadoc's "MAVEN_LOCAL / NONE edges and the version-bump path are 4C-2b" sentence updated to "NONE edges inject nothing; BOM declaration-site bumps are deferred until the KB records declaration sites"):

```java
    /** Invocation-global init-script flag: present when ANY plan edge fell back to MAVEN_LOCAL.
     *  Applied to every repo's Gradle calls, not just direct consumers — init scripts are the only
     *  channel that reaches included builds inside a composite (an INCLUDE_BUILD consumer whose
     *  provider has its own MAVEN_LOCAL provider), and an appended local maven repo is inert for
     *  builds that never request a planned version. */
    public static List<String> mavenLocalArgs(List<PlanModel.PlanEdge> edges, Path initScript) {
        for (PlanModel.PlanEdge edge : edges) {
            if ("MAVEN_LOCAL".equals(edge.mechanism())) {
                return List.of("--init-script", initScript.toAbsolutePath().toString());
            }
        }
        return List.of();
    }
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.MavenLocalPublisherTest' --tests 'sdd.cli.implement.MavenLocalInitTest' --tests 'sdd.cli.implement.PropagationTest'`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: run-scoped maven-local publisher, init-script injection, and edge flag"
```

---

### Task 4: RepoPropagation + Orchestrator hooks (bump per attempt, publish after success)

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/RepoPropagation.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/Orchestrator.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java` (constructor call only — keeps compiling with empty propagation until Task 5)
- Test: `sdd-cli/src/test/java/sdd/cli/implement/OrchestratorTest.java`

**Interfaces:**
- Produces: `record RepoPropagation(List<BumpEdit> bumps, PublishSpec publish)` with nested `record BumpEdit(String group, String name, String oldVersion, String newVersion)` and `record PublishSpec(String version, Path m2Dir)` (null publish = no MAVEN_LOCAL consumers), plus `static RepoPropagation none()`. `Orchestrator` constructor becomes 10-arg: `(RepoStepRunner, ChatModel coder, String coderModelName, ChatModel escalation, String escalationModelName, Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget, Map<String, RepoPropagation> propagation, MavenLocalPublisher publisher)`. Behavior: bump edits applied via `VersionBump.apply` immediately after EVERY `RunGit.startBranch` (attempt 1 and attempt 2), events recorded (`"bump: g:n old -> new in K file(s)"` / `"bump: no declaration of g:n:old found — left unedited"`); on `StepResult.SUCCESS`, after `commitAll`, a non-null `PublishSpec` triggers `publisher.publish(repoRoot, settingsFor.apply(repo).javaHome(), version, m2Dir)` — failure with an infra-classified log → `state.pause` + `PAUSED_INFRA` + break; other failure → `FAILED` with detail `"publish failed: …"`; only a successful (or unneeded) publish transitions to SUCCEEDED. Publish events are added to `events` and `writeAgentEvents` is called again (overwrite includes them).
- Consumes: Task 2's `VersionBump.apply`; Task 3's `MavenLocalPublisher`; `InfraClassifier.isInfra` (sdd-agent, 4C-3).

- [ ] **Step 1: Update the constructor helpers and write the failing tests.** In `OrchestratorTest`, replace the two `orchestrator(...)` helpers with:

```java
    private Orchestrator orchestrator(ChatModel coder, ChatModel escalation) {
        return orchestrator(coder, escalation, Map.of());
    }

    private Orchestrator orchestrator(ChatModel coder, ChatModel escalation,
                                      Map<String, RepoPropagation> propagation) {
        return new Orchestrator(new RepoStepRunner(db.jdbi()), coder, "qwen", escalation, "deepseek",
                repo -> RunnerSettings.defaults(null), new RunStore(InstantSource.fixed(Instant.EPOCH)),
                30_000_000L, propagation, new MavenLocalPublisher());
    }

    private Orchestrator orchestrator(ScriptedChatModel model) {
        return orchestrator(model, model);
    }
```

(The budget test constructs `new Orchestrator(...)` inline — extend that call the same way: `…, 10L, Map.of(), new MavenLocalPublisher())`.) Then add four tests:

```java
    @Test
    void appliesBumpEditsBeforeTheAgentAndCommitsThem() throws Exception {
        // Verify passes ONLY if the bump is already in the tree when the gate runs — this pins the
        // bump's placement (after startBranch, before the agent/verify), not just its eventual content.
        FixtureRepo lib = repoWith("lib", "grep -q 'com.acme:core:1.1.0' build.gradle && exit 0 || exit 1");
        lib.file("build.gradle", "dependencies { implementation \"com.acme:core:1.0.0\" }\n")
                .commit("build file");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib", new RepoPropagation(
                List.of(new RepoPropagation.BumpEdit("com.acme", "core", "1.0.0", "1.1.0")), null));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Files.readString(lib.path().resolve("build.gradle")))
                .contains("com.acme:core:1.1.0");
        assertThat(RunGit.isClean(lib.path())).isTrue();   // the bump edit rode the checkpoint commit
    }

    @Test
    void bumpsAreReappliedAfterTheEscalationReset() throws Exception {
        // Attempt 1 fails verify (marker absent); the escalation's edit adds the marker. The verify
        // gate ALSO requires the bumped pin — so this passes only if applyBumps runs again after the
        // escalation-path startBranch (the hard reset wiped attempt 1's bump).
        FixtureRepo lib = repoWith("lib",
                "grep -q escalated A.java && grep -q 'com.acme:core:1.1.0' build.gradle && exit 0 || exit 1");
        lib.file("build.gradle", "dependencies { implementation \"com.acme:core:1.0.0\" }\n")
                .commit("build file");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib", new RepoPropagation(
                List.of(new RepoPropagation.BumpEdit("com.acme", "core", "1.0.0", "1.1.0")), null));
        ScriptedChatModel coder = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalation = new ScriptedChatModel(List.of(
                call("3", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int escalated; }\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"escalated\"}")));

        Orchestrator.RunResult result = orchestrator(coder, escalation, propagation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Files.readString(lib.path().resolve("build.gradle")))
                .contains("com.acme:core:1.1.0");
        assertThat(RunGit.isClean(lib.path())).isTrue();
    }

    @Test
    void publishesAMavenLocalProviderAfterSuccess() throws Exception {
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *publishToMavenLocal*) echo \"$*\" > publish-args; exit 0 ;; *) exit 0 ;; esac");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Path m2 = runDir.resolve("m2");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib",
                new RepoPropagation(List.of(), new RepoPropagation.PublishSpec("2.0.0", m2)));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        String args = Files.readString(lib.path().resolve("publish-args"));
        assertThat(args).contains("-Pversion=2.0.0")
                .contains("-Dmaven.repo.local=" + m2.toAbsolutePath());
    }

    @Test
    void infraClassifiedPublishFailurePausesTheRun() throws Exception {
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *publishToMavenLocal*) echo 'Could not resolve com.acme:x'; exit 1 ;; *) exit 0 ;; esac");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib",
                new RepoPropagation(List.of(), new RepoPropagation.PublishSpec("2.0.0", runDir.resolve("m2"))));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_INFRA);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);   // walk stopped
    }

    @Test
    void nonInfraPublishFailureFailsTheRepoAndCascades() throws Exception {
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *publishToMavenLocal*) echo 'no publishing plugin applied'; exit 1 ;; *) exit 0 ;; esac");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib",
                new RepoPropagation(List.of(), new RepoPropagation.PublishSpec("2.0.0", runDir.resolve("m2"))));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().repos().stream()
                .filter(r -> r.repo().equals("lib")).findFirst().orElseThrow().detail())
                .contains("publish failed");
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
    }
```

New import in `OrchestratorTest`: none beyond what exists (`Map`, `Files`, `RepoState` already imported).

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.OrchestratorTest'`

- [ ] **Step 3: Implement.** `RepoPropagation.java`:

```java
package sdd.cli.implement;

import java.nio.file.Path;
import java.util.List;

/** Precomputed per-repo propagation work (4C-2b): deterministic bump edits applied after every
 *  branch reset, and the run-scoped publish a MAVEN_LOCAL provider owes its consumers on success. */
public record RepoPropagation(List<BumpEdit> bumps, PublishSpec publish) {
    public record BumpEdit(String group, String name, String oldVersion, String newVersion) {
    }

    /** null publish on the enclosing record = this repo provides no MAVEN_LOCAL edge. */
    public record PublishSpec(String version, Path m2Dir) {
    }

    public RepoPropagation {
        bumps = List.copyOf(bumps);
    }

    public static RepoPropagation none() {
        return new RepoPropagation(List.of(), null);
    }
}
```

`Orchestrator.java` — exact edits (everything else unchanged):

1. New imports: `sdd.agent.run.InfraClassifier;` — plus fields and the widened constructor:

```java
    private final Map<String, RepoPropagation> propagation;
    private final MavenLocalPublisher publisher;

    public Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName,
                        ChatModel escalation, String escalationModelName,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget,
                        Map<String, RepoPropagation> propagation, MavenLocalPublisher publisher) {
        this.runner = runner;
        this.coder = coder;
        this.coderModelName = coderModelName;
        this.escalation = escalation;
        this.escalationModelName = escalationModelName;
        this.settingsFor = settingsFor;
        this.store = store;
        this.runTokenBudget = runTokenBudget;
        this.propagation = Map.copyOf(propagation);
        this.publisher = publisher;
    }
```

2. In the attempt try-block, add a bump call after EACH of the two `RunGit.startBranch(...)` lines:

```java
                    RunGit.startBranch(step.repoRoot(), branch, base);
                    applyBumps(repo, step, events);
```

(and identically inside the escalation block after its `RunGit.startBranch`.)

3. The `StepResult.SUCCESS` branch becomes:

```java
                if (outcome.result() == StepResult.SUCCESS) {
                    String sha = RunGit.commitAll(step.repoRoot(), "sdd: " + runId + " " + repo);
                    RepoPropagation prop = propagation.getOrDefault(repo, RepoPropagation.none());
                    if (prop.publish() != null) {
                        MavenLocalPublisher.Result published = publisher.publish(step.repoRoot(),
                                settingsFor.apply(repo).javaHome(), prop.publish().version(),
                                prop.publish().m2Dir());
                        events.add("publish " + prop.publish().version() + ": " + summarize(published.log()));
                        store.writeAgentEvents(runDir, repo, events);   // overwrite now includes publish events
                        if (!published.ok()) {
                            if (InfraClassifier.isInfra(published.log())) {
                                state.pause("infrastructure failure publishing " + repo
                                        + " — fix the environment and resume");
                                transition(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                                        attemptTag + "publish: " + summarize(published.log()));
                                break;
                            }
                            transition(runDir, state, repo, RepoState.FAILED, branch, null,
                                    attemptTag + "publish failed: " + summarize(published.log()));
                            continue;
                        }
                    }
                    transition(runDir, state, repo, RepoState.SUCCEEDED, branch, sha,
                            attemptTag + outcome.summary());
                } else if (outcome.result() == StepResult.INFRA) {
```

4. New private helpers:

```java
    private void applyBumps(String repo, RepoStep step, List<String> events) {
        for (RepoPropagation.BumpEdit bump : propagation.getOrDefault(repo, RepoPropagation.none()).bumps()) {
            List<java.nio.file.Path> edited = VersionBump.apply(step.repoRoot(), bump.group(),
                    bump.name(), bump.oldVersion(), bump.newVersion());
            String coordinate = bump.group() + ":" + bump.name();
            if (edited.isEmpty()) {
                events.add("bump: no declaration of " + coordinate + ":" + bump.oldVersion()
                        + " found — left unedited");
            } else {
                events.add("bump: " + coordinate + " " + bump.oldVersion() + " -> " + bump.newVersion()
                        + " in " + edited.size() + " file(s)");
            }
        }
    }

    private static String summarize(String log) {
        String flat = log.replace('\n', ' ').strip();
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }
```

5. Class javadoc: append "MAVEN_LOCAL propagation (4C-2b): bump edits re-applied after every branch reset; providers publish to the run-scoped m2 after their checkpoint commit."

`ImplementCommand.java` — extend the orchestrator construction (real planning arrives in Task 5):

```java
                Orchestrator orchestrator = new Orchestrator(new RepoStepRunner(jdbi), coder, coderName,
                        escalation, escalationName, settingsFor, store, RUN_TOKEN_BUDGET,
                        Map.of(), new MavenLocalPublisher());
```

(`Map` is already imported; add `import sdd.cli.implement.MavenLocalPublisher;` — note ImplementCommand imports `sdd.cli.implement.*` classes individually.)

- [ ] **Step 4: Run — expect PASS, then the full sdd-cli suite.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: orchestrator applies version bumps per attempt and publishes maven-local providers"
```

---

### Task 5: PropagationPlanner + ImplementCommand wiring + end-to-end MAVEN_LOCAL proof

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/PropagationPlanner.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/PropagationPlannerTest.java`, `sdd-cli/src/test/java/sdd/cli/ImplementCommandMavenLocalTest.java`

**Interfaces:**
- Produces: `PropagationPlanner.plan(Jdbi jdbi, PlanModel plan, Path runDir, Map<String, String> plannedVersions, List<String> problems)` → `Map<String, RepoPropagation>` — per repo: bump edits for every outbound edge to a STEPPED provider where the declared version is PINNED-shaped (non-null, non-SNAPSHOT, non-dynamic) and differs from the provider's planned version (missing planned version for a needed provider → problem); a `PublishSpec(plannedVersion, runDir.resolve("m2"))` when the repo is the `toRepo` of any MAVEN_LOCAL edge AND has a plan step (missing planned version → problem). Repos with no work are absent from the map. `ImplementCommand`: propagation planned inside BOTH fork branches before their lock acquisition (problems → exit 4, lock never taken); init script written after the fork when `Propagation.mavenLocalArgs` is non-empty; `settingsFor` appends `mavenLocalArgs(activePlan.edges(), MavenLocalInit.scriptPath(activeRunDir))` to the include-build args; orchestrator gets the planned map + a real publisher.
- Consumes: Tasks 1–4 (`PlannedVersions`, `DeclaredDeps`, `RepoPropagation`, `MavenLocalInit`, `Propagation.mavenLocalArgs`, the 10-arg `Orchestrator`).

- [ ] **Step 1: Write the failing tests.** `PropagationPlannerTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropagationPlannerTest {
    @TempDir Path ws;

    private static PlanModel.PlanStep step(String repo) {
        return new PlanModel.PlanStep(repo, List.of(), "patch", List.of(), List.of(),
                List.of(), List.of(), "x");
    }

    private void seedKb(Database db) {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                    + "declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (1, 'com.acme', 'lib', 'compileClasspath', '1.2.3', 'DIRECT', 'PINNED', 1, 2)");
        });
    }

    @Test
    void plansPublishForSteppedProvidersAndPinBumpsForConsumers() {
        try (Database db = Database.open(ws)) {
            seedKb(db);
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "PINNED", "MAVEN_LOCAL")),
                    List.of(), List.of(step("lib"), step("svc")));
            List<String> problems = new ArrayList<>();

            Map<String, RepoPropagation> result = PropagationPlanner.plan(db.jdbi(), plan,
                    Path.of("/run"), Map.of("lib", "1.3.0", "svc", "0.1.1"), problems);

            assertThat(problems).isEmpty();
            assertThat(result.get("lib").publish())
                    .isEqualTo(new RepoPropagation.PublishSpec("1.3.0", Path.of("/run/m2")));
            assertThat(result.get("svc").bumps()).containsExactly(
                    new RepoPropagation.BumpEdit("com.acme", "lib", "1.2.3", "1.3.0"));
            assertThat(result.get("svc").publish()).isNull();
        }
    }

    @Test
    void stepLessProvidersNeedNoPublishAndNoBumps() {
        try (Database db = Database.open(ws)) {
            seedKb(db);
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "none", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "PINNED", "MAVEN_LOCAL")),
                    List.of(), List.of(step("svc")));   // lib has NO step -> unchanged artifact
            List<String> problems = new ArrayList<>();

            Map<String, RepoPropagation> result = PropagationPlanner.plan(db.jdbi(), plan,
                    Path.of("/run"), Map.of("lib", "1.2.3", "svc", "0.1.1"), problems);

            assertThat(problems).isEmpty();
            assertThat(result).isEmpty();
        }
    }

    @Test
    void snapshotDeclarationsNeverBump() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1, 'com.acme', 'lib', 'compileClasspath', '1.0-SNAPSHOT', 'DIRECT', "
                        + "'SNAPSHOT', 1, 2)");
            });
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "none", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "MAVEN_LOCAL")),
                    List.of(), List.of(step("lib"), step("svc")));
            List<String> problems = new ArrayList<>();

            Map<String, RepoPropagation> result = PropagationPlanner.plan(db.jdbi(), plan,
                    Path.of("/run"), Map.of("lib", "1.0-SNAPSHOT", "svc", "0.1.1"), problems);

            assertThat(problems).isEmpty();
            assertThat(result.containsKey("svc")).isFalse();               // no bump for a snapshot pin
            assertThat(result.get("lib").publish().version()).isEqualTo("1.0-SNAPSHOT");   // republish same snapshot
        }
    }

    @Test
    void missingPlannedVersionForANeededProviderIsAProblem() {
        try (Database db = Database.open(ws)) {
            seedKb(db);
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "PINNED", "MAVEN_LOCAL")),
                    List.of(), List.of(step("lib"), step("svc")));
            List<String> problems = new ArrayList<>();

            PropagationPlanner.plan(db.jdbi(), plan, Path.of("/run"), Map.of(), problems);

            assertThat(problems).hasSize(2);   // publish needs it AND svc's pin bump needs it
            assertThat(problems.get(0)).contains("lib");
        }
    }
}
```

`ImplementCommandMavenLocalTest.java` — the e2e proof, modeled verbatim on `ImplementCommandPropagationTest`'s fixture scaffolding (read that file first; reuse its `repo(...)` helper shape, `done()` builder, `sdd.yml`/`s.md` content, and SHA wiring exactly), with these deltas: svc's fixture gets a `build.gradle` containing `implementation "com.acme:lib:1.2.3"` committed before the SHA is recorded; the plan.json edge is `{"from_repo":"svc","to_repo":"lib","mode":"PINNED","mechanism":"MAVEN_LOCAL"}` and lib's `version_action` is `"minor"`; the KB seeding adds these EXACT rows after the template's two repo inserts (template order: lib first → repo id 1, svc second → repo id 2 — note this is the REVERSE of PropagationPlannerTest's seedKb, so do not copy ids from there; module ids follow their own insertion order, lib module = 1, svc module = 2):

```java
                h.execute("INSERT INTO module(repo_id, gradle_path, grp, name, version, kind) "
                        + "VALUES (1, ':', 'com.acme', 'lib', '1.2.3', 'LIBRARY')");     // lib root module -> PlannedVersions
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'SERVICE')");  // svc root module
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2, 'com.acme', 'lib', 'compileClasspath', '1.2.3', 'DIRECT', 'PINNED', 1, 1)");
                        // from = svc's module (id 2), to = lib's module (id 1) -> DeclaredDeps.between("svc","lib")
```

The stub gradlew scripts are:

- lib: `case "$*" in *publishToMavenLocal*) echo "$*" > publish-args; exit 0 ;; *) exit 0 ;; esac`
- svc: `case "$*" in *--init-script*) exit 0 ;; *) exit 1 ;; esac` (its verify passes ONLY with the injected init script)

```java
    @Test
    void mavenLocalFallbackPublishesBumpsAndInjectsTheInitScript() throws Exception {
        // ... fixture setup per the deltas above; coderForTest = new ScriptedChatModel(List.of(done(), done())) ...
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("lib: SUCCEEDED").contains("svc: SUCCEEDED");
        Path runDir = ws.resolve(".sdd/runs/SPEC-9-v1");
        assertThat(runDir.resolve("maven-local-init.gradle")).exists();
        String publishArgs = Files.readString(lib.path().resolve("publish-args"));
        assertThat(publishArgs).contains("-Pversion=1.3.0")
                .contains("-Dmaven.repo.local=" + runDir.resolve("m2").toAbsolutePath());
        assertThat(Files.readString(svc.path().resolve("build.gradle")))
                .contains("com.acme:lib:1.3.0");   // pin bumped on svc's run branch
    }
```

(Write the fixture block out fully in the test file — every elided line is a verbatim copy from `ImplementCommandPropagationTest` with the stated deltas. Mind two things: the KB `module`/`dep_edge` inserts must use the repo-row ids in insertion order, and svc's `build.gradle` must exist BEFORE `base_sha` is captured so pre-flight's clean-tree check passes.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.PropagationPlannerTest' --tests 'sdd.cli.ImplementCommandMavenLocalTest'`

- [ ] **Step 3: Implement.** `PropagationPlanner.java`:

```java
package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Precomputes all 4C-2b propagation work from KB + plan, deterministically and BEFORE the run lock
 * is taken (a planning failure aborts cleanly at exit 4). Publish: providers of MAVEN_LOCAL edges —
 * only those WITH plan steps; a step-less provider ships no change, so consumers keep resolving its
 * already-published artifact. Bumps: PINNED-shaped declarations (non-null, non-SNAPSHOT,
 * non-dynamic) onto stepped providers, on ALL mechanisms — under INCLUDE_BUILD the substitution
 * ignores the requested version, and the release runbook needs the new pin either way. BOM
 * declaration sites are deferred until the KB records declaration files.
 */
public final class PropagationPlanner {
    private PropagationPlanner() {
    }

    public static Map<String, RepoPropagation> plan(Jdbi jdbi, PlanModel plan, Path runDir,
                                                    Map<String, String> plannedVersions,
                                                    List<String> problems) {
        Path m2 = runDir.resolve("m2");
        Map<String, RepoPropagation> result = new LinkedHashMap<>();
        for (PlanModel.PlanRepo repo : plan.repos()) {
            String name = repo.name();
            List<RepoPropagation.BumpEdit> bumps = new ArrayList<>();
            for (PlanModel.PlanEdge edge : plan.edges()) {
                if (!edge.fromRepo().equals(name) || plan.step(edge.toRepo()).isEmpty()) {
                    continue;   // outbound edges only; an unchanged provider needs no bump
                }
                String planned = plannedVersions.get(edge.toRepo());
                for (DeclaredDeps.Declared dep : DeclaredDeps.between(jdbi, name, edge.toRepo())) {
                    if (dep.declaredVersion() == null || snapshotOrDynamic(dep.declaredVersion())) {
                        continue;   // BOM (deferred) / SNAPSHOT / DYNAMIC — no pin to move
                    }
                    if (planned == null) {
                        problems.add(name + " pins " + dep.group() + ":" + dep.name() + ":"
                                + dep.declaredVersion() + " but no planned version is computable for "
                                + edge.toRepo() + " — re-index or fix the root-module version");
                        continue;
                    }
                    if (!planned.equals(dep.declaredVersion())) {
                        bumps.add(new RepoPropagation.BumpEdit(dep.group(), dep.name(),
                                dep.declaredVersion(), planned));
                    }
                }
            }
            RepoPropagation.PublishSpec publish = null;
            boolean providesMavenLocal = plan.edges().stream().anyMatch(e ->
                    e.toRepo().equals(name) && "MAVEN_LOCAL".equals(e.mechanism()));
            if (providesMavenLocal && plan.step(name).isPresent()) {
                String planned = plannedVersions.get(name);
                if (planned == null) {
                    problems.add(name + " must publish to the run-scoped m2 but no planned version is "
                            + "computable — re-index or fix the root-module version");
                } else {
                    publish = new RepoPropagation.PublishSpec(planned, m2);
                }
            }
            if (!bumps.isEmpty() || publish != null) {
                result.put(name, new RepoPropagation(bumps, publish));
            }
        }
        return result;
    }

    private static boolean snapshotOrDynamic(String version) {
        return version.endsWith("-SNAPSHOT") || version.contains("+")
                || version.startsWith("latest.") || version.startsWith("[") || version.startsWith("(");
    }
}
```

`ImplementCommand.java` — exact edits:

1. Declare before the fork (next to the existing `RunState initialState = null;`):

```java
                Map<String, RepoPropagation> propagation = Map.of();
```

2. RESUME branch — insert between the `PreFlight.checkResume` gate and `store.acquireLock(runDir)`:

```java
                    List<String> propagationProblems = new ArrayList<>();
                    propagation = PropagationPlanner.plan(jdbi, plan, runDir,
                            PlannedVersions.compute(jdbi, plan), propagationProblems);
                    if (!propagationProblems.isEmpty()) {
                        propagationProblems.forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
```

3. FRESH branch — insert the identical block between the `PreFlight.check` gate and `runDir = store.create(...)`.

4. After the fork, next to the existing `activePlan`/`activeSteps` aliases, add:

```java
                final Path activeRunDir = runDir;
                final Map<String, RepoPropagation> activePropagation = propagation;
                if (!Propagation.mavenLocalArgs(activePlan.edges(),
                        MavenLocalInit.scriptPath(activeRunDir)).isEmpty()) {
                    try {
                        MavenLocalInit.write(activeRunDir);
                    } catch (RuntimeException e) {
                        store.releaseLock(activeRunDir);   // post-lock write must not leak the lock on failure
                        throw e;
                    }
                }
```

(`runDir` is reassigned in the fresh branch, so the lambda below MUST capture `activeRunDir`, never `runDir`. The write happens after the lock is held in both branches, so an IO failure must release before propagating to the outer catch — same exposure class as `store.create`'s own post-lock snapshot writes.)

5. `settingsFor` — extend the extraArgs assembly:

```java
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = activeSteps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    List<String> extraArgs = new ArrayList<>(sdd.cli.implement.Propagation.includeBuildArgs(
                            repo, activePlan.edges(), paths));
                    extraArgs.addAll(sdd.cli.implement.Propagation.mavenLocalArgs(
                            activePlan.edges(), MavenLocalInit.scriptPath(activeRunDir)));
                    return RunnerSettings.defaults(javaHome, extraArgs);
                };
```

6. Orchestrator construction — replace Task 4's placeholders:

```java
                Orchestrator orchestrator = new Orchestrator(new RepoStepRunner(jdbi), coder, coderName,
                        escalation, escalationName, settingsFor, store, RUN_TOKEN_BUDGET,
                        activePropagation, new MavenLocalPublisher());
```

(Imports to add: `java.util.ArrayList`, `sdd.cli.implement.PropagationPlanner`, `sdd.cli.implement.PlannedVersions`, `sdd.cli.implement.RepoPropagation`, `sdd.cli.implement.MavenLocalInit`, `sdd.cli.implement.Propagation` if not present.)

- [ ] **Step 4: Run — expect PASS, then the full suite.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: sdd implement executes the maven-local fallback with planned-version bumps"
```

---

## Verification

1. `./gradlew build` — all modules green; `GradleToolTest.disallowedTaskNeverRuns` untouched and green (allowlist invariant).
2. Every mechanism claim has a subprocess-real proof: publisher argv (`-Pversion`, `-Dmaven.repo.local`) captured by a stub gradlew; init-script flag proven load-bearing (svc's verify fails without it); bump edits proven committed (clean tree post-checkpoint) and re-applied semantics covered by the per-attempt call placement; publish failure routing (infra → PAUSED_INFRA, other → FAILED + cascade) asserted at orchestrator level; the whole path plan.json edge → publish/bump/inject proven end-to-end in `ImplementCommandMavenLocalTest`.
3. Run-dir contract gains: `maven-local-init.gradle` (when any MAVEN_LOCAL edge) and `m2/` (created at first publish).
4. Real-estate smoke remains blocked on Qwen weights and is all-INCLUDE_BUILD anyway — this phase is validated exclusively on fixtures, as scoped at the 4C-2 split.

## Self-Review (completed at write time)

1. **Spec coverage (design line 61 fallback path):** provider publish with planned version + run-scoped m2 → Tasks 1, 3, 4; init-script repo injection reaching every Gradle call including verify → Tasks 3, 5 (via the shared gradleExtraArgs channel); PINNED version-bump at the declaration site → Tasks 2, 4, 5; "COMPOSITE injects nothing" holds (mavenLocalArgs keys on mechanism, and NONE/COMPOSITE edges trigger nothing). BOM + step-less widening explicitly deferred with the KB-schema justification.
2. **Placeholder scan:** the two deliberate ellipses are the e2e fixture blocks in Tasks 5 (and Task 5's planner-test seed helper reuse), each pinned to verbatim in-repo templates (`ImplementCommandPropagationTest`) with exhaustive deltas listed. No TBDs.
3. **Type consistency:** `RepoPropagation`/`BumpEdit`/`PublishSpec` (T4) produced by `PropagationPlanner` (T5) and consumed by `Orchestrator` (T4); `PlannedVersions.compute` (T1) and `DeclaredDeps.between` (T2) consumed by T5's planner; `VersionBump.apply(Path,String,String,String,String)` (T2) consumed by T4's `applyBumps`; `MavenLocalPublisher.publish(Path,Path,String,Path)`/`Result(ok,log)` (T3) consumed by T4; `MavenLocalInit.scriptPath/write` (T3) consumed by T5; `Propagation.mavenLocalArgs(List<PlanEdge>,Path)` (T3) consumed by T5. Orchestrator arity 10 consistent across T4 tests, T4 ImplementCommand stub, and T5 final wiring.
4. **Judgment calls for reviewers:** ratified list in Global Constraints (step-less providers, all-mechanism PINNED bumps, invocation-global init script, publish-failure routing, SUCCEEDED-after-publish, per-attempt bump reapplication). The `-Pversion` override limitation is a design-level spike item, not this phase's to solve.
