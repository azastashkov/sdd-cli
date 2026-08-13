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

    /** Three independent (no edges) repos, all SUCCEEDED, all PENDING going in. {@code a} carries
     *  TWO checkpoint commits so an interactive approve has something real to squash — proving the
     *  loop reuses DecisionCommand's squash follow-up rather than just flipping the decision. */
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
                  "edges":[],
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
                new RepoRun("a", RepoState.SUCCEEDED, A_BRANCH, aCheckpoint, "ok"),
                new RepoRun("b", RepoState.SUCCEEDED, B_BRANCH, bCheckpoint, "ok"),
                new RepoRun("c", RepoState.SUCCEEDED, C_BRANCH, cCheckpoint, "ok")), null, 12L));

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

    @Test
    void walksInScheduleOrderApprovingRejectingAndQuittingPersistsAfterEachStep() throws Exception {
        Fixture f = fixture();
        RunContext run = RunContext.load(ws, f.planPath(), new PrintWriter(new StringWriter()));
        assertThat(run).isNotNull();
        run.collectDiffs();

        BufferedReader in = new BufferedReader(new StringReader("a\nr\nq\n"));
        StringWriter outSw = new StringWriter();
        StringWriter errSw = new StringWriter();

        InteractiveReview.run(in, new PrintWriter(outSw), new PrintWriter(errSw), run);

        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("a").decision()).isEqualTo(Decision.APPROVED);
        assertThat(persisted.get("b").decision()).isEqualTo(Decision.REJECTED);
        assertThat(persisted).doesNotContainKey("c");   // 'q' left it implicitly PENDING

        // The run branch actually got squashed — proof the loop reused DecisionCommand's follow-up
        // rather than only flipping the decision.
        assertThat(commitsOnBranch(f.a().path(), f.aBase(), A_BRANCH)).isEqualTo(1);
        assertThat(RunGit.currentBranch(f.a().path())).isNotEqualTo(A_BRANCH);   // restored

        // report.md was re-rendered when the loop ended (even though it ended via 'q').
        assertThat(outSw.toString()).contains("review written:");
        assertThat(f.runDir().resolve("review/report.md")).exists();
    }

    @Test
    void persistsAfterEveryDecisionNotOnlyAtTheEndOfTheScript() throws Exception {
        Fixture f = fixture();
        RunContext run = RunContext.load(ws, f.planPath(), new PrintWriter(new StringWriter()));
        run.collectDiffs();

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
                new PrintWriter(new StringWriter()), run))
                .isInstanceOf(IOException.class);

        // Despite the crash, repo a's decision already made it to disk — a FRESH store proves it,
        // not the in-memory Decisions object the (aborted) loop was holding.
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("a").decision()).isEqualTo(Decision.APPROVED);
    }

    @Test
    void viewPrintsTheDiffAndSkipLeavesTheRepoPending() throws Exception {
        Fixture f = fixture();
        RunContext run = RunContext.load(ws, f.planPath(), new PrintWriter(new StringWriter()));
        run.collectDiffs();

        // a: view then skip; b: skip; c: skip -> quit via EOF.
        BufferedReader in = new BufferedReader(new StringReader("v\ns\ns\ns\n"));
        StringWriter outSw = new StringWriter();

        InteractiveReview.run(in, new PrintWriter(outSw), new PrintWriter(new StringWriter()), run);

        assertThat(outSw.toString()).contains("A.java");   // the diff content for repo a
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted).isEmpty();   // every repo skipped, all still implicitly PENDING
    }

    @Test
    void redoDowngradesAndAnUnrecognizedOptionReprompts() throws Exception {
        Fixture f = fixture();
        RunContext run = RunContext.load(ws, f.planPath(), new PrintWriter(new StringWriter()));
        run.collectDiffs();
        // Pre-approve b via the same machinery the script commands use, so redo on a has something
        // downstream — no: a/b/c share no edges in this fixture, so redo just records REDO cleanly.
        BufferedReader in = new BufferedReader(new StringReader("zzz\nd\ns\ns\n"));
        StringWriter outSw = new StringWriter();

        InteractiveReview.run(in, new PrintWriter(outSw), new PrintWriter(new StringWriter()), run);

        assertThat(outSw.toString()).contains("unrecognized");
        Map<String, DecisionRecord> persisted = RunStore.system().readDecisions(f.runDir());
        assertThat(persisted.get("a").decision()).isEqualTo(Decision.REDO);
    }
}
