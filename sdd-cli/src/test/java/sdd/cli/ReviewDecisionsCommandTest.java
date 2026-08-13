package sdd.cli;

import org.eclipse.jgit.api.Git;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Gate-2 decision commands end-to-end through picocli — including the two parse-level
 * regressions the subcommands introduce (a parent positional that must no longer be required, and
 * a {@code --workspace} that must be inherited rather than silently defaulted).
 */
class ReviewDecisionsCommandTest {
    private static final String RUN_ID = "SPEC-9-v1";
    private static final String LIB_BRANCH = "sdd/SPEC-9-v1/lib";
    private static final String SVC_BRANCH = "sdd/SPEC-9-v1/svc";

    @TempDir Path ws;

    /** A two-repo estate: svc consumes lib, both SUCCEEDED, lib's run branch carrying TWO
     *  checkpoint commits (so approve has something real to squash) and svc's exactly one (so the
     *  nothing-to-squash branch is exercised too). */
    private record Fixture(FixtureRepo lib, FixtureRepo svc, String libBase, String svcBase,
                           String libCheckpoint, String svcCheckpoint, String originalBranch,
                           Path planPath, Path runDir) {
    }

    private Fixture fixture() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());   // "main" — jgit's default init branch
        RunGit.startBranch(lib.path(), LIB_BRANCH, libBase);
        lib.file("A.java", "class A { int x; }\n").commit("sdd: checkpoint 1");
        lib.file("B.java", "class B {}\n").commit("sdd: checkpoint 2");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);   // as if the user returned it there after implement

        FixtureRepo svc = FixtureRepo.in(ws, "svc").file("S.java", "class S {}\n");
        gradlewStub(svc);
        svc.commit("base");
        String svcBase = svc.headSha();
        RunGit.startBranch(svc.path(), SVC_BRANCH, svcBase);
        svc.file("S.java", "class S { int y; }\n").commit("sdd: checkpoint");
        String svcCheckpoint = svc.headSha();
        RunGit.checkout(svc.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')",
                        lib.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', ?, 'SERVICE')",
                        svc.path().toString());
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
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"},
                           {"name":"svc","role":"dependent","annotation":"X","version_action":"patch","base_sha":"%s"}],
                  "order":[["lib"],["svc"]],
                  "edges":[{"from_repo":"svc","to_repo":"lib","mode":"SNAPSHOT","mechanism":"INCLUDE_BUILD"}],
                  "contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."},
                           {"repo":"svc","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],
                    "files":["S.java"],"verification":[],"sub_spec":"Add y to S."}] }
                """.formatted(specSha, libBase, svcBase);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, RUN_ID, planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);   // mirrors real state: implement's finally already released it
        store.writeState(runDir, new RunState(RUN_ID, List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, LIB_BRANCH, libCheckpoint, "ok"),
                new RepoRun("svc", RepoState.SUCCEEDED, SVC_BRANCH, svcCheckpoint, "ok")), null, 21L));

        return new Fixture(lib, svc, libBase, svcBase, libCheckpoint, svcCheckpoint, originalBranch,
                planPath, runDir);
    }

    private static void gradlewStub(FixtureRepo repo) throws Exception {
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
    }

    private record Invocation(int exit, String out, String err) {
    }

    private static Invocation exec(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new ReviewCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute(args);
        return new Invocation(exit, out.toString(), err.toString());
    }

    /** Counts against the branch ref rather than HEAD, so asserting it cannot disturb (or silently
     *  repair) the working checkout the test is also asserting about. */
    private static int commitsOnBranch(Path repo, String base, String branch) throws Exception {
        try (Git git = Git.open(repo.toFile())) {
            var from = git.getRepository().resolve(base);
            var to = git.getRepository().resolve("refs/heads/" + branch);
            int n = 0;
            for (var ignored : git.log().addRange(from, to).call()) {
                n++;
            }
            return n;
        }
    }

    private static String checkpointOf(RunState state, String repo) {
        return state.repos().stream().filter(r -> r.repo().equals(repo)).findFirst()
                .orElseThrow().checkpointSha();
    }

    @Test
    void approveSquashesTheRunBranchAndRewritesTheRecordedCheckpoint() throws Exception {
        Fixture f = fixture();

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("lib approved").contains("squashed 2 commits into ")
                .contains("review written:");

        // (a) the decision is on disk, and a FRESH store reads it back — resumability (d).
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("lib").decision()).isEqualTo(Decision.APPROVED);
        assertThat(persisted).doesNotContainKey("svc");   // untouched repos stay implicitly PENDING

        // (b) the run branch collapsed to the one commit a human reviewed.
        String newHead = RunGit.branchHead(f.lib().path(), LIB_BRANCH);
        assertThat(newHead).isNotEqualTo(f.libCheckpoint());
        assertThat(commitsOnBranch(f.lib().path(), f.libBase(), LIB_BRANCH)).isEqualTo(1);

        // (c) state.json now records the squashed sha. Without this, Resume.prepare would fail the
        // repo (branchHead != checkpointSha) and the retry redo prints would hard-fail.
        RunState reloaded = RunStore.system().readState(f.runDir());
        assertThat(checkpointOf(reloaded, "lib")).isEqualTo(newHead);
        assertThat(checkpointOf(reloaded, "svc")).isEqualTo(f.svcCheckpoint());   // sibling untouched
        assertThat(reloaded.tokensSpent()).isEqualTo(21L);                        // rest of the state intact

        // (d) the estate is exactly where the human left it, and the report was refreshed.
        assertThat(RunGit.currentBranch(f.lib().path())).isEqualTo(f.originalBranch());
        assertThat(RunGit.currentBranch(f.svc().path())).isEqualTo(f.originalBranch());
        assertThat(f.runDir().resolve("review/report.md")).exists();
        assertThat(Files.readString(f.runDir().resolve("events.jsonl")))
                .contains("APPROVED").contains("lib");
    }

    @Test
    void aRejectedUpstreamRefusesItsDownstreamApprovalWithExitTwo() throws Exception {
        Fixture f = fixture();

        Invocation rejected = exec("--workspace", ws.toString(), "reject", "lib",
                f.planPath().toString(), "--reason", "wrong API");
        assertThat(rejected.exit()).isZero();
        assertThat(rejected.out()).contains("lib rejected");

        Invocation refused = exec("--workspace", ws.toString(), "approve", "svc",
                f.planPath().toString());

        assertThat(refused.exit()).isEqualTo(2);
        assertThat(refused.err()).contains("svc").contains("lib").contains("REJECTED");

        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("lib").decision()).isEqualTo(Decision.REJECTED);
        assertThat(persisted.get("lib").reason()).isEqualTo("wrong API");
        assertThat(persisted).doesNotContainKey("svc");   // the refusal recorded nothing

        // Neither repo was touched: reject never squashes, and a refused approve is a no-op.
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isEqualTo(f.libCheckpoint());
        assertThat(RunGit.branchHead(f.svc().path(), SVC_BRANCH)).isEqualTo(f.svcCheckpoint());
        assertThat(commitsOnBranch(f.lib().path(), f.libBase(), LIB_BRANCH)).isEqualTo(2);
    }

    @Test
    void redoDowngradesApprovedDownstreamAndPrintsTheRetryCommand() throws Exception {
        Fixture f = fixture();
        assertThat(exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString()).exit())
                .isZero();
        Invocation approvedSvc = exec("--workspace", ws.toString(), "approve", "svc",
                f.planPath().toString());
        assertThat(approvedSvc.exit()).isZero();
        // svc's run branch is a single commit past base — approve applies, the squash is a no-op.
        assertThat(approvedSvc.out()).contains("svc is already a single commit past");

        Invocation redo = exec("--workspace", ws.toString(), "redo", "lib", f.planPath().toString(),
                "--reason", "needs rework", "--no-reverify");

        assertThat(redo.exit()).isZero();
        assertThat(redo.out()).contains("lib marked for redo")
                .contains("downgraded to PENDING (re-decide): svc")
                .contains("then run: sdd implement --workspace " + ws + " --retry lib "
                        + f.planPath());

        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("lib").decision()).isEqualTo(Decision.REDO);
        assertThat(persisted.get("lib").reason()).isEqualTo("needs rework");
        assertThat(persisted.get("svc").decision()).isEqualTo(Decision.PENDING);   // re-decide, not rejected
    }

    @Test
    void aLiveLockRefusesTheDecisionWithExitFourAndChangesNothing() throws Exception {
        Fixture f = fixture();
        // Our own pid is provably alive, so RunStore.isLockHeld sees a live lock rather than a stale one.
        Files.writeString(f.runDir().resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.err()).contains(RUN_ID).contains("lock held");
        assertThat(f.runDir().resolve("review/decisions.json")).doesNotExist();
        assertThat(RunGit.branchHead(f.lib().path(), LIB_BRANCH)).isEqualTo(f.libCheckpoint());
        assertThat(commitsOnBranch(f.lib().path(), f.libBase(), LIB_BRANCH)).isEqualTo(2);
    }

    @Test
    void theWorkspaceOptionIsInheritedAndTheParentPositionalIsNoLongerRequired() throws Exception {
        Fixture f = fixture();

        // Regression 1: picocli validates the PARENT's required positionals before recursing into a
        // subcommand, so a required <planJsonPath> on ReviewCommand made every subcommand invocation
        // die with "Missing required parameter" and exit 4 — indistinguishable from bad input.
        // Regression 2: picocli does not inherit parent options; without scope=INHERIT the
        // subcommand would silently read the CURRENT directory instead of --workspace.
        Invocation r = exec("--workspace", ws.toString(), "approve", "lib", f.planPath().toString());

        assertThat(r.err()).doesNotContain("Missing required parameter");
        assertThat(r.exit()).isZero();
        assertThat(f.runDir().resolve("review/decisions.json")).exists();   // written under THAT workspace

        // An inherited option binds to the parent's field wherever it appears, so the other order
        // a human will type has to reach the same workspace.
        Invocation after = exec("reject", "svc", f.planPath().toString(), "--workspace", ws.toString());
        assertThat(after.exit()).isZero();
        assertThat(RunStore.system().readDecisions(f.runDir()).get("svc").decision())
                .isEqualTo(Decision.REJECTED);

        // A genuinely missing plan argument still reports itself clearly, on both commands.
        Invocation bare = exec();
        assertThat(bare.exit()).isEqualTo(4);
        assertThat(bare.err()).contains("missing <spec>.plan.json");

        Invocation noPlan = exec("--workspace", ws.toString(), "approve", "lib");
        assertThat(noPlan.exit()).isEqualTo(4);
        assertThat(noPlan.err()).contains("missing <spec>.plan.json");

        // The real invocation path is one level deeper than the tests above — "sdd review approve"
        // makes ReviewCommand both a subcommand and a parent, which is where @ParentCommand
        // injection and an inherited option could plausibly diverge from the two-level case.
        StringWriter rootOut = new StringWriter();
        CommandLine root = new CommandLine(new SddCli());
        root.setOut(new PrintWriter(rootOut));
        int rootExit = root.execute("review", "--workspace", ws.toString(), "approve", "lib",
                f.planPath().toString());

        assertThat(rootExit).isZero();
        // Re-approving is idempotent: the branch is already the single squashed commit, so nothing
        // is re-squashed and the recorded checkpoint stays put.
        assertThat(rootOut.toString()).contains("lib approved")
                .contains("lib is already a single commit past");
        assertThat(commitsOnBranch(f.lib().path(), f.libBase(), LIB_BRANCH)).isEqualTo(1);
    }

    @Test
    void plainReviewStillBehavesExactlyAsBefore() throws Exception {
        Fixture f = fixture();

        Invocation r = exec("--workspace", ws.toString(), f.planPath().toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("review written:");
        String report = Files.readString(f.runDir().resolve("review/report.md"));
        assertThat(report).contains("SUCCEEDED").contains("Release runbook").contains("lib").contains("svc");
        assertThat(Files.readString(f.runDir().resolve("review/lib.diff"))).contains("A.java");
        assertThat(RunGit.currentBranch(f.lib().path())).isEqualTo(f.originalBranch());
        assertThat(RunGit.currentBranch(f.svc().path())).isEqualTo(f.originalBranch());
        // No decision was made, so nothing decision-shaped exists yet.
        assertThat(f.runDir().resolve("review/decisions.json")).doesNotExist();
    }
}
