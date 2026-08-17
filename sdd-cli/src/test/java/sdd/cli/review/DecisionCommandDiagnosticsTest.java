package sdd.cli.review;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.ReviewCommand;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 B3's Gate-2 decision events: "for each repo, whether the local squash was applied and
 * whether it squashed, whether the checkpoint write succeeded, and whether a Bitbucket merge was
 * consequently attempted... A reader must be able to confirm from the log alone that no merge
 * followed a refused squash." This is exactly what {@link DecisionCommandBitbucketTest} already
 * proves at the STDOUT/WireMock level (Task 5's "single most important assertion") — these tests
 * prove the SAME property is independently visible in the diagnostics file, which is the whole
 * point of Task 8: a remote reader with no stdout, only a pasted file, must be able to reach the
 * identical conclusion.
 */
class DecisionCommandDiagnosticsTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;
    private static final String RUN_ID = "SPEC-1-v1";
    private static final String BRANCH = "sdd/SPEC-1-v1/lib";

    private record Fixture(FixtureRepo lib, Path planPath) {
    }

    private Fixture fixture(Integer prId, boolean pullRequests) throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String base = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());
        RunGit.startBranch(lib.path(), BRANCH, base);
        lib.file("A.java", "class A { int x; }\n").commit("sdd: checkpoint 1");
        lib.file("B.java", "class B {}\n").commit("sdd: checkpoint 2");
        String checkpoint = lib.headSha();
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
                    base_url: %s
                    token: sk-super-secret-bb-token
                    project: TRADING
                    default_reviewers: []
                  pull_requests: %s
                """.formatted(wm.baseUrl(), pullRequests));
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-1
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: x.

                ## Acceptance Criteria
                - A1: y.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        String planJson = """
                { "spec_id":"SPEC-1","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, base);
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, RUN_ID, planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState(RUN_ID, List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, BRANCH, checkpoint, "ok", null, prId,
                        prId == null ? null : "https://bb.corp.local/pull-requests/" + prId)),
                null, 21L));

        return new Fixture(lib, planPath);
    }

    private static int exec(Path ws, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new ReviewCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        List<String> full = new java.util.ArrayList<>(List.of("--workspace", ws.toString()));
        full.addAll(List.of(args));
        return cli.execute(full.toArray(new String[0]));
    }

    private String diagnosticsContent() throws IOException {
        Path dir = ws.resolve(".sdd/diagnostics");
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                all.append(Files.readString(f));
            }
        }
        return all.toString();
    }

    @Test
    void aRefusedSquashLogsSquashRefusedAndNoMergeEventFollowsForThatRepo() throws Exception {
        Fixture f = fixture(17, true);
        Files.writeString(f.lib().path().resolve("A.java"), "class A { /* uncommitted */ }\n");

        exec(ws, "approve", "lib", f.planPath().toString());

        String content = diagnosticsContent();
        assertThat(content).contains("gate2 repo=lib squash refused");
        // The proof: no "merge" event of any kind for lib after a refused squash.
        assertThat(content).doesNotContain("merge attempted").doesNotContain("merge succeeded")
                .doesNotContain("merge failed").doesNotContain("merge not attempted");
    }

    @Test
    void aGrantedSquashLogsSquashThenCheckpointWriteInOrderBeforeAnyMergeOutcome() throws Exception {
        Fixture f = fixture(17, true);

        exec(ws, "approve", "lib", f.planPath().toString());

        String content = diagnosticsContent();
        assertThat(content).contains("squash applied (squashed=true)").contains("checkpoint write succeeded");
        int squashAt = content.indexOf("squash applied (squashed=true)");
        int checkpointAt = content.indexOf("checkpoint write succeeded");
        assertThat(squashAt).isLessThan(checkpointAt);
        // WireMock is not a real git-over-HTTP server, so the push (and therefore the merge it
        // gates) fails — that failure is still a "merge was attempted" fact, just an unsuccessful
        // one, and it must come AFTER the checkpoint write, never before.
        int mergeOutcomeAt = content.indexOf("merge failed");
        assertThat(mergeOutcomeAt).isGreaterThan(checkpointAt);
    }

    @Test
    void pullRequestsOffLogsMergeNotAttemptedRatherThanSilenceOrAFalseMergeClaim() throws Exception {
        Fixture f = fixture(null, false);

        exec(ws, "approve", "lib", f.planPath().toString());

        assertThat(diagnosticsContent()).contains("merge not attempted (pull_requests off)");
    }

    @Test
    void theBitbucketTokenNeverReachesTheDiagnosticsFileAcrossAnApproveRun() throws Exception {
        Fixture f = fixture(17, true);

        exec(ws, "approve", "lib", f.planPath().toString());

        assertThat(diagnosticsContent()).doesNotContain("sk-super-secret-bb-token");
    }
}
