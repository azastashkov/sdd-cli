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
 */
class CleanCommandTest {
    @TempDir Path ws;

    private static final String RUN_ID = "SPEC-9-v1";
    private static final String LIB_BRANCH = "sdd/SPEC-9-v1/lib";
    private static final String SVC_BRANCH = "sdd/SPEC-9-v1/svc";

    private record Fixture(FixtureRepo lib, FixtureRepo svc, String libBase, String svcBase,
                           Path planPath, Path runDir) {
    }

    /** lib is APPROVED and left back on its original branch; svc is REJECTED and left checked out
     *  ON its own run branch — the realistic post-implement shape (Orchestrator never restores a
     *  repo's original branch), and exactly the "currently checked out branch being deleted" case
     *  the brief calls out. */
    private Fixture fixture() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n").commit("base");
        String libBase = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());
        RunGit.startBranch(lib.path(), LIB_BRANCH, libBase);
        lib.file("A.java", "class A { int x; }\n").commit("sdd: checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);

        FixtureRepo svc = FixtureRepo.in(ws, "svc").file("S.java", "class S {}\n").commit("base");
        String svcBase = svc.headSha();
        RunGit.startBranch(svc.path(), SVC_BRANCH, svcBase);
        svc.file("S.java", "class S { int y; }\n").commit("sdd: checkpoint");
        String svcCheckpoint = svc.headSha();
        // left checked out on SVC_BRANCH deliberately

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')",
                        lib.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', ?, 'SERVICE')",
                        svc.path().toString());
            });
        }
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"},
                           {"name":"svc","role":"dependent","annotation":"X","version_action":"patch","base_sha":"%s"}],
                  "order":[["lib"],["svc"]],
                  "edges":[{"from_repo":"svc","to_repo":"lib","mode":"SNAPSHOT","mechanism":"INCLUDE_BUILD"}],
                  "contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."},
                           {"repo":"svc","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],
                    "files":["S.java"],"verification":[],"sub_spec":"Add y to S."}] }
                """.formatted(libBase, svcBase);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, RUN_ID, planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState(RUN_ID, List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, LIB_BRANCH, libCheckpoint, "ok"),
                new RepoRun("svc", RepoState.SUCCEEDED, SVC_BRANCH, svcCheckpoint, "ok")), null, 0L));
        store.writeDecisions(runDir, Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, "wrong API")));

        return new Fixture(lib, svc, libBase, svcBase, planPath, runDir);
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

        Invocation r = exec("--workspace", ws.toString(), f.planPath().toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("would delete (pass --force to apply):")
                .contains("svc").contains(SVC_BRANCH)
                .contains(f.runDir().toString());
        assertThat(r.out()).doesNotContain(LIB_BRANCH);

        // Nothing on disk moved.
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isNotEmpty();
        assertThat(f.runDir()).exists();
    }

    @Test
    void forceDeletesTheUnapprovedBranchAndRunDirButNeverTheApprovedOne() throws Exception {
        Fixture f = fixture();
        assertThat(RunGit.currentBranch(f.svc().path())).isEqualTo(SVC_BRANCH);   // the checked-out case

        Invocation r = exec("--workspace", ws.toString(), "--force", f.planPath().toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("deleted").contains("svc").contains(SVC_BRANCH);

        // svc's branch is gone even though it was the currently checked-out branch — the
        // CannotDeleteCurrentBranchException case the brief calls out, handled rather than thrown.
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isEmpty();
        // lib's branch survives untouched — that work must never be destroyed.
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isNotEmpty();
        assertThat(f.runDir()).doesNotExist();
    }

    @Test
    void aRunWhereEverySingleRepoIsApprovedHasNothingToCleanAndKeepsTheRunDir() throws Exception {
        Fixture f = fixture();
        RunStore.system().writeDecisions(f.runDir(), Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.APPROVED, "")));

        Invocation dryRun = exec("--workspace", ws.toString(), f.planPath().toString());
        assertThat(dryRun.exit()).isZero();
        assertThat(dryRun.out()).contains("nothing to clean");

        Invocation forced = exec("--workspace", ws.toString(), "--force", f.planPath().toString());
        assertThat(forced.exit()).isZero();
        assertThat(forced.out()).contains("nothing to clean");
        assertThat(f.runDir()).exists();   // an all-approved run is never touched
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isNotEmpty();
    }

    @Test
    void aBareInvocationWithNoPlanScansEveryRunDirUnderTheWorkspace() throws Exception {
        Fixture f = fixture();

        Invocation r = exec("--workspace", ws.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("svc").contains(SVC_BRANCH);
        assertThat(r.out()).doesNotContain(LIB_BRANCH);
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
    void aRepoWithNoPathOnRecordFailsInIsolationAndStillExitsTwoNamingIt() throws Exception {
        Fixture f = fixture();
        // Break svc's KB path so its branch delete cannot even find the repo, while lib remains
        // untouched — proves per-repo isolation: one repo's failure is reported, not fatal to the
        // command, and never strands a sibling run.
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute("DELETE FROM repo WHERE name = 'svc'"));
        }

        Invocation r = exec("--workspace", ws.toString(), "--force", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("svc");
        // lib was never a candidate at all, so it is certainly untouched.
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isNotEmpty();
    }
}
