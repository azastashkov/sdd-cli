# Phase 4C-3b: Concurrency + Run Hardening — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdd implement` runs independent repos in parallel on virtual threads under `gradle_workers`/`model_concurrency` semaphores, takes its run budget and M7 verification exclusions from sdd.yml, waits out endpoint outages with `--wait-endpoint`, and hardens the run around the gaps recorded in the 4C-3/4C-2b reviews (partial tokens, run-level pause events, stale locks, propagation snapshot, bump/init-script edge cases, and the four deferred interaction tests).

**Architecture:** Config first: sdd.yml gains an optional `run:` section and `verification_exclusions:` map (sdd-core). The agent surface (sdd-agent/sdd-core) gains the throttle and verification seams: `RunnerSettings` carries a verification-task LIST and an optional Gradle `Semaphore`, `GradleTool` acquires it around each subprocess, `ModelException` carries partial tokens, and a `ThrottledChatModel` decorator caps concurrent model calls. The orchestrator (sdd-cli) then recomputes true parallel layers from plan edges at implement time (plan.json unchanged — its `order` entries are singleton units except SCC cycles, which stay internally sequential), executes each layer on virtual threads with all shared-state mutation behind one lock, and `ImplementCommand` wires config, `--wait-endpoint` polling via the existing `EndpointProbe`, and the propagation snapshot.

**Tech Stack:** Java 21 (virtual threads, no preview flags), `java.util.concurrent.Semaphore`, Jackson, picocli, JUnit 5 + AssertJ, FixtureRepo + stub `gradlew` scripts, ScriptedChatModel/ChatModel lambdas.

## Global Constraints

- **Scope:** parallel-within-level + semaphores, `run:`/exclusions config, `--wait-endpoint`, M7 "not locally verified", plan-step verification wiring, partial-token accounting, run-level pause events, PID-stamped stale-lock detection, propagation snapshot for `--resume`, publish==current warning, version-substring guard, init-script quote escaping, mixed-mechanism e2e, and the four deferred interaction tests. Explicitly DEFERRED to **4C-3c**: full M8 staleness recovery (needs sdd-index re-index integration), the design's `transcript.jsonl`/`edits.jsonl` (needs a 4B ContextWindow surface), agent-events append-across-resume (same on-disk-contract area), and publish-only resume.
- **Ratified interpretations (flag at review if you disagree):** (a) parallel layers are RECOMPUTED at implement time from `plan.edges()` over the plan's order units — plan.json's approved format is untouched; a multi-member unit (SCC cycle) is atomic and internally sequential; (b) `gradle_workers` counts concurrent `./gradlew` subprocesses (agent `run_gradle`, verify gate, AND the MavenLocal publisher); `model_concurrency` is ONE semaphore shared by coder and escalation; defaults `gradle_workers: 2`, `model_concurrency: 2`, `token_budget: 30000000`; (c) a pause stops SCHEDULING (no new repo starts, later layers don't run) but repos already running finish and record their outcomes; the FIRST pause wins — later pause attempts on other threads are no-ops; (d) an empty/absent plan-step `verification` list means the DEFAULT `check` task (fixtures write `verification: []` everywhere — treating empty as "skip" would silently vacate every existing e2e verify gate); only sdd.yml `verification_exclusions` can empty the effective list, which skips the gate and surfaces "not locally verified" (M7); (e) a verification task not in `GradleTool`'s allowlist fails the gate loudly with the existing "gradle task not allowed" message — a plan-authoring error, never silently dropped; (f) a 4xx `ModelException` on any worker thread stops scheduling and rethrows after the layer drains (exit 4 upstream, lock released by the orchestrator's finally); (g) budget-paused runs are now resumable after raising `run.token_budget` in sdd.yml — the CLI hint says so.
- **Thread-safety invariant:** every mutation of `RunState` and every `state.json`/`events.jsonl` write happens while holding the orchestrator's single `lock`; per-repo `agent-events.jsonl` writes and git/gradle work stay outside it (naturally partitioned per repo).
- **Orchestrator constructor arity does NOT change** (still 10 args) — throttling rides `ChatModel` decoration and `RunnerSettings.gradlePermits`, both injected via existing parameters.
- **`GradleToolTest.disallowedTaskNeverRuns` stays untouched and green**; `GradleTool.ALLOWED` is not widened.
- **Zero-test-breaking** outside files a task explicitly edits. Arity changes and their complete call-site lists: `SddConfig` 7→9 components (Task 1 — `ConfigLoader` plus FOUR sdd-index test files constructing the 7-arg form, all listed in Task 1's Files; re-verify with `grep -rn "new SddConfig(" sdd-*/src`); `RunnerSettings` 9→10 components with `verificationTask` → `verificationTasks` (Task 2 — constructed only via its own factories; `RepoStepRunner` is the only production reader); `ModelException` gains a field (additive). If an implementer finds an unlisted broken call site, fix it mechanically and note it in the report.
- Commit messages: conventional commits, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Context (verified against the code, 2026-08-13, main @ 053cc50)

- `plan.order()` entries are SINGLETON units except SCC cycle groups (`ExecutionOrder` pops one Kahn-ready node at a time, alphabetical) — naive "parallelize within an order entry" yields no parallelism; layers must be derived from `plan.edges()` at implement time. `PlanValidator` tolerates same-position edges only inside cycle units.
- `RunState` (plain `LinkedHashMap`, unsynchronized `pause`/`addTokens`) and `RunStore.writeState`/`appendEvent` have zero synchronization; every `RepoState` write already funnels through `Orchestrator.transition()` — the natural lock seam. `writeAgentEvents` targets per-repo files (naturally partitioned).
- `GradleTool` is a concrete class built fresh inside `RepoStepRunner.run` from `RunnerSettings` — a `Semaphore` field on `RunnerSettings` threaded into a new `GradleTool` ctor arg is the minimal correctly-scoped `gradle_workers` seam. `ChatModel` is a SAM interface — a decorator is the established injection pattern.
- `AgentLoop.run` accumulates `tokens` locally and loses them when `model.complete` throws; `RepoStepRunner` likewise; `Orchestrator` adds tokens only on the normal return path. `ModelException` (sdd-core) already crosses all three layers raw.
- `PlanModel.PlanStep.verification()` (List<String>) is parsed from plan.json but consumed NOWHERE — the verify gate always runs the hardcoded `RunnerSettings.verificationTask = "check"`. Design line 63: "the plan step's verification tasks (per-repo exclusions from sdd.yml, surfaced as 'not locally verified' — M7)".
- `state.pause()` has 4 call sites in `Orchestrator` (budget top-of-loop, endpoint, publish-infra, verify-infra); the budget one writes NO events.jsonl line today. `appendEvent` is repo-scoped.
- Lock file is a zero-byte sentinel; `ImplementCommand`'s javadoc documents manual removal after hard crashes. `EndpointProbe.probe(ModelEndpoint)` exists, tested, called only by `sdd doctor`. `HttpChatModel` already implements the design's 6-try backoff/jitter/Retry-After — only post-pause waiting is missing.
- `ImplementCommand` post-4C-2b: `RUN_TOKEN_BUDGET = 30_000_000L` constant (comment: "sdd.yml override is 4C-3b"); fresh/resume fork with `activePlan`/`activeSteps`/`activeRunDir` aliases; propagation planned pre-lock in both branches; `PropagationPlanner`'s javadoc documents the live-KB-on-resume limitation this plan closes.
- `VersionBump.apply`'s direct path uses unanchored `String.replace` (`…:1.2.3` matches inside `…:1.2.30`); `MavenLocalInit.write` interpolates the m2 path raw into a Groovy single-quoted literal.
- Java 21 toolchain repo-wide; `Executors.newVirtualThreadPerTaskExecutor()` available without flags.

---

### Task 1: sdd.yml `run:` section + verification exclusions (sdd-core)

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/config/RunSettings.java`
- Modify: `sdd-core/src/main/java/sdd/core/config/SddConfig.java`
- Modify: `sdd-core/src/main/java/sdd/core/config/ConfigLoader.java`
- Modify (mechanical, arity only — append `RunSettings.defaults(), Map.of()` to each 7-arg `new SddConfig(...)`): `sdd-index/src/test/java/sdd/index/GoldenEstateTest.java`, `sdd-index/src/test/java/sdd/index/IndexServiceTest.java`, `sdd-index/src/test/java/sdd/index/SourceEndToEndTest.java`, `sdd-index/src/test/java/sdd/index/IndexServiceIT.java`
- Test: `sdd-core/src/test/java/sdd/core/config/ConfigLoaderTest.java` (add tests)

**Interfaces:**
- Produces: `record RunSettings(int gradleWorkers, int modelConcurrency, long tokenBudget)` with `static RunSettings defaults()` = `(2, 2, 30_000_000L)`. `SddConfig` gains two trailing components: `RunSettings run, Map<String, List<String>> verificationExclusions` (never null; empty map default). sdd.yml shapes: `run: {gradle_workers: N, model_concurrency: N, token_budget: N}` (all keys optional) and `verification_exclusions: {repo-name: [task, ...]}`. Malformed values throw `ConfigException` naming the key.
- Consumes: existing `ConfigLoader` optional-section parse pattern (`artifact_overrides`/`manual_edges`).

- [ ] **Step 1: Write the failing tests** (append to `ConfigLoaderTest`, following its `write(yaml)` + assert pattern; base every yaml on the file's existing minimal valid models block):

```java
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
                """);
        SddConfig config = ConfigLoader.load(ws);
        assertThat(config.run()).isEqualTo(new RunSettings(4, 1, 5_000_000L));
    }

    @Test
    void runSectionDefaultsWhenAbsentOrPartial() throws Exception {
        Path absent = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                """);
        assertThat(ConfigLoader.load(absent).run()).isEqualTo(RunSettings.defaults());
        assertThat(RunSettings.defaults()).isEqualTo(new RunSettings(2, 2, 30_000_000L));

        Path partial = write("""
                models:
                  planner: { base_url: http://x/v1, model: p }
                  coder: { base_url: http://y/v1, model: c }
                run:
                  gradle_workers: 8
                """);
        assertThat(ConfigLoader.load(partial).run()).isEqualTo(new RunSettings(8, 2, 30_000_000L));
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
```

(If `ConfigLoaderTest` lacks `assertThatThrownBy` or `RunSettings` imports, add them; match the file's existing `write` helper name/signature exactly — read the file first.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-core:test --tests 'sdd.core.config.ConfigLoaderTest'`

- [ ] **Step 3: Implement.** `RunSettings.java`:

```java
package sdd.core.config;

/** The sdd.yml run: section — implement-time throttles and the run-wide token budget
 *  (design lines 59-60: run budget, gradle_workers/model_concurrency semaphores). */
public record RunSettings(int gradleWorkers, int modelConcurrency, long tokenBudget) {
    public static RunSettings defaults() {
        return new RunSettings(2, 2, 30_000_000L);
    }
}
```

`SddConfig` gains the two trailing components (update its sole constructor call in `ConfigLoader`):

```java
public record SddConfig(
        Path workspace,
        String retrieval,
        Map<String, ModelEndpoint> models,
        Map<Integer, Path> jdkHomes,
        List<String> excludes,
        Map<String, String> artifactOverrides,
        List<ManualEdge> manualEdges,
        RunSettings run,
        Map<String, List<String>> verificationExclusions) {}
```

`ConfigLoader`: after the `manual_edges` block, parse both sections with the existing helpers/`ConfigException` conventions:

```java
        RunSettings run = RunSettings.defaults();
        Object runNode = root.get("run");
        if (runNode instanceof Map<?, ?> rm) {
            // parseInt/parseLong take (String where, String value) — see the max_tokens/timeout_seconds
            // pattern in this file; wrap raw node values with String.valueOf.
            int gradleWorkers = rm.get("gradle_workers") != null
                    ? parseInt("run.gradle_workers", String.valueOf(rm.get("gradle_workers"))) : run.gradleWorkers();
            int modelConcurrency = rm.get("model_concurrency") != null
                    ? parseInt("run.model_concurrency", String.valueOf(rm.get("model_concurrency"))) : run.modelConcurrency();
            long tokenBudget = rm.get("token_budget") != null
                    ? parseLong("run.token_budget", String.valueOf(rm.get("token_budget"))) : run.tokenBudget();
            run = new RunSettings(gradleWorkers, modelConcurrency, tokenBudget);
        } else if (runNode != null) {
            throw new ConfigException("run must be a mapping, got: " + runNode);
        }

        Map<String, List<String>> verificationExclusions = new LinkedHashMap<>();
        Object exclusionsNode = root.get("verification_exclusions");
        if (exclusionsNode instanceof Map<?, ?> em) {
            for (Map.Entry<?, ?> entry : em.entrySet()) {
                if (!(entry.getValue() instanceof List<?> tasks)) {
                    throw new ConfigException("verification_exclusions." + entry.getKey()
                            + " must be a list of task names");
                }
                List<String> names = new ArrayList<>();
                for (Object task : tasks) {
                    names.add(String.valueOf(task));
                }
                verificationExclusions.put(String.valueOf(entry.getKey()), List.copyOf(names));
            }
        } else if (exclusionsNode != null) {
            throw new ConfigException("verification_exclusions must be a mapping, got: " + exclusionsNode);
        }
```

and thread both into the `new SddConfig(...)` return. (Adapt the `parseInt`/`parseLong` helper names to what the file actually has — if there is no `parseLong`, add one mirroring `parseInt`. `grep -rn "new SddConfig(" sdd-*/src` and fix any other construction site the same way — none is expected outside `ConfigLoader`.)

- [ ] **Step 4: Run — expect PASS, then the full sdd-core suite plus a whole-build compile check** (SddConfig is consumed across modules; accessor-only readers compile unchanged).
Run: `./gradlew :sdd-core:test && ./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: sdd.yml run section and per-repo verification exclusions"
```

---

### Task 2: Agent-surface seams — throttles, verification-task list, partial tokens (sdd-agent + sdd-core)

**Files:**
- Modify: `sdd-agent/src/main/java/sdd/agent/run/RunnerSettings.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/tool/GradleTool.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/run/RepoStepRunner.java`
- Modify: `sdd-core/src/main/java/sdd/core/llm/ModelException.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/loop/AgentLoop.java`
- Create: `sdd-core/src/main/java/sdd/core/llm/ThrottledChatModel.java`
- Test: `sdd-agent/src/test/java/sdd/agent/tool/GradleToolThrottleTest.java` (create), `sdd-agent/src/test/java/sdd/agent/run/RepoStepRunnerTest.java` (add), `sdd-core/src/test/java/sdd/core/llm/ThrottledChatModelTest.java` (create)

**Interfaces:**
- Produces: `RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock, Path javaHome, Duration gradleTimeout, List<String> verificationTasks, int maxTokensPerCall, String systemPrompt, List<String> gradleExtraArgs, Semaphore gradlePermits)` — `verificationTask` (String) becomes `verificationTasks` (List, copied defensively); new nullable `gradlePermits`; `defaults(Path)`/`defaults(Path, List)` keep their signatures (producing `List.of("check")`, null permits); new `static RunnerSettings custom(Path javaHome, List<String> gradleExtraArgs, List<String> verificationTasks, Semaphore gradlePermits)`. `GradleTool` gains a 5-arg ctor `(Path, Path, Duration, List<String>, Semaphore)` (4-arg delegates null); a non-null semaphore is acquired (uninterruptibly) around the subprocess section of `execute` and released in a finally. `RepoStepRunner`: the DONE branch runs EVERY task in `verificationTasks` in order (first failure is the verdict; infra retry re-runs the full list once); an EMPTY list skips the gate — event `"verify: skipped — not locally verified (all verification tasks excluded)"`, verificationOutput `"not locally verified"`, SUCCESS. `ModelException` gains `long tokensSoFar()` (0 in existing ctors) and `ModelException withTokens(long total)` (copies message/statusCode, sets tokens, chains the original as cause); `AgentLoop` catches `ModelException` around `model.complete` and rethrows `e.withTokens(tokens + e.tokensSoFar())` — the inbound exception may ALREADY carry tokens (a test double, or a future nested wrap) and clobbering them with the local count alone would zero the carry; `RepoStepRunner` catches around `loop.run` and rethrows `e.withTokens(tokens + e.tokensSoFar())` for the same reason. `ThrottledChatModel(ChatModel delegate, Semaphore permits)` implements `ChatModel`, acquiring/releasing around `complete`.
- Consumes: nothing from Task 1 (module-independent; Task 5 joins them).

- [ ] **Step 1: Write the failing tests.** `ThrottledChatModelTest.java`:

```java
package sdd.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThrottledChatModelTest {
    private static final ChatRequest REQ = new ChatRequest("m", List.of(), List.of(), 16, 0.0);

    @Test
    void acquiresAPermitAroundTheCallAndReleasesIt() {
        Semaphore permits = new Semaphore(1);
        ChatModel inner = req -> {
            assertThat(permits.availablePermits()).isZero();   // held during the call
            return new ChatResponse(new ChatMessage("assistant", "ok", List.of(), null),
                    "stop", new Usage(1, 1));
        };

        ChatResponse response = new ThrottledChatModel(inner, permits).complete(REQ);

        assertThat(response.message().content()).isEqualTo("ok");
        assertThat(permits.availablePermits()).isEqualTo(1);   // released after
    }

    @Test
    void releasesThePermitWhenTheDelegateThrows() {
        Semaphore permits = new Semaphore(1);
        ChatModel inner = req -> {
            throw new ModelException("boom", 500);
        };

        assertThatThrownBy(() -> new ThrottledChatModel(inner, permits).complete(REQ))
                .isInstanceOf(ModelException.class);
        assertThat(permits.availablePermits()).isEqualTo(1);
    }
}
```

(Adjust the `ChatMessage` constructor call to the real record shape — read `ChatMessage.java` first and use whatever builds an assistant text message.)

`GradleToolThrottleTest.java` — proves permits serialize two concurrent subprocesses:

```java
package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

class GradleToolThrottleTest {
    @TempDir Path ws;

    private Path repoWith(String name, String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve(name));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void aSingletonSemaphoreSerializesConcurrentGradleRuns() throws Exception {
        // Overlap-marker scheme (portable — BSD date has no %N): each stub flags itself as
        // running, records an overlap file if it ever sees the other's flag, and unflags on exit.
        // Under a 1-permit semaphore the overlap file must never appear.
        Path overlap = ws.resolve("overlap");
        String scriptA = "[ -f " + ws.resolve("b.running") + " ] && touch " + overlap + "; "
                + "touch " + ws.resolve("a.running") + "; sleep 0.3; "
                + "[ -f " + ws.resolve("b.running") + " ] && touch " + overlap + "; "
                + "rm -f " + ws.resolve("a.running") + "; exit 0";
        String scriptB = "[ -f " + ws.resolve("a.running") + " ] && touch " + overlap + "; "
                + "touch " + ws.resolve("b.running") + "; sleep 0.3; "
                + "[ -f " + ws.resolve("a.running") + " ] && touch " + overlap + "; "
                + "rm -f " + ws.resolve("b.running") + "; exit 0";
        Path a = repoWith("a", scriptA);
        Path b = repoWith("b", scriptB);
        Semaphore permits = new Semaphore(1);
        GradleTool toolA = new GradleTool(a, null, Duration.ofMinutes(1), List.of(), permits);
        GradleTool toolB = new GradleTool(b, null, Duration.ofMinutes(1), List.of(), permits);

        Thread threadA = Thread.ofVirtual().start(() -> toolA.run("check"));
        Thread threadB = Thread.ofVirtual().start(() -> toolB.run("check"));
        threadA.join();
        threadB.join();

        assertThat(Files.exists(overlap))
                .as("runs must not overlap under a 1-permit semaphore").isFalse();
    }
}
```

Append to `RepoStepRunnerTest` (reuse its `gradlew`/`call`/`step` helpers; `run(model)` uses `RunnerSettings.defaults(null)` which now carries `List.of("check")`):

```java
    @Test
    void anEmptyVerificationListSkipsTheGateAsNotLocallyVerified() throws Exception {
        gradlew("exit 1");   // would fail the gate if it ran — proves the skip is real
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        StepOutcome outcome = new RepoStepRunner(db.jdbi()).run(step(repoRoot), model, "qwen",
                RunnerSettings.custom(null, List.of(), List.of(), null), "");

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);
        assertThat(outcome.verificationOutput()).isEqualTo("not locally verified");
        assertThat(outcome.events()).anyMatch(e -> e.contains("not locally verified"));
    }

    @Test
    void multipleVerificationTasksAllRunAndTheFirstFailureWins() throws Exception {
        // compileJava passes, test fails: the verdict must be test's failure, after both ran.
        gradlew("echo \"$1\" >> tasks-run; case \"$1\" in compileJava) exit 0 ;; *) echo 'A.java:1: error: bad'; exit 1 ;; esac");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));

        StepOutcome outcome = new RepoStepRunner(db.jdbi()).run(step(repoRoot), model, "qwen",
                RunnerSettings.custom(null, List.of(), List.of("compileJava", "test"), null), "");

        assertThat(outcome.result()).isEqualTo(StepResult.VERIFY_FAILED);
        assertThat(Files.readString(repoRoot.resolve("tasks-run"))).contains("compileJava").contains("test");
    }

    @Test
    void partialTokensSurviveAnEndpointFailureMidStep() throws Exception {
        gradlew("exit 1");   // first done fails verify -> loop re-enters -> second call throws
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ChatModel flaky = req -> {
            if (calls.incrementAndGet() == 1) {
                return call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}");
            }
            throw new ModelException("transport error: refused", new java.io.IOException("x"));
        };

        try {
            new RepoStepRunner(db.jdbi()).run(step(repoRoot), flaky, "qwen",
                    RunnerSettings.defaults(null), "");
            org.junit.jupiter.api.Assertions.fail("expected ModelException");
        } catch (ModelException e) {
            assertThat(e.tokensSoFar()).isEqualTo(15L);   // call 1's Usage(10,5) survives the throw
        }
    }
```

(The `flaky` lambda returns the `ChatResponse` the `call(...)` helper builds — if the helper returns `ChatResponse` directly this compiles as-is; otherwise inline the equivalent construction. New imports: `sdd.core.llm.ChatModel`, `sdd.core.llm.ModelException`.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-agent:test :sdd-core:test`

- [ ] **Step 3: Implement**, in this order:

`ModelException` — add the field, keep both existing constructors' behavior (tokens 0):

```java
public class ModelException extends RuntimeException {
    private final int statusCode;
    private final long tokensSoFar;

    public ModelException(String message, int statusCode) {
        this(message, statusCode, 0L);
    }

    public ModelException(String message, int statusCode, long tokensSoFar) {
        super(message);
        this.statusCode = statusCode;
        this.tokensSoFar = tokensSoFar;
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.tokensSoFar = 0L;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Prompt+completion tokens the failed run had already spent when this was thrown (best effort). */
    public long tokensSoFar() {
        return tokensSoFar;
    }

    /** A copy carrying the caller's running token total, chaining this exception as the cause. */
    public ModelException withTokens(long total) {
        ModelException copy = new ModelException(getMessage(), statusCode, total);
        copy.initCause(this);
        return copy;
    }
}
```

`ThrottledChatModel.java`:

```java
package sdd.core.llm;

import java.util.concurrent.Semaphore;

/** Caps concurrent model calls (design line 60: model_concurrency semaphore). A decorator on the
 *  existing ChatModel seam so neither the agent loop nor the orchestrator knows it is throttled. */
public final class ThrottledChatModel implements ChatModel {
    private final ChatModel delegate;
    private final Semaphore permits;

    public ThrottledChatModel(ChatModel delegate, Semaphore permits) {
        this.delegate = delegate;
        this.permits = permits;
    }

    @Override
    public ChatResponse complete(ChatRequest req) throws ModelException {
        permits.acquireUninterruptibly();
        try {
            return delegate.complete(req);
        } finally {
            permits.release();
        }
    }
}
```

`AgentLoop` — wrap the single `model.complete(...)` call site:

```java
            ChatResponse response;
            try {
                response = model.complete(request);
            } catch (ModelException e) {
                // Partial spend must reach the run-budget accounting. ADD to any tokens the
                // exception already carries — overwriting would zero an upstream carry.
                throw e.withTokens(tokens + e.tokensSoFar());
            }
```

(adapting local variable names to the file; only this call site changes).

`RunnerSettings` — new shape (both `defaults` keep signatures; the compact constructor copies both lists):

```java
public record RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock,
                             Path javaHome, Duration gradleTimeout, List<String> verificationTasks,
                             int maxTokensPerCall, String systemPrompt, List<String> gradleExtraArgs,
                             Semaphore gradlePermits) {
    // DEFAULT_SYSTEM_PROMPT unchanged

    public RunnerSettings {
        verificationTasks = List.copyOf(verificationTasks);
        gradleExtraArgs = List.copyOf(gradleExtraArgs);
    }

    public static RunnerSettings defaults(Path javaHome) {
        return defaults(javaHome, List.of());
    }

    public static RunnerSettings defaults(Path javaHome, List<String> gradleExtraArgs) {
        return custom(javaHome, gradleExtraArgs, List.of("check"), null);
    }

    public static RunnerSettings custom(Path javaHome, List<String> gradleExtraArgs,
                                        List<String> verificationTasks, Semaphore gradlePermits) {
        return new RunnerSettings(AgentBudget.defaults(), 80_000, InstantSource.system(), javaHome,
                Duration.ofMinutes(15), verificationTasks, 4096, DEFAULT_SYSTEM_PROMPT,
                gradleExtraArgs, gradlePermits);
    }
}
```

(import `java.util.concurrent.Semaphore`.)

`GradleTool` — 5th ctor arg + acquire around the subprocess:

```java
    private final Semaphore permits;

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout) {
        this(repoRoot, javaHome, timeout, java.util.List.of(), null);
    }

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout, java.util.List<String> extraArgs) {
        this(repoRoot, javaHome, timeout, extraArgs, null);
    }

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout, java.util.List<String> extraArgs,
                      Semaphore permits) {
        this.repoRoot = repoRoot;
        this.javaHome = javaHome;
        this.timeout = timeout;
        this.extraArgs = java.util.List.copyOf(extraArgs);
        this.permits = permits;
    }
```

and in `execute`, after the allowlist + wrapper checks (so rejections never consume a permit), wrap everything from temp-log creation through process completion:

```java
        if (permits != null) {
            permits.acquireUninterruptibly();
        }
        try {
            // existing body: createTempFile, ProcessBuilder, start, waitFor, read log, caps
        } finally {
            if (permits != null) {
                permits.release();
            }
            // existing temp-log cleanup stays inside its own finally as today
        }
```

(Restructure minimally: the existing try/catch/finally block moves inside the permit guard; the timeout early-return path must still release — returning from inside the try is fine, the finally runs.)

`RepoStepRunner` — three edits: (1) the `GradleTool` construction adds `settings.gradlePermits()` as the 5th arg; (2) wrap `loop.run`:

```java
            AgentOutcome outcome;
            try {
                outcome = loop.run(settings.systemPrompt(), workOrder, modelName,
                        settings.maxTokensPerCall());
            } catch (ModelException e) {
                throw e.withTokens(tokens + e.tokensSoFar());
            }
```

(3) the DONE branch consults the list (the infra retry re-runs the whole list; everything else keeps Task-2/4C-3 logic verbatim):

```java
                case DONE -> {
                    if (settings.verificationTasks().isEmpty()) {
                        events.add("verify: skipped — not locally verified (all verification tasks excluded)");
                        return outcome(StepResult.SUCCESS, outcome.summary(), events,
                                "not locally verified", tokens);
                    }
                    VerificationRunner.Verdict verdict = verifyAll(verifier, settings.verificationTasks());
                    if (!verdict.passed() && verdict.infra()) {
                        events.add("verify: infra-classified failure — retrying once");
                        verdict = verifyAll(verifier, settings.verificationTasks());
                        ...
```

with the new helper beside `verifyOnce`:

```java
    private static VerificationRunner.Verdict verifyAll(VerificationRunner verifier, List<String> tasks) {
        VerificationRunner.Verdict last = null;
        for (String task : tasks) {
            last = verifyOnce(verifier, task);
            if (!last.passed()) {
                return last;
            }
        }
        return last;
    }
```

(Every other `settings.verificationTask()` reference becomes the list form; `grep -n "verificationTask" sdd-*/src` must come back clean of the singular name afterward.)

- [ ] **Step 4: Run — expect PASS, then the full build** (RunnerSettings arity change: only its own factories construct it; `RepoStepPropagationTest` and all sdd-cli callers use `defaults`, unchanged signatures).
Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-agent/src sdd-core/src
git commit -m "feat: gradle/model throttling seams, verification task lists, partial-token carry"
```

---

### Task 3: Scheduler.levels + RunStore hardening (run events, PID lock, propagation snapshot)

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/Scheduler.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/SchedulerTest.java` (add), `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java` (add)

**Interfaces:**
- Produces: `Scheduler.levels(List<List<String>> order, List<PlanModel.PlanEdge> edges)` → `List<List<List<String>>>` — layered batching of the plan's order UNITS: a unit joins a layer once every unit it (transitively via direct unit-deps) depends on is in an earlier layer; units with no edges between them share a layer; multi-member units stay intact (atomic); throws `IllegalStateException` on an unresolvable inter-unit cycle (cannot happen for a valid plan — defensive). `RunStore.appendRunEvent(Path runDir, String detail)` — appends `{"at":"<instant>","run":"pause","detail":"<escaped>"}` to events.jsonl. `RunStore.acquireLock` writes the current PID into the lock file (`CREATE_NEW`, atomic); on `FileAlreadyExistsException` it reads the recorded PID — a dead PID (or a PID no `ProcessHandle` knows) is a STALE lock, silently deleted and re-acquired; a live PID (or an empty/unparseable legacy lock) keeps today's `IllegalStateException`, now including the owner PID when known. `RunStore.writePropagation(Path runDir, Map<String, RepoPropagation> propagation)` → `<runDir>/propagation.json`; `RunStore.readPropagation(Path runDir)` → the map (with `m2Dir` recomputed as `runDir.resolve("m2")`) or `null` when the file is absent (pre-4C-3b runs).
- Consumes: `RepoPropagation`/`BumpEdit`/`PublishSpec` (4C-2b), `PlanModel.PlanEdge`.

- [ ] **Step 1: Write the failing tests.** Append to `SchedulerTest`:

```java
    @Test
    void levelsBatchIndependentUnitsTogether() {
        List<List<String>> order = List.of(List.of("a"), List.of("b"), List.of("c"));
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("c", "a", "PINNED", "MAVEN_LOCAL"));   // c consumes a; b is free

        List<List<List<String>>> layers = Scheduler.levels(order, edges);

        assertThat(layers).containsExactly(
                List.of(List.of("a"), List.of("b")),   // a and b are simultaneously ready
                List.of(List.of("c")));
    }

    @Test
    void aCycleUnitStaysAtomicAndItsConsumersWait() {
        List<List<String>> order = List.of(List.of("x", "y"), List.of("z"));
        List<PlanModel.PlanEdge> edges = List.of(
                new PlanModel.PlanEdge("x", "y", "SNAPSHOT", "INCLUDE_BUILD"),   // intra-unit edge
                new PlanModel.PlanEdge("y", "x", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("z", "x", "SNAPSHOT", "INCLUDE_BUILD"));

        List<List<List<String>>> layers = Scheduler.levels(order, edges);

        assertThat(layers).containsExactly(
                List.of(List.of("x", "y")),
                List.of(List.of("z")));
    }

    @Test
    void levelsWithNoEdgesIsOneLayer() {
        List<List<String>> order = List.of(List.of("a"), List.of("b"));
        assertThat(Scheduler.levels(order, List.of()))
                .containsExactly(List.of(List.of("a"), List.of("b")));
    }
```

Append to `RunStoreTest` (reuse its `@TempDir ws` + fixed-clock store pattern):

```java
    @Test
    void appendRunEventWritesARunScopedLine() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.appendRunEvent(runDir, "run token budget exhausted (42 tokens)");

        assertThat(Files.readString(runDir.resolve("events.jsonl")))
                .contains("\"run\":\"pause\"")
                .contains("run token budget exhausted");
    }

    @Test
    void aStaleLockFromADeadProcessIsReclaimed() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = Files.createDirectories(ws.resolve(".sdd/runs/S-v1"));
        Process dead = new ProcessBuilder("true").start();
        dead.waitFor();
        Files.writeString(runDir.resolve("lock"), Long.toString(dead.pid()));

        store.acquireLock(runDir);   // must NOT throw: owner is dead

        assertThat(Files.readString(runDir.resolve("lock")))
                .isEqualTo(Long.toString(ProcessHandle.current().pid()));
    }

    @Test
    void aLiveLockStillRefuses() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = Files.createDirectories(ws.resolve(".sdd/runs/S-v2"));
        Files.writeString(runDir.resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        assertThatThrownBy(() -> store.acquireLock(runDir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    void propagationSnapshotRoundTrips() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v3", "{}", "");
        Map<String, RepoPropagation> map = Map.of(
                "svc", new RepoPropagation(
                        List.of(new RepoPropagation.BumpEdit("com.acme", "lib", "1.2.3", "1.3.0")), null),
                "lib", new RepoPropagation(List.of(),
                        new RepoPropagation.PublishSpec("1.3.0", runDir.resolve("m2"))));

        store.writePropagation(runDir, map);
        Map<String, RepoPropagation> read = store.readPropagation(runDir);

        assertThat(read).isEqualTo(map);
        assertThat(store.readPropagation(ws.resolve("nowhere"))).isNull();
    }
```

(Add `Map`/`RepoPropagation` imports as needed; check the existing `create` overload arity in the file.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.SchedulerTest' --tests 'sdd.cli.implement.RunStoreTest'`

- [ ] **Step 3: Implement.** `Scheduler.levels`:

```java
    /** Layered batching of the plan's order units for parallel-within-level execution (4C-3b).
     *  plan.order() is a valid but SERIALIZED topo order (one unit per entry except SCC cycles);
     *  true parallelism is recomputed here from the edges: a layer is every unit whose provider
     *  units have all been placed in earlier layers. Units are atomic — a multi-member cycle unit
     *  is scheduled as one entry and executed internally sequentially by the orchestrator. */
    public static List<List<List<String>>> levels(List<List<String>> order,
                                                  List<PlanModel.PlanEdge> edges) {
        Map<String, Integer> unitOf = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            for (String repo : order.get(i)) {
                unitOf.put(repo, i);
            }
        }
        Map<Integer, Set<Integer>> providers = new HashMap<>();
        for (PlanModel.PlanEdge edge : edges) {
            Integer consumer = unitOf.get(edge.fromRepo());
            Integer provider = unitOf.get(edge.toRepo());
            if (consumer != null && provider != null && !consumer.equals(provider)) {
                providers.computeIfAbsent(consumer, k -> new HashSet<>()).add(provider);
            }
        }
        List<List<List<String>>> layers = new ArrayList<>();
        Set<Integer> placed = new HashSet<>();
        while (placed.size() < order.size()) {
            List<List<String>> layer = new ArrayList<>();
            List<Integer> ready = new ArrayList<>();
            for (int i = 0; i < order.size(); i++) {
                if (!placed.contains(i) && placed.containsAll(providers.getOrDefault(i, Set.of()))) {
                    ready.add(i);
                }
            }
            if (ready.isEmpty()) {
                throw new IllegalStateException("execution order units form a cycle — plan is invalid");
            }
            for (int i : ready) {
                layer.add(order.get(i));
            }
            placed.addAll(ready);
            layers.add(layer);
        }
        return layers;
    }
```

(new imports: `HashMap`, `Map`. `sequence`/`upstreams`/`blockedByUpstream` unchanged.)

`RunStore` — `appendRunEvent` beside `appendEvent`:

```java
    /** A run-scoped (not repo-scoped) event line — today only pauses use it. */
    public void appendRunEvent(Path runDir, String detail) {
        String line = "{\"at\":\"" + clock.instant() + "\",\"run\":\"pause\",\"detail\":"
                + jsonString(detail) + "}\n";
        try {
            Files.writeString(runDir.resolve("events.jsonl"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

`acquireLock` — PID stamp + staleness:

```java
    public void acquireLock(Path runDir) {
        Path lock = runDir.resolve("lock");
        String pid = Long.toString(ProcessHandle.current().pid());
        try {
            Files.writeString(lock, pid, StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            if (lockIsStale(lock)) {
                try {
                    Files.deleteIfExists(lock);
                    Files.writeString(lock, pid, StandardOpenOption.CREATE_NEW);
                    return;
                } catch (IOException retry) {
                    throw new UncheckedIOException(retry);
                }
            }
            throw new IllegalStateException("run " + runDir.getFileName() + " is already in progress "
                    + "(lock held at " + lock + ownerSuffix(lock) + "); remove the lock to override");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Stale = the lock names a PID that is provably not alive. Empty/legacy/unreadable locks are
     *  treated as LIVE (safe default: refuse, let the human decide). */
    private static boolean lockIsStale(Path lock) {
        try {
            String text = Files.readString(lock).strip();
            if (text.isEmpty()) {
                return false;
            }
            long pid = Long.parseLong(text);
            return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }

    private static String ownerSuffix(Path lock) {
        try {
            String text = Files.readString(lock).strip();
            return text.isEmpty() ? "" : " by pid " + text;
        } catch (IOException e) {
            return "";
        }
    }
```

Propagation snapshot (local DTO records, `m2Dir` derived from `runDir` on read — `Path` never serialized):

```java
    public void writePropagation(Path runDir, Map<String, RepoPropagation> propagation) {
        record BumpDto(String group, String name, String oldVersion, String newVersion) {
        }
        record PropDto(List<BumpDto> bumps, String publishVersion) {
        }
        Map<String, PropDto> dto = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, RepoPropagation> entry : propagation.entrySet()) {
            List<BumpDto> bumps = entry.getValue().bumps().stream()
                    .map(b -> new BumpDto(b.group(), b.name(), b.oldVersion(), b.newVersion()))
                    .toList();
            String publishVersion = entry.getValue().publish() == null
                    ? null : entry.getValue().publish().version();
            dto.put(entry.getKey(), new PropDto(bumps, publishVersion));
        }
        try {
            Files.writeString(runDir.resolve("propagation.json"),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The frozen propagation plan, or null for runs from before the snapshot existed. */
    public Map<String, RepoPropagation> readPropagation(Path runDir) {
        Path file = runDir.resolve("propagation.json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(Files.readString(file));
            Map<String, RepoPropagation> result = new java.util.LinkedHashMap<>();
            root.properties().forEach(entry -> {
                List<RepoPropagation.BumpEdit> bumps = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode bump : entry.getValue().path("bumps")) {
                    bumps.add(new RepoPropagation.BumpEdit(bump.path("group").asText(),
                            bump.path("name").asText(), bump.path("oldVersion").asText(),
                            bump.path("newVersion").asText()));
                }
                com.fasterxml.jackson.databind.JsonNode version = entry.getValue().path("publishVersion");
                RepoPropagation.PublishSpec publish = version.isNull() || version.isMissingNode()
                        ? null : new RepoPropagation.PublishSpec(version.asText(), runDir.resolve("m2"));
                result.put(entry.getKey(), new RepoPropagation(bumps, publish));
            });
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

(If the project's Jackson version lacks `JsonNode.properties()`, use `root.fields()` with an iterator — check what `PlanJsonReader` uses and match it.)

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: implement-time layer batching, run events, stale locks, propagation snapshot"
```

---

### Task 4: Parallel orchestrator

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/Orchestrator.java` (restructure `run` into layered parallel execution + `runRepo`)
- Test: `sdd-cli/src/test/java/sdd/cli/implement/OrchestratorTest.java` (add tests; existing tests unchanged — the constructor keeps its 10 args)

**Interfaces:**
- Produces: behaviorally identical single-repo/sequential semantics, plus: layers from `Scheduler.levels` run on virtual threads (one task per UNIT; multi-member units internally sequential); all `RunState` mutations + `state.json`/`events.jsonl` writes under one private `lock`; first pause wins (`pauseLocked` no-ops when already paused) and every pause now also writes a run-level event line (including the budget pause, previously unlogged); repos already running when a pause lands finish and record outcomes; later layers never start; partial tokens from a caught `ModelException` are added to the budget (`state.addTokens(e.tokensSoFar())`); a rethrown 4xx is captured per-unit into a `fatal` slot, stops scheduling, and is rethrown after the layers drain (lock still released in `finally`); the publisher call acquires `settingsFor.apply(repo).gradlePermits()` when non-null.
- Consumes: Task 3's `Scheduler.levels` + `RunStore.appendRunEvent`; Task 2's `ModelException.tokensSoFar`/`RunnerSettings.gradlePermits`.

- [ ] **Step 1: Write the failing tests.** Append to `OrchestratorTest`:

```java
    private static PlanModel planTwoIndependent(String aBase, String bBase) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("alib", "seed", "SEED", "minor", aBase),
                        new PlanModel.PlanRepo("blib", "seed", "SEED", "minor", bBase)),
                List.of(List.of("alib"), List.of("blib")),
                List.of(),   // no edges: both units land in one layer
                List.of(), List.of());
    }

    @Test
    void independentReposRunConcurrentlyWithinALayer() throws Exception {
        // Each verify stub writes its start marker then waits (max ~8s) for the OTHER repo's
        // marker. Sequential execution can never satisfy the first repo; parallel satisfies both.
        String scriptA = "touch " + ws.resolve("a-started") + "; i=0; "
                + "while [ ! -f " + ws.resolve("b-started") + " ] && [ $i -lt 80 ]; do sleep 0.1; i=$((i+1)); done; "
                + "[ -f " + ws.resolve("b-started") + " ]";
        String scriptB = "touch " + ws.resolve("b-started") + "; i=0; "
                + "while [ ! -f " + ws.resolve("a-started") + " ] && [ $i -lt 80 ]; do sleep 0.1; i=$((i+1)); done; "
                + "[ -f " + ws.resolve("a-started") + " ]";
        FixtureRepo alib = repoWith("alib", scriptA);
        FixtureRepo blib = repoWith("blib", scriptB);
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("alib", step("alib", alib.path()),
                "blib", step("blib", blib.path()));
        // Thread-safe model double: every call is an identical done() — safe under concurrency.
        ChatModel parallelSafe = req -> call("x", "done", "{\"result\":\"success\",\"summary\":\"ok\"}");

        Orchestrator.RunResult result = orchestrator(parallelSafe, parallelSafe)
                .run(runDir, planTwoIndependent(alib.headSha(), blib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("alib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("blib")).isEqualTo(RepoState.SUCCEEDED);
    }

    @Test
    void budgetPauseWritesARunLevelEventLine() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}")));
        Orchestrator tight = new Orchestrator(new RepoStepRunner(db.jdbi()), model, "qwen", model,
                "deepseek", repo -> RunnerSettings.defaults(null),
                new RunStore(InstantSource.fixed(Instant.EPOCH)), 10L, Map.of(), new MavenLocalPublisher());

        Orchestrator.RunResult result = tight.run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(Files.readString(runDir.resolve("events.jsonl")))
                .contains("\"run\":\"pause\"").contains("token budget");
    }

    @Test
    void partialTokensFromAnEndpointFailureCountAgainstTheBudget() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ChatModel dead = req -> {
            throw new ModelException("transport error: refused", new java.io.IOException("x"))
                    .withTokens(12345L);
        };

        Orchestrator.RunResult result = orchestrator(dead, dead)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().tokensSpent()).isEqualTo(12345L);
    }
```

(`withTokens` chains and returns a copy — the lambda throws the copy directly. New import: none beyond existing `ChatModel`/`ModelException` from the 4C-3 tests.)

- [ ] **Step 2: Run — expect RED** (`independentReposRunConcurrentlyWithinALayer` under the sequential walk: alib ends FAILED after ~32s of marker-wait verify cycles across both attempts, then blib SUCCEEDS immediately because alib's marker is already on disk — exit 2, failing the exit-0/both-SUCCEEDED assertions; the other two tests fail on the missing run-event line / token count).
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.OrchestratorTest'`

- [ ] **Step 3: Implement.** Restructure `Orchestrator.run(Path, PlanModel, Map, RunState)` — constructor, `ESCALATE`, `applyBumps`, `attemptDigest`, `summarize`, `slug`, `endpointTrouble` all unchanged; the run body and transition helpers become:

```java
    private final Object lock = new Object();

    /** Resume entry: repos already SUCCEEDED or FAILED in the passed state are not re-run. */
    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps, RunState state) {
        String runId = runDir.getFileName().toString();
        AtomicReference<RuntimeException> fatal = new AtomicReference<>();
        try {
            synchronized (lock) {
                store.writeState(runDir, state);
            }
            for (List<List<String>> layer : Scheduler.levels(plan.order(), plan.edges())) {
                synchronized (lock) {
                    if (state.pausedReason() != null) {
                        break;
                    }
                }
                if (fatal.get() != null) {
                    break;
                }
                List<List<String>> units = layer.stream()
                        .map(unit -> unit.stream().filter(steps::containsKey).toList())
                        .filter(unit -> !unit.isEmpty())
                        .toList();
                if (units.isEmpty()) {
                    continue;
                }
                try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (List<String> unit : units) {
                        pool.submit(() -> {
                            try {
                                for (String repo : unit) {   // cycle units stay internally sequential
                                    if (fatal.get() != null
                                            || !runRepo(runDir, runId, plan, steps, state, repo)) {
                                        break;
                                    }
                                }
                            } catch (RuntimeException e) {
                                fatal.compareAndSet(null, e);   // 4xx config errors et al: stop + rethrow
                            }
                        });
                    }
                }   // ExecutorService.close() waits for every submitted unit to finish
            }
        } finally {
            store.releaseLock(runDir);
        }
        if (fatal.get() != null) {
            throw fatal.get();
        }
        boolean paused = state.pausedReason() != null;
        boolean allSucceeded = state.repos().stream().allMatch(r -> r.state() == RepoState.SUCCEEDED);
        return new RunResult(paused ? 3 : allSucceeded ? 0 : 2, state);
    }

    /** One repo, both attempts. Returns false when the walk must stop (a pause landed). */
    private boolean runRepo(Path runDir, String runId, PlanModel plan, Map<String, RepoStep> steps,
                            RunState state, String repo) {
        synchronized (lock) {
            if (state.pausedReason() != null) {
                return false;
            }
            RepoState already = state.stateOf(repo);
            if (already == RepoState.SUCCEEDED || already == RepoState.FAILED) {
                return true;
            }
            if (state.tokensSpent() >= runTokenBudget) {
                pauseLocked(runDir, state,
                        "run token budget exhausted (" + state.tokensSpent() + " tokens)");
                return false;
            }
            if (Scheduler.blockedByUpstream(repo, plan.edges(), state)) {
                transitionLocked(runDir, state, repo, RepoState.SKIPPED_UPSTREAM_FAILED, null, null,
                        "upstream failed");
                return true;
            }
            transitionLocked(runDir, state, repo, RepoState.IN_PROGRESS, null, null, "");
        }
        RepoStep step = steps.get(repo);
        String branch = "sdd/" + runId + "/" + slug(repo);
        String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
        List<String> events = new ArrayList<>();
        StepOutcome outcome;
        boolean escalated = false;
        try {
            RunGit.startBranch(step.repoRoot(), branch, base);
            applyBumps(repo, step, events);
            outcome = runner.run(step, coder, coderModelName, settingsFor.apply(repo), "");
            events.addAll(outcome.events());
            boolean escalationAllowed;
            synchronized (lock) {
                state.addTokens(outcome.tokens());
                escalationAllowed = state.tokensSpent() < runTokenBudget;
            }
            if (ESCALATE.contains(outcome.result()) && escalationAllowed) {
                escalated = true;
                events.add("attempt 2: hard reset to base, escalating to " + escalationModelName);
                RunGit.startBranch(step.repoRoot(), branch, base);
                applyBumps(repo, step, events);
                StepOutcome second = runner.run(step, escalation, escalationModelName,
                        settingsFor.apply(repo), attemptDigest(outcome));
                events.addAll(second.events());
                synchronized (lock) {
                    state.addTokens(second.tokens());
                }
                outcome = second;
            }
        } catch (ModelException e) {
            synchronized (lock) {
                state.addTokens(e.tokensSoFar());
            }
            store.writeAgentEvents(runDir, repo, events);
            if (endpointTrouble(e)) {
                synchronized (lock) {
                    pauseLocked(runDir, state, "model endpoint unavailable: " + e.getMessage());
                    transitionLocked(runDir, state, repo, RepoState.PAUSED_ENDPOINT, branch, null,
                            e.getMessage());
                }
                return false;
            }
            throw e;   // 4xx configuration errors: captured by the unit task into fatal
        }
        store.writeAgentEvents(runDir, repo, events);
        String attemptTag = escalated ? "attempt 2 (" + escalationModelName + ") " : "";
        if (outcome.result() == StepResult.SUCCESS) {
            String sha = RunGit.commitAll(step.repoRoot(), "sdd: " + runId + " " + repo);
            RepoPropagation prop = propagation.getOrDefault(repo, RepoPropagation.none());
            if (prop.publish() != null) {
                RunnerSettings settings = settingsFor.apply(repo);
                java.util.concurrent.Semaphore permits = settings.gradlePermits();
                if (permits != null) {
                    permits.acquireUninterruptibly();
                }
                MavenLocalPublisher.Result published;
                try {
                    published = publisher.publish(step.repoRoot(), settings.javaHome(),
                            prop.publish().version(), prop.publish().m2Dir());
                } finally {
                    if (permits != null) {
                        permits.release();
                    }
                }
                events.add("publish " + prop.publish().version() + ": " + summarize(published.log()));
                store.writeAgentEvents(runDir, repo, events);
                if (!published.ok()) {
                    if (InfraClassifier.isInfra(published.log())) {
                        synchronized (lock) {
                            pauseLocked(runDir, state, "infrastructure failure publishing " + repo
                                    + " — fix the environment and resume");
                            transitionLocked(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                                    attemptTag + "publish: " + summarize(published.log()));
                        }
                        return false;
                    }
                    synchronized (lock) {
                        transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                                attemptTag + "publish failed: " + summarize(published.log()));
                    }
                    return true;
                }
            }
            synchronized (lock) {
                transitionLocked(runDir, state, repo, RepoState.SUCCEEDED, branch, sha,
                        attemptTag + outcome.summary());
            }
        } else if (outcome.result() == StepResult.INFRA) {
            synchronized (lock) {
                pauseLocked(runDir, state, "infrastructure failure in " + repo
                        + " — fix the environment and resume");
                transitionLocked(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                        attemptTag + outcome.summary());
            }
            return false;
        } else {
            synchronized (lock) {
                transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                        attemptTag + outcome.result() + ": " + outcome.summary());
            }
        }
        return true;
    }

    /** Caller must hold lock. First pause wins; every pause is also a run-level event line. */
    private void pauseLocked(Path runDir, RunState state, String reason) {
        if (state.pausedReason() == null) {
            state.pause(reason);
            store.appendRunEvent(runDir, reason);
            store.writeState(runDir, state);
        }
    }

    /** Caller must hold lock. */
    private void transitionLocked(Path runDir, RunState state, String repo, RepoState to,
                                  String branch, String sha, String detail) {
        RepoState from = state.stateOf(repo);
        state.set(repo, to, branch, sha, detail);
        store.appendEvent(runDir, repo, from, to, detail);
        store.writeState(runDir, state);
    }
```

New imports: `java.util.concurrent.ExecutorService`, `java.util.concurrent.Executors`, `java.util.concurrent.atomic.AtomicReference`. Class javadoc: replace "Concurrency and M8 staleness recovery are 4C-3b" with "Repos run parallel-within-layer on virtual threads (M8 staleness recovery is 4C-3c); all shared state is guarded by a single lock, and the first pause wins."

- [ ] **Step 4: Run — expect PASS, all pre-existing OrchestratorTest tests included** (their plans all carry edges, so their layers are singletons and behavior is unchanged; the cascade/budget/pause assertions hold).
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: parallel-within-layer orchestration with locked state and first-pause-wins"
```

---

### Task 5: ImplementCommand wiring — config, throttles, M7, --wait-endpoint, snapshot, warnings

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/PlannedVersions.java` (expose `current`)
- Test: `sdd-cli/src/test/java/sdd/cli/ImplementCommandWaitEndpointTest.java` (create), `sdd-cli/src/test/java/sdd/cli/ImplementCommandMavenLocalTest.java` (add snapshot + warning assertions), `sdd-cli/src/test/java/sdd/cli/ImplementCommandTest.java` (add M7 test)

**Interfaces:**
- Produces: `PlannedVersions.current(Jdbi, String repo)` (public; exposes the existing private `rootVersion`). `ImplementCommand`: (1) `RUN_TOKEN_BUDGET` constant deleted — budget from `config.run().tokenBudget()`; (2) one `Semaphore gradlePermits = new Semaphore(config.run().gradleWorkers())` and one `Semaphore modelPermits = new Semaphore(config.run().modelConcurrency())` per invocation; coder AND escalation each wrapped in `new ThrottledChatModel(model, modelPermits)` AFTER the test-seam fallback resolution; (3) `settingsFor` computes effective verification tasks: `activePlan.step(repo).map(PlanModel.PlanStep::verification).filter(v -> !v.isEmpty()).orElse(List.of("check"))` minus `config.verificationExclusions().getOrDefault(repo, List.of())`, and returns `RunnerSettings.custom(javaHome, extraArgs, effectiveTasks, gradlePermits)`; when the subtraction empties the list, print `"warn: <repo>: all verification tasks excluded by sdd.yml — will be marked not locally verified"` once at settings-build time is NOT possible (lambda) — instead print it during the planning phase (see step 3 edit 5); (4) `--wait-endpoint` flag: when the run exits 3 with a `pausedReason` containing `"endpoint"`, poll both coder and planner endpoints via a probe seam until both answer, then re-enter the run in resume mode in-process, looping until a non-endpoint exit; (5) fresh runs write `store.writePropagation(runDir, propagation)` right after `store.create`; the resume branch uses `store.readPropagation(runDir)` when the snapshot exists (skipping live re-planning entirely) and falls back to live planning only for pre-snapshot run dirs; `PropagationPlanner`'s javadoc "Known limitation" sentence is replaced with "--resume reads the frozen snapshot from the run dir (propagation.json); live recomputation happens only for pre-4C-3b run dirs."; (6) after fresh planning, for every repo whose `PublishSpec.version()` equals `PlannedVersions.current(jdbi, repo)`, print `"warn: <repo> republishes its current version <v> — consumers may resolve a stale released artifact"`; (7) the budget-pause hint becomes `"raise run.token_budget in sdd.yml, then: sdd implement --resume <plan>"`. Test seams: `Function<ModelEndpoint, EndpointProbe.ProbeResult> probeForTest` (null → `EndpointProbe::probe`) and `long waitPollMillis = 30_000` (tests set 1).
- Consumes: Tasks 1-4 (`RunSettings`, `ThrottledChatModel`, `RunnerSettings.custom`, `Scheduler.levels`-driven orchestrator, `writePropagation`/`readPropagation`, `appendRunEvent`).

- [ ] **Step 1: Write the failing tests.** `ImplementCommandWaitEndpointTest.java` — fixture copied verbatim from `ImplementCommandResumeTest`'s helpers (2 repos, `exit 0` stubs, NONE-mechanism edge), with:

```java
    @Test
    void waitEndpointRecoversInProcessOnceTheEndpointAnswers() throws Exception {
        // ... fixture setup verbatim from ImplementCommandResumeTest ...
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = req -> {
            if (calls.incrementAndGet() == 1) {
                throw new ModelException("transport error: refused", new java.io.IOException("x"));
            }
            return done();
        };
        cmd.probeForTest = endpoint -> new EndpointProbe.ProbeResult(true, "HTTP 200");
        cmd.waitPollMillis = 1;
        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));

        int exit = cli.execute("--workspace", ws.toString(), "--wait-endpoint",
                ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);   // paused once, waited, auto-resumed to completion
        assertThat(out.toString()).contains("waiting for model endpoints")
                .contains("lib: SUCCEEDED").contains("svc: SUCCEEDED");
    }

    @Test
    void withoutTheFlagAnEndpointPauseStillExitsThree() throws Exception {
        // ... same fixture; coder always throws transport errors; no --wait-endpoint ...
        assertThat(exit).isEqualTo(3);
    }
```

Append to `ImplementCommandTest` (reuse its happy-path fixture; the spec id there is SPEC-101):

```java
    @Test
    void sddYmlVerificationExclusionsSkipTheGateAndSurfaceNotLocallyVerified() throws Exception {
        // Same fixture as runsASingleRepoPlanToCompletion, with TWO deltas:
        // 1. sdd.yml gains:  verification_exclusions:\n  lib: [check]
        // 2. the stub gradlew is "exit 1" — the run can only succeed if the gate is skipped.
        // ... assemble and execute ...
        assertThat(exit).isEqualTo(0);
        assertThat(runDirEvents).contains("not locally verified");   // lib/agent-events.jsonl content
    }
```

Append to `ImplementCommandMavenLocalTest` (inside/alongside the existing e2e — read the file and reuse its fixture):

```java
    @Test
    void freshRunsSnapshotThePropagationPlanAndWarnOnSameVersionRepublish() throws Exception {
        // Fixture identical to mavenLocalFallbackPublishesBumpsAndInjectsTheInitScript, except
        // lib's version_action is "none" (planned == current == 1.2.3, and svc's pin needs no bump).
        // ... execute ...
        assertThat(exit).isEqualTo(0);
        assertThat(ws.resolve(".sdd/runs/SPEC-9-v1/propagation.json")).exists();
        assertThat(out.toString()).contains("warn: lib republishes its current version 1.2.3");
    }
```

(Write all fixtures out fully in the test files — every elided line is a verbatim copy of the named existing test with the stated deltas.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.ImplementCommandWaitEndpointTest' --tests 'sdd.cli.ImplementCommandTest' --tests 'sdd.cli.ImplementCommandMavenLocalTest'`

- [ ] **Step 3: Implement.** `PlannedVersions` — rename the private `rootVersion` to a public accessor (keeping a private alias if other internal callers exist):

```java
    /** The repo's current KB root-module version, or null when unindexed. */
    public static String current(Jdbi jdbi, String repo) {
        // body of the existing private rootVersion(jdbi, repo)
    }
```

(`compute` calls `current` now.)

`ImplementCommand` — seven edits:

1. Fields:

```java
    @Option(names = "--wait-endpoint",
            description = "After an endpoint pause, poll the model endpoints and auto-resume when they answer")
    boolean waitEndpoint;

    Function<ModelEndpoint, EndpointProbe.ProbeResult> probeForTest;   // test seam; null = real probe
    long waitPollMillis = 30_000;

    private String lastPausedReason;
    private SddConfig lastConfig;
```

(delete `RUN_TOKEN_BUDGET`.)

2. `call()` becomes a loop delegating to the existing body, renamed `runPlan`:

```java
    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        while (true) {
            Integer exit = runPlan(out, err);
            if (exit == 3 && waitEndpoint && lastPausedReason != null
                    && lastPausedReason.contains("endpoint")) {
                waitForEndpoints(out);
                resume = true;   // state.json now exists; re-enter through the snapshot path
                continue;
            }
            return exit;
        }
    }

    private void waitForEndpoints(PrintWriter out) {
        out.println("waiting for model endpoints to answer (--wait-endpoint)...");
        Function<ModelEndpoint, EndpointProbe.ProbeResult> probe =
                probeForTest != null ? probeForTest : EndpointProbe::probe;
        List<ModelEndpoint> endpoints = List.of(lastConfig.models().get("coder"),
                lastConfig.models().get("planner"));
        while (true) {
            boolean allUp = endpoints.stream().allMatch(endpoint -> probe.apply(endpoint).ok());
            if (allUp) {
                out.println("endpoints answering — resuming");
                return;
            }
            try {
                Thread.sleep(waitPollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
```

(`runPlan(PrintWriter out, PrintWriter err)` is the old `call()` body verbatim, minus the writer initialization; it sets `lastConfig = config` right after `ConfigLoader.load`, and sets `lastPausedReason = result.state().pausedReason()` where the exit-3 block prints.)

3. Budget + throttles (in `runPlan`, at the model-construction block):

```java
                Semaphore gradlePermits = new Semaphore(config.run().gradleWorkers());
                Semaphore modelPermits = new Semaphore(config.run().modelConcurrency());
                ChatModel coder = coderForTest != null ? coderForTest : new HttpChatModel(coderEndpoint);
                ChatModel escalation = escalationForTest != null ? escalationForTest
                        : coderForTest != null ? coderForTest : new HttpChatModel(plannerEndpoint);
                coder = new ThrottledChatModel(coder, modelPermits);
                escalation = new ThrottledChatModel(escalation, modelPermits);
```

and the orchestrator construction passes `config.run().tokenBudget()` where `RUN_TOKEN_BUDGET` was.

4. `settingsFor` — effective verification tasks + permits:

```java
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = activeSteps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    List<String> extraArgs = new ArrayList<>(Propagation.includeBuildArgs(
                            repo, activePlan.edges(), paths));
                    extraArgs.addAll(Propagation.mavenLocalArgs(
                            activePlan.edges(), MavenLocalInit.scriptPath(activeRunDir)));
                    List<String> tasks = new ArrayList<>(activePlan.step(repo)
                            .map(PlanModel.PlanStep::verification)
                            .filter(v -> !v.isEmpty())
                            .orElse(List.of("check")));
                    tasks.removeAll(config.verificationExclusions().getOrDefault(repo, List.of()));
                    return RunnerSettings.custom(javaHome, extraArgs, tasks, gradlePermits);
                };
```

5. Exclusion warning at planning time (both branches, after `steps` is resolved — a plain loop, not in the lambda):

```java
                for (String repo : steps.keySet()) {
                    List<String> planned = plan.step(repo).map(PlanModel.PlanStep::verification)
                            .filter(v -> !v.isEmpty()).orElse(List.of("check"));
                    List<String> excluded = config.verificationExclusions().getOrDefault(repo, List.of());
                    if (!excluded.isEmpty() && excluded.containsAll(planned)) {
                        out.println("warn: " + repo + ": all verification tasks excluded by sdd.yml — "
                                + "will be marked not locally verified");
                    }
                }
```

6. Snapshot + warning: fresh branch, after `runDir = store.create(...)`:

```java
                    store.writePropagation(runDir, propagation);
                    for (Map.Entry<String, RepoPropagation> entry : propagation.entrySet()) {
                        RepoPropagation.PublishSpec publish = entry.getValue().publish();
                        if (publish != null
                                && publish.version().equals(PlannedVersions.current(jdbi, entry.getKey()))) {
                            out.println("warn: " + entry.getKey() + " republishes its current version "
                                    + publish.version() + " — consumers may resolve a stale released artifact");
                        }
                    }
```

Resume branch — replace the live planning block with:

```java
                    Map<String, RepoPropagation> snapshot = store.readPropagation(runDir);
                    if (snapshot != null) {
                        propagation = snapshot;
                    } else {
                        // pre-4C-3b run dir: fall back to live planning (KB drift caveat applies)
                        List<String> propagationProblems = new ArrayList<>();
                        propagation = PropagationPlanner.plan(jdbi, plan, runDir,
                                PlannedVersions.compute(jdbi, plan), propagationProblems);
                        if (!propagationProblems.isEmpty()) {
                            propagationProblems.forEach(p -> err.println("problem: " + p));
                            return 4;
                        }
                    }
```

and update `PropagationPlanner`'s javadoc sentence as specified in Interfaces.

7. The budget-pause hint:

```java
                    if (result.state().pausedReason().contains("token budget")) {
                        out.println("raise run.token_budget in sdd.yml, then: sdd implement --resume "
                                + planJsonPath);
                    } else {
                        out.println("resume with: sdd implement --resume " + planJsonPath);
                    }
```

- [ ] **Step 4: Run — expect PASS, then the full suite** (all existing ImplementCommand* tests ride the default config — `run:` absent → defaults 2/2/30M, exclusions empty → behavior unchanged; the ThrottledChatModel wrap is transparent to ScriptedChatModel).
Run: `./gradlew :sdd-cli:test && ./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: config-driven throttles and budget, M7 exclusions, --wait-endpoint, propagation snapshot"
```

---

### Task 6: Polish + the deferred interaction tests

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/VersionBump.java` (anchored replace)
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/MavenLocalInit.java` (Groovy escaping)
- Test: `sdd-cli/src/test/java/sdd/cli/implement/VersionBumpTest.java` (add), `sdd-cli/src/test/java/sdd/cli/implement/MavenLocalInitTest.java` (add), `sdd-cli/src/test/java/sdd/cli/implement/OrchestratorTest.java` (add 3), `sdd-cli/src/test/java/sdd/cli/ImplementCommandTest.java` (add 1), `sdd-cli/src/test/java/sdd/cli/ImplementCommandMixedMechanismTest.java` (create)

**Interfaces:**
- Produces: `VersionBump`'s direct-declaration path anchors the match — `g:n:old` is NOT rewritten when followed by a digit or dot (`1.2.3` never matches inside `1.2.30`); `MavenLocalInit.write` escapes `\` and `'` (in that order) in the interpolated path. Five new tests covering the 4C-3 review's deferred interaction list plus the mixed-mechanism e2e.
- Consumes: everything landed in Tasks 1-5.

- [ ] **Step 1: Write the failing polish tests.** Append to `VersionBumpTest`:

```java
    @Test
    void aLongerVersionSharingThePrefixIsNotCorrupted() throws Exception {
        Files.writeString(repo.resolve("build.gradle"), """
                dependencies {
                    implementation "com.acme:lib:1.2.3"
                    implementation "com.acme:lib:1.2.30"
                }
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        String content = Files.readString(repo.resolve("build.gradle"));
        assertThat(content).contains("com.acme:lib:1.3.0");
        assertThat(content).contains("com.acme:lib:1.2.30");   // untouched
        assertThat(content).doesNotContain("1.3.00");
    }
```

Append to `MavenLocalInitTest`:

```java
    @Test
    void escapesQuotesInTheWorkspacePath() throws Exception {
        Path runDir = Files.createDirectories(ws.resolve("o'brien"));

        Path script = MavenLocalInit.write(runDir);

        String content = Files.readString(script);
        assertThat(content).contains("o\\'brien");
        assertThat(content).doesNotContain("uri('" + runDir.resolve("m2").toAbsolutePath() + "')");
    }
```

- [ ] **Step 2: Implement the two fixes.** `VersionBump.apply`'s non-catalog branch becomes a regex replace:

```java
                String updated;
                if (file.getFileName().toString().equals("libs.versions.toml")) {
                    updated = bumpCatalog(content, coordinate, oldVersion, newVersion);
                } else {
                    updated = content.replaceAll(
                            java.util.regex.Pattern.quote(coordinate + ":" + oldVersion) + "(?![\\d.])",
                            java.util.regex.Matcher.quoteReplacement(coordinate + ":" + newVersion));
                }
```

`MavenLocalInit.write`: build the path string first, escape, then format:

```java
        String m2 = runDir.resolve("m2").toAbsolutePath().toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'");
```

and use `%s` with `m2`. Run both test classes green.

- [ ] **Step 3: Write the four deferred interaction tests + mixed e2e (TDD applies to any behavior they flush out — they are EXPECTED to pass against Tasks 1-5's code; a failure is a real finding to fix, not to paper over).** Append to `OrchestratorTest`:

```java
    @Test
    void anInfraFailureOnTheEscalatedAttemptStillPausesTheRun() throws Exception {
        // Attempt 1: two verify-fail cycles (plain exit 1 while the marker is absent) -> VERIFY_FAILED.
        // Attempt 2: the escalation writes the marker; with it present the stub emits an
        // infra-classified failure — twice (retry-once) -> StepResult.INFRA -> PAUSED_INFRA.
        FixtureRepo lib = repoWith("lib",
                "if grep -q escalated A.java 2>/dev/null; then echo 'Could not resolve com.acme:x'; exit 1; else exit 1; fi");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ScriptedChatModel coderScript = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalationScript = new ScriptedChatModel(List.of(
                call("3", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int escalated; }\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"escalated\"}")));

        Orchestrator.RunResult result = orchestrator(coderScript, escalationScript)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_INFRA);
    }

    @Test
    void escalationIsDeniedWhenTheBudgetIsAlreadySpent() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 1");   // verify always fails
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ScriptedChatModel coderScript = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalationScript = new ScriptedChatModel(List.of());   // must never be consumed
        Orchestrator tight = new Orchestrator(new RepoStepRunner(db.jdbi()), coderScript, "qwen",
                escalationScript, "deepseek", repo -> RunnerSettings.defaults(null),
                new RunStore(InstantSource.fixed(Instant.EPOCH)), 20L, Map.of(), new MavenLocalPublisher());
        // attempt 1 spends 2 calls x 15 tokens = 30 > 20: the escalation gate must refuse.

        Orchestrator.RunResult result = tight.run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);   // no attempt 2
        assertThat(escalationScript.requests()).isEmpty();
        // Single-repo plan: no later start-gate fires after the FAILED transition, so the run ends
        // PARTIAL — the denied escalation, not a pause, is what this test pins.
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void aResumedWalkReskipsDownstreamOfAPersistedFailure() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        RunState persisted = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.FAILED, "sdd/S-v1/lib", null, "VERIFY_FAILED: x"),
                new RepoRun("svc", RepoState.PENDING, null, null, "")), null, 0L);
        ScriptedChatModel model = new ScriptedChatModel(List.of());   // nothing may run

        Orchestrator.RunResult result = orchestrator(model)
                .run(runDir, plan(lib.headSha(), svc.headSha()), steps, persisted);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
        assertThat(model.requests()).isEmpty();
    }
```

Append to `ImplementCommandTest`:

```java
    @Test
    void aFourHundredFromTheModelAbortsWithExitFourAndReleasesTheLock() throws Exception {
        // Same fixture as runsASingleRepoPlanToCompletion, but the coder throws a 400.
        // ... fixture ...
        cmd.coderForTest = req -> {
            throw new ModelException("HTTP 400: bad request", 400);
        };
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(4);
        assertThat(ws.resolve(".sdd/runs/SPEC-101-v1/lock")).doesNotExist();   // finally released it
    }
```

`ImplementCommandMixedMechanismTest.java` — 3 repos, both mechanisms on one consumer (fixture scaffolding copied from `ImplementCommandMavenLocalTest`, KB extended): repos `lib` (INCLUDE_BUILD provider, stub `exit 0`), `legacy` (MAVEN_LOCAL provider, stub accepting `publishToMavenLocal` → `echo "$*" > publish-args; exit 0`, else `exit 0`; KB root-module version `2.0.0`, `version_action: "patch"`), `svc` (consumer; `build.gradle` pinning `com.acme:legacy:2.0.0`; stub `case "$*" in *--include-build*--init-script*|*--init-script*--include-build*) exit 0 ;; *) exit 1 ;; esac` — verify passes only when BOTH flags arrive). Edges: `svc→lib` INCLUDE_BUILD, `svc→legacy` MAVEN_LOCAL. KB: repo rows for all three; module rows for `legacy` (version 2.0.0) and `svc`; one `dep_edge` svc-module → legacy-module (declared `2.0.0`, DIRECT, PINNED, internal). Plan order `[[lib],[legacy],[svc]]`. **CRITICAL — thread-safe model double required:** `lib` and `legacy` share no edge, so `Scheduler.levels` batches them into ONE layer and they run CONCURRENTLY — a shared `ScriptedChatModel` (unsynchronized ArrayDeque) would race. Use a stateless lambda instead: `cmd.coderForTest = req -> done();` (every call returns a fresh done — order-independent, thread-safe). Assertions: exit 0, all three SUCCEEDED, `legacy`'s `publish-args` contains `-Pversion=2.0.1`, `svc`'s `build.gradle` contains `com.acme:legacy:2.0.1`, and svc's stub having passed proves both flags composed on one invocation.

- [ ] **Step 4: Run everything.**
Run: `./gradlew :sdd-cli:test && ./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: bump anchoring, init-script escaping, mixed-mechanism e2e, interaction tests"
```

---

## Verification

1. `./gradlew build` — all modules green; `GradleToolTest.disallowedTaskNeverRuns` untouched.
2. Concurrency proven subprocess-real: the two-repo barrier test can only pass under parallel execution; the 1-permit `GradleToolThrottleTest` proves serialization by timestamps; `ThrottledChatModelTest` proves permit hygiene including the throwing path.
3. M7 proven end-to-end: exclusions in sdd.yml skip a failing gate and surface "not locally verified" via events; plan-step verification lists finally drive the gate (multi-task test).
4. Resilience carry-overs each pinned: partial tokens (`tokensSpent == 12345`), run-level budget pause line, stale-lock reclamation vs live-lock refusal, propagation snapshot round-trip + resume-reads-snapshot, `--wait-endpoint` in-process recovery, 4xx → exit 4 with lock released, escalation-denied-by-budget, attempt-2 INFRA, resume re-skip, mixed-mechanism flag composition.
5. Real-estate smoke remains blocked on served-model availability; everything here is fixture-proven.

## Self-Review (completed at write time)

1. **Spec coverage:** design line 60 (virtual-thread workers, separate gradle_workers/model_concurrency semaphores) → Tasks 2+4+5; line 59 (30M budget, now configurable) → Tasks 1+5; line 63 M7 (plan-step verification tasks, sdd.yml exclusions, "not locally verified") → Tasks 2+5; line 71 (--wait-endpoint) → Task 5; 4C-3 final-review queue (partial tokens, run-level pause event, lock staleness, 4 interaction tests) → Tasks 2/3/4/6; 4C-2b queue (propagation snapshot, substring guard, URI escaping, publish==current warning, mixed e2e) → Tasks 3/5/6. Deferred to 4C-3c: M8, transcript/edits.jsonl, agent-events-across-resume, publish-only resume — listed in Global Constraints with reasons.
2. **Placeholder scan:** deliberate verbatim-copy ellipses exist only in Task 5/6 e2e fixtures, each pinned to a named existing test file with exhaustive deltas.
3. **Type consistency:** `RunSettings` (T1) consumed by T5; `RunnerSettings.custom(Path, List, List, Semaphore)` (T2) consumed by T5's settingsFor and T2's own tests; `GradleTool` 5-arg (T2) consumed by `RepoStepRunner` (T2); `ModelException.withTokens/tokensSoFar` (T2) consumed by T4; `Scheduler.levels(List<List<String>>, List<PlanEdge>)` (T3) consumed by T4; `appendRunEvent(Path, String)` (T3) consumed by T4's `pauseLocked`; `writePropagation/readPropagation` (T3) consumed by T5; `ThrottledChatModel(ChatModel, Semaphore)` (T2) consumed by T5; `PlannedVersions.current(Jdbi, String)` (T5) self-contained. Orchestrator stays 10-arg throughout.
4. **Judgment calls for reviewers:** ratified list (a)-(g) in Global Constraints; the barrier-test timing window (8s) trades a slow failure mode for a deterministic parallel proof; `--wait-endpoint` polls forever by design (user interrupt is the escape); stale-lock takeover is silent (documented in the method javadoc).
