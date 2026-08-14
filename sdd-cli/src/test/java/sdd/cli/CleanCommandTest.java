package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.review.Decision;
import sdd.cli.review.DecisionRecord;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sdd clean} (design line 21/94): discards the run branches of repos that never got
 * APPROVED, plus the run dir — gated behind {@code --force} since deletion is irreversible.
 *
 * <p>Fixture: three independent repos (lib, svc, aux), each left checked out on its OWN run
 * branch — the realistic post-implement shape, since {@code Orchestrator} never restores it — so
 * every candidate below is automatically also exercising the "currently checked out branch being
 * deleted" case. Each test writes exactly the decisions it needs; {@link #fixture} writes none.
 */
class CleanCommandTest {
    @TempDir Path ws;

    private static final String RUN_ID = "SPEC-9-v1";
    private static final String LIB_BRANCH = "sdd/SPEC-9-v1/lib";
    private static final String SVC_BRANCH = "sdd/SPEC-9-v1/svc";
    private static final String AUX_BRANCH = "sdd/SPEC-9-v1/aux";

    private record Fixture(FixtureRepo lib, FixtureRepo svc, FixtureRepo aux, String libBase,
                           String svcBase, String auxBase, Path planPath, Path runDir) {
    }

    private static FixtureRepo repoOn(Path ws, String name, String branch, String fileName) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file(fileName, "class " + name + " {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), branch, base);
        repo.file(fileName, "class " + name + " { int x; }\n").commit("sdd: checkpoint");
        return repo;   // left checked out on its own run branch — no restore
    }

    private Fixture fixture() throws Exception {
        FixtureRepo lib = repoOn(ws, "lib", LIB_BRANCH, "A.java");
        FixtureRepo svc = repoOn(ws, "svc", SVC_BRANCH, "S.java");
        FixtureRepo aux = repoOn(ws, "aux", AUX_BRANCH, "X.java");

        String libBaseSha = gitBase(lib);
        String svcBaseSha = gitBase(svc);
        String auxBaseSha = gitBase(aux);
        String libCheckpoint = lib.headSha();
        String svcCheckpoint = svc.headSha();
        String auxCheckpoint = aux.headSha();

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')",
                        lib.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', ?, 'SERVICE')",
                        svc.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('aux', ?, 'SERVICE')",
                        aux.path().toString());
            });
        }
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"},
                           {"name":"svc","role":"dependent","annotation":"X","version_action":"patch","base_sha":"%s"},
                           {"name":"aux","role":"dependent","annotation":"X","version_action":"patch","base_sha":"%s"}],
                  "order":[["lib"],["svc"],["aux"]],
                  "edges":[],
                  "contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."},
                           {"repo":"svc","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],
                    "files":["S.java"],"verification":[],"sub_spec":"Add x to S."},
                           {"repo":"aux","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],
                    "files":["X.java"],"verification":[],"sub_spec":"Add x to X."}] }
                """.formatted(libBaseSha, svcBaseSha, auxBaseSha);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, RUN_ID, planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState(RUN_ID, List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, LIB_BRANCH, libCheckpoint, "ok", null),
                new RepoRun("svc", RepoState.SUCCEEDED, SVC_BRANCH, svcCheckpoint, "ok", null),
                new RepoRun("aux", RepoState.SUCCEEDED, AUX_BRANCH, auxCheckpoint, "ok", null)), null, 0L));

        return new Fixture(lib, svc, aux, libBaseSha, svcBaseSha, auxBaseSha, planPath, runDir);
    }

    private static String gitBase(FixtureRepo repo) throws Exception {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo.path().toFile())) {
            // The first commit on the run branch's history — i.e. "base" — resolvable without
            // tracking it separately: it's the sole parent of the checkpoint commit.
            var head = git.getRepository().resolve("HEAD");
            return git.getRepository().parseCommit(head).getParent(0).getName();
        }
    }

    private static void decide(Path runDir, Map<String, DecisionRecord> decisions) {
        RunStore.system().writeDecisions(runDir, decisions);
    }

    private record Invocation(int exit, String out, String err) {
    }

    private static Invocation exec(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new CleanCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute(args);
        return new Invocation(exit, out.toString(), err.toString());
    }

    @Test
    void dryRunListsOnlyTheUnapprovedRepoAndChangesNothing() throws Exception {
        Fixture f = fixture();
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, "wrong API"),
                "aux", new DecisionRecord(Decision.APPROVED, "")));

        Invocation r = exec("--workspace", ws.toString(), f.planPath().toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("would delete (pass --force to apply):")
                .contains("svc").contains(SVC_BRANCH)
                .contains(f.runDir().toString());
        assertThat(r.out()).doesNotContain(LIB_BRANCH).doesNotContain(AUX_BRANCH);

        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isNotEmpty();
        assertThat(f.runDir()).exists();
    }

    @Test
    void forceDeletesTheUnapprovedBranchAndRunDirButNeverAnApprovedOne() throws Exception {
        Fixture f = fixture();
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, "wrong API"),
                "aux", new DecisionRecord(Decision.APPROVED, "")));
        assertThat(RunGit.currentBranch(f.svc().path())).isEqualTo(SVC_BRANCH);   // the checked-out case

        Invocation r = exec("--workspace", ws.toString(), "--force", f.planPath().toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("deleted").contains("svc").contains(SVC_BRANCH)
                .contains("left svc detached at");

        // svc's branch is gone even though it was the currently checked-out branch — the
        // CannotDeleteCurrentBranchException case the brief calls out, handled rather than thrown.
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isEmpty();
        // Both approved repos survive untouched — that work must never be destroyed.
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isNotEmpty();
        assertThat(RunGit.branchHead(f.aux().path(), AUX_BRANCH)).isNotEmpty();
        assertThat(f.runDir()).doesNotExist();
    }

    @Test
    void aFullyApprovedRunKeepsItsRunDirAndSaysSoRatherThanNothingToClean() throws Exception {
        Fixture f = fixture();
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.APPROVED, ""),
                "aux", new DecisionRecord(Decision.APPROVED, "")));

        // The run dir is the only record of what was approved — report, diffs, contracts, runbook,
        // events. Ratified (g)'s "plus the run dir" is about discarding an abandoned run, not
        // shredding the audit trail of a successful one, so it stays; only the wording changes,
        // because "nothing to clean" tells a human there was nothing there.
        Invocation dryRun = exec("--workspace", ws.toString(), f.planPath().toString());
        assertThat(dryRun.exit()).isZero();
        assertThat(dryRun.out()).contains(RUN_ID).contains("fully approved").contains("run dir kept")
                .contains(f.runDir().toString());
        assertThat(dryRun.out()).doesNotContain("nothing to clean");

        Invocation forced = exec("--workspace", ws.toString(), "--force", f.planPath().toString());
        assertThat(forced.exit()).isZero();
        assertThat(forced.out()).contains("fully approved").contains("run dir kept");
        assertThat(forced.out()).doesNotContain("nothing to clean");
        assertThat(f.runDir()).exists();
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
    }

    @Test
    void aBareInvocationWithNoPlanScansEveryRunDirUnderTheWorkspace() throws Exception {
        Fixture f = fixture();
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, ""),
                "aux", new DecisionRecord(Decision.APPROVED, "")));

        Invocation r = exec("--workspace", ws.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("svc").contains(SVC_BRANCH);
        assertThat(r.out()).doesNotContain(LIB_BRANCH).doesNotContain(AUX_BRANCH);
    }

    @Test
    void anExplicitlyNamedPlanWithNoRunDirExitsFour() throws Exception {
        Files.writeString(ws.resolve("nope.plan.json"), """
                { "spec_id":"SPEC-99","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[],"order":[],"edges":[],"contracts":[],"steps":[] }
                """);

        Invocation r = exec("--workspace", ws.toString(), ws.resolve("nope.plan.json").toString());

        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.err()).contains("no run to clean");
    }

    @Test
    void noWorkspaceAtAllIsIdempotentlyNothingToClean() throws Exception {
        Invocation r = exec("--workspace", ws.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("nothing to clean");
    }

    @Test
    void aLiveLockRefusesCleaningEntirelyAndChangesNothingEvenWithForce() throws Exception {
        Fixture f = fixture();
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, ""),
                "aux", new DecisionRecord(Decision.APPROVED, "")));
        // Our own pid is provably alive, so RunStore.isLockHeld sees a live lock rather than a stale one.
        Files.writeString(f.runDir().resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        Invocation dryRun = exec("--workspace", ws.toString(), f.planPath().toString());
        assertThat(dryRun.exit()).isEqualTo(4);
        assertThat(dryRun.err()).contains(RUN_ID).contains("in progress (lock held)");

        Invocation forced = exec("--workspace", ws.toString(), "--force", f.planPath().toString());
        assertThat(forced.exit()).isEqualTo(4);
        assertThat(forced.err()).contains("in progress (lock held)");

        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
        assertThat(f.runDir()).exists();
    }

    /** A second, independent run (different spec id, different repo) alongside {@link #fixture}'s —
     *  its one repo is REJECTED with its KB path already broken, so a {@code --force} pass over it
     *  hits a genuine per-repo failure rather than succeeding. */
    private Path secondRunWithARealPerRepoFailure() throws Exception {
        String branch = "sdd/SPEC-10-v1/other";
        FixtureRepo other = FixtureRepo.in(ws, "other").file("O.java", "class O {}\n").commit("base");
        String otherBase = other.headSha();
        RunGit.startBranch(other.path(), branch, otherBase);
        other.file("O.java", "class O { int x; }\n").commit("sdd: checkpoint");
        String otherCheckpoint = other.headSha();

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute("INSERT INTO repo(name, path, kind) VALUES ('other', ?, 'SERVICE')",
                    other.path().toString()));
        }
        String planJson = """
                { "spec_id":"SPEC-10","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"other","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["other"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"other","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["O.java"],"verification":[],"sub_spec":"Add x to O."}] }
                """.formatted(otherBase);
        Files.writeString(ws.resolve("s2.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-10-v1", planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-10-v1",
                List.of(new RepoRun("other", RepoState.SUCCEEDED, branch, otherCheckpoint, "ok", null)), null, 0L));
        decide(runDir, Map.of("other", new DecisionRecord(Decision.REJECTED, "")));

        // Broken AFTER decisions are written, so repoPaths() — built once per invocation — simply
        // never contains 'other', tripping the real "no repo path on record" failure in cleanOne.
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute("DELETE FROM repo WHERE name = 'other'"));
        }
        return runDir;
    }

    @Test
    void aLockedRunExitsFourButStillReportsAnUnlockedSiblingsRealFailure() throws Exception {
        Fixture f = fixture();
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, ""),
                "aux", new DecisionRecord(Decision.APPROVED, "")));
        Files.writeString(f.runDir().resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        Path otherRunDir = secondRunWithARealPerRepoFailure();

        Invocation r = exec("--workspace", ws.toString(), "--force");

        // The lock wins the exit code...
        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.err()).contains(RUN_ID).contains("in progress (lock held)");
        // ...but the unlocked sibling's OWN genuine failure must still reach the human, not be
        // silently discarded just because some other run in the same invocation was locked.
        assertThat(r.err()).contains("failed:").contains("other");
        // Neither run was actually mutated: the locked one was never even read, and the unlocked
        // one's failure blocked its own branch delete and run dir removal.
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
        assertThat(f.runDir()).exists();
        assertThat(otherRunDir).exists();
    }

    @Test
    void perRepoIsolationLetsASiblingCandidateSucceedAndKeepsTheRunDirOnFailure() throws Exception {
        Fixture f = fixture();
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, ""),
                "aux", new DecisionRecord(Decision.REJECTED, "")));
        // Break svc's KB path so its branch delete cannot even find the repo, while aux — the OTHER
        // non-approved candidate — has a perfectly good path and must still get deleted: proof that
        // one repo's failure does not strand a sibling candidate in the SAME run.
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute("DELETE FROM repo WHERE name = 'svc'"));
        }

        Invocation r = exec("--workspace", ws.toString(), "--force", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("failed:").contains("svc");

        // aux — the sibling candidate — was deleted despite svc's failure.
        assertThat(RunGit.branchHead(f.aux().path(), AUX_BRANCH)).isEmpty();
        // svc itself was never touched (we broke its path before we could even try).
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
        // lib (approved) is, as always, untouched.
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isNotEmpty();
        // The run dir survives — the property that prevents an orphaned, un-cleaned-up svc branch
        // from ever being forgotten: a partial failure must leave the run findable for a retry.
        assertThat(f.runDir()).exists();
    }

    @Test
    void aCorruptDecisionTokenProtectsItsBranchAndTheRunDirEvenNextToAValidApproval() throws Exception {
        Fixture f = fixture();
        // lib: a genuine, valid approval. svc: hand-edited/future-versioned garbage in place of a
        // real Decision token. aux: never recorded at all (absent key) — genuinely PENDING, and
        // legitimately eligible for deletion; this is what tells "never reviewed" apart from
        // "corrupted" in the same run.
        Files.createDirectories(f.runDir().resolve("review"));
        Files.writeString(f.runDir().resolve("review/decisions.json"), """
                { "lib": { "decision": "APPROVED", "reason": "" },
                  "svc": { "decision": "BOGUS_FUTURE_TOKEN", "reason": "" } }
                """);

        Invocation r = exec("--workspace", ws.toString(), "--force", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("svc").contains("could not be parsed");

        // The malformed-token repo is untouched — never deleted, regardless of what it might
        // secretly have meant.
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
        // The validly-approved repo is (as always) untouched.
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isNotEmpty();
        // The genuinely-never-reviewed repo is legitimately eligible and gets deleted — proving the
        // corrupted case is handled DIFFERENTLY from a plain absent/PENDING entry, not just
        // conservatively lumped in with it.
        assertThat(RunGit.branchHead(f.aux().path(), AUX_BRANCH)).isEmpty();
        // The run dir survives: it is not fully cleaned while svc's status is still ambiguous.
        assertThat(f.runDir()).exists();
    }

    @Test
    void aStateJsonNamingABranchOutsideThisRunIsRefusedRatherThanObeyed() throws Exception {
        Fixture f = fixture();
        // Ruling 8 hardened decisions.json against hand edits; state.json had no equivalent guard,
        // and this is the one genuinely irreversible command in the phase. A corrupted or
        // hand-edited record naming "main" would have had --force delete main.
        RunStore.system().writeState(f.runDir(), new RunState(RUN_ID, List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, LIB_BRANCH, f.lib().headSha(), "ok", null),
                new RepoRun("svc", RepoState.SUCCEEDED, "main", f.svc().headSha(), "ok", null),
                new RepoRun("aux", RepoState.SUCCEEDED, AUX_BRANCH, f.aux().headSha(), "ok", null))
                , null, 0L));
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, ""),
                "aux", new DecisionRecord(Decision.REJECTED, "")));

        Invocation dryRun = exec("--workspace", ws.toString(), f.planPath().toString());
        assertThat(dryRun.exit()).isEqualTo(2);
        assertThat(dryRun.err()).contains("svc").contains("refusing to delete");
        assertThat(dryRun.out()).doesNotContain("  svc  main");

        Invocation forced = exec("--workspace", ws.toString(), "--force", f.planPath().toString());

        assertThat(forced.exit()).isEqualTo(2);
        assertThat(forced.err()).contains("svc").contains("refusing to delete").contains("main");
        // main is intact — the whole point.
        assertThat(RunGit.branchHead(f.svc().path(), "main")).isNotEmpty();
        // aux, whose record names a genuine run branch, is still deleted: one repo's refusal must
        // not strand its siblings.
        assertThat(RunGit.branchHead(f.aux().path(), AUX_BRANCH)).isEmpty();
        // Something was skipped, so the run stays findable — same shape as Ruling 8's unparseable
        // decision token.
        assertThat(f.runDir()).exists();
    }

    @Test
    void neverReviewedRepoIsWarnedAboutBeforeBeingDeleted() throws Exception {
        Fixture f = fixture();
        // lib: approved. svc: explicitly rejected. aux: no entry at all — never reviewed.
        decide(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, "")));

        Invocation dryRun = exec("--workspace", ws.toString(), f.planPath().toString());
        assertThat(dryRun.out()).contains("warning:").contains("never-reviewed").contains("aux");

        Invocation forced = exec("--workspace", ws.toString(), "--force", f.planPath().toString());
        assertThat(forced.out()).contains("warning:").contains("never-reviewed").contains("aux");
        assertThat(RunGit.branchHead(f.aux().path(), AUX_BRANCH)).isEmpty();   // legitimately eligible
    }
}
