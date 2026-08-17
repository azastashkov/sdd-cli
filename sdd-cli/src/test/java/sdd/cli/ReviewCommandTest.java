package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.implement.ContractActualizer;
import sdd.cli.implement.PlanModel;
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

import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewCommandTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    @Test
    void reviewProducesReportDiffsAndRunbook() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());   // "main" — jgit's default init branch

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n");
        lib.commit("checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);   // as if the user returned it there after implement

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
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
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);   // mirrors real state: implement's finally already released it
        RunState state = new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 15L);
        store.writeState(runDir, state);

        ReviewCommand cmd = new ReviewCommand();
        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

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
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String baseSha = lib.headSha();

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
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
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        RunState state = new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.FAILED, null, null, "boom", null)), null, 0L);
        store.writeState(runDir, state);

        ReviewCommand cmd = new ReviewCommand();
        int exit = new CommandLine(cmd).execute("--workspace", ws.toString(),
                ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(2);
        Path review = ws.resolve(".sdd/runs/SPEC-9-v1/review");
        assertThat(review.resolve("report.md")).exists();
        assertThat(Files.readString(review.resolve("report.md"))).contains("FAILED");

        // A plan whose runId has no run directory at all must abort with exit 4, not attempt a review.
        String noRunPlan = """
                { "spec_id":"SPEC-99","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s99.plan.json"), noRunPlan);

        StringWriter err = new StringWriter();
        CommandLine noRunCli = new CommandLine(new ReviewCommand());
        noRunCli.setErr(new PrintWriter(err));
        int noRunExit = noRunCli.execute("--workspace", ws.toString(), ws.resolve("s99.plan.json").toString());

        assertThat(noRunExit).isEqualTo(4);
        assertThat(err.toString()).contains("no run to review");
    }

    @Test
    void anUnresolvableCheckpointShaYieldsADiffFailureNotALostReport() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String baseSha = lib.headSha();

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
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
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        // A checkpoint sha that does not exist in the repo — e.g. the run branch was pruned, or
        // the object was gc'd. The diff/diffstat pass must not be allowed to abort the whole review.
        String missingSha = "0000000000000000000000000000000000000000";
        RunState state = new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/lib", missingSha, "ok", null)), null, 5L);
        store.writeState(runDir, state);

        ReviewCommand cmd = new ReviewCommand();
        int exit = new CommandLine(cmd).execute("--workspace", ws.toString(), "--no-rebuild",
                ws.resolve("s.plan.json").toString());

        Path review = ws.resolve(".sdd/runs/SPEC-9-v1/review");
        assertThat(review.resolve("report.md")).exists();
        String report = Files.readString(review.resolve("report.md"));
        assertThat(report).contains("Diff failures").contains("lib");
        assertThat(review.resolve("lib.diff")).doesNotExist();
        // The exit code still reflects only state/rebuild outcome (SUCCEEDED, no rebuild run) — a
        // diff failure alone must not change it.
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void aRepoThatCannotBeStagedFailsTheReviewEvenWithNothingToVerify() throws Exception {
        // Pins a deliberate behaviour change: a repo whose verification tasks are ALL excluded used
        // to short-circuit before the checkout, so it was never staged and a plain review exited 0.
        // It is still a provider whose working tree its consumers compose, and ContractRecheck
        // assumes every repo sits on its checkpoint — so it is staged now, and a staging failure
        // fails the review instead of passing silently.
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n");
        lib.commit("checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);
        // An uncommitted edit to the one file that differs between the two branches: jgit refuses
        // the checkout rather than clobbering it, which is the realistic way staging fails.
        Files.writeString(lib.path().resolve("A.java"), "class A { int mine; }\n");

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                verification_exclusions:
                  lib: [check]
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
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 3L));

        int exit = new CommandLine(new ReviewCommand())
                .execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(2);
        String report = Files.readString(ws.resolve(".sdd/runs/SPEC-9-v1/review/report.md"));
        assertThat(report).contains("Staging failures").contains("lib");
        // The human's uncommitted work is exactly where they left it.
        assertThat(Files.readString(lib.path().resolve("A.java"))).isEqualTo("class A { int mine; }\n");
    }

    @Test
    void aFailedBranchRestoreForcesExitTwoEvenWhenEverythingElsePasses() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        String originalBranch = RunGit.currentBranch(lib.path());   // "main" — jgit's default init branch,
                                                                     // readable even pre-commit (unborn HEAD)
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        // The stub gradlew is the rebuild's only foothold in the repo while it's checked out to the
        // checkpoint branch: it deletes the original branch out from under the review, so restoring
        // to it afterward genuinely fails (verified standalone: RunGit.checkout on a deleted branch
        // throws IllegalStateException). It must be committed into "base" (not written after) so
        // startBranch's git-clean of the checkpoint branch doesn't wipe it as an untracked file.
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\ngit branch -D " + originalBranch + "\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        lib.commit("base");
        String baseSha = lib.headSha();

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n");
        lib.commit("checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);   // as if the user returned it there after implement

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
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
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        RunState state = new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 15L);
        store.writeState(runDir, state);

        ReviewCommand cmd = new ReviewCommand();
        int exit = new CommandLine(cmd).execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        // Every rebuild passed (the stub exits 0) and every repo is SUCCEEDED — the restore failure
        // is the ONLY thing wrong here, and it must still force exit 2: a repo stranded off its
        // original branch demands human action, matching the report's own exit-code legend ("2 = a
        // repo is not SUCCEEDED or a rebuild/checkout failed" — a failed restore IS a failed checkout).
        assertThat(exit).isEqualTo(2);
        Path review = ws.resolve(".sdd/runs/SPEC-9-v1/review");
        assertThat(review.resolve("report.md")).exists();
        String report = Files.readString(review.resolve("report.md"));
        assertThat(report).contains("Branch restore failures").contains("lib");
    }

    /** Both new tests below exercise the package-private {@code in} field: the injection point that
     *  lets {@code --interactive} run without a real terminal. */

    @Test
    void interactiveRefusesEntirelyWhileTheRunIsLockedAndWritesNoReport() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        lib.commit("base");
        String baseSha = lib.headSha();

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/lib", baseSha, "ok", null)), null, 0L));
        // Our own pid is provably alive, so RunStore.isLockHeld sees a live lock, not a stale one.
        Files.writeString(runDir.resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        ReviewCommand cmd = new ReviewCommand();
        cmd.interactive = true;
        cmd.in = new java.io.BufferedReader(new java.io.StringReader("a\n"));
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(4);
        assertThat(err.toString()).contains("SPEC-9-v1").contains("in progress (lock held)");
        // Refused before doing ANY work — not even the read-only report got written.
        assertThat(runDir.resolve("review/report.md")).doesNotExist();
        assertThat(runDir.resolve("review/decisions.json")).doesNotExist();
    }

    @Test
    void interactivePropagatesAFollowUpExitCodeEvenWhenTheBaseReviewWouldHavePassed() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n").commit("checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);
        // Dirty the working tree so approve's squash follow-up refuses — the load-bearing case
        // DecisionCommand.Approve's follow-up exists for, which --interactive must not swallow.
        Files.writeString(lib.path().resolve("A.java"), "class A { int mine; }\n");

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 0L));

        ReviewCommand cmd = new ReviewCommand();
        cmd.interactive = true;
        cmd.noRebuild = true;   // isolates this test to the interactive follow-up, not the rebuild pass
        cmd.in = new java.io.BufferedReader(new java.io.StringReader("a\n"));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        // The base (non-interactive) review alone would have exited 0 here — SUCCEEDED, no rebuild
        // run, nothing stranded. The squash refusal inside --interactive must still win.
        assertThat(exit).isEqualTo(2);
        assertThat(err.toString()).contains("squash refused");
        assertThat(RunStore.system().readDecisions(runDir).get("lib").decision())
                .isEqualTo(Decision.APPROVED);   // the decision stands; only the squash was refused
        assertThat(Files.readString(lib.path().resolve("A.java"))).isEqualTo("class A { int mine; }\n");
    }

    @Test
    void divergenceDoesNotChangeTheExitCode() throws Exception {
        // The real-estate case (design line 66: "mismatches = report warnings, human adjudicates"):
        // trading-product-a shipped Tier where the contract said Optional<Tier>. The implementation
        // has been wrong since implement time (fresh == recorded, so the drift axis is MATCHES),
        // but it diverges from what Gate 1 approved. A human adjudicates it in the report — it must
        // never fail the review on its own.
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file(
                "src/main/java/com/trading/pricing/core/TierResolver.java", """
                package com.trading.pricing.core;
                public class TierResolver {
                    public Tier tierFor(String account) { return null; }
                }
                """);
        lib.commit("base");
        String baseSha = lib.headSha();

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')",
                    lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],
                  "contracts":[{"id":"c1","kind":"java-api","provider":"lib","consumers":[],
                    "body":"TierResolver.tierFor","compat":null,
                    "declared":["com.trading.pricing.core.TierResolver#tierFor(String): Optional<Tier>"]}],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["TierResolver.java"],"verification":[],"sub_spec":"Add TierResolver."}] }
                """.formatted(baseSha);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, "");
        // The recorded contract matches what a real "sdd implement" run would have actualized from
        // this exact (wrong) source — fresh will equal recorded, pinning Status.MATCHES so the
        // divergence can only be visible via the conformance axis, never the drift axis.
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of(), "TierResolver.tierFor", null,
                List.of("com.trading.pricing.core.TierResolver#tierFor(String): Optional<Tier>"));
        store.writeContract(runDir, "c1",
                ContractActualizer.actualize(lib.path(), List.of(contract)).get("c1"));
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/lib", baseSha, "ok", null)),
                null, 0L));

        int exit = review(new StringWriter(), new StringWriter(), "--no-rebuild", planPath.toString());

        assertThat(exit).isZero();
        String report = Files.readString(runDir.resolve("review/report.md"));
        assertThat(report).contains("DIVERGED_FROM_PLAN")
                .contains("declared but not found: "
                        + "com.trading.pricing.core.TierResolver#tierFor(String):Optional<Tier>");
    }

    /** A one-repo estate whose run branch carries one checkpoint commit and is left restored to the
     *  branch the human was on — the shape every remaining test starts from. */
    private record Fixture(FixtureRepo lib, String runBranch, String checkpointSha,
                           String originalBranch, Path planPath, Path runDir) {
    }

    private Fixture fixture() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n").commit("sdd: checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')",
                    lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(baseSha);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 5L));

        return new Fixture(lib, runBranch, checkpointSha, originalBranch, planPath, runDir);
    }

    /** Moves the run branch one commit past the recorded checkpoint, as a human poking at the
     *  branch after the run would, and returns the new head. */
    private static String moveBranchPastCheckpoint(Fixture f) throws Exception {
        RunGit.checkout(f.lib().path(), f.runBranch());
        f.lib().file("A.java", "class A { int mine; }\n").commit("a human's own commit");
        String moved = f.lib().headSha();
        RunGit.checkout(f.lib().path(), f.originalBranch());
        return moved;
    }

    private int review(StringWriter out, StringWriter err, String... extraArgs) {
        CommandLine cli = new CommandLine(new ReviewCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        String[] args = new String[extraArgs.length + 2];
        args[0] = "--workspace";
        args[1] = ws.toString();
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        return cli.execute(args);
    }

    @Test
    void aLiveLockRefusesEvenAPlainNonInteractiveReviewWithExitFour() throws Exception {
        Fixture f = fixture();
        // Our own pid is provably alive, so RunStore.isLockHeld sees a live lock, not a stale one.
        Files.writeString(f.runDir().resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        StringWriter err = new StringWriter();
        int exit = review(new StringWriter(), err, "--no-rebuild", f.planPath().toString());

        assertThat(exit).isEqualTo(4);
        assertThat(err.toString()).contains("SPEC-9-v1").contains("in progress (lock held)");
        // A review that races sdd implement would read a half-written state.json and check repos
        // out from under it — nothing at all may be produced.
        assertThat(f.runDir().resolve("review/report.md")).doesNotExist();
    }

    @Test
    void aStaleLockWarnsAndTheReviewStillRuns() throws Exception {
        Fixture f = fixture();
        // A pid no process could hold: the run whose sdd implement crashed is exactly the run a
        // human most needs to review, so a stale lock must never block it.
        Files.writeString(f.runDir().resolve("lock"), "999999999");

        StringWriter err = new StringWriter();
        int exit = review(new StringWriter(), err, "--no-rebuild", f.planPath().toString());

        assertThat(exit).isZero();
        assertThat(err.toString()).contains("stale lock");
        assertThat(f.runDir().resolve("review/report.md")).exists();
    }

    @Test
    void aRunBranchMovedOffItsCheckpointIsReportedAsDriftAndForcesExitTwo() throws Exception {
        Fixture f = fixture();
        String moved = moveBranchPastCheckpoint(f);

        int exit = review(new StringWriter(), new StringWriter(), "--no-rebuild",
                f.planPath().toString());

        // Nothing failed and nothing is unstaged — the drift alone must fail the review, because
        // the diffs and runbook below describe a checkpoint the branch no longer carries.
        assertThat(exit).isEqualTo(2);
        String report = Files.readString(f.runDir().resolve("review/report.md"));
        assertThat(report).contains("## Checkpoint drift");
        assertThat(report).contains("lib: branch " + f.runBranch() + " is at " + moved.substring(0, 7)
                + ", checkpoint was " + f.checkpointSha().substring(0, 7)
                + " — diffs and runbook describe the checkpoint");
    }

    @Test
    void aDeletedRunBranchIsReportedWithoutFailingTheReview() throws Exception {
        Fixture f = fixture();
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(f.lib().path().toFile())) {
            git.branchDelete().setBranchNames(f.runBranch()).setForce(true).call();
        }

        int exit = review(new StringWriter(), new StringWriter(), "--no-rebuild",
                f.planPath().toString());

        // Nothing moved, so this is not drift and must not fail the review — but the runbook still
        // names the branch, so the report cannot stay silent about it either.
        assertThat(exit).isZero();
        String report = Files.readString(f.runDir().resolve("review/report.md"));
        assertThat(report).doesNotContain("## Checkpoint drift");
        assertThat(report).contains("run branch " + f.runBranch() + " no longer exists");
    }

    @Test
    void anApprovedRepoWhoseCheckpointWasRewrittenIsNotDrift() throws Exception {
        Fixture f = fixture();
        moveBranchPastCheckpoint(f);
        // sdd review approve DELIBERATELY squashes the run branch and rewrites state.json's
        // checkpoint. Flagging that as drift would mean the tool accusing its own approve of
        // tampering, and no run could ever exit 0 again after a single approval.
        RunStore.system().writeDecisions(f.runDir(),
                Map.of("lib", new DecisionRecord(Decision.APPROVED, "")));

        int exit = review(new StringWriter(), new StringWriter(), "--no-rebuild",
                f.planPath().toString());

        assertThat(exit).isZero();
        String report = Files.readString(f.runDir().resolve("review/report.md"));
        assertThat(report).doesNotContain("## Checkpoint drift");
        assertThat(report).contains("- **lib**: SUCCEEDED, decision: APPROVED");
    }

    @Test
    void reviewSucceedsWithEveryTiersApiKeyEnvVarUnset() throws Exception {
        // sdd review calls no model at all — its contract re-check is deterministic JavaParser
        // re-extraction, its rebuild is Gradle, its report is string building. A workspace whose
        // sdd.yml declares model tiers must not need every tier's credential exported just to run
        // this read-only Gate-2 command (the live defect this guards against exited 4 before any
        // report was produced).
        Fixture f = fixture();
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: "${SDD_LIVE_FIXES_TEST_UNSET_API_KEY}" }
                  coder: { base_url: http://y/v1, model: qwen, api_key: "${SDD_LIVE_FIXES_TEST_UNSET_API_KEY}" }
                """);

        int exit = review(new StringWriter(), new StringWriter(), "--no-rebuild",
                f.planPath().toString());

        assertThat(exit).isZero();
        assertThat(f.runDir().resolve("review/report.md")).exists();
    }

    /**
     * The finding this closes: a repo declaring binary-compatible whose gate never ran was
     * indistinguishable from one that passed — SUCCEEDED, unmentioned in the report, exit 0.
     */
    @Test
    void aDeclaredGuaranteeWhoseGateNeverRanFailsTheReview() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n");
        lib.commit("checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
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
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],
                  "contracts":[{"id":"c1","kind":"java-api","provider":"lib","consumers":[],
                    "body":"b","compat":"binary-compatible"}],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":["c1"],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 15L));
        store.writeCompatGates(runDir, "lib", List.of(new sdd.cli.implement.CompatGate(
                "binary-compatible", sdd.cli.implement.CompatGate.Outcome.SKIPPED,
                "baseline build failed — no such task 'jar'")));

        int exit = new CommandLine(new ReviewCommand()).execute("--workspace", ws.toString(),
                ws.resolve("s.plan.json").toString());

        // Every repo SUCCEEDED and nothing drifted; the ONLY thing wrong is that the guarantee the
        // plan declared was never checked. Exit 0 here would be this command asserting it holds.
        assertThat(exit).isEqualTo(2);
        String report = Files.readString(ws.resolve(".sdd/runs/SPEC-9-v1/review/report.md"));
        assertThat(report).contains("## Compatibility gates that did not run");
        assertThat(report).contains("baseline build failed");
    }

    @Test
    void aDeclaredGuaranteeWhoseGatePassedStillReviewsClean() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n");
        lib.commit("checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
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
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],
                  "contracts":[{"id":"c1","kind":"java-api","provider":"lib","consumers":[],
                    "body":"b","compat":"binary-compatible"}],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":["c1"],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 15L));
        store.writeCompatGates(runDir, "lib", List.of(new sdd.cli.implement.CompatGate(
                "binary-compatible", sdd.cli.implement.CompatGate.Outcome.PASSED, "2 jar pair(s) compared")));

        int exit = new CommandLine(new ReviewCommand()).execute("--workspace", ws.toString(),
                ws.resolve("s.plan.json").toString());

        // The other half, and the one that matters more: this must NOT become a review that warns
        // about every run, or a reader learns to ignore the warning.
        assertThat(exit).isEqualTo(0);
        assertThat(Files.readString(ws.resolve(".sdd/runs/SPEC-9-v1/review/report.md")))
                .doesNotContain("Compatibility gates that did not run");
    }

    // --- Task 4: Gate-2 Jira write-back -----------------------------------------------------

    private static final String MODELS_YAML = """
            models:
              planner: { base_url: http://x/v1, model: p, api_key: k }
              coder: { base_url: http://y/v1, model: qwen }
            """;

    /** Same one-repo estate {@link #fixture()} builds, except {@code spec.md} carries a "##
     *  Sources" bullet naming a fetched Jira root issue — the write-back's only trigger for
     *  touching config/network at all (see {@code JiraWriteBack.post}'s empty-{@code jiraKeys}
     *  short-circuit and {@code ReviewCommand.commentOnJiraSources}'s own re-read of this file). */
    private Fixture jiraSourceFixture() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n").commit("sdd: checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')",
                    lib.path().toString()));
        }
        String specText = """
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

                ## Sources
                - jira PROJ-9 updated 2026-08-16T09:12:00Z %s/browse/PROJ-9
                """.formatted(wm.baseUrl());
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(baseSha);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, specText);
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 5L));

        return new Fixture(lib, runBranch, checkpointSha, originalBranch, planPath, runDir);
    }

    @Test
    void reviewCommentsOnEachJiraSourceIssueWhenWriteBackIsConfigured() throws Exception {
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")).willReturn(created()));
        Fixture f = jiraSourceFixture();
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                  write_back: comment
                """.formatted(wm.baseUrl()));

        StringWriter out = new StringWriter();
        int exit = review(out, new StringWriter(), "--no-rebuild", f.planPath().toString());

        assertThat(exit).isZero();
        assertThat(out.toString()).contains("review written: ").contains("commented on PROJ-9");
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> requests =
                wm.getServeEvents().getServeEvents();
        String postedBody = requests.stream()
                .filter(e -> e.getRequest().getUrl().equals("/rest/api/2/issue/PROJ-9/comment"))
                .findFirst().orElseThrow().getRequest().getBodyAsString();
        assertThat(postedBody).contains("SPEC-9")
                .contains("Decisions: 0 approved, 0 rejected, 0 redo, 1 pending");
    }

    @Test
    void reviewWithWriteBackNoneOrAbsentPostsNothingAndPrintsNothing() throws Exception {
        Fixture f = jiraSourceFixture();
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                """.formatted(wm.baseUrl()));   // write_back defaults to none

        StringWriter out = new StringWriter();
        int exit = review(out, new StringWriter(), "--no-rebuild", f.planPath().toString());

        assertThat(exit).isZero();
        assertThat(out.toString()).doesNotContain("commented on").doesNotContain("jira comment failed");
        wm.verify(0, postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")));
    }

    @Test
    void reviewNoCommentFlagSuppressesEvenWhenWriteBackIsConfigured() throws Exception {
        Fixture f = jiraSourceFixture();
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                  write_back: comment
                """.formatted(wm.baseUrl()));

        StringWriter out = new StringWriter();
        int exit = review(out, new StringWriter(), "--no-rebuild", "--no-comment", f.planPath().toString());

        assertThat(exit).isZero();
        assertThat(out.toString()).doesNotContain("commented on");
        wm.verify(0, postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")));
    }

    @Test
    void reviewWithAFailingJiraCommentWarnsButExitCodeStaysZeroAndReportStillWritten() throws Exception {
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")).willReturn(unauthorized()));
        Fixture f = jiraSourceFixture();
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                  write_back: comment
                """.formatted(wm.baseUrl()));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = review(out, err, "--no-rebuild", f.planPath().toString());

        // The property most likely to regress (Task 4 brief): a failed post must never flip an
        // otherwise-clean review's exit code.
        assertThat(exit).isZero();
        assertThat(err.toString()).contains("  warn: jira comment failed: ");
        assertThat(f.runDir().resolve("review/report.md")).exists();
    }
}
