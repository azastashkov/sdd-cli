package sdd.cli.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The human terminal half of Gate 2 (design line 67): a scripted {@link BufferedReader} stands in
 * for a person typing at a prompt, so the whole walk is exercised without a real terminal.
 */
class InteractiveReviewTest {
    @TempDir Path ws;

    private static final String RUN_ID = "SPEC-9-v1";
    private static final String A_BRANCH = "sdd/SPEC-9-v1/a";
    private static final String B_BRANCH = "sdd/SPEC-9-v1/b";
    private static final String C_BRANCH = "sdd/SPEC-9-v1/c";

    private record Fixture(FixtureRepo a, FixtureRepo b, FixtureRepo c, String aBase,
                           Path planPath, Path runDir) {
    }

    private static void gradlewStub(FixtureRepo repo) throws Exception {
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
    }

    /** Three repos, all SUCCEEDED, all PENDING going in. {@code b} consumes {@code a} (one edge) so
     *  a redo on {@code a} has a real downstream subtree to re-verify and downgrade. {@code a}
     *  carries TWO checkpoint commits so an interactive approve has something real to squash —
     *  proving the loop reuses DecisionCommand's squash follow-up rather than just flipping the
     *  decision. */
    private Fixture fixture() throws Exception {
        FixtureRepo a = FixtureRepo.in(ws, "a").file("A.java", "class A {}\n");
        gradlewStub(a);
        a.commit("base");
        String aBase = a.headSha();
        String originalBranch = RunGit.currentBranch(a.path());
        RunGit.startBranch(a.path(), A_BRANCH, aBase);
        a.file("A.java", "class A { int x; }\n").commit("sdd: checkpoint 1");
        a.file("A2.java", "class A2 {}\n").commit("sdd: checkpoint 2");
        String aCheckpoint = a.headSha();
        RunGit.checkout(a.path(), originalBranch);

        FixtureRepo b = FixtureRepo.in(ws, "b").file("B.java", "class B {}\n");
        gradlewStub(b);
        b.commit("base");
        String bBase = b.headSha();
        RunGit.startBranch(b.path(), B_BRANCH, bBase);
        b.file("B.java", "class B { int y; }\n").commit("sdd: checkpoint");
        String bCheckpoint = b.headSha();
        RunGit.checkout(b.path(), originalBranch);

        FixtureRepo c = FixtureRepo.in(ws, "c").file("C.java", "class C {}\n");
        gradlewStub(c);
        c.commit("base");
        String cBase = c.headSha();
        RunGit.startBranch(c.path(), C_BRANCH, cBase);
        c.file("C.java", "class C { int z; }\n").commit("sdd: checkpoint");
        String cCheckpoint = c.headSha();
        RunGit.checkout(c.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('a', ?, 'LIBRARY')", a.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('b', ?, 'LIBRARY')", b.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('c', ?, 'LIBRARY')", c.path().toString());
            });
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"a","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"},
                           {"name":"b","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"},
                           {"name":"c","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["a"],["b"],["c"]],
                  "edges":[{"from_repo":"b","to_repo":"a","mode":"SNAPSHOT","mechanism":"INCLUDE_BUILD"}],
                  "contracts":[],
                  "steps":[{"repo":"a","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."},
                           {"repo":"b","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["B.java"],"verification":[],"sub_spec":"Add y to B."},
                           {"repo":"c","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["C.java"],"verification":[],"sub_spec":"Add z to C."}] }
                """.formatted(aBase, bBase, cBase);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, RUN_ID, planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState(RUN_ID, List.of(
                new RepoRun("a", RepoState.SUCCEEDED, A_BRANCH, aCheckpoint, "ok", null),
                new RepoRun("b", RepoState.SUCCEEDED, B_BRANCH, bCheckpoint, "ok", null),
                new RepoRun("c", RepoState.SUCCEEDED, C_BRANCH, cCheckpoint, "ok", null)), null, 12L));

        return new Fixture(a, b, c, aBase, planPath, runDir);
    }

    private static int commitsOnBranch(Path repo, String base, String branch) throws Exception {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(repo.toFile())) {
            var from = git.getRepository().resolve(base);
            var to = git.getRepository().resolve("refs/heads/" + branch);
            int n = 0;
            for (var ignored : git.log().addRange(from, to).call()) {
                n++;
            }
            return n;
        }
    }

    private InteractiveReview.Context context(Fixture f) throws Exception {
        return context(f, new RebuildPass.Outcome(Map.of(), List.of(), List.of(), List.of(), List.of()),
                RebuildScope.none());
    }

    private InteractiveReview.Context context(Fixture f, RebuildPass.Outcome baseline,
                                              RebuildScope baselineScope) throws Exception {
        RunContext run = RunContext.load(ws, f.planPath(), new PrintWriter(new StringWriter()));
        assertThat(run).isNotNull();
        run.collectDiffs();
        return new InteractiveReview.Context(run, ws, f.planPath(), baseline, baselineScope);
    }

    @Test
    void walksInScheduleOrderApprovingRejectingAndQuittingPersistsAfterEachStep() throws Exception {
        Fixture f = fixture();
        InteractiveReview.Context ctx = context(f);

        // a: approve. b: reject, with a reason (the reason prompt is a second line). c: quit.
        BufferedReader in = new BufferedReader(new StringReader("a\nr\nwrong API\nq\n"));
        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();

        int exit = InteractiveReview.run(in, new PrintWriter(outSw), new PrintWriter(errSw), ctx);

        assertThat(exit).isZero();
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("a").decision()).isEqualTo(Decision.APPROVED);
        assertThat(persisted.get("b").decision()).isEqualTo(Decision.REJECTED);
        assertThat(persisted.get("b").reason()).isEqualTo("wrong API");
        assertThat(persisted).doesNotContainKey("c");   // 'q' left it implicitly PENDING

        // The run branch actually got squashed — proof the loop reused DecisionCommand's follow-up
        // rather than only flipping the decision.
        assertThat(commitsOnBranch(f.a().path(), f.aBase(), A_BRANCH)).isEqualTo(1);
        assertThat(RunGit.currentBranch(f.a().path())).isNotEqualTo(A_BRANCH);   // restored

        // report.md was re-rendered when the loop ended (even though it ended via 'q').
        assertThat(outSw.toString()).contains("review written:");
        assertThat(f.runDir().resolve("review/report.md")).exists();

        // Both decisions reached events.jsonl, not just decisions.json — asserted against the
        // single record (repo + from + to together) so a crossed decision between a and b would
        // fail this rather than pass on two independently-true substrings.
        String events = Files.readString(f.runDir().resolve("events.jsonl"));
        assertThat(events).contains("\"repo\":\"a\",\"from\":\"PENDING\",\"to\":\"APPROVED\"");
        assertThat(events).contains("\"repo\":\"b\",\"from\":\"PENDING\",\"to\":\"REJECTED\"");
    }

    @Test
    void persistsAfterEveryDecisionNotOnlyAtTheEndOfTheScript() throws Exception {
        Fixture f = fixture();
        InteractiveReview.Context ctx = context(f);

        // A reader that yields exactly one line ("a") and then blows up on the NEXT read — standing
        // in for a crash immediately after the first decision, before the loop ever reaches 'b'.
        BufferedReader crashing = new BufferedReader(new StringReader("a\n")) {
            private boolean served;

            @Override
            public String readLine() throws IOException {
                if (!served) {
                    served = true;
                    return super.readLine();
                }
                throw new IOException("simulated crash");
            }
        };

        assertThatThrownBy(() -> InteractiveReview.run(crashing, new PrintWriter(new StringWriter()),
                new PrintWriter(new StringWriter()), ctx))
                .isInstanceOf(IOException.class);

        // Despite the crash, repo a's decision already made it to disk — a FRESH store proves it,
        // not the in-memory Decisions object the (aborted) loop was holding.
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("a").decision()).isEqualTo(Decision.APPROVED);
    }

    @Test
    void viewPrintsTheDiffAndSkipLeavesTheRepoPendingWithNoReRender() throws Exception {
        Fixture f = fixture();
        InteractiveReview.Context ctx = context(f);

        // a: view then skip; b: skip; c: skip.
        BufferedReader in = new BufferedReader(new StringReader("v\ns\ns\ns\n"));
        StringWriter outSw = new StringWriter();

        int exit = InteractiveReview.run(in, new PrintWriter(outSw), new PrintWriter(new StringWriter()), ctx);

        assertThat(exit).isZero();
        assertThat(outSw.toString()).contains("A.java");   // the diff content for repo a
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted).isEmpty();   // every repo skipped, all still implicitly PENDING
        // Nothing was decided, so the report must not have been touched at all.
        assertThat(f.runDir().resolve("review/report.md")).doesNotExist();
        assertThat(outSw.toString()).doesNotContain("review written:");
    }

    @Test
    void redoReVerifiesTheDownstreamSubtreeAndDowngradesAnEarlierApproval() throws Exception {
        Fixture f = fixture();

        // Session 1: leave a PENDING (skip), approve b (allowed — a is only PENDING, not
        // REJECTED/REDO), skip c.
        InteractiveReview.run(new BufferedReader(new StringReader("s\na\ns\n")),
                new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), context(f));
        assertThat(RunStore.system().readDecisions(f.runDir()).get("b").decision())
                .isEqualTo(Decision.APPROVED);

        // Session 2 (a fresh walk, as a human re-running --interactive would do): a is still
        // PENDING, so it's visited again. Redo it, with a reason. b, downstream of a, gets
        // downgraded back to PENDING mid-walk and is re-prompted (skip); c stays skipped too.
        StringWriter outSw = new StringWriter();
        int exit = InteractiveReview.run(
                new BufferedReader(new StringReader("d\nneeds rework\ns\ns\n")),
                new PrintWriter(outSw), new PrintWriter(new StringWriter()), context(f));

        assertThat(exit).isZero();   // b's re-verify passes (the gradlew stub exits 0)
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("a").decision()).isEqualTo(Decision.REDO);
        assertThat(persisted.get("a").reason()).isEqualTo("needs rework");
        assertThat(persisted.get("b").decision()).isEqualTo(Decision.PENDING);   // downgraded, re-decide

        // The SAME downstream re-verify DecisionCommand.Redo runs — not just the bare Decisions
        // state transition — reused via DecisionCommand.redoFollowUp.
        assertThat(outSw.toString()).contains("downgraded to PENDING (re-decide): b")
                .contains("re-verify b: OK")
                .contains("then run: sdd implement --workspace " + ws + " --retry a " + f.planPath());
    }

    @Test
    void anUnrecognizedOptionRepromptsTheSameRepo() throws Exception {
        Fixture f = fixture();
        InteractiveReview.Context ctx = context(f);
        BufferedReader in = new BufferedReader(new StringReader("zzz\ns\ns\ns\n"));
        StringWriter outSw = new StringWriter();

        InteractiveReview.run(in, new PrintWriter(outSw), new PrintWriter(new StringWriter()), ctx);

        assertThat(outSw.toString()).contains("unrecognized");
    }

    @Test
    void promptLabelsRedoAsReDoNotDedo() {
        // Live output rendered "[d]edo" — the key binding (d) is correct, only the label read wrong.
        assertThat(InteractiveReview.PROMPT)
                .isEqualTo("[a]pprove / [r]eject / re[d]o / [v]iew diff / [s]kip / [q]uit: ")
                .doesNotContain("dedo");
    }

    @Test
    void aDirtyTreeRefusesTheSquashAndPropagatesExitTwoWithoutLosingTheDecision() throws Exception {
        Fixture f = fixture();
        InteractiveReview.Context ctx = context(f);
        // Dirty a's working tree (it's currently on its ORIGINAL branch, restored by fixture()) so
        // SquashApprove.approve refuses — the load-bearing case DecisionCommand.Approve's follow-up
        // exists for, which the interactive loop must not silently swallow.
        Files.writeString(f.a().path().resolve("A.java"), "class A { int mine; }\n");

        int exit = InteractiveReview.run(new BufferedReader(new StringReader("a\n")),
                new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), ctx);

        assertThat(exit).isEqualTo(2);
        // The decision itself still stands — only the squash was refused.
        assertThat(RunStore.system().readDecisions(f.runDir()).get("a").decision())
                .isEqualTo(Decision.APPROVED);
        assertThat(Files.readString(f.a().path().resolve("A.java"))).isEqualTo("class A { int mine; }\n");
    }

    @Test
    void theFinalReRenderCarriesTheCallersBaselineRebuildDataThrough() throws Exception {
        Fixture f = fixture();
        RebuildPass.Outcome baseline = new RebuildPass.Outcome(
                Map.of("a", new EstateRebuild.Result(true, "ok log")), List.of(), List.of(), List.of(),
                List.of());
        InteractiveReview.Context ctx = context(f, baseline, RebuildScope.estate());

        // One real decision (reject a, empty reason) triggers the re-render; b and c are skipped.
        InteractiveReview.run(new BufferedReader(new StringReader("r\n\ns\ns\n")),
                new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), ctx);

        String report = Files.readString(f.runDir().resolve("review/report.md"));
        // Not "skipped (--no-rebuild)" — the caller's real rebuild pass ran seconds earlier in the
        // same process, and the loop must not overwrite that with nothing.
        assertThat(report).contains("Estate rebuild: 1 passed, 0 failed");
    }
}
