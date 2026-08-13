# Phase 5B: `sdd review` Decisions — Approve / Reject / Redo, Squash, Clean, Status

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Gate 2 — a human records approve / reject / redo per repo, the decisions persist and survive restarts, cross-repo invariants keep the estate coherent, a redo automatically re-verifies the downstream subtree, approving squashes a repo's run branch into one templated commit carrying an `Sdd-Run:` trailer, and `sdd clean` / `sdd status` finish the Phase-5 CLI surface.

**Architecture:** The decision machinery is a pure state model (`Decisions`) persisted to `<runDir>/review/decisions.json`, so every invariant is unit-testable without a terminal. Three scriptable subcommands (`sdd review approve|reject|redo <repo>`) mutate it, and an interactive loop is a thin shell over the same calls — CI and humans drive identical logic. 5A's rebuild block is extracted into a reusable `RebuildPass` so both the consistency pass and a redo's downstream re-verification share one restore-safe implementation. `SquashApprove` collapses a repo's `base..checkpoint` range into one commit on the run branch and rewrites `state.json`'s checkpoint to the new sha so the rest of the pipeline stays coherent.

**Tech Stack:** Java 21, JGit 6.10.0, Jackson, picocli 4.7.6 (nested subcommands), JUnit 5 + AssertJ, `FixtureRepo`.

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — Component 4 (lines 65-67), plus line 21 / line 94 (`status` + `clean` in the Phase-5 CLI surface), line 58 (never push), line 61 (mechanism recorded in the Gate-2 report), line 71 (events + exit taxonomy).

## Global Constraints

- **Scope = the decision half of Component 4, plus the two Phase-5 CLI commands (`clean`, `status`) that neither 5A nor an earlier phase claimed.** 5A already ships the report, diffs, contract re-check, estate rebuild and runbook.
- **Ratified interpretations (flag at review if you disagree):**
  - **(a) Decisions never touch `main` and never push.** Approve squashes the run branch onto itself and leaves it there; promoting to a mainline stays a human act guided by the runbook (design line 58: never push/remote; the user's standing constraint is "never merge test changes to their mains").
  - **(b) `redo` records the decision AND automatically re-verifies the downstream subtree** (design line 67: "library redo auto-re-verifies downstream subtree"). Re-*verify* is not re-*implement*: it reuses `RebuildPass`, which 5A already shipped restore-safe. `redo` still does **not** invoke `sdd implement` — it prints the exact `--retry` command. `--no-reverify` opts out.
  - **(c)** Approving a repo whose consumed upstream is REJECTED or REDO is refused, naming the blocking upstream — the design's invariant, extended to REDO because a redone upstream is equally unsettled.
  - **(d)** Rejecting or redoing an upstream **downgrades already-APPROVED downstream repos to PENDING** (not REJECTED — the human must re-decide) and says so.
  - **(e)** A repo may only be approved when its run state is SUCCEEDED. A repo in `plan.order()` but absent from `state.repos()` yields `stateOf == null`; report that as `"<repo> has no run state"`, never `"is null"`.
  - **(f)** Approve is idempotent: an already-squashed repo re-approves as a no-op reporting its existing sha.
  - **(g)** `sdd clean` deletes only branches for repos NOT approved in this run, plus the run dir; it requires `--force` to delete and otherwise prints what it would delete. Exit 0 when there is nothing to clean (idempotent).
- **Estate safety:** every command that checks a repo out records the original position (branch name, or `detached:<sha>`) and restores it in a `finally`, per-repo; one failure never strands siblings; a failed restore forces a non-zero exit. This is 5A's established invariant and must not weaken.
- **Exit codes** (design line 71 taxonomy): `0` success; `2` refused-by-invariant, partial, or applied-but-squash-failed (print the failure); `4` unusable input / no run dir / live lock held. `--interactive` returns the same code the non-interactive report would.
- **Every decision transition appends to `events.jsonl`** (design line 71: "Every transition → `events.jsonl` + one structured console line").
- **Zero-test-breaking** outside files a task explicitly edits; full `./gradlew build` green at every task boundary.
- Commit messages: conventional commits, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Context (verified against the code, main @ `c026eb1`)

Signatures below were checked against source; the literals in the test blocks compile as written.

- `PlanModel` is a 9-component record; `PlanRepo(name, role, annotation, versionAction, baseSha)`; `PlanEdge(fromRepo, toRepo, mode, mechanism)` where **`fromRepo` = consumer, `toRepo` = provider**. `RunState(String, List<RepoRun>, String, long)`; `RepoRun(repo, state, branch, checkpointSha, detail)` — `branch` and `checkpointSha` are **nullable**. `RunState.stateOf` returns **null** for an unknown repo.
- `RunGit` has `head, isClean, startBranch, commitAll, branchHead, resetHard, diff, diffStat, currentBranch, checkout`, and `private static final PersonIdent IDENT = new PersonIdent("sdd", "sdd@local")` (line 25). `Git` and `ResetCommand` are already imported. There is **no** branch delete and **no** squash.
- `RunStore` has `create, acquireLock, releaseLock, readState, writeState, readPropagation, writePropagation, readContract, writeContract, reviewDir, writeReview, appendEvent`, a `JSON` field, and `private static boolean lockIsStale(Path lock)` (line 71) implementing PID-liveness. `writeState` uses a temp+rename idiom (lines 129-137) — copy it. `readPropagation` uses `root.properties()` (line 246) — that is the in-repo Jackson idiom.
- `ReviewCommand` is `@Command(name = "review", exitCodeOnInvalidInput = 4)` with `--workspace`, `--no-rebuild`, and a **required** positional `<plan.json>` (line 56). Its `sanitize` is byte-identical to `ImplementCommand`'s. Its private `runRebuild(plan, state, byName, paths, config, runDir, rebuilds, notLocallyVerified, restoreFailures, err)` checks every SUCCEEDED repo out and restores all of them in one `finally` — so **after its loop, all SUCCEEDED repos are simultaneously on their checkpoint branches**. `ContractRecheck.check` is currently called at line 122, *before* that, i.e. against whatever branch the human is on.
- `Orchestrator` never restores a repo's original branch (its only `RunGit` calls are `startBranch`/`commitAll` at lines 177/194/243), so after `sdd implement` each repo is left checked out **on** its run branch.
- `Resume.prepare` (lines 47-51) fails a SUCCEEDED repo whose `branchHead != checkpointSha`, returning exit 4 — `--retry` waives that only for the named repo.
- `Scheduler.upstreams(repo, edges)` gives direct providers; `Scheduler.sequence(plan.order())` flattens the layered order.
- `SddCli` registers `{Doctor, Index, Plan, Graph, Implement, Review}`. There is no `StatusCommand` and no `name = "clean"` anywhere.
- `FixtureRepo.in(tmp, name).file(...).commit(...)`, `.headSha()`, `.path()` all exist (`sdd-core/src/testFixtures/java/sdd/core/testing/FixtureRepo.java`).

---

### Task 1: Decision model, persistence, and the git/lock primitives

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/Decision.java`, `sdd-cli/src/main/java/sdd/cli/review/DecisionRecord.java`, `sdd-cli/src/main/java/sdd/cli/review/Decisions.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java`, `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/DecisionsTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java` (append), `sdd-cli/src/test/java/sdd/cli/implement/RunGitTest.java` (append)

**Interfaces:**
- Produces: `enum Decision { PENDING, APPROVED, REJECTED, REDO }`; `record DecisionRecord(Decision decision, String reason)` (reason is `""` when none — a rejection's reason is half the decision and must persist, not just print).
- `Decisions` — mutable per-run model: `Decisions(Map<String,DecisionRecord> initial)`, `static Decisions empty()`, `Decision of(String repo)` (PENDING when absent), `String reasonOf(String repo)`, `Map<String,DecisionRecord> asMap()` (sorted), and three transitions returning `record Outcome(boolean applied, String message, List<String> downgraded)`:
  - `approve(String repo, PlanModel plan, RunState state)` — refuses when `state.stateOf(repo)` is null (`"<repo> has no run state"`) or not SUCCEEDED (`"<repo> is FAILED, only SUCCEEDED repos can be approved"`), or when a transitive upstream is REJECTED/REDO (`"<repo> cannot be approved while upstream <up> is REJECTED"`, naming the nearest blocker).
  - `reject(String repo, PlanModel plan, String reason)` / `redo(String repo, PlanModel plan, String reason)` — always apply; downgrade every transitive DOWNSTREAM repo currently APPROVED back to PENDING (clearing its reason), returning their names sorted in `downgraded`. Consumers of a repo are the `fromRepo` of edges whose `toRepo` is it; walk transitively with a visited set.
- `RunStore.readDecisions(Path runDir)` → `Map<String,DecisionRecord>` (empty when absent; an unrecognized decision token maps to PENDING rather than throwing), `RunStore.writeDecisions(Path runDir, Map<String,DecisionRecord>)` → `<runDir>/review/decisions.json`, pretty-printed, **temp+rename** like `writeState`. `RunStore.isLockHeld(Path runDir)` → true only when `lock` exists **and** `!lockIsStale(lock)` — a bare existence check would permanently block review of the very run whose `implement` crashed.
- `RunGit.isAtCheckpoint(Path repo, String branch, String checkpointSha)` → false when `branch` or `checkpointSha` is null (both are nullable), else `branchHead(repo, branch).equals(checkpointSha)`.
- Consumes: `PlanModel`, `RunState`, `RepoState`, `Scheduler`.

- [ ] **Step 1: Write the failing tests.** `DecisionsTest.java`:

```java
package sdd.cli.review;

import org.junit.jupiter.api.Test;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionsTest {
    // svc consumes lib; app consumes svc  (fromRepo = consumer, toRepo = provider)
    private static PlanModel plan() {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b"),
                        new PlanModel.PlanRepo("app", "dependent", "X", "patch", "c")),
                List.of(List.of("lib"), List.of("svc"), List.of("app")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
                        new PlanModel.PlanEdge("app", "svc", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
    }

    private static RunState allGreen() {
        return new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "aaa", ""),
                new RepoRun("svc", RepoState.SUCCEEDED, "sdd/S-v1/svc", "bbb", ""),
                new RepoRun("app", RepoState.SUCCEEDED, "sdd/S-v1/app", "ccc", "")), null, 0L);
    }

    @Test
    void approveRequiresASucceededRunState() {
        Decisions d = Decisions.empty();
        assertThat(d.of("lib")).isEqualTo(Decision.PENDING);
        assertThat(d.approve("lib", plan(), allGreen()).applied()).isTrue();
        assertThat(d.of("lib")).isEqualTo(Decision.APPROVED);

        RunState failed = new RunState("S-v1", List.of(
                new RepoRun("svc", RepoState.FAILED, null, null, "boom")), null, 0L);
        Decisions.Outcome notGreen = d.approve("svc", plan(), failed);
        assertThat(notGreen.applied()).isFalse();
        assertThat(notGreen.message()).contains("FAILED").contains("only SUCCEEDED");
    }

    @Test
    void aRepoMissingFromTheRunStateIsReportedByName() {
        RunState partial = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "b", "aaa", "")), null, 0L);

        Decisions.Outcome outcome = Decisions.empty().approve("app", plan(), partial);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.message()).contains("app").contains("no run state").doesNotContain("null");
    }

    @Test
    void aRejectedUpstreamBlocksItsDownstreamTransitively() {
        Decisions d = Decisions.empty();
        d.reject("lib", plan(), "wrong API");

        Decisions.Outcome direct = d.approve("svc", plan(), allGreen());
        Decisions.Outcome transitive = d.approve("app", plan(), allGreen());   // app -> svc -> lib

        assertThat(direct.applied()).isFalse();
        assertThat(direct.message()).contains("lib").contains("REJECTED");
        assertThat(transitive.applied()).isFalse();
        assertThat(d.of("svc")).isEqualTo(Decision.PENDING);
        assertThat(d.reasonOf("lib")).isEqualTo("wrong API");
    }

    @Test
    void aRedoUpstreamAlsoBlocks() {
        Decisions d = Decisions.empty();
        d.redo("lib", plan(), "");
        assertThat(d.approve("svc", plan(), allGreen()).message()).contains("REDO");
    }

    @Test
    void redoingAnUpstreamDowngradesApprovedDownstreamToPending() {
        Decisions d = Decisions.empty();
        d.approve("lib", plan(), allGreen());
        d.approve("svc", plan(), allGreen());
        d.approve("app", plan(), allGreen());

        Decisions.Outcome outcome = d.redo("lib", plan(), "needs rework");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.downgraded()).containsExactly("app", "svc");
        assertThat(d.of("lib")).isEqualTo(Decision.REDO);
        assertThat(d.of("svc")).isEqualTo(Decision.PENDING);   // re-decide, not auto-rejected
        assertThat(d.of("app")).isEqualTo(Decision.PENDING);
    }

    @Test
    void mapRoundTripPreservesDecisionsAndReasons() {
        Decisions d = Decisions.empty();
        d.approve("lib", plan(), allGreen());
        d.reject("svc", plan(), "flaky test");

        Decisions restored = new Decisions(d.asMap());

        assertThat(restored.of("lib")).isEqualTo(Decision.APPROVED);
        assertThat(restored.of("svc")).isEqualTo(Decision.REJECTED);
        assertThat(restored.reasonOf("svc")).isEqualTo("flaky test");
        assertThat(restored.of("app")).isEqualTo(Decision.PENDING);
    }
}
```

Append to `RunStoreTest`:

```java
    @Test
    void decisionsRoundTripUnderTheReviewDir() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        assertThat(store.readDecisions(runDir)).isEmpty();
        store.writeDecisions(runDir, Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, "flaky test")));

        assertThat(runDir.resolve("review/decisions.json")).exists();
        assertThat(store.readDecisions(runDir).get("svc").reason()).isEqualTo("flaky test");
        assertThat(store.readDecisions(runDir).get("lib").decision()).isEqualTo(Decision.APPROVED);
    }

    @Test
    void anUnknownDecisionTokenDegradesToPendingRatherThanCrashing() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        Files.createDirectories(store.reviewDir(runDir));
        Files.writeString(store.reviewDir(runDir).resolve("decisions.json"),
                "{\"lib\":{\"decision\":\"BLESSED\",\"reason\":\"\"}}");

        assertThat(store.readDecisions(runDir).get("lib").decision()).isEqualTo(Decision.PENDING);
    }

    @Test
    void aStaleLockIsNotReportedAsHeld() throws Exception {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        assertThat(store.isLockHeld(runDir)).isTrue();           // our own live PID
        Files.writeString(runDir.resolve("lock"), "999999999\n"); // a PID that cannot be alive
        assertThat(store.isLockHeld(runDir)).isFalse();

        store.releaseLock(runDir);
        assertThat(store.isLockHeld(runDir)).isFalse();
    }
```

Append to `RunGitTest`:

```java
    @Test
    void isAtCheckpointComparesBranchHeadAndToleratesNulls() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);

        assertThat(RunGit.isAtCheckpoint(repo.path(), "sdd/S-v1/lib", base)).isTrue();
        assertThat(RunGit.isAtCheckpoint(repo.path(), "sdd/S-v1/lib", "0000000")).isFalse();
        assertThat(RunGit.isAtCheckpoint(repo.path(), null, base)).isFalse();
        assertThat(RunGit.isAtCheckpoint(repo.path(), "sdd/S-v1/lib", null)).isFalse();
    }
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.DecisionsTest' --tests 'sdd.cli.implement.RunStoreTest' --tests 'sdd.cli.implement.RunGitTest'`

- [ ] **Step 3: Implement.** `Decisions` holds a `LinkedHashMap<String,DecisionRecord>`; `upstreamsOf` uses `Scheduler.upstreams(repo, plan.edges())` breadth-first with a visited set, returning the first REJECTED/REDO found; `downstreamOf` walks `plan.edges()` filtering `edge.toRepo().equals(current)` → `edge.fromRepo()`. `asMap()` returns a `TreeMap`.

In `RunStore`, `readDecisions` parses with the existing `root.properties()` idiom, mapping each value's `decision` field through a helper that catches `IllegalArgumentException` and returns `Decision.PENDING` — a hand-edited or future-versioned file must not crash the command with `"No enum constant …"`. `writeDecisions` **must** use the temp+rename idiom copied from `writeState`: two concurrent `sdd review approve` calls are explicitly supported usage, and a plain `writeString` loses one silently and truncates on a crash. `isLockHeld` reuses the existing private `lockIsStale`.

- [ ] **Step 4: Run — expect PASS.** Run: `./gradlew :sdd-cli:test`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: persisted Gate-2 decision model with cross-repo invariants"
```

---

### Task 2: Squash-approve

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/SquashApprove.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/SquashApproveTest.java`

**Interfaces:**
- Produces: `RunGit.squashOnto(Path repo, String branch, String baseSha, String message)` → the resulting sha. `SquashApprove.approve(Path repoRoot, String repo, String runId, String specId, RepoRun run, String baseSha)` → `record Result(boolean applied, boolean squashed, String sha, String message)`, where `applied=false` carries a refusal message and leaves the repo untouched.
- Consumes: nothing from Task 1 except `RunGit.isAtCheckpoint`.

**Three correctness requirements, each of which the obvious implementation was empirically shown to violate. Do not skip them.**

1. **Capture the head *before* the soft reset.** The reset moves the branch ref to `baseSha`, so testing `isClean()` *after* it and returning `resolve("HEAD")` yields `baseSha` and **silently drops every checkpoint commit** whenever the branch's net delta is empty (a revert, or a bump-then-unbump). Resolve the head first, and if the checkpoint tree equals the base tree, return that head untouched without resetting at all.
2. **Do not call `git.add()`.** After a soft reset the index *already* holds the exact checkpoint tree — including deletions, which were verified to survive correctly. Adding `.` overwrites index entries from the working tree, so the "approved" commit picks up the human's unrelated edits and untracked files and is **not** the tree they reviewed. Soft reset + `commit()` alone reproduces the checkpoint tree byte-for-byte.
3. **Gate on preconditions.** Refuse (`applied=false`) unless `RunGit.isClean(repoRoot)` — `sdd implement` gets away with the same shape only because `PreFlight` gates on `isClean` first, whereas review runs on a live checkout at an arbitrary time — and unless `RunGit.isAtCheckpoint(repoRoot, run.branch(), run.checkpointSha())`, so a branch that moved since the run is never squashed blind.

`SquashApprove` records the original position and restores it in a `finally` itself, not only in the command layer. Commit message template:

```
sdd: <repo> for <specId>

Squashed <n> checkpoint commit(s) from run <runId>.

Sdd-Run: <runId>
```

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.review;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SquashApproveTest {
    @TempDir Path tmp;

    private static int commitsSince(Path repo, String base) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            var from = git.getRepository().resolve(base);
            var to = git.getRepository().resolve("HEAD");
            int n = 0;
            for (var ignored : git.log().addRange(from, to).call()) {
                n++;
            }
            return n;
        }
    }

    private static RepoRun runOn(Path repo, String branch) {
        return new RepoRun("lib", RepoState.SUCCEEDED, branch, RunGit.branchHead(repo, branch), "");
    }

    @Test
    void collapsesEveryCheckpointCommitIntoOneTrailerCarryingCommit() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: first");
        Files.writeString(repo.path().resolve("B.java"), "class B {}\n");
        RunGit.commitAll(repo.path(), "sdd: second");

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(result.applied()).isTrue();
        assertThat(result.squashed()).isTrue();
        assertThat(result.message()).contains("Sdd-Run: S-v1").contains("sdd: lib for SPEC-9");
        assertThat(commitsSince(repo.path(), base)).isEqualTo(1);
        assertThat(RunGit.diffStat(repo.path(), base, result.sha()).filesChanged()).isEqualTo(2);
        assertThat(Files.readString(repo.path().resolve("A.java"))).contains("int x;");
        assertThat(Files.readString(repo.path().resolve("B.java"))).contains("class B");
    }

    @Test
    void aDeletionInACheckpointSurvivesTheSquash() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib")
                .file("A.java", "class A {}\n").file("Gone.java", "class Gone {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.delete(repo.path().resolve("Gone.java"));
        RunGit.commitAll(repo.path(), "sdd: drop it");

        SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(repo.path().resolve("Gone.java")).doesNotExist();
    }

    @Test
    void aNetZeroCheckpointRangeKeepsItsHeadInsteadOfCollapsingToBase() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: change");
        Files.writeString(repo.path().resolve("A.java"), "class A {}\n");
        RunGit.commitAll(repo.path(), "sdd: revert it");
        String head = RunGit.branchHead(repo.path(), "sdd/S-v1/lib");

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                runOn(repo.path(), "sdd/S-v1/lib"), base);

        assertThat(result.sha()).isEqualTo(head);                                 // not base
        assertThat(RunGit.branchHead(repo.path(), "sdd/S-v1/lib")).isEqualTo(head);
        assertThat(commitsSince(repo.path(), base)).isEqualTo(2);                 // history intact
    }

    @Test
    void aDirtyWorkingTreeIsRefusedRatherThanSweptIntoTheApprovedCommit() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: change");
        RepoRun run = runOn(repo.path(), "sdd/S-v1/lib");
        Files.writeString(repo.path().resolve("scratch.txt"), "my notes\n");   // untracked junk

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                run, base);

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("uncommitted");
        assertThat(commitsSince(repo.path(), base)).isEqualTo(1);   // untouched
        assertThat(Files.readString(repo.path().resolve("scratch.txt"))).isEqualTo("my notes\n");
    }

    @Test
    void aBranchThatMovedOffItsCheckpointIsRefused() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/S-v1/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        RunGit.commitAll(repo.path(), "sdd: change");
        RepoRun stale = new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", base, "");

        SquashApprove.Result result = SquashApprove.approve(repo.path(), "lib", "S-v1", "SPEC-9",
                stale, base);

        assertThat(result.applied()).isFalse();
        assertThat(result.message()).contains("checkpoint");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.SquashApproveTest'`

- [ ] **Step 3: Implement.**

```java
    /** Collapse everything on {@code branch} since {@code baseSha} into ONE commit. A soft reset
     *  leaves the checkpoint tree staged, so committing without any {@code add} reproduces that
     *  tree exactly — deletions included. Adding from the working tree would sweep in whatever the
     *  human happens to have lying around, so we deliberately do not. Never touches another branch
     *  and never pushes (design line 58). */
    public static String squashOnto(Path repo, String branch, String baseSha, String message) {
        try (Git git = Git.open(repo.toFile())) {
            String head = branchHead(repo, branch);   // BEFORE the reset — see the net-zero case
            git.checkout().setName(branch).call();
            var repository = git.getRepository();
            var headTree = repository.parseCommit(repository.resolve(head)).getTree();
            var baseTree = repository.parseCommit(repository.resolve(baseSha)).getTree();
            if (headTree.equals(baseTree)) {
                return head;   // nothing to squash; leave the branch exactly where it is
            }
            git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(baseSha).call();
            return git.commit().setMessage(message).setAuthor(IDENT).setCommitter(IDENT)
                    .call().getName();
        } catch (Exception e) {
            throw new IllegalStateException("cannot squash " + branch + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }
```

`SquashApprove.approve`: check `isClean` → refuse `"<repo> has uncommitted changes; commit or stash them before approving"`; check `isAtCheckpoint` → refuse `"<repo> branch <b> is no longer at its checkpoint <sha7>"`; count the commits in `baseSha..branchHead` and return `squashed=false` with the current head when the count is `<= 1`. That count guard is where idempotence lives — `squashOnto` alone is **not** idempotent, since a second call on a real delta produces a fresh sha. Record `currentBranch`/`head` up front and restore it in a `finally`.

- [ ] **Step 4: Run — expect PASS.** Run: `./gradlew :sdd-cli:test`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: squash a run branch into one Sdd-Run commit"
```

---

### Task 3: Extract `RebuildPass`; re-check contracts against checkpoint trees

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/RebuildPass.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/ReviewCommand.java` (delete `runRebuild`, delegate), `sdd-cli/src/main/java/sdd/cli/review/ContractRecheck.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/RebuildPassTest.java`, `sdd-cli/src/test/java/sdd/cli/ReviewCommandTest.java` (existing cases must keep passing)

**Why this task exists:** today `ContractRecheck.check` runs at `ReviewCommand.java:122`, *before* any checkout, so it extracts from whatever branch the human is standing on — usually `main`, without the run's changes — and will report `DRIFTED`/`NOT_EXTRACTABLE` for essentially every contract. A section that cries wolf on every run defeats spec line 66's "human adjudicates". This is a pure refactor plus a call-site move; it must not change any other behavior.

**Interfaces:**
- Produces: `RebuildPass.run(Collection<String> repos, PlanModel plan, RunState state, Map<String,Path> paths, SddConfig config, Path runDir, RunStore store, boolean recheckContracts, PrintWriter err)` → `record Outcome(Map<String,EstateRebuild.Result> rebuilds, List<String> notLocallyVerified, List<String> restoreFailures, List<ContractRecheck.Finding> contracts)`. It is `ReviewCommand.runRebuild` moved verbatim — same checkout, same per-repo try/catch, same single `finally` restore — with two changes: it iterates `Scheduler.sequence(plan.order())` **filtered to `repos`**, and when `recheckContracts` is true it calls `ContractRecheck.check` at the END of the `try` block, before the `finally` restores anything. At that point every SUCCEEDED repo in scope is simultaneously on its checkpoint branch, so the extractors read the trees the run actually produced. That is the whole fix.
- `ContractRecheck.Finding` gains a trailing `String extractedFrom` — the provider's `RunGit.currentBranch` at extraction time (`"detached:<sha>"` when empty). Under `--no-rebuild` nothing is checked out, so this records the human's branch and makes an otherwise-inexplicable `DRIFTED` adjudicable. Add the `RunGit` import.
- Consumes: nothing from Tasks 1-2.

- [ ] **Step 1: Write the failing test.** `RebuildPassTest`: build a two-repo fixture where the checkpoint branch contains a source file that `main` does not; run `RebuildPass.run(..., recheckContracts = true, ...)`; assert (a) the contract finding's `extractedFrom` is the checkpoint branch, not `main`; (b) both repos are restored to their original branches afterwards; (c) passing a `repos` subset rebuilds only that subset. Read `ReviewCommandTest` first and reuse its fixture scaffolding (real `FixtureRepo`, KB row, `sdd.yml`, `s.md`, `s.plan.json`, hand-assembled run dir). Write it out fully.
- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.RebuildPassTest'`
- [ ] **Step 3: Implement.** Move the body; move `tasksFor`/`extraArgsFor`/`javaHomeFor` (currently `ReviewCommand` privates) into `RebuildPass` and have `ReviewCommand` call through, so exactly one copy exists. In `ReviewCommand`, delete the standalone `ContractRecheck.check` call at line 122 and take contracts from the `Outcome`; when `--no-rebuild` is set, call `ContractRecheck.check` directly as today. Update all 12 `ContractRecheck.Finding` construction sites (5 in `ContractRecheck.java`, 7 in `ReviewReportTest.java`) — grep, do not guess.
- [ ] **Step 4: Run — expect PASS, including every pre-existing `ReviewCommandTest` case unchanged.** Run: `./gradlew :sdd-cli:test && ./gradlew build`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "refactor: extract RebuildPass and re-check contracts on checkpoint trees"
```

---

### Task 4: `sdd review approve|reject|redo` subcommands

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/DecisionCommand.java` (an abstract base plus three `@Command` subclasses, all in `sdd.cli.review`)
- Modify: `sdd-cli/src/main/java/sdd/cli/ReviewCommand.java`
- Test: `sdd-cli/src/test/java/sdd/cli/ReviewDecisionsCommandTest.java`

**Interfaces:**
- Produces: `sdd review approve <repo> <plan.json>`, `sdd review reject <repo> <plan.json> [--reason <text>]`, `sdd review redo <repo> <plan.json> [--reason <text>] [--no-reverify]`.
- Consumes: Tasks 1-3.

**Two picocli facts, both reproduced against 4.7.6 — the task is unimplementable without them:**

1. **`ReviewCommand`'s positional is required, and picocli validates the parent's required args before recursing into a subcommand.** `sdd review approve lib p.plan.json` fails with `Missing required parameter: '<planJsonPath>'` and exit 4 — which looks exactly like a legitimate "unusable input" exit and will be misdiagnosed. **Fix:** change line 56 to `@Parameters(index = "0", arity = "0..1", description = "The approved <spec>.plan.json")` and null-check it at the top of `call()` (`if (planJsonPath == null) { err.println("error: missing <spec>.plan.json"); return 4; }`). With that, all five arg shapes parse: `approve lib p.plan.json`, `--workspace w approve lib p.plan.json`, `p.plan.json`, `approve lib`, and bare `review`.
2. **picocli does not inherit parent options.** `review --workspace w approve lib p.plan.json` silently leaves the subcommand's `ws` at its default and reads the wrong workspace with no error. **Fix:** declare it `@Option(names = "--workspace", scope = CommandLine.ScopeType.INHERIT)` on `ReviewCommand`.

**Behavior.** Each subcommand: load the run exactly as `ReviewCommand` does (runId from the plan file name, frozen `runDir/plan.json`, `RunState`) — extract that loading into one package-visible helper that both the report path and the decision commands call; **refuse with exit 4 when `store.isLockHeld(runDir)`** (`"error: run <runId> is in progress (lock held) — wait for sdd implement to finish"`); read decisions; apply the transition; persist; `store.appendEvent(runDir, repo, <from>, <to>, detail)`; print one structured line plus any downgrades (`"downgraded to PENDING (re-decide): app, svc"`); then **re-render and re-write `report.md`**, so the artifact a human hands to a colleague reflects the decision instead of being a pre-decision snapshot.

- `approve` with `applied=true` also runs `SquashApprove.approve`. On `squashed=true`, **write the new sha back into `state.json`** via `RunStore.writeState`, with that repo's `RepoRun.checkpointSha` replaced. This is load-bearing, not bookkeeping: `Resume.prepare` fails any SUCCEEDED repo whose `branchHead != checkpointSha` with exit 4, so without the write-back the very `sdd implement --retry` command that `redo` prints would hard-fail in any run where some other repo had been approved — and Task 6's checkpoint-drift check would fire on every approved repo, accusing approve of tampering. Print `"squashed 3 commits into <sha7>"` / `"already a single commit (<sha7>)"`. If the decision applied but the squash refused, exit **2** with the refusal printed — the decision stands, the squash did not.
- `redo`, unless `--no-reverify`, runs `RebuildPass.run` over the transitive downstream closure of the repo (design line 67's "auto-re-verifies downstream subtree"), prints each verdict, and includes the results in the re-rendered report. Then prints the follow-up: `"then run: sdd implement --workspace <ws> --retry <repo> <plan.json>"`.

- [ ] **Step 1: Write the failing test** — `ReviewDecisionsCommandTest`, reusing `ReviewCommandTest`'s scaffolding. Cover: (a) `approve lib` on a SUCCEEDED repo exits 0, writes `decisions.json` with `lib: APPROVED`, squashes to one commit, **rewrites `state.json`'s checkpoint to the new sha**, and leaves the repo on its original branch; (b) `reject lib --reason "wrong API"` then `approve svc` exits 2 naming `lib`, with the reason persisted; (c) `redo lib --no-reverify` after both were approved exits 0, prints the `--retry` command, and downgrades `svc` to PENDING on disk; (d) a fresh `RunStore` re-reading `decisions.json` sees the persisted state (resumability); (e) with a live lock file present, `approve` exits 4 and changes nothing; (f) `sdd review --workspace <w> approve lib <plan.json>` parses and uses `<w>` — the regression test for both picocli facts; (g) plain `sdd review <plan.json>` still behaves exactly as before.
- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.ReviewDecisionsCommandTest'`
- [ ] **Step 3: Implement**, registering the three classes in `ReviewCommand`'s `@Command(subcommands = {...})`.
- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew :sdd-cli:test && ./gradlew build`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: sdd review approve/reject/redo with persisted decisions"
```

---

### Task 5: Interactive flow + `sdd clean`

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/InteractiveReview.java`, `sdd-cli/src/main/java/sdd/cli/CleanCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/ReviewCommand.java` (`--interactive`), `sdd-cli/src/main/java/sdd/cli/SddCli.java`, `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/InteractiveReviewTest.java`, `sdd-cli/src/test/java/sdd/cli/CleanCommandTest.java`

**Interfaces:**
- Produces: `InteractiveReview.run(BufferedReader in, PrintWriter out, ...)` — for each repo in `Scheduler.sequence` order whose decision is PENDING, print the repo's report line and prompt `"[a]pprove / [r]eject / [d]edo / [v]iew diff / [s]kip / [q]uit: "`, dispatching to the SAME `Decisions` methods the subcommands use. `v` prints the repo's `.diff`; `s` leaves it PENDING; `q` exits the loop. **Persist after every single decision** so a crash or `q` loses nothing, and re-render `report.md` when the loop ends. Driven by an injected reader/writer so it is fully testable without a terminal. `ReviewCommand --interactive` runs it after writing the report and returns the same exit code the non-interactive path would.
- `RunGit.deleteBranch(Path repo, String branch)` — no-op when the branch is absent (JGit returns an empty list, no throw). When it *is* the currently checked-out branch, JGit throws `CannotDeleteCurrentBranchException`, and `setForce(true)` does **not** help — force only bypasses the merged-ness check. Since `Orchestrator` never restores a repo's original branch, **every repo is normally left sitting on its run branch after `sdd implement`**, so this is the common case, not an edge case: check `currentBranch(repo).equals(branch)` first and check out the plan's `baseSha` before deleting. Call `.setBranchNames(branch).setForce(true)`.
- `CleanCommand` — `@Command(name = "clean")`, `--workspace`, `--force`, and an **optional** positional `<plan.json>` (spec line 21 shows a bare `sdd clean`; with no argument, operate over every run dir under the workspace's runs directory). Lists each repo whose decision is NOT APPROVED together with its run branch, plus the run dir. Without `--force`: print under `"would delete (pass --force to apply):"`, change nothing, exit 0. With `--force`: delete those branches — **never** an APPROVED repo's branch, that work must survive — and remove the run dir, printing each deletion. Wrap each repo in try/catch, collect failures, and exit 2 with the list rather than aborting (the per-repo isolation constraint). Exit 0 when there is nothing to clean; exit 4 only when an explicitly named plan has no run dir.
- Consumes: Tasks 1-4.

- [ ] **Step 1: Write the failing tests.** `InteractiveReviewTest`: feed a scripted `BufferedReader` (`"a\nr\nq\n"`) over a three-repo fixture; assert the resulting decisions and that `decisions.json` on disk is already correct after the mid-script `q` (proving the after-each-decision write). `CleanCommandTest`: a run where `lib` is APPROVED and `svc` is REJECTED → the dry run names `svc`'s branch and the run dir but not `lib`'s and deletes nothing; `--force` removes `svc`'s branch and the run dir while `lib`'s branch survives; **and a case where the repo is still checked out on the branch being deleted**, which must succeed rather than throw.
- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.InteractiveReviewTest' --tests 'sdd.cli.CleanCommandTest'`
- [ ] **Step 3: Implement**, registering `CleanCommand.class` in `SddCli`.
- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew :sdd-cli:test && ./gradlew build`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: interactive Gate-2 review flow and sdd clean"
```

---

### Task 6: Report integration + carried hardening

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/review/ReviewReport.java`, `sdd-cli/src/main/java/sdd/cli/ReviewCommand.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/ReviewReportTest.java`, `sdd-cli/src/test/java/sdd/cli/ReviewCommandTest.java`

**Interfaces:**
- Produces, in `ReviewReport.render` (which gains a trailing `Map<String,DecisionRecord> decisions` parameter — there are **five** call sites: `ReviewCommand.java:134` and `ReviewReportTest.java:38,55,74,95`; grep, don't guess):
  1. **Decisions**: each repo bullet gains `, decision: <DECISION>` (plus `(<reason>)` when non-empty); Summary gains `- Decisions: <a> approved, <r> rejected, <d> redo, <p> pending`.
  2. **`## Propagation`**: `<consumer> -> <provider>: <mode>/<mechanism>` per edge, omitted when the plan has no edges. Design line 61 says the Gate-2 report records the chosen mechanism; today it does not.
  3. **`## Checkpoint drift`**: any SUCCEEDED repo where `!RunGit.isAtCheckpoint(...)` gets `"<repo>: branch <b> is at <head7>, checkpoint was <cp7> — diffs and runbook describe the checkpoint"`, and its presence forces exit 2. **Two exclusions are mandatory:** skip APPROVED repos (Task 4 rewrote their checkpoint deliberately, so they are not drifted), and skip repos whose `branchHead` is `""` — an unresolvable sha is already reported as a diff failure, and treating it as drift breaks the existing passing test `anUnresolvableCheckpointShaYieldsADiffFailureNotALostReport` (`ReviewCommandTest.java:240-256`), which sets `checkpointSha = "000…0"` on a non-existent branch and asserts exit 0 with the comment "a diff failure alone must not change it."
  4. **`extractedFrom`** (added in Task 3) rendered in the contract section.
- `ReviewCommand` gains the same `isLockHeld` guard as the decision subcommands: exit 4 on a *live* lock, warn and proceed on a stale one. Update the class javadoc at lines 41-43, which currently asserts "no lock is taken; implement already released it".
- Consumes: Tasks 1-5.

- [ ] **Step 1: Write the failing tests** in `ReviewReportTest` (decision lines and counts, propagation section present and omitted, drift section, `extractedFrom` rendered) and `ReviewCommandTest` (live lock → exit 4 with nothing written; stale lock → proceeds with a warning; a moved run branch → exit 2 with the drift section; an APPROVED repo whose checkpoint was rewritten → **no** drift, exit 0).
- [ ] **Step 2: Run — expect RED.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.ReviewReportTest' --tests 'sdd.cli.ReviewCommandTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew :sdd-cli:test && ./gradlew build`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: decisions, propagation and drift in the Gate-2 report; lock guard"
```

---

### Task 7: `sdd status`

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/StatusCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/SddCli.java`
- Test: `sdd-cli/src/test/java/sdd/cli/StatusCommandTest.java`

**Interfaces:**
- Produces: `sdd status [<plan.json>]` with `--workspace`. Spec line 21 lists `sdd status / sdd clean` in the CLI surface and line 94 puts both in Phase 5; neither 5A nor any earlier phase shipped `status`, so without this task Phase 5 closes with a silently unshipped clause. Read-only: never checks anything out, never takes the lock. With a plan argument, print that run; with none, print one line per run dir under the workspace, newest first. Per run: run id, whether the lock is live (`in progress` / `idle`), total tokens, and one line per repo — `<repo>  <RepoState>  <Decision>  <branch>` — plus a trailing summary worded identically to the report's `- Decisions: …` line. Exit 0 always, except 4 for an explicitly named plan with no run dir.
- Consumes: Tasks 1-6.

- [ ] **Step 1: Write the failing test.** `StatusCommandTest`: a run dir with two SUCCEEDED repos and one FAILED, `lib` APPROVED in `decisions.json` → output contains `lib`, `SUCCEEDED` and `APPROVED` on one line, the FAILED repo's line, `1 approved`, and the run id; a live lock → `in progress`; a released lock → `idle`; a named plan with no run dir → exit 4.
- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.StatusCommandTest'`
- [ ] **Step 3: Implement**, registering `StatusCommand.class` in `SddCli`.
- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew :sdd-cli:test && ./gradlew build`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: sdd status over run state and Gate-2 decisions"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. Design line 67 clause coverage: approve/reject/redo/view flow (Tasks 4-5); decisions persisted and resumable (Task 1 plus the after-each-decision writes); squash to one templated commit with the `Sdd-Run:` trailer (Task 2); dependent-unapprovable-while-upstream-rejected (Task 1); **library redo auto-re-verifies downstream subtree** (Task 4 via Task 3's `RebuildPass`); `sdd clean` (Task 5). Line 61's mechanism-in-report and lines 21/94's `status` are Tasks 6-7; line 71's events are Task 4.
3. Estate safety: approve and every rebuild restore in a `finally`; `sdd clean` never deletes an APPROVED repo's branch; nothing pushes; nothing touches `main`.
4. Pipeline coherence: after approving a repo, `sdd review` still exits 0 (no false drift) and `sdd implement --resume` / `--retry` still work (checkpoint written back in Task 4) — asserted in Task 4's and Task 6's tests.
5. Real-estate readiness: the existing `SPEC-101-v1` run has six SUCCEEDED repos with checkpoints and a written report, so the whole flow can be exercised end to end after merge.

## Known carried items (explicitly NOT in this phase)

- **The contract re-check compares fresh extraction against the run's own actualized body, not the plan's declared delta** (spec line 66 says "diff vs plan deltas"). `PlanContract.body()` is used only as a selector inside `ContractActualizer.javaApi`, never as the comparison baseline — so an implementation that shipped an interface differing from what Gate 1 approved reads as `MATCHES`. Task 3 moves the extraction onto the right trees but does not add this second axis. Fixing it means a `DIVERGED_FROM_PLAN` status comparing fresh output against `contract.body()` as a containment check. **Carried to 5C.**
- **The estate rebuild covers only SUCCEEDED repos**, not "all affected repos" (spec line 66). 5A ratified this; a FAILED repo's siblings are never rebuilt against the real estate.
- **The report's "failure codes"** are `RepoState` plus `run.detail()` free text rather than the ladder's `VERIFY_FAILED/EXHAUSTED/BUDGET/MALFORMED/WEDGED` (spec line 141).
- **"Polish + docs"** (spec line 94) is unclaimed by any phase.

## Self-Review (completed at write time)

1. **Spec coverage:** every clause of design line 67 maps to a task (Verification 2); lines 21/94's `status` is Task 7 and `clean` is Task 5; lines 61 and 71 are Tasks 6 and 4. Four clauses this phase does *not* close are named explicitly above rather than left to be discovered later.
2. **Placeholder scan:** the only elisions are the fixture blocks in Tasks 3-7, each pinned to `ReviewCommandTest`'s existing scaffolding with its assertions enumerated.
3. **Type consistency:** `Decision`/`DecisionRecord`/`Decisions.Outcome(applied, message, downgraded)` (T1) → T4/T5/T6/T7; `RunStore.readDecisions`/`writeDecisions`/`isLockHeld` (T1) → T4/T5/T6/T7; `RunGit.isAtCheckpoint` (T1) → T2/T6; `squashOnto` (T2) → T2; `RunGit.deleteBranch` (T5) → T5; `SquashApprove.Result(applied, squashed, sha, message)` (T2) → T4; `RebuildPass.Outcome` (T3) → T3/T4; `ContractRecheck.Finding`'s new `extractedFrom` (T3) → 12 sites; `ReviewReport.render`'s new trailing parameter (T6) → 5 sites.
4. **Judgment calls for reviewers:** ratified (a)-(g) — notably never touching `main`, redo re-verifying but not re-implementing, REDO blocking approval like REJECTED, downgrade-to-PENDING rather than REJECTED, and `sdd clean` requiring `--force`.
