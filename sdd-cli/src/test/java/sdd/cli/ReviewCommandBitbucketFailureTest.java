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

/**
 * Task 5 brief §5's explicit test: "A Bitbucket failure during {@code sdd review} still writes
 * {@code report.md} and leaves the exit code unchanged." {@code atlassian.bitbucket.base_url}
 * points at a port nothing listens on, so both the push and every REST call {@link
 * sdd.cli.review.BitbucketReview} attempts fail — proving the whole integration is best-effort at
 * the outermost level, not just within {@link sdd.cli.review.BitbucketClient} itself.
 */
class ReviewCommandBitbucketFailureTest {
    @TempDir Path ws;

    @Test
    void aBitbucketOutageDuringSddReviewStillWritesReportMdAndLeavesTheExitCodeUnchanged() throws Exception {
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
                atlassian:
                  bitbucket:
                    base_url: http://127.0.0.1:1
                    token: sk-token
                    project: TRADING
                    default_reviewers: []
                    timeout_seconds: 1
                  pull_requests: true
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
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 15L));

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new ReviewCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        // Same exit code and report.md content a Bitbucket-less run would have produced — an
        // outage must not turn a clean review into a failed one, nor withhold report.md.
        assertThat(exit).isEqualTo(0);
        Path report = ws.resolve(".sdd/runs/SPEC-9-v1/review/report.md");
        assertThat(report).exists();
        assertThat(Files.readString(report)).contains("SUCCEEDED").contains("Release runbook");
        assertThat(err.toString()).contains("warn: bitbucket:");
        // The estate itself is untouched: the repo is back on its original branch, exactly as a
        // Bitbucket-less sdd review would have left it.
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(originalBranch);
    }
}
