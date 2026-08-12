# Phase 4C-2 — INCLUDE_BUILD Propagation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a dependent repo compile/test against an in-run upstream's LIVE source tree — the design's primary "library propagation without publishing" — by threading `--include-build <provider-checkout>` flags into every Gradle call a consumer repo makes, invisibly to the agent, reaching both its `run_gradle` tool and the orchestrator's verify gate.

**Architecture:** A minimal, additive change through one seam the 4C-2 recon proved is clean: the SINGLE `GradleTool` instance built in `RepoStepRunner.run()` is shared by the agent's `Toolbox` AND the verify `VerificationRunner`, so one injection reaches both paths. `GradleTool` gains an `extraArgs` field (4-arg ctor overload) spliced into its command list; `RunnerSettings` gains a `gradleExtraArgs` field (a `defaults(Path, List)` overload); `RepoStepRunner` passes it through; a new `Propagation` helper (sdd-cli) computes each repo's `--include-build` flags from its INCLUDE_BUILD inbound edges (`from_repo == repo`, provider = `to_repo`, checkout path from the KB); and `ImplementCommand.settingsFor` wires it in. Every change is additive and breaks zero existing tests. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` line 61 (Library propagation without publishing) + line 57 (orchestrator appends substitution flags invisibly).

**Tech Stack:** Java 21, the merged 4A `sdd.agent.tool.GradleTool`, the merged 4B `sdd.agent.run.{RunnerSettings,RepoStepRunner}`, the merged 4C-1 `sdd.cli.implement.{PlanModel,Orchestrator,ImplementCommand}`, `FixtureRepo` + `ScriptedChatModel` testFixtures. No new dependencies.

## Global Constraints

- Java 21; no new dependencies. Every signature change is ADDITIVE (new ctor/factory overloads, a new record component reached only through the preserved `defaults(Path)` factory) so all existing tests keep compiling. Verify by running the FULL `sdd-agent` + `sdd-cli` suites after the shipped-code changes.
- **SCOPE = INCLUDE_BUILD (+ COMPOSITE/NONE = inject nothing).** Explicitly DEFERRED to a follow-on **4C-2b** (fixture-only, larger, zero estate coverage): the `MAVEN_LOCAL` fallback (`publishToMavenLocal -Pversion=<planned> -Dmaven.repo.local=<runDir>/m2` + allowlisting `publishToMavenLocal` + an ordered upstream-publish step + `m2/` run dir + init-script repo injection) and the PINNED/BOM **version-bump edit at the declaration site** (KB-read `declared_via`/`declared_version` + a version-action bump policy + cross-repo declaration-site resolution + widening `PreFlight`/`RunState` to step-less bom sites). This phase touches NO contracts (contract actualization + japicmp is 4D).
- **Edge direction (the 4C-1 correction, triple-confirmed):** plan.json edges are `from_repo = CONSUMER, to_repo = PROVIDER`. For consumer repo X, its include-build providers are the `to_repo` of edges where `from_repo == X` — the SAME direction as `Scheduler.upstreams`. Never filter on `to_repo == X`.
- **Mechanism is READ, never re-probed.** 4C-2 reads `plan.json.edges[].mechanism` (∈ `INCLUDE_BUILD | MAVEN_LOCAL | NONE`, chosen at approve time by the live smoke probe). Only `INCLUDE_BUILD` edges produce flags this phase; `MAVEN_LOCAL` and `NONE` produce nothing here.
- **Flags are invisible to the model** (design line 57): they are appended by the tooling layer; the agent neither chooses nor sees them and has no generic shell. They ride on the command line, never in the model-facing prompt.
- **Preserve every `GradleTool` guardrail:** the allowlist check (the `task` verb is still the single allowlisted token — extra args are appended flags, never a task), the env-scrub (`KEEP_ENV` only + `JAVA_HOME`), `--no-configuration-cache --no-daemon -q`, the head/tail output caps, and the process-tree kill on timeout. Extra args are command-line flags only; they change nothing about env, allowlist, or output handling.
- **Provider checkout paths come from the KB** `repo.path` column (the `Map<String,Path>` `ImplementCommand` already builds from `SELECT name, path FROM repo`), absolutized — NOT from plan.json (edges carry only repo names). The KB stays read-only.
- Never read or print `.env` or any `api_key`. Never push. Full `./gradlew build` before the final commit.

---

## File Structure

**Task 1:** `sdd-agent/src/main/java/sdd/agent/tool/GradleTool.java` (extra-args overload) + test
**Task 2:** `sdd-agent/src/main/java/sdd/agent/run/RunnerSettings.java` (field + overload) + `RepoStepRunner.java` (pass-through) + test
**Task 3:** `sdd-cli/src/main/java/sdd/cli/implement/Propagation.java` + test
**Task 4:** `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java` (settingsFor wiring) + test

---

### Task 1: GradleTool extra-args

**Files:**
- Modify: `sdd-agent/src/main/java/sdd/agent/tool/GradleTool.java`
- Test: `sdd-agent/src/test/java/sdd/agent/tool/GradleToolExtraArgsTest.java`

**Interfaces:**
- Produces: a new ctor `GradleTool(Path repoRoot, Path javaHome, Duration timeout, List<String> extraArgs)`; the existing 3-arg ctor delegates with `List.of()`. `extraArgs` is spliced into the subprocess command list immediately after the task verb, before `--no-configuration-cache`. Everything else (allowlist, env-scrub, output caps, timeout kill) is byte-for-byte unchanged.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradleToolExtraArgsTest {
    @TempDir Path repo;

    private void gradlew(String script) throws Exception {
        Path g = repo.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void appendsExtraArgsToTheGradleCommand() throws Exception {
        gradlew("echo \"$@\"; exit 0");   // echo the args the wrapper received
        GradleTool gradle = new GradleTool(repo, null, Duration.ofSeconds(5),
                List.of("--include-build", "/w/lib"));

        String out = gradle.run("check");

        assertThat(out).startsWith("exit 0")
                .contains("check")
                .contains("--include-build")
                .contains("/w/lib")
                .contains("--no-configuration-cache");   // guardrail flags still present
    }

    @Test
    void threeArgCtorAppendsNoExtraArgs() throws Exception {
        gradlew("echo \"$@\"; exit 0");
        GradleTool gradle = new GradleTool(repo, null, Duration.ofSeconds(5));

        String out = gradle.run("check");

        assertThat(out).startsWith("exit 0").contains("check").doesNotContain("--include-build");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE** (the 4-arg ctor doesn't exist).
Run: `./gradlew :sdd-agent:test --tests 'sdd.agent.tool.GradleToolExtraArgsTest'`

- [ ] **Step 3: Edit `GradleTool.java`.** Add the `extraArgs` field + the 4-arg ctor (3-arg delegates), and splice `extraArgs` into the command list. Replace the field/ctor block:

```java
    private final Path repoRoot;
    private final Path javaHome;
    private final Duration timeout;
    private final java.util.List<String> extraArgs;

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout) {
        this(repoRoot, javaHome, timeout, java.util.List.of());
    }

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout, java.util.List<String> extraArgs) {
        this.repoRoot = repoRoot;
        this.javaHome = javaHome;
        this.timeout = timeout;
        this.extraArgs = java.util.List.copyOf(extraArgs);
    }
```

and replace the `ProcessBuilder` construction inside `execute(...)` (currently `new ProcessBuilder(List.of("./gradlew", task, "--no-configuration-cache", "--no-daemon", "-q"))`) with a splice:

```java
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("./gradlew");
            command.add(task);
            command.addAll(extraArgs);          // orchestrator-appended substitution flags (invisible to the model)
            command.add("--no-configuration-cache");
            command.add("--no-daemon");
            command.add("-q");
            ProcessBuilder builder = new ProcessBuilder(command);
```

Leave the allowlist check, `scrubEnvironment`, redirect/timeout logic, and `tailCap`/`headCap` exactly as they are.

- [ ] **Step 4: Run the full sdd-agent suite — expect PASS** (the new test plus every existing `GradleToolTest`/`ToolboxTest`/`VerificationRunnerTest`/`RepoStepRunnerTest` stays green — the 3-arg ctor is preserved).
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-agent/src
git commit -m "feat: gradle tool accepts orchestrator-appended substitution flags"
```

---

### Task 2: Thread gradleExtraArgs through RunnerSettings → RepoStepRunner

**Files:**
- Modify: `sdd-agent/src/main/java/sdd/agent/run/RunnerSettings.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/run/RepoStepRunner.java`
- Test: `sdd-agent/src/test/java/sdd/agent/run/RepoStepPropagationTest.java`

**Interfaces:**
- Produces: `RunnerSettings` gains a `List<String> gradleExtraArgs` record component (last); `defaults(Path javaHome)` is preserved (delegates with `List.of()`); a new `defaults(Path javaHome, List<String> gradleExtraArgs)` overload sets it. `RepoStepRunner.run` builds its `GradleTool` with `settings.gradleExtraArgs()` (4th arg) — the ONE construction point shared by the agent's Toolbox and the verify gate.
- Consumes: the Task 1 `GradleTool` 4-arg ctor.

- [ ] **Step 1: Write the failing test** — proves the flags reach the verify gate (via `StepOutcome.verificationOutput`, which carries the compacted `check` output):

```java
package sdd.agent.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoStepPropagationTest {
    @TempDir Path ws;
    private Database db;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc','/w/svc','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','SERVICE')");
        });
        repoRoot = Files.createDirectories(ws.resolve("svc"));
        Files.writeString(repoRoot.resolve("A.java"), "class A {}\n");
        Path g = repoRoot.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\necho \"$@\"\nexit 0\n");   // green + echoes args
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void gradleExtraArgsReachTheVerifyGate() {
        RepoStep step = new RepoStep("svc", repoRoot, "noop", List.of(), List.of(),
                List.of(), List.of(), List.of());
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StepOutcome outcome = new RepoStepRunner(db.jdbi()).run(step, model, "qwen",
                RunnerSettings.defaults(null, List.of("--include-build", "/w/lib")));

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);
        assertThat(outcome.verificationOutput()).contains("--include-build").contains("/w/lib");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE** (the `defaults(Path, List)` overload / `gradleExtraArgs()` accessor don't exist).

- [ ] **Step 3: Edit `RunnerSettings.java`.** Add the record component (last) + the overload; keep `defaults(Path)`:

```java
public record RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock,
                             Path javaHome, Duration gradleTimeout, String verificationTask,
                             int maxTokensPerCall, String systemPrompt, List<String> gradleExtraArgs) {

    public static final String DEFAULT_SYSTEM_PROMPT = /* KEEP the existing multi-line value verbatim */ ...;

    public RunnerSettings {
        gradleExtraArgs = List.copyOf(gradleExtraArgs);
    }

    public static RunnerSettings defaults(Path javaHome) {
        return defaults(javaHome, List.of());
    }

    public static RunnerSettings defaults(Path javaHome, List<String> gradleExtraArgs) {
        return new RunnerSettings(AgentBudget.defaults(), 80_000, InstantSource.system(), javaHome,
                Duration.ofMinutes(15), "check", 4096, DEFAULT_SYSTEM_PROMPT, gradleExtraArgs);
    }
}
```

**MUST:** add `import java.util.List;` to `RunnerSettings.java` — it is NOT currently imported (only `AgentBudget`, `Path`, `Duration`, `InstantSource` are). And KEEP the existing `DEFAULT_SYSTEM_PROMPT` constant's real multi-line value exactly as it is (the `...` above is a placeholder — do not literally write `...`); only add the new component, the compact constructor, and the second `defaults` overload.

- [ ] **Step 4: Edit `RepoStepRunner.java`** — the single `GradleTool` construction line in `run(...)`:

```java
        GradleTool gradle = new GradleTool(step.repoRoot(), settings.javaHome(),
                settings.gradleTimeout(), settings.gradleExtraArgs());
```

Nothing else in `RepoStepRunner` changes — the same `gradle` reference already flows to both the `Toolbox` and the `VerificationRunner`.

- [ ] **Step 5: Run the full sdd-agent suite — expect PASS** (`RepoStepRunnerTest`/`OrchestratorTest` etc. use `RunnerSettings.defaults(null)` which is preserved).
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 6: Commit**

```bash
git add sdd-agent/src
git commit -m "feat: thread gradle substitution flags through the repo-step runner"
```

---

### Task 3: Propagation — compute a repo's include-build flags

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/Propagation.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/PropagationTest.java`

**Interfaces:**
- Produces: `public final class Propagation { public static List<String> includeBuildArgs(String repo, List<PlanModel.PlanEdge> edges, Map<String, Path> repoPaths); }` — for consumer `repo`, one `--include-build <abs provider checkout>` pair per edge where `fromRepo == repo` AND `mechanism == "INCLUDE_BUILD"`, provider = `toRepo` resolved through `repoPaths` (skipped if the path is unknown). `MAVEN_LOCAL`/`NONE` edges and outbound edges produce nothing.
- Consumes: `PlanModel.PlanEdge` (4C-1).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropagationTest {
    // from=consumer, to=provider. svc consumes lib (INCLUDE_BUILD) and legacy (MAVEN_LOCAL); app consumes svc (NONE).
    private static final List<PlanModel.PlanEdge> EDGES = List.of(
            new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
            new PlanModel.PlanEdge("svc", "legacy", "PINNED", "MAVEN_LOCAL"),
            new PlanModel.PlanEdge("app", "svc", "COMPOSITE", "NONE"));
    private static final Map<String, Path> PATHS = Map.of(
            "lib", Path.of("/w/lib"), "legacy", Path.of("/w/legacy"), "svc", Path.of("/w/svc"));

    @Test
    void injectsOnlyIncludeBuildProviders() {
        assertThat(Propagation.includeBuildArgs("svc", EDGES, PATHS))
                .containsExactly("--include-build", "/w/lib");   // NOT legacy (MAVEN_LOCAL)
    }

    @Test
    void noFlagsForARepoWithNoIncludeBuildInboundEdges() {
        assertThat(Propagation.includeBuildArgs("app", EDGES, PATHS)).isEmpty();   // its edge is NONE
        assertThat(Propagation.includeBuildArgs("lib", EDGES, PATHS)).isEmpty();   // provider only, no inbound
    }

    @Test
    void skipsAProviderMissingFromTheKb() {
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("svc", "ghost", "SNAPSHOT", "INCLUDE_BUILD"));
        assertThat(Propagation.includeBuildArgs("svc", edges, PATHS)).isEmpty();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `Propagation.java`:

```java
package sdd.cli.implement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the "invisible" Gradle substitution flags for a consumer repo (design line 61, primary path):
 * one {@code --include-build <provider checkout>} per INCLUDE_BUILD inbound edge. plan.json edge direction
 * is {@code from_repo = consumer, to_repo = provider} (same as {@code Scheduler.upstreams}), so a repo's
 * providers are the {@code toRepo} of edges where it is the {@code fromRepo}. MAVEN_LOCAL / NONE edges and
 * the version-bump path are 4C-2b.
 */
public final class Propagation {
    private Propagation() {
    }

    public static List<String> includeBuildArgs(String repo, List<PlanModel.PlanEdge> edges,
                                                Map<String, Path> repoPaths) {
        List<String> args = new ArrayList<>();
        for (PlanModel.PlanEdge edge : edges) {
            if (edge.fromRepo().equals(repo) && "INCLUDE_BUILD".equals(edge.mechanism())) {
                Path provider = repoPaths.get(edge.toRepo());
                if (provider != null) {
                    args.add("--include-build");
                    args.add(provider.toAbsolutePath().toString());
                }
            }
        }
        return args;
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-cli:test
git add sdd-cli/src
git commit -m "feat: compute per-repo include-build substitution flags"
```

---

### Task 4: Wire propagation into `sdd implement`

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java`
- Test: `sdd-cli/src/test/java/sdd/cli/ImplementCommandPropagationTest.java`

**Interfaces:**
- Produces: `ImplementCommand.call()`'s `settingsFor` lambda additionally computes `Propagation.includeBuildArgs(repo, plan.edges(), paths)` and passes it via `RunnerSettings.defaults(javaHome, extraArgs)`. Everything else in the command is unchanged.
- Consumes: `Propagation` (Task 3), the Task 2 `RunnerSettings.defaults(Path, List)`.

- [ ] **Step 1: Write the failing test** — an end-to-end run where the downstream repo's verification passes ONLY if the `--include-build` flag reached it (its stub `gradlew` exits non-zero without the flag). This proves the flag threads all the way from plan.json edges to the consumer's Gradle subprocess:

```java
package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementCommandPropagationTest {
    @TempDir Path ws;

    private FixtureRepo repo(String name, String gradlewBody) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + gradlewBody + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        return repo.commit("base");
    }

    private static ChatResponse done() {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall("d", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")),
                null), "tool_calls", new Usage(10, 5));
    }

    @Test
    void includeBuildFlagReachesTheConsumer() throws Exception {
        FixtureRepo lib = repo("lib", "exit 0");
        // svc's verification passes ONLY if --include-build was appended to its gradle call
        FixtureRepo svc = repo("svc", "case \"$*\" in *--include-build*) exit 0 ;; *) exit 1 ;; esac");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', ?, 'SERVICE')", svc.path().toString());
            });
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-9
                title: Prop
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: x

                ## Acceptance Criteria
                - A1: x
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[
                    {"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"},
                    {"name":"svc","role":"dependent","annotation":"CODE_CHANGE_LIKELY","version_action":"patch","base_sha":"%s"}],
                  "order":[["lib"],["svc"]],
                  "edges":[{"from_repo":"svc","to_repo":"lib","mode":"SNAPSHOT","mechanism":"INCLUDE_BUILD"}],
                  "contracts":[],
                  "steps":[
                    {"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],"files":[],"verification":[],"sub_spec":"x"},
                    {"repo":"svc","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],"files":[],"verification":[],"sub_spec":"x"}] }
                """.formatted(specSha, lib.headSha(), svc.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = new ScriptedChatModel(List.of(done(), done()));   // lib done, svc done

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);   // svc SUCCEEDED → its verify saw --include-build
        assertThat(out.toString()).contains("svc: SUCCEEDED").contains("lib: SUCCEEDED");
    }
}
```

- [ ] **Step 2: Run — expect FAILURE.** Without the wiring, svc's verify never sees `--include-build`, so svc's stub `gradlew` exits 1; the verify-retry then runs the loop again and exhausts the 2-response scripted model, so the run aborts → exit 4. (The exact pre-fix code — 2 vs 4 — doesn't matter; the assertions `exit == 0` and both `SUCCEEDED` are red, which is the point: red-before, green-after.)
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.ImplementCommandPropagationTest'`

- [ ] **Step 3: Edit `ImplementCommand.java`** — extend the `settingsFor` lambda (everything it needs — `plan`, `paths`, `steps`, `config` — is already in scope) to compute and pass the include-build flags:

```java
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = steps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    List<String> extraArgs = sdd.cli.implement.Propagation.includeBuildArgs(
                            repo, plan.edges(), paths);
                    return RunnerSettings.defaults(javaHome, extraArgs);
                };
```

(**MUST:** add `import java.util.List;` to `ImplementCommand.java` — it is NOT currently imported.)

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.ImplementCommandPropagationTest'`

- [ ] **Step 5: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: sdd implement injects include-build substitution per plan edges"
```

---

## Verification

1. `./gradlew build` — all 5 modules green; the full sdd-agent + sdd-cli suites confirm the additive changes broke nothing.
2. `GradleToolExtraArgsTest` proves the flags land on the command line (and the 3-arg ctor stays flag-free); `RepoStepPropagationTest` proves they reach the verify gate through the shared `GradleTool`; `PropagationTest` proves only INCLUDE_BUILD inbound edges (correct direction) produce flags; `ImplementCommandPropagationTest` proves the whole path — plan.json edge → consumer's Gradle subprocess — via a downstream stub that only passes WITH the flag.
3. Real-estate smoke (a served-Qwen coding run on the all-INCLUDE_BUILD trading estate, where a dependent finally compiles against the in-run upstream) is now unblocked for the first time — deferred to when the model weights are downloaded; this phase is exercised entirely through `ScriptedChatModel` + `FixtureRepo`s + stub `gradlew` scripts.

## Self-Review (completed at write time)

1. **Spec coverage (design line 61 primary path + line 57 invisibility):** `--include-build <provider checkout>` appended to every consumer Gradle call, invisibly, reaching both `run_gradle` and the verify gate via the single shared `GradleTool` — Tasks 1-2; the per-repo flag computation from INCLUDE_BUILD inbound edges with KB-resolved provider paths — Task 3; wired into `sdd implement` — Task 4. COMPOSITE/NONE inject nothing (Propagation only emits for `INCLUDE_BUILD`).
2. **Deferred → 4C-2b (fixture-only, recorded in Global Constraints):** the `MAVEN_LOCAL` fallback (publishToMavenLocal allowlist + ordered upstream publish + `m2/` + `-Pversion`/`-Dmaven.repo.local` + init-script injection) and the PINNED/BOM version-bump edit (KB-read `declared_via`/`declared_version` + version-action bump policy + cross-repo declaration-site resolution + `PreFlight`/`RunState` widening for step-less bom sites). 4D remains separate (contract actualization + japicmp). The trading estate is all-INCLUDE_BUILD, so 4C-2b has zero real-estate coverage and is validated against the fixture estate only.
3. **Interpretation:** 4C-2 is scoped to INCLUDE_BUILD (the estate-relevant primary path) per both recon reports; `MAVEN_LOCAL`/version-bump are split into 4C-2b because `publishToMavenLocal` is off `GradleTool.ALLOWED` and cannot ride this flag seam, and version-bump needs KB data absent from plan.json — a materially larger, fixture-only change.
4. **Placeholder scan:** none; every code step is complete.
5. **Additivity / no-break:** the 3-arg `GradleTool` ctor and `RunnerSettings.defaults(Path)` are both preserved (delegating to the new overloads), and no code calls `new RunnerSettings(...)` directly — so all 9 `new GradleTool(...)` sites and both `RunnerSettings.defaults(null)` sites keep compiling; `Toolbox`/`VerificationRunner`/`Orchestrator` signatures are untouched. Verified by running the full sdd-agent + sdd-cli suites at Tasks 1, 2, 4.
6. **Type consistency:** `GradleTool(Path, Path, Duration, List<String>)` (T1) consumed by `RepoStepRunner` (T2); `RunnerSettings.defaults(Path, List<String>)` + `gradleExtraArgs()` (T2) consumed by `ImplementCommand` (T4); `Propagation.includeBuildArgs(String, List<PlanModel.PlanEdge>, Map<String,Path>)` (T3) consumed by `ImplementCommand` (T4); edge direction `from=consumer,to=provider` matches `Scheduler.upstreams` and the corrected 4C-1 cascade.
7. **Adversarial hardening (2 critics against the real merged code):** compile+design — clean except one folded fix (`ImplementCommand` needed `import java.util.List;`, which the plan had falsely claimed present); confirmed all 4 shipped-code edits match the real lines, no external `new RunnerSettings(...)` caller (arity change safe), allowlist excludes `publishToMavenLocal`, edge direction matches `Scheduler.upstreams`, flags invisible. Test-quality — all 4 tests traced red-before/green-after and non-vacuous; folded: `RunnerSettings` genuinely lacks `import java.util.List;` (mandatory) + keep `DEFAULT_SYSTEM_PROMPT`; corrected Task 4's pre-fix narrative (svc's verify-retry exhausts the scripted model → exit 4, not a clean exit 2). Verified the green-build `OutputCompactor` path preserves the echoed args into `verificationOutput`, and (by decompiling JGit 6.10) that the agents' edit-free `done` still commits cleanly (empty commits allowed when `only` is empty).
