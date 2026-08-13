# Phase 5A: `sdd review` — Gate-2 Report, Diffs, Contract Re-check, Runbook

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdd review <plan.json>` turns a finished run into a human-reviewable Gate-2 package: a deterministic estate rebuild in topological order with the run's real substitution flags, a contract re-check against the actualized bodies, per-repo `.diff` files, a release runbook, and one `review/report.md` that carries statuses, diffstats, verification and contract results, token spend, and failure codes.

**Architecture:** All new code lives in a new `sdd.cli.review` package inside `sdd-cli`, reading everything it needs from the run dir (`RunStore`) and the repos' checkpoint branches — the KB stays read-only and no run state is mutated. Four pieces compose: `RunGit` gains commit-to-commit diff plus plain checkout/current-branch primitives; `ContractRecheck` re-runs the existing `ContractActualizer` against each green tree and diffs it against the recorded `contracts/<id>.md`; `EstateRebuild` re-verifies each repo in `Scheduler` order through an orchestrator-owned subprocess carrying the same `Propagation` flags the run used; `ReviewReport` renders the markdown, following `sdd-index`'s `CurationReport` idiom.

**Tech Stack:** Java 21, JGit (`DiffFormatter`/`CanonicalTreeParser`/`RevWalk`), picocli, Jackson (via `RunStore`), JUnit 5 + AssertJ, `FixtureRepo` + stub `gradlew` scripts.

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — Component 4 (line 65-67) and the Gate-2 description; Component 3's run-dir layout (line 60) supplies the inputs.

## Global Constraints

- **Scope = the deterministic, read-mostly half of Component 4.** Explicitly DEFERRED to **5B**: the interactive terminal approve/reject/redo/view flow, persisted decisions and resumability, the approve-time squash to one templated commit with the `Sdd-Run:` trailer, original-branch restore on approve, the "dependent unapprovable while consumed upstream rejected" and "library redo auto-re-verifies downstream subtree" invariants, and `sdd clean`. 5A produces the artifacts those decisions will be made from.
- **Ratified interpretations (flag at review if you disagree):** (a) the consistency rebuild runs by DEFAULT (design says "deterministic consistency pass first") with `--no-rebuild` to skip it, because it is the slow, checkout-mutating part; (b) the rebuild checks out each repo's checkpoint branch, and the command RESTORES every repo's original branch in a `finally` — review must not leave the estate somewhere the user did not put it; (c) a repo whose state is not SUCCEEDED is reported but never rebuilt and never diffed for content (nothing was checkpointed); (d) contract mismatches are WARNINGS that never change the exit code — design says "mismatches = report warnings, human adjudicates"; (e) the rebuild's verification task is the same effective task list `sdd implement` used, resolved the same way (plan step verification ∩ allowlist, else `check`, minus sdd.yml exclusions) so review and implement agree; (f) exit codes: `0` = report written and every rebuilt repo verified clean, `2` = report written but at least one rebuild failed or a repo is not SUCCEEDED, `4` = could not produce a report at all (no run dir, unreadable state, bad arguments) — mirroring `sdd implement`'s taxonomy, with `exitCodeOnInvalidInput = 4`.
- **Read-only guarantees:** the KB is never written; `state.json`, `events.jsonl`, `plan.json`, `spec.md`, `propagation.json` and the per-repo agent artifacts are never modified. Everything 5A writes goes under `<runDir>/review/`.
- **Guardrail invariant:** the rebuild is an orchestrator-owned privileged subprocess following the `MavenLocalPublisher`/`JarBuilder` precedent (own `ProcessBuilder`, env scrubbed to PATH/HOME/LANG/TMPDIR + JAVA_HOME, hard timeout, log shaped `"exit N\n…"`). It is never model-reachable and `GradleTool.ALLOWED` is not touched.
- **Zero-test-breaking** outside files a task explicitly edits; full `./gradlew build` green at every task boundary.
- Commit messages: conventional commits, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Context (verified against the code, 2026-08-13, main @ e77ed9e)

- `SddCli` registers subcommands in a `subcommands = {...}` array; `GraphCommand` is the closest analogue for a read-only command (`Callable<Integer>`, `--workspace`, `@Spec CommandSpec`, `--out`). `ImplementCommand` carries `exitCodeOnInvalidInput = 4`.
- `RunStore` already exposes `readState`, `readPropagation`, `readContract`, `create`, `writeContract`, `writeAgentEvents`, `writeTranscript`, `writeEdits`, plus a private `sanitize` used for per-repo directory names.
- `RunGit` has `head`, `isClean`, `startBranch`, `commitAll`, `branchHead`, `resetHard` — **no diff, no plain checkout, no current-branch accessor**.
- The JGit diff idiom exists in `sdd-index/src/main/java/sdd/index/scan/WorkspaceScanner.java:74-89` (`DiffFormatter` + `CanonicalTreeParser` + `RevWalk`); for two real commits both trees come from `CanonicalTreeParser` off one `RevWalk`, no `FileTreeIterator`.
- `ContractActualizer.actualize(Path repoRoot, List<PlanModel.PlanContract> provided)` → `Map<String,String>` of contract id → body, header line `"# actualized (<kind>)"`; the run wrote those bodies to `<runDir>/contracts/<id>.md`.
- `Scheduler.sequence(order)` flattens the plan's order; `PlanModel.PlanRepo(name, role, annotation, versionAction, baseSha)`, `PlanEdge(fromRepo, toRepo, mode, mechanism)`, `PlanContract(id, kind, provider, consumers, body, compat)`.
- `Propagation.includeBuildArgs(repo, edges, repoPaths)` and `Propagation.mavenLocalArgs(edges, initScript)`; `MavenLocalInit.scriptPath(runDir)`; `ImplementCommand.settingsFor` concatenates them in that order.
- `sdd-index/src/main/java/sdd/index/report/CurationReport.java` is the markdown precedent: one `StringBuilder`, sections omitted entirely when empty, `Files.writeString` at the end, an `Instant.now()` trailer plus a human-action hint line.
- No run-dir test helper exists; tests either drive `ImplementCommand.call()` end to end or hand-assemble with `RunStore` + `RunGit` + `FixtureRepo`.

---

### Task 1: Git and run-dir primitives for review

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/RunGitTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java`

**Interfaces:**
- Produces: `RunGit.diff(Path repo, String fromSha, String toSha)` → unified diff text (empty string when the trees are identical); `RunGit.diffStat(Path repo, String fromSha, String toSha)` → `record DiffStat(int filesChanged, int insertions, int deletions)`; `RunGit.currentBranch(Path repo)` → branch name or `""` when detached; `RunGit.checkout(Path repo, String branch)` — plain checkout, NO reset and NO clean (deliberately unlike `startBranch`, which destroys work). `RunStore.reviewDir(Path runDir)` → `<runDir>/review` (created on demand); `RunStore.writeReview(Path runDir, String fileName, String content)` writing under it (file name sanitized with the same helper as repo dirs).
- Consumes: nothing new.

- [ ] **Step 1: Write the failing tests.** Append to `RunGitTest` (its `@TempDir` field is named `tmp`):

```java
    @Test
    void diffAndDiffStatBetweenTwoCommits() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        repo.file("A.java", "class A { int x; }\n").file("B.java", "class B {}\n").commit("work");
        String head = repo.headSha();

        String diff = RunGit.diff(repo.path(), base, head);
        RunGit.DiffStat stat = RunGit.diffStat(repo.path(), base, head);

        assertThat(diff).contains("A.java").contains("B.java").contains("+class B {}");
        assertThat(stat.filesChanged()).isEqualTo(2);
        assertThat(stat.insertions()).isGreaterThan(0);
        assertThat(RunGit.diff(repo.path(), base, base)).isEmpty();
    }

    @Test
    void checkoutSwitchesBranchWithoutDestroyingWork() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String main = RunGit.currentBranch(repo.path());
        RunGit.startBranch(repo.path(), "sdd/run/lib", repo.headSha());
        java.nio.file.Files.writeString(repo.path().resolve("A.java"), "class A { int y; }\n");
        RunGit.commitAll(repo.path(), "checkpoint");
        String checkpoint = repo.headSha();

        RunGit.checkout(repo.path(), main);
        assertThat(RunGit.currentBranch(repo.path())).isEqualTo(main);
        RunGit.checkout(repo.path(), "sdd/run/lib");

        assertThat(RunGit.currentBranch(repo.path())).isEqualTo("sdd/run/lib");
        assertThat(RunGit.head(repo.path())).isEqualTo(checkpoint);   // work intact, not reset
        assertThat(java.nio.file.Files.readString(repo.path().resolve("A.java"))).contains("int y;");
    }
```

Append to `RunStoreTest`:

```java
    @Test
    void reviewArtifactsLandUnderTheReviewDir() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.writeReview(runDir, "report.md", "# Report\n");
        store.writeReview(runDir, "grp/lib.diff", "diff --git\n");

        assertThat(store.reviewDir(runDir)).isEqualTo(runDir.resolve("review"));
        assertThat(runDir.resolve("review/report.md")).hasContent("# Report\n");
        assertThat(runDir.resolve("review/grp-lib.diff")).exists();   // sanitized
    }
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.RunGitTest' --tests 'sdd.cli.implement.RunStoreTest'`

- [ ] **Step 3: Implement.** In `RunGit` (imports: `org.eclipse.jgit.diff.DiffFormatter`, `org.eclipse.jgit.diff.DiffEntry`, `org.eclipse.jgit.lib.ObjectId`, `org.eclipse.jgit.lib.Repository`, `org.eclipse.jgit.revwalk.RevWalk`, `org.eclipse.jgit.treewalk.CanonicalTreeParser`, `java.io.ByteArrayOutputStream`, `java.nio.charset.StandardCharsets`, `java.util.List`):

```java
    /** Per-file line counts for a commit-to-commit diff (Gate-2 report input). */
    public record DiffStat(int filesChanged, int insertions, int deletions) {
    }

    /** Unified diff between two commits; empty when the trees are identical. */
    public static String diff(Path repo, String fromSha, String toSha) {
        try (Git git = Git.open(repo.toFile());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository);
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repository);
                formatter.format(entries(repository, walk, formatter, fromSha, toSha));
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot diff " + repo + " " + fromSha + ".." + toSha
                    + ": " + e.getMessage(), e);
        }
    }

    public static DiffStat diffStat(Path repo, String fromSha, String toSha) {
        try (Git git = Git.open(repo.toFile());
             ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
            Repository repository = git.getRepository();
            try (RevWalk walk = new RevWalk(repository);
                 DiffFormatter formatter = new DiffFormatter(sink)) {
                formatter.setRepository(repository);
                List<DiffEntry> entries = entries(repository, walk, formatter, fromSha, toSha);
                int insertions = 0;
                int deletions = 0;
                for (DiffEntry entry : entries) {
                    for (var edit : formatter.toFileHeader(entry).toEditList()) {
                        insertions += edit.getEndB() - edit.getBeginB();
                        deletions += edit.getEndA() - edit.getBeginA();
                    }
                }
                return new DiffStat(entries.size(), insertions, deletions);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot diffstat " + repo + ": " + e.getMessage(), e);
        }
    }

    private static List<DiffEntry> entries(Repository repository, RevWalk walk,
                                           DiffFormatter formatter, String fromSha, String toSha)
            throws java.io.IOException {
        CanonicalTreeParser from = new CanonicalTreeParser();
        from.reset(walk.getObjectReader(), walk.parseCommit(ObjectId.fromString(fromSha)).getTree());
        CanonicalTreeParser to = new CanonicalTreeParser();
        to.reset(walk.getObjectReader(), walk.parseCommit(ObjectId.fromString(toSha)).getTree());
        return formatter.scan(from, to);
    }

    /** The checked-out branch, or "" when detached. */
    public static String currentBranch(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            String branch = git.getRepository().getBranch();
            return branch == null || ObjectId.isId(branch) ? "" : branch;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read branch of " + repo + ": " + e.getMessage(), e);
        }
    }

    /** Plain checkout — deliberately NO reset and NO clean (unlike startBranch), so review can
     *  visit a checkpoint and return the estate exactly as it found it. */
    public static void checkout(Path repo, String branch) {
        try (Git git = Git.open(repo.toFile())) {
            git.checkout().setName(branch).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot checkout " + branch + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }
```

In `RunStore`:

```java
    public Path reviewDir(Path runDir) {
        return runDir.resolve("review");
    }

    /** Gate-2 artifacts (design line 67). File names are sanitized like per-repo directories. */
    public void writeReview(Path runDir, String fileName, String content) {
        try {
            Path dir = Files.createDirectories(reviewDir(runDir));
            Files.writeString(dir.resolve(sanitize(fileName)), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
```

(`sanitize` already exists and maps `[^A-Za-z0-9._-]` to `-`, so `grp/lib.diff` becomes `grp-lib.diff`.)

- [ ] **Step 4: Run — expect PASS.** Run: `./gradlew :sdd-cli:test`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: commit diffs, plain checkout, and review-dir artifacts"
```

---

### Task 2: Contract re-check

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/ContractRecheck.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/ContractRecheckTest.java`

**Interfaces:**
- Produces: `record ContractRecheck.Finding(String contractId, String provider, String kind, Status status, String detail)` with `enum Status { MATCHES, DRIFTED, MISSING_RECORD, NOT_EXTRACTABLE }`, and `static List<Finding> check(PlanModel plan, RunState state, Map<String,Path> repoPaths, RunStore store, Path runDir)`. For every plan contract whose provider repo is SUCCEEDED: re-run `ContractActualizer.actualize(providerRoot, List.of(contract))`; compare the fresh body to `store.readContract(runDir, id)` after normalizing (strip the `# actualized (...)` header line, trim trailing whitespace on every line, drop blank lines) — equal → `MATCHES`; both present but different → `DRIFTED` with a short unified-ish summary naming the first added and first removed line; recorded body absent → `MISSING_RECORD` ("no actualized contract was recorded for this provider"); fresh extraction empty → `NOT_EXTRACTABLE` ("nothing extractable for kind <kind> in <repo>"). Contracts whose provider is not SUCCEEDED are skipped entirely (no Finding).
- Consumes: Task 1's nothing; existing `ContractActualizer`, `RunStore.readContract`, `PlanModel`, `RunState`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractRecheckTest {
    @TempDir Path ws;

    private Path libWith(String source) throws Exception {
        Path root = Files.createDirectories(ws.resolve("lib/src/main/java/com/acme"));
        Files.writeString(root.resolve("Api.java"),
                "package com.acme;\npublic class Api { " + source + " }\n");
        return ws.resolve("lib");
    }

    private static PlanModel plan(PlanModel.PlanContract contract) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(), List.of(contract), List.of());
    }

    private static RunState succeeded() {
        return new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "abc", "ok")), null, 0L);
    }

    @Test
    void matchingActualizationReportsMatches() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null);
        // record exactly what a fresh actualization produces
        store.writeContract(runDir, "c1",
                sdd.cli.implement.ContractActualizer.actualize(lib, List.of(c)).get("c1"));

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.MATCHES);
    }

    @Test
    void driftedTreeReportsDrifted() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null);
        store.writeContract(runDir, "c1",
                sdd.cli.implement.ContractActualizer.actualize(lib, List.of(c)).get("c1"));
        // the tree changes after the run recorded its contract
        Files.writeString(lib.resolve("src/main/java/com/acme/Api.java"),
                "package com.acme;\npublic class Api { public long f(int x) { return x; } }\n");

        List<ContractRecheck.Finding> findings = ContractRecheck.check(plan(c), succeeded(),
                Map.of("lib", lib), store, runDir);

        assertThat(findings.get(0).status()).isEqualTo(ContractRecheck.Status.DRIFTED);
        assertThat(findings.get(0).detail()).contains("long");
    }

    @Test
    void missingRecordAndNonSucceededProvider() throws Exception {
        Path lib = libWith("public int f(int x) { return x; }");
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");
        PlanModel.PlanContract c = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "Api.f", null);   // nothing written to contracts/

        assertThat(ContractRecheck.check(plan(c), succeeded(), Map.of("lib", lib), store, runDir))
                .singleElement()
                .satisfies(f -> assertThat(f.status())
                        .isEqualTo(ContractRecheck.Status.MISSING_RECORD));

        RunState failed = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.FAILED, null, null, "x")), null, 0L);
        assertThat(ContractRecheck.check(plan(c), failed, Map.of("lib", lib), store, runDir)).isEmpty();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.ContractRecheckTest'`

- [ ] **Step 3: Implement `ContractRecheck.java`:**

```java
package sdd.cli.review;

import sdd.cli.implement.ContractActualizer;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gate-2 contract re-check (design line 66): re-extract each green provider's real interface and
 * diff it against the body the run actualized. Mismatches are WARNINGS a human adjudicates — they
 * never fail the review. Providers that did not go green are skipped: nothing was checkpointed.
 */
public final class ContractRecheck {
    public enum Status { MATCHES, DRIFTED, MISSING_RECORD, NOT_EXTRACTABLE }

    public record Finding(String contractId, String provider, String kind, Status status,
                          String detail) {
    }

    private ContractRecheck() {
    }

    public static List<Finding> check(PlanModel plan, RunState state, Map<String, Path> repoPaths,
                                      RunStore store, Path runDir) {
        List<Finding> findings = new ArrayList<>();
        for (PlanModel.PlanContract contract : plan.contracts()) {
            if (state.stateOf(contract.provider()) != RepoState.SUCCEEDED) {
                continue;
            }
            Path root = repoPaths.get(contract.provider());
            if (root == null) {
                continue;
            }
            String fresh = ContractActualizer.actualize(root, List.of(contract)).get(contract.id());
            String recorded = store.readContract(runDir, contract.id());
            if (fresh == null || fresh.isBlank()) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.NOT_EXTRACTABLE,
                        "nothing extractable for kind " + contract.kind() + " in " + contract.provider()));
            } else if (recorded == null) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.MISSING_RECORD,
                        "no actualized contract was recorded for this provider"));
            } else if (normalize(fresh).equals(normalize(recorded))) {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.MATCHES, ""));
            } else {
                findings.add(new Finding(contract.id(), contract.provider(), contract.kind(),
                        Status.DRIFTED, summarize(normalize(recorded), normalize(fresh))));
            }
        }
        return findings;
    }

    /** Header line and blank/trailing whitespace are formatting, not interface content. */
    private static List<String> normalize(String body) {
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            String stripped = line.stripTrailing();
            if (!stripped.isBlank() && !stripped.startsWith("# actualized")) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static String summarize(List<String> recorded, List<String> fresh) {
        String added = fresh.stream().filter(l -> !recorded.contains(l)).findFirst().orElse("");
        String removed = recorded.stream().filter(l -> !fresh.contains(l)).findFirst().orElse("");
        StringBuilder detail = new StringBuilder();
        if (!removed.isEmpty()) {
            detail.append("no longer present: ").append(removed.strip());
        }
        if (!added.isEmpty()) {
            detail.append(detail.isEmpty() ? "" : "; ").append("now present: ").append(added.strip());
        }
        return detail.isEmpty() ? "bodies differ" : detail.toString();
    }
}
```

- [ ] **Step 4: Run — expect PASS.** Run: `./gradlew :sdd-cli:test`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: Gate-2 contract re-check against actualized bodies"
```

---

### Task 3: Estate rebuild + release runbook

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/EstateRebuild.java`, `sdd-cli/src/main/java/sdd/cli/review/ReleaseRunbook.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/EstateRebuildTest.java`, `sdd-cli/src/test/java/sdd/cli/review/ReleaseRunbookTest.java`

**Interfaces:**
- Produces: `EstateRebuild` — instance class, `EstateRebuild()` (15-minute timeout) / `EstateRebuild(Duration)`; `Result verify(Path repoRoot, Path javaHome, List<String> tasks, List<String> extraArgs)` with `record Result(boolean ok, String log)`; runs `./gradlew <task> <extraArgs...> --no-configuration-cache --no-daemon -q` once per task in order, stopping at the first failure, env-scrubbed exactly like `MavenLocalPublisher` (PATH/HOME/LANG/TMPDIR + JAVA_HOME), log shaped `"exit N\n…"` / `"timed out after Ns"` / `"no gradle wrapper in …"`, capped at 200_000 chars, process-tree kill on timeout. `ReleaseRunbook.render(PlanModel plan, RunState state)` → markdown string: an ordered numbered list over `Scheduler.sequence(plan.order())` naming, per repo, its checkpoint sha and the release action implied by its outbound edges — a repo consumed via a PINNED-mode edge gets "release, then merge pinned dependents"; SNAPSHOT/other consumers get "dependents pick up on republish"; a repo with no internal consumers gets "no downstream release step". Repos that are not SUCCEEDED are listed as `— not releasable (<state>)`.
- Consumes: `Scheduler.sequence`, `PlanModel`, `RunState`.

- [ ] **Step 1: Write the failing tests.** `EstateRebuildTest.java`:

```java
package sdd.cli.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EstateRebuildTest {
    @TempDir Path ws;

    private Path repoWith(String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve("lib"));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void runsEveryTaskWithSubstitutionFlagsAndPasses() throws Exception {
        Path repo = repoWith("echo \"$*\" >> calls; exit 0");

        EstateRebuild.Result result = new EstateRebuild().verify(repo, null,
                List.of("compileJava", "check"), List.of("--include-build", "/w/lib"));

        assertThat(result.ok()).isTrue();
        String calls = Files.readString(repo.resolve("calls"));
        assertThat(calls).contains("compileJava --include-build /w/lib").contains("check --include-build");
        assertThat(calls.lines()).hasSize(2);
    }

    @Test
    void stopsAtTheFirstFailingTask() throws Exception {
        Path repo = repoWith("echo \"$*\" >> calls; case \"$1\" in compileJava) exit 1 ;; *) exit 0 ;; esac");

        EstateRebuild.Result result = new EstateRebuild().verify(repo, null,
                List.of("compileJava", "check"), List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).startsWith("exit 1");
        assertThat(Files.readString(repo.resolve("calls")).lines()).hasSize(1);   // check never ran
    }

    @Test
    void missingWrapperFails() {
        EstateRebuild.Result result = new EstateRebuild()
                .verify(ws.resolve("nowhere"), null, List.of("check"), List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("no gradle wrapper");
    }
}
```

`ReleaseRunbookTest.java`:

```java
package sdd.cli.review;

import org.junit.jupiter.api.Test;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseRunbookTest {
    private static PlanModel plan(String mechanismMode) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", mechanismMode, "INCLUDE_BUILD")),
                List.of(), List.of());
    }

    @Test
    void pinnedConsumersRequireAMergeStep() {
        RunState state = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "aaaaaaa1", "ok"),
                new RepoRun("svc", RepoState.SUCCEEDED, "sdd/S-v1/svc", "bbbbbbb2", "ok")), null, 0L);

        String md = ReleaseRunbook.render(plan("PINNED"), state);

        assertThat(md).contains("1. trading").doesNotContain("trading");   // sanity: repos are lib/svc
        assertThat(md).contains("lib").contains("aaaaaaa1").contains("merge pinned dependents");
        assertThat(md).contains("svc").contains("no downstream release step");
    }

    @Test
    void snapshotConsumersPickUpOnRepublishAndFailedReposAreNotReleasable() {
        RunState state = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "aaaaaaa1", "ok"),
                new RepoRun("svc", RepoState.FAILED, null, null, "boom")), null, 0L);

        String md = ReleaseRunbook.render(plan("SNAPSHOT"), state);

        assertThat(md).contains("dependents pick up on republish");
        assertThat(md).contains("not releasable (FAILED)");
    }
}
```

(Drop the nonsensical first assertion in `pinnedConsumersRequireAMergeStep` — write it as `assertThat(md).contains("1. lib")`. The implementer must fix that line rather than copy it verbatim.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.*'`

- [ ] **Step 3: Implement.** `EstateRebuild.java` mirrors `MavenLocalPublisher` (read it first and copy its `scrub`, temp-log, timeout and cap handling verbatim), with `verify` looping the task list:

```java
    public Result verify(Path repoRoot, Path javaHome, List<String> tasks, List<String> extraArgs) {
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, "no gradle wrapper in " + repoRoot);
        }
        String lastLog = "exit 0\n";
        for (String task : tasks) {
            Result single = runTask(repoRoot, javaHome, task, extraArgs);
            lastLog = single.log();
            if (!single.ok()) {
                return single;
            }
        }
        return new Result(true, lastLog);
    }
```

where `runTask` builds `List.of("./gradlew", task)` + `extraArgs` + `--no-configuration-cache --no-daemon -q` and otherwise duplicates `MavenLocalPublisher.publish`'s body.

`ReleaseRunbook.java`:

```java
package sdd.cli.review;

import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.Scheduler;

import java.util.List;

/**
 * The Gate-2 release runbook (design line 66): the order a human releases the estate in, derived
 * from the plan's topological order and each repo's inbound consumers.
 */
public final class ReleaseRunbook {
    private ReleaseRunbook() {
    }

    public static String render(PlanModel plan, RunState state) {
        StringBuilder md = new StringBuilder();
        int step = 1;
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoState repoState = state.stateOf(repo);
            md.append(step++).append(". ").append(repo);
            if (repoState != RepoState.SUCCEEDED) {
                md.append(" — not releasable (").append(repoState == null ? "UNKNOWN" : repoState)
                        .append(")\n");
                continue;
            }
            String sha = state.repos().stream().filter(r -> r.repo().equals(repo)).findFirst()
                    .map(r -> r.checkpointSha() == null ? "" : r.checkpointSha()).orElse("");
            md.append(" — release from ").append(sha.isEmpty() ? "(no checkpoint)" : sha);
            List<String> pinned = plan.edges().stream()
                    .filter(e -> e.toRepo().equals(repo) && "PINNED".equals(e.mode()))
                    .map(PlanModel.PlanEdge::fromRepo).sorted().toList();
            List<String> others = plan.edges().stream()
                    .filter(e -> e.toRepo().equals(repo) && !"PINNED".equals(e.mode()))
                    .map(PlanModel.PlanEdge::fromRepo).sorted().toList();
            if (!pinned.isEmpty()) {
                md.append(", then merge pinned dependents: ").append(String.join(", ", pinned));
            }
            if (!others.isEmpty()) {
                md.append(pinned.isEmpty() ? ", " : "; ").append("dependents pick up on republish: ")
                        .append(String.join(", ", others));
            }
            if (pinned.isEmpty() && others.isEmpty()) {
                md.append(" — no downstream release step");
            }
            md.append('\n');
        }
        return md.toString();
    }
}
```

- [ ] **Step 4: Run — expect PASS.** Run: `./gradlew :sdd-cli:test`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: estate rebuild verifier and release runbook"
```

---

### Task 4: `ReviewCommand` + report renderer

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/ReviewReport.java`, `sdd-cli/src/main/java/sdd/cli/ReviewCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/SddCli.java`
- Test: `sdd-cli/src/test/java/sdd/cli/ReviewCommandTest.java`

**Interfaces:**
- Produces: `ReviewReport.render(...)` taking `(String runId, PlanModel plan, RunState state, Map<String,RunGit.DiffStat> diffStats, Map<String,EstateRebuild.Result> rebuilds, List<String> notLocallyVerified, List<String> restoreFailures, List<ContractRecheck.Finding> contracts, String runbook, boolean rebuilt)` → markdown. Sections, each omitted when empty (CurationReport idiom): title + run id; **Summary** (repo count by state, total tokens, exit-code meaning); **Repos** (one bullet per repo: state, checkpoint sha, diffstat, rebuild verdict — or `not locally verified (all verification tasks excluded)` for repos in `notLocallyVerified` — and detail); **Rebuild failures** (only failing repos, with the log's first 400 chars); **Contract re-check** (only non-MATCHES findings); **Branch restore failures** (only when non-empty — these leave the estate off its original position and need human action); **Release runbook**; trailer with `Instant.now()` and a hint naming the diff files' location. `ReviewCommand`: `@Command(name = "review", exitCodeOnInvalidInput = 4)`, options `--workspace` (default `.`), `--no-rebuild`, positional `<plan.json>` (same `.plan.json` validation as `ImplementCommand`). Behavior: derive `runId` from the CLI-passed plan file, then **load `runDir/plan.json` — the frozen snapshot — for everything else** (contracts, edges, order, base SHAs), exactly as `ImplementCommand --resume` does; a drifted live plan file must not skew the review. Require `<workspace>/.sdd/runs/<runId>/state.json` (else exit 4, `"error: no run to review at <runDir>"`). Repo paths come from the KB `repo` table as in `ImplementCommand`. Per SUCCEEDED repo with a checkpoint, write `review/<repo>.diff` from **`RunGit.diff(root, plan.repo(name).orElseThrow().baseSha(), run.checkpointSha())`** (never live HEAD). Then the contract re-check, then the rebuild unless `--no-rebuild`, then render/write `review/report.md`, print its path.
  **Duplication to copy byte-for-byte from `ImplementCommand` (all reachable via public APIs; `sanitize` is private so it must be reproduced exactly, INCLUDING its blank→`"run"` fallback, or runIds diverge):** `sanitize(specId) + "-v" + planVersion`; effective verification tasks = plan step `verification` ∩ `GradleTool.allowedTasks()`, else `List.of("check")`, minus `config.verificationExclusions().getOrDefault(repo, List.of())`; extraArgs = `Propagation.includeBuildArgs(repo, plan.edges(), paths)` then `Propagation.mavenLocalArgs(plan.edges(), MavenLocalInit.scriptPath(runDir))`; javaHome = `config.jdkHomes().get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)))`.
  **Empty effective task list** (sdd.yml excluded everything) → do NOT call `verify` (it would return ok on zero tasks and the report would claim a clean verification); record the repo as `"not locally verified (all verification tasks excluded)"` and treat it as neither pass nor fail.
  **Exit codes:** `0` when every repo is SUCCEEDED and no rebuild failed; `2` when any repo is not SUCCEEDED or any rebuild/checkout failed; `4` only when no report could be produced. The outer catch returns **4** (`ImplementCommand`'s idiom — NOT `GraphCommand`'s `return 1`, which is outside this taxonomy).
- Consumes: Tasks 1-3.

- [ ] **Step 1: Write the failing e2e test** (`ReviewCommandTest.java`) — build a finished run by hand rather than driving `sdd implement` (faster and hermetic); read `ImplementCommandTest` first for the sdd.yml/spec/plan-json fixture idiom and reuse it:

```java
    @Test
    void reviewProducesReportDiffsAndRunbook() throws Exception {
        // lib: real repo, base commit + a checkpoint commit on the run branch, stub gradlew exit 0
        // KB row for lib; sdd.yml; s.md; s.plan.json (spec_id SPEC-9, one repo, one step, no edges)
        // hand-build the run dir: store.create(...), then state.json via RunStore.writeState with
        // lib SUCCEEDED + its real checkpoint sha
        // ... fixture ...
        int exit = new CommandLine(cmd).execute("--workspace", ws.toString(),
                ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        Path review = ws.resolve(".sdd/runs/SPEC-9-v1/review");
        assertThat(review.resolve("report.md")).exists();
        assertThat(Files.readString(review.resolve("report.md")))
                .contains("SUCCEEDED").contains("Release runbook").contains("lib");
        assertThat(Files.readString(review.resolve("lib.diff"))).contains("A.java");
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(originalBranch);   // restored
    }

    @Test
    void aFailedRepoYieldsExitTwoAndNoRunDirYieldsExitFour() throws Exception {
        // same fixture but state.json marks lib FAILED -> exit 2, report still written
        // and: a workspace whose run dir does not exist -> exit 4, "no run to review"
    }
```

(Write both out fully against the real fixture idiom; the elisions above mark where `ImplementCommandTest`'s scaffolding is copied.)

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.ReviewCommandTest'`

- [ ] **Step 3: Implement** `ReviewReport` (single `StringBuilder`, sections skipped when empty, trailer `"---\n\n_Generated: <Instant>_\n\nPer-repo diffs: <runDir>/review/<repo>.diff\n"`), then `ReviewCommand` following `GraphCommand`'s shape plus `ImplementCommand`'s plan-loading and KB-paths code, then register `ReviewCommand.class` in `SddCli`'s `subcommands` array. The rebuild block must be:

```java
            // Original position per repo: a branch name, or "detached:<sha>" when the user had a
            // detached HEAD (restoring by sha keeps the estate exactly where review found it).
            Map<String, String> originalPositions = new LinkedHashMap<>();
            try {
                for (String repo : Scheduler.sequence(plan.order())) {
                    if (state.stateOf(repo) != RepoState.SUCCEEDED) {
                        continue;
                    }
                    Path root = paths.get(repo);
                    RepoRun run = /* the RepoRun for repo */;
                    if (root == null || run.branch() == null) {
                        continue;
                    }
                    List<String> tasks = tasksFor(repo);
                    if (tasks.isEmpty()) {
                        notLocallyVerified.add(repo);
                        continue;
                    }
                    // A checkout can legitimately fail (uncommitted conflicting changes at review
                    // time). Record it as a failed rebuild and keep going — the report must still
                    // be produced (ratified (c)/(f)).
                    try {
                        String branch = RunGit.currentBranch(root);
                        originalPositions.putIfAbsent(repo,
                                branch.isEmpty() ? "detached:" + RunGit.head(root) : branch);
                        RunGit.checkout(root, run.branch());
                        rebuilds.put(repo, rebuild.verify(root, javaHomeFor(root), tasks,
                                extraArgsFor(repo)));
                    } catch (RuntimeException e) {
                        rebuilds.put(repo, new EstateRebuild.Result(false,
                                "checkout failed: " + e.getMessage()));
                    }
                }
            } finally {
                // One failed restore must not strand the remaining repos on checkpoint branches.
                for (Map.Entry<String, String> entry : originalPositions.entrySet()) {
                    String target = entry.getValue().startsWith("detached:")
                            ? entry.getValue().substring("detached:".length()) : entry.getValue();
                    try {
                        RunGit.checkout(paths.get(entry.getKey()), target);
                    } catch (RuntimeException e) {
                        restoreFailures.add(entry.getKey() + ": " + e.getMessage());
                        err.println("warn: could not restore " + entry.getKey() + " to " + target
                                + ": " + e.getMessage());
                    }
                }
            }
```

`restoreFailures` is rendered as its own report section (omitted when empty), and `notLocallyVerified` repos are shown in the Repos section with that wording rather than a verification verdict. `tasksFor`/`extraArgsFor`/`javaHomeFor` reproduce `ImplementCommand`'s resolutions exactly as listed in the Interfaces block above.

- [ ] **Step 4: Run — expect PASS, then the full build.**
Run: `./gradlew :sdd-cli:test && ./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: sdd review emits the Gate-2 report, diffs, and runbook"
```

---

## Verification

1. `./gradlew build` — all modules green; no existing suite touched beyond the two files Task 1 extends.
2. Design line 66-67 clause coverage: consistency rebuild in topo order with final substitution flags (Task 3 + Task 4 wiring, `--no-rebuild` escape hatch); contract re-check as warnings (Task 2, exit code unaffected); release runbook (Task 3); `review/report.md` with statuses, diffstats, verification results, contract results, token spend and failure codes (Task 4); per-repo `.diff` files (Tasks 1 + 4).
3. Estate safety: every repo the rebuild checks out is restored to its original branch in a `finally`, asserted by the e2e test.
4. Real-estate readiness: two fully-green runs exist (tagged `sdd-green-SPEC-101-v1` and the current checkpoints), so `sdd review` can be exercised against real artifacts immediately after merge.

## Self-Review (completed at write time)

1. **Spec coverage:** design line 66's four deliverables (rebuild, contract re-check, runbook, Gate-2 report + diffs) each map to a task; line 67's interactive flow, invariants, squash-approve and `sdd clean` are explicitly deferred to 5B in Global Constraints.
2. **Placeholder scan:** the only elisions are Task 4's fixture blocks, pinned to `ImplementCommandTest`'s existing scaffolding, plus one deliberately-broken assertion in `ReleaseRunbookTest` that the task text calls out and instructs the implementer to fix (a live check that the tests are read, not pasted).
3. **Type consistency:** `RunGit.DiffStat` (T1) consumed by T4's report; `RunStore.writeReview`/`reviewDir` (T1) consumed by T4; `ContractRecheck.Finding`/`Status` (T2) consumed by T4; `EstateRebuild.Result` and `ReleaseRunbook.render` (T3) consumed by T4. `ContractRecheck.check(PlanModel, RunState, Map, RunStore, Path)` and `EstateRebuild.verify(Path, Path, List, List)` are the exact signatures T4 calls.
4. **Judgment calls for reviewers:** ratified list (a)-(f) — notably rebuild-by-default with restore, contract mismatches as non-fatal warnings, and the 0/2/4 exit taxonomy.
