# Phase 4C-3: Resilience — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdd implement` survives the real world: a failed repo gets a second attempt on the escalation model from a hard-reset tree, infrastructure flakes pause instead of failing, a dead model endpoint pauses instead of crashing, a run-wide token budget pauses instead of burning, and `--resume` continues a paused/crashed run from its checkpoints.

**Architecture:** Resilience lives at two layers. In `sdd-agent`: the verify gate classifies INFRA failures from the raw Gradle log (retry-once, then a new `StepResult.INFRA`), `StepOutcome` gains token accounting, and `RepoStepRunner.run` accepts a prior-attempt digest. In `sdd-cli`: `Orchestrator` wraps the per-repo call in a bounded 2-attempt loop (attempt 2 = hard reset to base + digest + DeepSeek escalation), catches `ModelException` into `PAUSED_ENDPOINT`, routes `INFRA` to `PAUSED_INFRA`, enforces a run-wide token budget, and returns exit 3 when paused; `--resume` reconciles persisted state against branch-HEAD checkpoints and re-enters the same walk.

**Tech Stack:** Java 21, JGit, Jackson (tree mode), picocli, JUnit 5 + AssertJ, ScriptedChatModel/FixtureRepo test fixtures, stub `gradlew` shell scripts.

## Global Constraints

- **Scope = resilience core only.** Explicitly DEFERRED to **4C-3b**: virtual-thread concurrency + `gradle_workers`/`model_concurrency` semaphores (Scheduler keeps flattening levels), full M8 staleness recovery (pre-flight still hard-fails on drift for fresh runs), `--wait-endpoint` (EndpointProbe stays unused), per-repo `sdd.yml` verification exclusions (M7), the design's full `transcript.jsonl`/`edits.jsonl` (needs a 4B ContextWindow surface), and any INFRA "scoped skip" alternative (this phase always pauses the run).
- **Attempt taxonomy (ratify at review):** attempt 2 triggers on `VERIFY_FAILED, EXHAUSTED, BUDGET, MALFORMED, WEDGED`. `BLOCKED` does NOT escalate (the agent explicitly requested a human decision — escalating burns tokens to re-ask the same question); `INFRA` does NOT escalate (the machine is broken, not the model). Max 2 attempts, per design line 59.
- **Endpoint trouble** = `ModelException.statusCode()` of `0` (transport), `429`, or `>= 500` — all three already survived HttpChatModel's 6 internal retries. Any other code (400/401/404…) is a configuration bug: rethrow → exit 4.
- **Exit taxonomy completes:** `0 = COMPLETE`, `2 = PARTIAL`, `3 = PAUSED` (new), `4 = ABORTED` (+ picocli usage errors via `exitCodeOnInvalidInput = 4`).
- **Run-wide token budget:** 30,000,000 tokens (design line 59), checked at the top of each repo's turn and before escalation; exhaustion pauses (exit 3), never aborts. Constant in `ImplementCommand`; sdd.yml configurability is 4C-3b. Because `tokensSpent` persists across `--resume`, a budget-paused run re-pauses immediately on resume — the CLI's pause message says so instead of offering a dead-end `--resume` hint.
- **Escalation model = the `planner` sdd.yml entry** (DeepSeek per design line 26 "attempt-2 escalation"); no new config keys.
- **plan.json edge direction:** `from_repo` = consumer, `to_repo` = provider (matches `Scheduler.upstreams`).
- **Module boundary:** `sdd-agent` must not depend on `sdd-cli`. INFRA classification therefore lives in `sdd-agent` (it needs the raw pre-compaction log, which never leaves `VerificationRunner`).
- **Zero-test-breaking** for suites not explicitly updated by a task. Constructor-arity changes (`StepOutcome`, `Verdict`, `Orchestrator`) are confined to files this plan edits; each task lists the call sites it must fix. If an implementer finds an unlisted broken call site, fix it mechanically (add the new argument's neutral value) and note it in the report.
- **PAUSED semantics:** a paused run stops the walk immediately (`break`), leaves remaining repos `PENDING`, releases the lock (the run is not in progress), persists `pausedReason` in state.json, and prints a `--resume` hint. `Scheduler.blockedByUpstream` needs no PAUSED handling: a pause always ends the walk in the same iteration, and `--resume` resets PAUSED repos to PENDING before re-entering.
- Commit messages: conventional commits, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Context (verified against the code, 2026-08-13)

- `Orchestrator.run()`'s per-repo loop calls `runner.run(...)` exactly once; `RunGit.startBranch` already hard-resets an existing branch to base (reuse for attempt 2) but does NOT remove untracked files an agent created — attempt 2 and `--resume` need a clean step or attempt-1 debris leaks in.
- `ModelException` propagates uncaught `HttpChatModel → AgentLoop → RepoStepRunner → Orchestrator` — today an endpoint outage crashes `sdd implement` to exit 4.
- `VerificationRunner.verify` feeds `gradle.runFull(task)` (raw, ≤200k chars) straight into `OutputCompactor.compact` and discards the raw text; `Verdict.output()` is compacted (≤6000 chars). INFRA patterns must be matched on the raw string inside `verify`.
- `AgentOutcome.tokens()` is summed per `loop.run()` but discarded by `RepoStepRunner`; `StepOutcome` has no token field; nothing accumulates run-wide.
- `RunStore.writeAgentEvents` OVERWRITES `agent-events.jsonl` (no append) — the orchestrator must accumulate events across attempts and write once per repo.
- `state.json` shape today: `{"runId": ..., "repos": [{repo,state,branch,checkpointSha,detail}]}` — no pausedReason/tokensSpent; `RunStore` has no reader.
- `ScriptedChatModel` cannot throw mid-script; `ChatModel` is a functional interface, so a throwing lambda is the fault-injection double.
- `ConfigLoader` already requires both `models.planner` and `models.coder`; `new HttpChatModel(config.models().get("planner"))` needs zero new config code.

---

### Task 1: RepoStepRunner surface — token accounting + prior-attempt digest

**Files:**
- Modify: `sdd-agent/src/main/java/sdd/agent/run/StepOutcome.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/run/RepoStepRunner.java`
- Test: `sdd-agent/src/test/java/sdd/agent/run/RepoStepRunnerTest.java` (add tests; existing tests unchanged)

**Interfaces:**
- Produces: `StepOutcome(StepResult result, String summary, List<String> events, String verificationOutput, long tokens)` — `tokens` = prompt+completion tokens summed across every internal `loop.run()` of this attempt. `RepoStepRunner.run(RepoStep, ChatModel, String, RunnerSettings, String priorDigest)` — 5-arg overload; a non-blank `priorDigest` is appended to the initial work order (verify-retry / context-restart rebuilds do NOT re-append it: their own failure context supersedes it). The existing 4-arg `run` delegates with `""`.
- Consumes: `AgentOutcome.tokens()` (existing, currently discarded).

- [ ] **Step 1: Write the failing tests** (append to `RepoStepRunnerTest`; the class's existing helpers `gradlew(...)`, `call(...)`, `step(...)` are reused — do not duplicate them):

```java
    @Test
    void accumulatesTokensAcrossTheAttempt() throws Exception {
        gradlew("exit 0");
        // Each scripted response carries Usage(10, 5) = 15 tokens; 2 calls = 30.
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"added x\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.tokens()).isEqualTo(30L);
    }

    @Test
    void appendsThePriorDigestToTheInitialWorkOrder() throws Exception {
        gradlew("exit 0");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        new RepoStepRunner(db.jdbi()).run(step(repoRoot), model, "qwen",
                RunnerSettings.defaults(null), "\n\n## PRIOR ATTEMPT DIGEST\nattempt 1 sank");

        assertThat(model.requests().get(0).messages())
                .anyMatch(m -> m.content() != null && m.content().contains("## PRIOR ATTEMPT DIGEST"));
    }
```

- [ ] **Step 2: Run — expect COMPILE FAILURE** (`tokens()` and the 5-arg `run` don't exist).
Run: `./gradlew :sdd-agent:test --tests 'sdd.agent.run.RepoStepRunnerTest'`

- [ ] **Step 3: Implement.** `StepOutcome.java` becomes:

```java
package sdd.agent.run;

import java.util.List;

/** The terminal state of one repo-step attempt. verificationOutput is the compacted gate result (or "");
 *  tokens is the prompt+completion total across every model call of the attempt. */
public record StepOutcome(StepResult result, String summary, List<String> events,
                          String verificationOutput, long tokens) {
    public StepOutcome {
        events = List.copyOf(events);
    }
}
```

In `RepoStepRunner.java`: replace the 4-arg `run` with a delegating overload plus the real 5-arg method, accumulate tokens, and thread them through the `outcome` helper. Exact new/changed members (the `while` body's switch cases keep their existing logic — only the lines shown change):

```java
    public StepOutcome run(RepoStep step, ChatModel model, String modelName, RunnerSettings settings) {
        return run(step, model, modelName, settings, "");
    }

    public StepOutcome run(RepoStep step, ChatModel model, String modelName, RunnerSettings settings,
                           String priorDigest) {
```

Inside the 5-arg method: initial work order becomes `String workOrder = WorkOrder.build(jdbi, step) + (priorDigest.isBlank() ? "" : priorDigest);`; declare `long tokens = 0;` next to `int verifyCycles = 0;`; immediately after `events.addAll(outcome.events());` add `tokens += outcome.tokens();`; change the private helper and every call site to pass `tokens`:

```java
    private static StepOutcome outcome(StepResult result, String summary, List<String> events,
                                       String verification, long tokens) {
        return new StepOutcome(result, summary, events, verification, tokens);
    }
```

(Seven `outcome(...)` call sites in the switch gain the `tokens` argument. `grep -rn "new StepOutcome(" sdd-*/src` and fix any direct construction — none is expected outside this file.)

- [ ] **Step 4: Run — expect PASS**, including all pre-existing RepoStepRunner tests.
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-agent/src
git commit -m "feat: step outcomes carry token totals and accept a prior-attempt digest"
```

---

### Task 2: INFRA classification at the verify gate

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/run/InfraClassifier.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/run/VerificationRunner.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/run/StepResult.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/run/RepoStepRunner.java` (DONE branch only)
- Test: `sdd-agent/src/test/java/sdd/agent/run/InfraClassifierTest.java` (create), `sdd-agent/src/test/java/sdd/agent/run/RepoStepRunnerTest.java` (add tests)

**Interfaces:**
- Produces: `InfraClassifier.isInfra(String rawGradleLog)` (static, case-insensitive pattern match); `VerificationRunner.Verdict(boolean passed, String output, boolean infra)` — `infra` is true only when the gate failed AND the RAW log matched; `StepResult.INFRA` (new enum value); `RepoStepRunner`: an infra-classified verify failure is retried once without involving the agent — a second infra failure returns `StepResult.INFRA`. Design line 63: "INFRA-classified failures never reach the agent — retry once, then PAUSED_INFRA".
- Consumes: Task 1's `outcome(..., tokens)` helper.

- [ ] **Step 1: Write the failing classifier test** (`InfraClassifierTest.java`):

```java
package sdd.agent.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InfraClassifierTest {
    @Test
    void matchesEachInfraFamily() {
        assertThat(InfraClassifier.isInfra("exit 1\n* What went wrong:\nCould not resolve com.acme:lib:1.0.")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nCould not download guava-33.0.jar")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nCould not GET 'https://repo.maven.apache.org/...'")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\njava.net.UnknownHostException: repo.maven.apache.org")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nConnection refused (Connection refused)")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nConnection reset by peer")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nconnect timed out")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nRead timed out")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nNo route to host")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nGradle build daemon disappeared unexpectedly")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nUnable to start the daemon process.")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nTimeout waiting to lock journal cache")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nCannot connect to the Docker daemon at unix:///var/run/docker.sock")).isTrue();
        assertThat(InfraClassifier.isInfra("exit 1\nNo space left on device")).isTrue();
        assertThat(InfraClassifier.isInfra("timed out after 900s")).isTrue();   // GradleTool's timeout string
    }

    @Test
    void realBuildFailuresAreNotInfra() {
        assertThat(InfraClassifier.isInfra("exit 1\nA.java:3: error: ';' expected")).isFalse();
        assertThat(InfraClassifier.isInfra("exit 1\n> Task :test FAILED\n3 tests completed, 1 failed")).isFalse();
        assertThat(InfraClassifier.isInfra("exit 0\nBUILD SUCCESSFUL")).isFalse();
        assertThat(InfraClassifier.isInfra("")).isFalse();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `InfraClassifier.java`:

```java
package sdd.agent.run;

import java.util.List;
import java.util.Locale;

/**
 * Classifies a RAW (pre-compaction) Gradle log as an infrastructure failure — dependency
 * resolution, network, daemon, Docker, disk, or the GradleTool subprocess timeout — per design
 * line 63. Deliberately matched on the raw log: the compacted output may have dropped the
 * telltale line. Patterns are matched case-insensitively anywhere in the log.
 */
public final class InfraClassifier {
    private static final List<String> PATTERNS = List.of(
            // dependency resolution / repository access
            "could not resolve", "could not download", "could not get", "could not head",
            // network
            "unknownhostexception", "connection refused", "connection reset",
            "connect timed out", "read timed out", "no route to host",
            // gradle daemon
            "gradle build daemon disappeared", "unable to start the daemon process",
            "timeout waiting to lock",
            // docker / disk
            "cannot connect to the docker daemon", "no space left on device");

    private InfraClassifier() {
    }

    public static boolean isInfra(String rawGradleLog) {
        if (rawGradleLog == null || rawGradleLog.isEmpty()) {
            return false;
        }
        if (rawGradleLog.startsWith("timed out after")) {   // GradleTool's process-timeout marker
            return true;
        }
        String lower = rawGradleLog.toLowerCase(Locale.ROOT);
        for (String pattern : PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
```

`VerificationRunner.java` — `Verdict` gains `infra`, `verify` classifies on the raw log:

```java
    public record Verdict(boolean passed, String output, boolean infra) {
    }

    public Verdict verify(String task) {
        String raw = gradle.runFull(task);
        String compacted = compactor.compact(raw, task);
        boolean passed = compacted.startsWith("exit 0");
        return new Verdict(passed, compacted, !passed && InfraClassifier.isInfra(raw));
    }
```

`StepResult.java` becomes:

```java
package sdd.agent.run;

public enum StepResult { SUCCESS, VERIFY_FAILED, BLOCKED, EXHAUSTED, BUDGET, MALFORMED, WEDGED, INFRA }
```

`RepoStepRunner` DONE branch: extract the existing try/catch into a helper and add the infra retry-once. The DONE case becomes exactly:

```java
                case DONE -> {
                    VerificationRunner.Verdict verdict = verifyOnce(verifier, settings.verificationTask());
                    if (!verdict.passed() && verdict.infra()) {
                        events.add("verify: infra-classified failure — retrying once");
                        verdict = verifyOnce(verifier, settings.verificationTask());
                        if (!verdict.passed() && verdict.infra()) {
                            lastVerification = verdict.output();
                            return outcome(StepResult.INFRA, "infrastructure failure at the verify gate",
                                    events, lastVerification, tokens);
                        }
                    }
                    lastVerification = verdict.output();
                    if (verdict.passed()) {
                        return outcome(StepResult.SUCCESS, outcome.summary(), events, lastVerification, tokens);
                    }
                    if (++verifyCycles >= MAX_VERIFY_CYCLES) {
                        return outcome(StepResult.VERIFY_FAILED, "verification failed", events, lastVerification, tokens);
                    }
                    workOrder = WorkOrder.build(jdbi, step)
                            + "\n\n## Verification failed — fix and finish again\n" + verdict.output();
                }
```

with the new private helper (replaces the inline try/catch that built the synthetic Verdict — note the 3-arg Verdict):

```java
    private static VerificationRunner.Verdict verifyOnce(VerificationRunner verifier, String task) {
        try {
            return verifier.verify(task);
        } catch (RuntimeException e) {
            return new VerificationRunner.Verdict(false, "verification error: " + e.getMessage(), false);
        }
    }
```

(`grep -rn "new VerificationRunner.Verdict(\|new Verdict(" sdd-*/src` and add `false` to any other 2-arg construction — `VerificationRunnerTest` likely constructs none, but its assertions on `verify()` results compile unchanged since `passed`/`output` accessors are untouched.)

- [ ] **Step 3: Add the two runner-level tests** (append to `RepoStepRunnerTest`; the stub `gradlew` uses a marker file so the first verify call fails infra and the second passes — proving retry-once recovers WITHOUT consuming a model response):

```java
    @Test
    void transientInfraFailureAtVerifyIsRetriedAndRecovers() throws Exception {
        gradlew("if [ -f infra-done ]; then exit 0; else touch infra-done; echo 'Could not resolve com.acme:lib:1.0'; exit 1; fi");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);   // one done() sufficed: agent never saw the flake
    }

    @Test
    void persistentInfraFailureReturnsInfraWithoutReenteringTheAgent() throws Exception {
        gradlew("echo 'Could not resolve com.acme:lib:1.0'; exit 1");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.INFRA);   // NOT VERIFY_FAILED: the agent got no retry prompt
    }
```

- [ ] **Step 4: Run — expect PASS** (new tests + the full module: pre-existing verify-fail tests must still see `VERIFY_FAILED`, since plain `exit 1` logs match no infra pattern).
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-agent/src
git commit -m "feat: classify infrastructure failures at the verify gate with retry-once"
```

---

### Task 3: Resilient orchestrator — multi-attempt escalation, PAUSED_*, run budget, exit 3

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/Orchestrator.java` (full rewrite below)
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RepoState.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunState.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java` (Snapshot shape only)
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java` (untracked clean in startBranch)
- Modify: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java` (escalation wiring + PAUSED label)
- Test: `sdd-cli/src/test/java/sdd/cli/implement/OrchestratorTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/RunGitTest.java`

**Interfaces:**
- Produces: `Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName, ChatModel escalation, String escalationModelName, Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget)` — the unused `InstantSource clock` parameter is REMOVED. `run(Path, PlanModel, Map<String, RepoStep>)` (fresh) delegates to `run(Path, PlanModel, Map<String, RepoStep>, RunState)` (Task 5's resume entry — repos already `SUCCEEDED`/`FAILED` in the passed state are skipped). `RepoState` gains `PAUSED_INFRA, PAUSED_ENDPOINT`. `RunState` gains `pause(String)/pausedReason()/addTokens(long)/tokensSpent()` and a resume constructor `RunState(String runId, List<RepoRun> repos, String pausedReason, long tokensSpent)`. state.json gains `pausedReason` (null when running) and `tokensSpent`. `RunGit.startBranch` additionally removes untracked files/dirs after positioning the branch.
- Consumes: Task 1's 5-arg `run` + `StepOutcome.tokens()`; Task 2's `StepResult.INFRA`; `ModelException.statusCode()`.

- [ ] **Step 1: Write the failing tests.** In `RunGitTest`, add:

```java
    @Test
    void startBranchRemovesUntrackedFilesFromAPriorAttempt() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");   // NB: RunGitTest's @TempDir field is named tmp
        RunGit.startBranch(repo.path(), "sdd/run/lib", repo.headSha());
        Files.writeString(repo.path().resolve("Debris.java"), "class Debris {}\n");   // agent-created, never committed
        Files.createDirectories(repo.path().resolve("build/tmp"));

        RunGit.startBranch(repo.path(), "sdd/run/lib", repo.headSha());

        assertThat(Files.exists(repo.path().resolve("Debris.java"))).isFalse();
        assertThat(Files.exists(repo.path().resolve("build"))).isFalse();
    }
```

In `OrchestratorTest`: update the `orchestrator(...)` helper for the new constructor, update the existing cascade test (a `VERIFY_FAILED` lib now escalates, so the escalation model needs its own script), and add the new tests. Replace the helper with:

```java
    private Orchestrator orchestrator(ChatModel coder, ChatModel escalation) {
        return new Orchestrator(new RepoStepRunner(db.jdbi()), coder, "qwen", escalation, "deepseek",
                repo -> RunnerSettings.defaults(null), new RunStore(InstantSource.fixed(Instant.EPOCH)),
                30_000_000L);
    }

    private Orchestrator orchestrator(ScriptedChatModel model) {
        return orchestrator(model, model);   // legacy tests: same script serves both attempts
    }
```

Update `failedUpstreamCascadesToDownstreamSkip`: lib's attempt 1 consumes two `done` responses (two verify-fail cycles → `VERIFY_FAILED`), and attempt 2 (escalated) consumes two more before lib is finally `FAILED`; keep the assertions, change the script to four `done` calls:

```java
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"try3\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"try4\"}")));
```

Add the new tests:

```java
    @Test
    void secondAttemptEscalatesFromAHardResetTreeAndSucceeds() throws Exception {
        // Verification passes only when A.java contains the marker the ESCALATION model writes.
        FixtureRepo lib = repoWith("lib", "grep -q escalated A.java && exit 0 || exit 1");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ScriptedChatModel coder = new ScriptedChatModel(List.of(
                call("1", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int attempt1; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalation = new ScriptedChatModel(List.of(
                call("4", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int escalated; }\"}"),
                call("5", "done", "{\"result\":\"success\",\"summary\":\"escalated fix\"}")));

        Orchestrator.RunResult result = orchestrator(coder, escalation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        String a = Files.readString(lib.path().resolve("A.java"));
        assertThat(a).contains("escalated").doesNotContain("attempt1");   // attempt 1's edit was hard-reset away
        assertThat(escalation.requests().get(0).messages())
                .anyMatch(m -> m.content() != null && m.content().contains("previous attempt"));   // digest delivered
    }

    @Test
    void endpointOutagePausesTheRunAtExit3() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ChatModel dead = req -> {
            throw new ModelException("transport error: Connection refused", new java.io.IOException("refused"));
        };

        Orchestrator.RunResult result = orchestrator(dead, dead).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_ENDPOINT);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);   // walk stopped, not cascaded
        assertThat(result.state().pausedReason()).contains("endpoint");
        assertThat(Files.readString(runDir.resolve("state.json"))).contains("PAUSED_ENDPOINT").contains("pausedReason");
    }

    @Test
    void infraFailurePausesTheRunAtExit3() throws Exception {
        FixtureRepo lib = repoWith("lib", "echo 'Could not resolve com.acme:x'; exit 1");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_INFRA);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);
    }

    @Test
    void runTokenBudgetExhaustionPausesBeforeTheNextRepo() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}")));
        Orchestrator tight = new Orchestrator(new RepoStepRunner(db.jdbi()), model, "qwen", model, "deepseek",
                repo -> RunnerSettings.defaults(null), new RunStore(InstantSource.fixed(Instant.EPOCH)),
                10L);   // lib's single call spends 15 tokens > 10

        Orchestrator.RunResult result = tight.run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);
        assertThat(result.state().pausedReason()).contains("token budget");
    }
```

Add the single-repo plan helper next to the existing `plan(...)`:

```java
    private static PlanModel planFor(String repo, String base) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo(repo, "seed", "SEED", "minor", base)),
                List.of(List.of(repo)), List.of(), List.of(), List.of());
    }
```

New imports in `OrchestratorTest`: `sdd.core.llm.ModelException`, `sdd.core.llm.ChatModel`.

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.OrchestratorTest' --tests 'sdd.cli.implement.RunGitTest'`

- [ ] **Step 3: Implement.** `RepoState.java`:

```java
package sdd.cli.implement;

public enum RepoState {
    PENDING, IN_PROGRESS, SUCCEEDED, FAILED, SKIPPED_UPSTREAM_FAILED, PAUSED_INFRA, PAUSED_ENDPOINT
}
```

`RunState.java` — add the pause/token fields and the resume constructor (existing members unchanged):

```java
    private String pausedReason;   // null while the run is live; set exactly once, at the pause site
    private long tokensSpent;

    public RunState(String runId, List<RepoRun> repos, String pausedReason, long tokensSpent) {
        this.runId = runId;
        for (RepoRun repo : repos) {
            this.repos.put(repo.repo(), repo);
        }
        this.pausedReason = pausedReason;
        this.tokensSpent = tokensSpent;
    }

    public void pause(String reason) {
        this.pausedReason = reason;
    }

    public String pausedReason() {
        return pausedReason;
    }

    public void addTokens(long tokens) {
        this.tokensSpent += tokens;
    }

    public long tokensSpent() {
        return tokensSpent;
    }
```

`RunStore.writeState`'s local Snapshot record becomes:

```java
        record Snapshot(String runId, String pausedReason, long tokensSpent, List<RepoRun> repos) {
        }
        ...
                    .writeValueAsString(new Snapshot(state.runId(), state.pausedReason(),
                            state.tokensSpent(), state.repos()));
```

`RunGit.startBranch` — after the existing if/else, before the closing of the try block, add the untracked clean (both paths: an existing branch may carry debris; a fresh branch off a dirty resume tree may too):

```java
            git.clean().setCleanDirectories(true).setForce(true).call();
```

`Orchestrator.java` — full replacement:

```java
package sdd.cli.implement;

import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.agent.run.StepOutcome;
import sdd.agent.run.StepResult;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ModelException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Drives up to two attempts per repo across the plan's execution order (design Component 3):
 * branch off base, run the 4B agent, escalate a failed attempt to the planner-tier model from a
 * hard-reset tree, checkpoint-commit on success, cascade failures to downstream skips, and pause
 * the run (exit 3) on endpoint outage, infrastructure failure, or run-budget exhaustion.
 * Concurrency and M8 staleness recovery are 4C-3b.
 */
public final class Orchestrator {
    /** Attempt-2 triggers. BLOCKED asked for a human; INFRA pauses; SUCCESS needs nothing. */
    private static final Set<StepResult> ESCALATE = Set.of(StepResult.VERIFY_FAILED,
            StepResult.EXHAUSTED, StepResult.BUDGET, StepResult.MALFORMED, StepResult.WEDGED);

    private final RepoStepRunner runner;
    private final ChatModel coder;
    private final String coderModelName;
    private final ChatModel escalation;
    private final String escalationModelName;
    private final Function<String, RunnerSettings> settingsFor;
    private final RunStore store;
    private final long runTokenBudget;

    public record RunResult(int exitCode, RunState state) {
    }

    public Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName,
                        ChatModel escalation, String escalationModelName,
                        Function<String, RunnerSettings> settingsFor, RunStore store, long runTokenBudget) {
        this.runner = runner;
        this.coder = coder;
        this.coderModelName = coderModelName;
        this.escalation = escalation;
        this.escalationModelName = escalationModelName;
        this.settingsFor = settingsFor;
        this.store = store;
        this.runTokenBudget = runTokenBudget;
    }

    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps) {
        String runId = runDir.getFileName().toString();
        // Only repos with a runnable step are tracked. Step-less affected repos (bom / bump-only sites,
        // whose version-bump edits are 4C-2b) would otherwise orphan at PENDING and force a spurious exit 2.
        List<String> runnable = Scheduler.sequence(plan.order()).stream()
                .filter(steps::containsKey).toList();
        return run(runDir, plan, steps, new RunState(runId, runnable));
    }

    /** Resume entry: repos already SUCCEEDED or FAILED in the passed state are not re-run. */
    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps, RunState state) {
        String runId = runDir.getFileName().toString();
        try {
            store.writeState(runDir, state);   // inside the try so an IO failure still releases the lock
            for (String repo : Scheduler.sequence(plan.order())) {
                if (!steps.containsKey(repo)) {
                    continue;
                }
                RepoState already = state.stateOf(repo);
                if (already == RepoState.SUCCEEDED || already == RepoState.FAILED) {
                    continue;   // settled in a prior (resumed) walk
                }
                if (state.tokensSpent() >= runTokenBudget) {
                    state.pause("run token budget exhausted (" + state.tokensSpent() + " tokens)");
                    store.writeState(runDir, state);
                    break;
                }
                if (Scheduler.blockedByUpstream(repo, plan.edges(), state)) {
                    transition(runDir, state, repo, RepoState.SKIPPED_UPSTREAM_FAILED, null, null,
                            "upstream failed");
                    continue;
                }
                RepoStep step = steps.get(repo);
                transition(runDir, state, repo, RepoState.IN_PROGRESS, null, null, "");
                String branch = "sdd/" + runId + "/" + slug(repo);
                String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
                List<String> events = new ArrayList<>();
                StepOutcome outcome;
                boolean escalated = false;
                try {
                    RunGit.startBranch(step.repoRoot(), branch, base);
                    outcome = runner.run(step, coder, coderModelName, settingsFor.apply(repo), "");
                    events.addAll(outcome.events());
                    state.addTokens(outcome.tokens());
                    if (ESCALATE.contains(outcome.result()) && state.tokensSpent() < runTokenBudget) {
                        escalated = true;
                        events.add("attempt 2: hard reset to base, escalating to " + escalationModelName);
                        RunGit.startBranch(step.repoRoot(), branch, base);
                        StepOutcome second = runner.run(step, escalation, escalationModelName,
                                settingsFor.apply(repo), attemptDigest(outcome));
                        events.addAll(second.events());
                        state.addTokens(second.tokens());
                        outcome = second;
                    }
                } catch (ModelException e) {
                    store.writeAgentEvents(runDir, repo, events);
                    if (endpointTrouble(e)) {
                        state.pause("model endpoint unavailable: " + e.getMessage());
                        transition(runDir, state, repo, RepoState.PAUSED_ENDPOINT, branch, null,
                                e.getMessage());
                        break;
                    }
                    throw e;   // 4xx configuration errors abort the run (exit 4 upstream)
                }
                store.writeAgentEvents(runDir, repo, events);
                String attemptTag = escalated ? "attempt 2 (" + escalationModelName + ") " : "";
                if (outcome.result() == StepResult.SUCCESS) {
                    String sha = RunGit.commitAll(step.repoRoot(), "sdd: " + runId + " " + repo);
                    transition(runDir, state, repo, RepoState.SUCCEEDED, branch, sha,
                            attemptTag + outcome.summary());
                } else if (outcome.result() == StepResult.INFRA) {
                    state.pause("infrastructure failure in " + repo + " — fix the environment and resume");
                    transition(runDir, state, repo, RepoState.PAUSED_INFRA, branch, null,
                            attemptTag + outcome.summary());
                    break;
                } else {
                    transition(runDir, state, repo, RepoState.FAILED, branch, null,
                            attemptTag + outcome.result() + ": " + outcome.summary());
                }
            }
        } finally {
            store.releaseLock(runDir);
        }
        boolean paused = state.pausedReason() != null;
        boolean allSucceeded = state.repos().stream().allMatch(r -> r.state() == RepoState.SUCCEEDED);
        return new RunResult(paused ? 3 : allSucceeded ? 0 : 2, state);
    }

    private static boolean endpointTrouble(ModelException e) {
        int status = e.statusCode();
        return status == 0 || status == 429 || status >= 500;
    }

    private static String attemptDigest(StepOutcome first) {
        String verification = first.verificationOutput().isEmpty() ? "none" : first.verificationOutput();
        return "\n\n## A previous attempt by a smaller model failed — you are the escalation\n"
                + "It ended " + first.result() + ": " + first.summary() + "\n"
                + "The tree has been hard-reset to base, so its edits are gone. Do not repeat its "
                + "mistakes. Its last verification output:\n" + verification;
    }

    private static String slug(String repo) {
        return repo.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private void transition(Path runDir, RunState state, String repo, RepoState to, String branch,
                            String sha, String detail) {
        RepoState from = state.stateOf(repo);
        state.set(repo, to, branch, sha, detail);
        store.appendEvent(runDir, repo, from, to, detail);
        store.writeState(runDir, state);
    }
}
```

`ImplementCommand.java` — three edits:

1. New field next to `coderForTest`:

```java
    ChatModel coderForTest;        // test seam — mirrors ApproveCommand.smokeForTest
    ChatModel escalationForTest;   // test seam for the attempt-2 model
```

2. Replace the coder/orchestrator construction block with (note: when `coderForTest` is set and no explicit escalation double is given, the coder double serves both — a scripted test must never fall through to a real HTTP client):

```java
                ModelEndpoint coderEndpoint = config.models().get("coder");
                ModelEndpoint plannerEndpoint = config.models().get("planner");
                ChatModel coder = coderForTest != null ? coderForTest : new HttpChatModel(coderEndpoint);
                ChatModel escalation = escalationForTest != null ? escalationForTest
                        : coderForTest != null ? coderForTest : new HttpChatModel(plannerEndpoint);
                String coderName = coderEndpoint.model();
                String escalationName = plannerEndpoint.model();
```

and the orchestrator line:

```java
                Orchestrator orchestrator = new Orchestrator(new RepoStepRunner(jdbi), coder, coderName,
                        escalation, escalationName, settingsFor, store, RUN_TOKEN_BUDGET);
```

with the class constant:

```java
    private static final long RUN_TOKEN_BUDGET = 30_000_000L;   // design line 59; sdd.yml override is 4C-3b
```

3. The summary line's label gains PAUSED, plus the resume hint:

```java
                String label = result.exitCode() == 0 ? "COMPLETE"
                        : result.exitCode() == 3 ? "PAUSED" : "PARTIAL";
                out.println("run " + runId + " " + label
                        + " (state: " + runDir.resolve("state.json") + ")");
                if (result.exitCode() == 3) {
                    out.println("paused: " + result.state().pausedReason());
                    if (result.state().pausedReason().contains("token budget")) {
                        // tokensSpent persists across --resume, so a budget pause re-pauses immediately;
                        // honesty over a dead-end hint until 4C-3b makes the budget configurable.
                        out.println("the run token budget is fixed this phase — delete " + runDir
                                + " to start over, or wait for 4C-3b's configurable budget");
                    } else {
                        out.println("resume with: sdd implement --resume " + planJsonPath);
                    }
                }
```

(`--resume` itself ships in Task 5; the hint text is stable either way. Check `ImplementCommandTest` for any scripted non-SUCCESS coder path: with the fallback above the coder double also serves attempt 2, so such a test needs its script extended by the second attempt's calls — extend the script rather than asserting exhaustion.)

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: multi-attempt escalation, pause states, and run token budget in sdd implement"
```

---

### Task 4: Input hardening — post-parse plan validation, usage-error exit code, spec snapshot

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/PlanJsonReader.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java` (create() overload)
- Modify: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/PlanJsonReaderTest.java` (add tests), `sdd-cli/src/test/java/sdd/cli/ImplementCommandTest.java` (add tests)

**Interfaces:**
- Produces: `PlanJsonReader.validate(PlanModel plan)` (static, throws `IllegalArgumentException` naming the defect) — rejects: a step whose repo is absent from the flattened `order` (today silently dropped → false COMPLETE); a repo appearing twice across `order` (today runs twice, the second `startBranch` wiping the first checkpoint); an edge endpoint naming a repo absent from `repos[]`; an `order` entry absent from `repos[]`. `RunStore.create(Path workspace, String runId, String planJson, String specText)` — also snapshots `spec.md` into the run dir; the existing 3-arg overload delegates with `""` (writes an empty snapshot). `@Command(..., exitCodeOnInvalidInput = 4)` on `ImplementCommand`.
- Consumes: `PlanModel` accessors (Task-independent).

- [ ] **Step 1: Write the failing tests.** Append to `PlanJsonReaderTest` — and add the two imports the file lacks today: `import java.util.List;` and `import static org.assertj.core.api.Assertions.assertThatThrownBy;` (it currently only static-imports `assertThat` and fully qualifies `java.util.List`):

```java
    @Test
    void validateRejectsAStepWhoseRepoIsMissingFromOrder() {
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(), List.of(),
                List.of(new PlanModel.PlanStep("ghost", List.of(), "patch", List.of(), List.of(),
                        List.of(), List.of(), "x")));
        assertThatThrownBy(() -> PlanJsonReader.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost").hasMessageContaining("order");
    }

    @Test
    void validateRejectsADuplicateOrderEntry() {
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib"), List.of("lib")), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> PlanJsonReader.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lib").hasMessageContaining("twice");
    }

    @Test
    void validateRejectsAnEdgeNamingAnUnknownRepo() {
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")),
                List.of(new PlanModel.PlanEdge("lib", "ghost", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
        assertThatThrownBy(() -> PlanJsonReader.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void validateAcceptsTheCanonicalFixture() {
        PlanJsonReader.validate(PlanJsonReader.read(PlanJsonReaderTestFixture.PLAN));   // must not throw
    }
```

Append to `ImplementCommandTest` (reuse its existing workspace-building helpers):

```java
    @Test
    void unknownOptionAbortsWithExitFour() {
        int exit = new CommandLine(new ImplementCommand()).execute("--no-such-flag");
        assertThat(exit).isEqualTo(4);
    }
```

and, inside an existing happy-path test after a successful `execute`, one new assertion:

```java
        assertThat(ws.resolve(".sdd/runs/SPEC-1-v1/spec.md")).exists();   // adjust runId to that test's spec id
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.PlanJsonReaderTest' --tests 'sdd.cli.ImplementCommandTest'`

- [ ] **Step 3: Implement.** Add to `PlanJsonReader`:

```java
    /** Cross-field sanity for a (possibly hand-edited) plan.json. Throws IllegalArgumentException
     *  naming the first defect; PlanJsonReader.read stays parse-only. */
    public static void validate(PlanModel plan) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (PlanModel.PlanRepo repo : plan.repos()) {
            known.add(repo.name());
        }
        java.util.Set<String> ordered = new java.util.HashSet<>();
        for (java.util.List<String> level : plan.order()) {
            for (String repo : level) {
                if (!ordered.add(repo)) {
                    throw new IllegalArgumentException("plan.json order lists " + repo + " twice");
                }
                if (!known.contains(repo)) {
                    throw new IllegalArgumentException("plan.json order names unknown repo " + repo);
                }
            }
        }
        for (PlanModel.PlanStep step : plan.steps()) {
            if (!ordered.contains(step.repo())) {
                throw new IllegalArgumentException("plan.json step for " + step.repo()
                        + " is missing from order — it would be silently skipped");
            }
        }
        for (PlanModel.PlanEdge edge : plan.edges()) {
            if (!known.contains(edge.fromRepo()) || !known.contains(edge.toRepo())) {
                throw new IllegalArgumentException("plan.json edge " + edge.fromRepo() + " -> "
                        + edge.toRepo() + " names a repo missing from repos[]");
            }
        }
    }
```

(The message must contain the offending repo name; keep it a single plain sentence.)

`RunStore.create` gains the 4-arg overload; the 3-arg delegates:

```java
    public Path create(Path workspace, String runId, String planJson) {
        return create(workspace, runId, planJson, "");
    }

    public Path create(Path workspace, String runId, String planJson, String specText) {
```

with one added line after the `plan.json` write:

```java
            Files.writeString(runDir.resolve("spec.md"), specText);
```

`ImplementCommand`: the `@Command` annotation becomes

```java
@Command(name = "implement",
        description = "Execute an approved plan.json across the estate",
        exitCodeOnInvalidInput = 4)
```

after `PlanModel plan = PlanJsonReader.read(planText);` add `PlanJsonReader.validate(plan);` (an `IllegalArgumentException` reaches the existing outer catch → exit 4), and the create call becomes:

```java
                Path runDir = store.create(workspace, runId, planText, specText);
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: validate plan.json cross-fields, snapshot the spec, exit 4 on usage errors"
```

---

### Task 5: --resume

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/Resume.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java` (readState + acquireLock)
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java` (branchHead)
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/PreFlight.java` (checkResume)
- Modify: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/ResumeTest.java` (create), `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java` (add), `sdd-cli/src/test/java/sdd/cli/ImplementCommandResumeTest.java` (create)

**Interfaces:**
- Produces: `RunStore.readState(Path runDir)` → `RunState` (Jackson tree over state.json, tolerant of null branch/checkpointSha); `RunStore.acquireLock(Path runDir)` (same atomic create + same "already in progress" message as `create`; `create` refactors onto it); `RunGit.branchHead(Path repo, String branch)` → SHA of `refs/heads/<branch>` or `""`; `Resume.prepare(RunState persisted, Map<String, RepoStep> steps)` → `Resume.Prep(RunState state, List<String> problems)` — SUCCEEDED repos keep their state after verifying branch HEAD == checkpointSha (mismatch/missing branch ⇒ problem); FAILED repos stay FAILED (a genuine two-attempt failure is not silently retried); `IN_PROGRESS`/`PAUSED_*`/`SKIPPED_UPSTREAM_FAILED` reset to PENDING (branch kept, checkpoint/detail cleared; cascade re-decides skips); pausedReason cleared; tokensSpent carried. `PreFlight.checkResume(steps, plan, state)` — for PENDING repos only: checkout exists, `gradlew` executable, base SHA present; clean-tree and HEAD-drift checks are deliberately skipped (`startBranch` hard-resets AND cleans untracked, so tree state is irrelevant on resume). `ImplementCommand --resume`: reads plan.json AND spec.md from the run-dir snapshots (never the live files), refuses if no state.json exists; a fresh (non-resume) run now refuses when state.json already exists ("resume with --resume, or delete <runDir> to start over").
- Consumes: Task 3's `run(runDir, plan, steps, state)` resume entry + `RunState` resume constructor; Task 4's spec snapshot.

- [ ] **Step 1: Write the failing unit tests.** `ResumeTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.RepoStep;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeTest {
    @TempDir Path ws;

    private static RepoStep step(String repo, Path root) {
        return new RepoStep(repo, root, "x", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static RunState persisted(RepoRun... repos) {
        return new RunState("S-v1", List.of(repos), "model endpoint unavailable: x", 123L);
    }

    @Test
    void verifiedSucceededReposAreKeptAndOthersReset() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(lib.path(), "sdd/S-v1/lib", lib.headSha());
        String checkpoint = RunGit.commitAll(lib.path(), "checkpoint");
        RunState state = persisted(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", checkpoint, "done"),
                new RepoRun("svc", RepoState.PAUSED_ENDPOINT, "sdd/S-v1/svc", null, "outage"),
                new RepoRun("app", RepoState.SKIPPED_UPSTREAM_FAILED, null, null, "upstream failed"));

        Resume.Prep prep = Resume.prepare(state, Map.of("lib", step("lib", lib.path())));

        assertThat(prep.problems()).isEmpty();
        assertThat(prep.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(prep.state().stateOf("svc")).isEqualTo(RepoState.PENDING);
        assertThat(prep.state().stateOf("app")).isEqualTo(RepoState.PENDING);
        assertThat(prep.state().pausedReason()).isNull();
        assertThat(prep.state().tokensSpent()).isEqualTo(123L);
    }

    @Test
    void failedReposStayFailed() {
        RunState state = persisted(new RepoRun("lib", RepoState.FAILED, "sdd/S-v1/lib", null, "VERIFY_FAILED"));

        Resume.Prep prep = Resume.prepare(state, Map.of());

        assertThat(prep.problems()).isEmpty();
        assertThat(prep.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
    }

    @Test
    void aDriftedCheckpointIsAProblem() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        RunGit.startBranch(lib.path(), "sdd/S-v1/lib", lib.headSha());
        RunState state = persisted(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "0000000000000000000000000000000000000000", "done"));

        Resume.Prep prep = Resume.prepare(state, Map.of("lib", step("lib", lib.path())));

        assertThat(prep.problems()).hasSize(1);
        assertThat(prep.problems().get(0)).contains("lib").contains("checkpoint");
    }
}
```

Append to `RunStoreTest`:

```java
    @Test
    void readStateRoundTripsThePauseFields() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "spec body");
        RunState state = new RunState("S-v1", List.of("lib"));
        state.set("lib", RepoState.PAUSED_ENDPOINT, "sdd/S-v1/lib", null, "outage");
        state.pause("model endpoint unavailable: x");
        state.addTokens(42L);
        store.writeState(runDir, state);

        RunState read = store.readState(runDir);

        assertThat(read.runId()).isEqualTo("S-v1");
        assertThat(read.stateOf("lib")).isEqualTo(RepoState.PAUSED_ENDPOINT);
        assertThat(read.pausedReason()).isEqualTo("model endpoint unavailable: x");
        assertThat(read.tokensSpent()).isEqualTo(42L);
        assertThat(ws.resolve(".sdd/runs/S-v1/spec.md")).hasContent("spec body");
    }
```

`ImplementCommandResumeTest.java` — the end-to-end proof, structured exactly like `ImplementCommandPropagationTest` (copy its `repo(...)`/`done()` helpers and workspace fixture verbatim, with a 2-repo lib→svc plan whose edge mechanism is `NONE` to keep gradle stubs at `exit 0`; spec id `SPEC-9`, both stub `gradlew` scripts `exit 0`):

```java
    @Test
    void aPausedRunResumesToCompletion() throws Exception {
        // ... fixture setup identical to ImplementCommandPropagationTest (repos lib+svc, db rows,
        //     sdd.yml, s.md, s.plan.json with base SHAs) ...

        ImplementCommand first = new ImplementCommand();
        first.coderForTest = req -> {
            throw new ModelException("transport error: Connection refused", new java.io.IOException("x"));
        };
        CommandLine firstCli = new CommandLine(first);
        firstCli.setOut(new PrintWriter(new StringWriter()));
        int firstExit = firstCli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());
        assertThat(firstExit).isEqualTo(3);

        ImplementCommand second = new ImplementCommand();
        second.coderForTest = new ScriptedChatModel(List.of(done(), done()));   // lib done, svc done
        StringWriter out = new StringWriter();
        CommandLine secondCli = new CommandLine(second);
        secondCli.setOut(new PrintWriter(out));
        int secondExit = secondCli.execute("--workspace", ws.toString(), "--resume",
                ws.resolve("s.plan.json").toString());

        assertThat(secondExit).isEqualTo(0);
        assertThat(out.toString()).contains("lib: SUCCEEDED").contains("svc: SUCCEEDED");
    }

    @Test
    void aFreshRerunOverAnExistingRunRefusesAndPointsAtResume() throws Exception {
        // ... same fixture; run once to completion with a scripted coder ...
        // then a second NON-resume execute must exit 4 and mention --resume:
        assertThat(secondExit).isEqualTo(4);
        assertThat(errText).contains("--resume");
    }
```

(Write both tests out fully in the file — the elided fixture lines are verbatim copies from `ImplementCommandPropagationTest`; the second test's first run uses `new ScriptedChatModel(List.of(done(), done()))`.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.ResumeTest' --tests 'sdd.cli.implement.RunStoreTest' --tests 'sdd.cli.ImplementCommandResumeTest'`

- [ ] **Step 3: Implement.** `RunGit.branchHead`:

```java
    /** HEAD of refs/heads/<branch>, or "" if the branch does not exist. */
    public static String branchHead(Path repo, String branch) {
        try (Git git = Git.open(repo.toFile())) {
            var id = git.getRepository().resolve("refs/heads/" + branch);
            return id == null ? "" : id.name();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read branch " + branch + " of " + repo + ": "
                    + e.getMessage(), e);
        }
    }
```

`RunStore` — extract the lock block from `create` into `acquireLock` and add `readState`:

```java
    public void acquireLock(Path runDir) {
        Path lock = runDir.resolve("lock");
        try {
            Files.createFile(lock);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new IllegalStateException("run " + runDir.getFileName() + " is already in progress "
                    + "(lock held at " + lock + "); remove the lock to override");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public RunState readState(Path runDir) {
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    JSON.readTree(Files.readString(runDir.resolve("state.json")));
            List<RepoRun> repos = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : root.path("repos")) {
                repos.add(new RepoRun(node.path("repo").asText(),
                        RepoState.valueOf(node.path("state").asText()),
                        node.path("branch").isNull() ? null : node.path("branch").asText(),
                        node.path("checkpointSha").isNull() ? null : node.path("checkpointSha").asText(),
                        node.path("detail").asText("")));
            }
            com.fasterxml.jackson.databind.JsonNode paused = root.path("pausedReason");
            return new RunState(root.path("runId").asText(), repos,
                    paused.isMissingNode() || paused.isNull() ? null : paused.asText(),
                    root.path("tokensSpent").asLong(0));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

(`create` keeps its behavior but calls `acquireLock(runDir)` in place of its inline lock block.)

`Resume.java`:

```java
package sdd.cli.implement;

import sdd.agent.run.RepoStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconciles a persisted run state for --resume (design line 60: branch HEADs are the checkpoints).
 * SUCCEEDED survives only if its branch still points at the recorded checkpoint; FAILED stays FAILED
 * (two attempts already spent); everything else re-runs from PENDING — startBranch hard-resets and
 * cleans, so whatever a crash or pause left in the tree is irrelevant.
 */
public final class Resume {
    public record Prep(RunState state, List<String> problems) {
        public Prep {
            problems = List.copyOf(problems);
        }
    }

    private Resume() {
    }

    public static Prep prepare(RunState persisted, Map<String, RepoStep> steps) {
        List<String> problems = new ArrayList<>();
        List<RepoRun> reconciled = new ArrayList<>();
        for (RepoRun repo : persisted.repos()) {
            switch (repo.state()) {
                case SUCCEEDED -> {
                    RepoStep step = steps.get(repo.repo());
                    String head = step == null || repo.branch() == null
                            ? "" : RunGit.branchHead(step.repoRoot(), repo.branch());
                    if (!head.equals(repo.checkpointSha())) {
                        problems.add(repo.repo() + ": checkpoint " + repo.checkpointSha()
                                + " is no longer the HEAD of " + repo.branch() + " (found "
                                + (head.isEmpty() ? "no branch" : head) + ") — cannot resume this run");
                    }
                    reconciled.add(repo);
                }
                case FAILED -> reconciled.add(repo);
                default -> reconciled.add(new RepoRun(repo.repo(), RepoState.PENDING, repo.branch(), null, ""));
            }
        }
        return new Prep(new RunState(persisted.runId(), reconciled, null, persisted.tokensSpent()), problems);
    }
}
```

`PreFlight.checkResume` (refactor the three environment checks into a private helper shared with `check`; drift/clean checks intentionally absent — the class javadoc's "hard-fails on drift" sentence gains "(fresh runs; resume trusts checkpoints instead)"):

```java
    /** Resume gate: environment-only checks for the repos that will actually run. Tree state is NOT
     *  checked — startBranch hard-resets to base and cleans untracked debris on entry. */
    public static Result checkResume(Map<String, RepoStep> steps, PlanModel plan, RunState state) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, RepoStep> entry : steps.entrySet()) {
            if (state.stateOf(entry.getKey()) != RepoState.PENDING) {
                continue;
            }
            environment(entry.getKey(), entry.getValue(), plan, problems);
        }
        return new Result(problems.isEmpty(), problems);
    }
```

where `environment(repo, step, plan, problems)` is the extracted dir/gradlew/base-SHA block from `check` (which now calls it too, keeping its clean-tree + drift checks inline after it).

`ImplementCommand` — add the option and the resume branch. New field:

```java
    @Option(names = "--resume", description = "Resume a paused or crashed run of this plan from its checkpoints")
    boolean resume;
```

Inside `call()`, after `runId` is computed the flow forks (replace the single `store.create` line):

```java
                RunStore store = RunStore.system();
                Path runDir = workspace.resolve(".sdd/runs/" + runId);
                RunState initialState = null;
                if (resume) {
                    if (!Files.exists(runDir.resolve("state.json"))) {
                        err.println("error: no run to resume at " + runDir);
                        return 4;
                    }
                    planText = Files.readString(runDir.resolve("plan.json"));   // snapshots, not live files
                    plan = PlanJsonReader.read(planText);
                    PlanJsonReader.validate(plan);
                    specText = Files.readString(runDir.resolve("spec.md"));
                    parsedSpec = SpecParser.parse(specText);
                    steps = RepoStepResolver.resolve(plan, parsedSpec, paths);
                    Resume.Prep prep = Resume.prepare(store.readState(runDir), steps);
                    if (!prep.problems().isEmpty()) {
                        prep.problems().forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
                    PreFlight.Result gate = PreFlight.checkResume(steps, plan, prep.state());
                    if (!gate.ok()) {
                        gate.problems().forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
                    store.acquireLock(runDir);   // LAST, after every gate: everything above is read-only,
                                                 // so no abort path between acquire and the orchestrator's
                                                 // finally-release can leak the lock and wedge future resumes
                    initialState = prep.state();
                } else {
                    if (Files.exists(runDir.resolve("state.json"))) {
                        err.println("error: run " + runId + " already exists — resume with --resume, "
                                + "or delete " + runDir + " to start over");
                        return 4;
                    }
                    PreFlight.Result preflight = PreFlight.check(steps, plan);
                    if (!preflight.ok()) {
                        preflight.problems().forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
                    runDir = store.create(workspace, runId, planText, specText);
                }
```

and the orchestrator invocation becomes:

```java
                Orchestrator.RunResult result = initialState == null
                        ? orchestrator.run(runDir, activePlan, activeSteps)
                        : orchestrator.run(runDir, activePlan, activeSteps, initialState);
```

This requires hoisting `planText`/`plan`/`specText`/`parsedSpec`/`steps` to non-final locals assigned before the fork (the fresh path keeps today's assignments; the resume path REPLACES them from the snapshots after `paths` is loaded — move the existing `PreFlight.check` call into the `else` branch as shown, and keep spec-sha warn + KB checks where they are). The resume path deliberately re-runs `RepoStepResolver.resolve` on snapshot content so work orders match the approved plan even if live files changed.

**MANDATORY relocation (compile-stopper otherwise):** the coder/escalation/`settingsFor` block that Task 3 left BEFORE this fork must MOVE to immediately AFTER the fork, because the `settingsFor` lambda captures `steps` and `plan` — locals now reassigned in the resume branch are no longer effectively final and javac rejects the lambda. Rebind the lambda to final aliases declared once the fork settles:

```java
                final PlanModel activePlan = plan;
                final Map<String, RepoStep> activeSteps = steps;
                ModelEndpoint coderEndpoint = config.models().get("coder");
                ModelEndpoint plannerEndpoint = config.models().get("planner");
                ChatModel coder = coderForTest != null ? coderForTest : new HttpChatModel(coderEndpoint);
                ChatModel escalation = escalationForTest != null ? escalationForTest
                        : coderForTest != null ? coderForTest : new HttpChatModel(plannerEndpoint);
                String coderName = coderEndpoint.model();
                String escalationName = plannerEndpoint.model();
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = activeSteps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    List<String> extraArgs = sdd.cli.implement.Propagation.includeBuildArgs(
                            repo, activePlan.edges(), paths);
                    return RunnerSettings.defaults(javaHome, extraArgs);
                };
```

**Known limitation to state in the class javadoc and the task's report:** a hard-killed process (SIGKILL, power loss) leaves the lock file behind — only in-process exits reach the orchestrator's `finally` release — so `--resume` after a hard crash aborts with the existing "already in progress … remove the lock to override" message. Manual lock removal is the deliberate escape hatch this phase; lock-staleness detection is 4C-3b territory.

- [ ] **Step 4: Run — expect PASS, then the full suite.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: sdd implement --resume continues a paused or crashed run from checkpoints"
```

---

## Verification

1. `./gradlew build` — all modules green; every pre-existing suite still passes (the only updated legacy tests are the `Orchestrator` constructor sites and the cascade test's extended script, both in-plan).
2. Resilience behaviors each have a subprocess-real test: escalation from a hard-reset tree (`secondAttemptEscalatesFromAHardResetTreeAndSucceeds` — proves reset, digest delivery, and second-model routing), infra retry-once vs. pause (`RepoStepRunnerTest` pair + `infraFailurePausesTheRunAtExit3`), endpoint outage → exit 3 with `PENDING` (not cascaded) downstream, budget pause, and the paused→resumed→COMPLETE end-to-end (`ImplementCommandResumeTest`).
3. Run-dir contract after this phase: `plan.json` + `spec.md` snapshots, `state.json` with `pausedReason`/`tokensSpent`, per-repo `agent-events.jsonl` containing both attempts' events, lock released on pause.
4. Real-estate smoke remains blocked on the Qwen weights; nothing here needs a live model.

## Self-Review (completed at write time)

1. **Spec coverage:** design line 59 (max 2 attempts, attempt 2 = hard reset + attempt-1 digest + DeepSeek) → Tasks 1+3; line 63 (INFRA never reaches the agent, retry once, then PAUSED_INFRA) → Task 2+3; line 71 (endpoint outage → PAUSED_ENDPOINT + resume command; exit codes 0/2/3/4) → Tasks 3+4; line 59 (30M run budget) → Task 3; line 60 (`--resume`: branch HEADs = checkpoints, reset IN_PROGRESS, snapshot spec, no re-create/lock) → Tasks 4+5; 4C-1 residuals (post-parse validation, exitCodeOnInvalidInput, FAILED-repo dirty tree, SUCCEEDED-repo-on-branch rerun collision) → Tasks 3 (clean), 4 (validation/exit), 5 (rerun guard + resume). Deferred list is in Global Constraints.
2. **Placeholder scan:** the one deliberate ellipsis is `ImplementCommandResumeTest`'s fixture block, which the task pins as "verbatim copies from `ImplementCommandPropagationTest`" — an existing in-repo file, not an unwritten design. No TBDs otherwise.
3. **Type consistency:** `StepOutcome` 5-field record (T1) consumed by T2's `outcome(...)` calls and T3's `outcome.tokens()`; `Verdict` 3-field (T2) constructed in T2's helper only; `Orchestrator` 8-arg constructor (T3) matches T3's test helper and ImplementCommand wiring; `run(…, RunState)` (T3) consumed by T5; `RunState` resume constructor `(String, List<RepoRun>, String, long)` (T3) used by T5's `Resume.prepare` and `RunStore.readState`; `create(Path,String,String,String)` (T4) used by T5's tests; `branchHead` (T5) used only in `Resume`. Edge direction untouched everywhere.
4. **Known judgment calls (for reviewers):** BLOCKED does not escalate; INFRA always pauses (scoped-skip deferred); resume keeps FAILED repos failed; resume-mode pre-flight skips tree checks by design (justified by startBranch reset+clean); ScriptedChatModel serves both attempt models in legacy tests via the two-arg helper and the ImplementCommand fallback.
