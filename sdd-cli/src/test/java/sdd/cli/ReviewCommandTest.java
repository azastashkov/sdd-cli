package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewCommandTest {
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
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok")), null, 15L);
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
                List.of(new RepoRun("lib", RepoState.FAILED, null, null, "boom")), null, 0L);
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
}
