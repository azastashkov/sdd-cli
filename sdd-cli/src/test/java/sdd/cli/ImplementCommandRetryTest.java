package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code sdd implement --retry <repo>}: re-runs an already-settled (SUCCEEDED or FAILED) repo on
 *  resume without hand-editing state.json. */
class ImplementCommandRetryTest {
    @TempDir Path ws;

    /** gradlew defers its pass/fail verdict to a marker file OUTSIDE the git repo (a sibling of the
     *  repo directory, directly under the workspace) so the same base commit can flip from failing to
     *  passing between two ImplementCommand invocations — RunGit.startBranch hard-resets and cleans
     *  the repo's own working tree on every attempt, which would otherwise erase any in-repo change. */
    private FixtureRepo repoGatedByMarker(String name) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n[ -f ../verify-pass ] && exit 0\nexit 1\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        return repo.commit("base");
    }

    private static ChatResponse done() {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall("d", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")),
                null), "tool_calls", new Usage(10, 5));
    }

    private void writeFixture(FixtureRepo lib) throws Exception {
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
                id: SPEC-7
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: x

                ## Acceptance Criteria
                - A1: x
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-7","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":[],"verification":[],"sub_spec":"x"}] }
                """.formatted(specSha, lib.headSha()));
    }

    @Test
    void retryReRunsASettledRepoToSuccessWithoutHandEditingState() throws Exception {
        FixtureRepo lib = repoGatedByMarker("lib");
        writeFixture(lib);

        // Attempt 1's model calls: verify (gradlew) always fails, so each attempt burns both of
        // RepoStepRunner's verify cycles before the orchestrator escalates once more — 4 "done" calls
        // total exhaust attempt 1 + attempt 2 and leave the repo FAILED.
        ImplementCommand first = new ImplementCommand();
        first.coderForTest = new ScriptedChatModel(List.of(done(), done(), done(), done()));
        StringWriter firstOut = new StringWriter();
        CommandLine firstCli = new CommandLine(first);
        firstCli.setOut(new PrintWriter(firstOut));
        int firstExit = firstCli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(firstExit).isEqualTo(2);
        assertThat(firstOut.toString()).contains("lib: FAILED");

        // Flip the marker so the SAME base commit's gradlew now passes, then retry with a fresh
        // scripted coder — no state.json hand-editing.
        Files.writeString(ws.resolve("verify-pass"), "");
        ImplementCommand second = new ImplementCommand();
        second.coderForTest = new ScriptedChatModel(List.of(done()));
        StringWriter secondOut = new StringWriter();
        CommandLine secondCli = new CommandLine(second);
        secondCli.setOut(new PrintWriter(secondOut));
        int secondExit = secondCli.execute("--workspace", ws.toString(), "--retry", "lib",
                ws.resolve("s.plan.json").toString());

        assertThat(secondExit).isEqualTo(0);
        assertThat(secondOut.toString()).contains("lib: SUCCEEDED");
    }

    @Test
    void retryOfAnUnknownRepoExitsFourAndLeavesNoLockFile() throws Exception {
        FixtureRepo lib = repoGatedByMarker("lib");
        writeFixture(lib);

        ImplementCommand first = new ImplementCommand();
        first.coderForTest = new ScriptedChatModel(List.of(done(), done(), done(), done()));
        CommandLine firstCli = new CommandLine(first);
        firstCli.setOut(new PrintWriter(new StringWriter()));
        int firstExit = firstCli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());
        assertThat(firstExit).isEqualTo(2);

        ImplementCommand second = new ImplementCommand();
        second.coderForTest = new ScriptedChatModel(List.of());
        StringWriter errOut = new StringWriter();
        CommandLine secondCli = new CommandLine(second);
        secondCli.setOut(new PrintWriter(new StringWriter()));
        secondCli.setErr(new PrintWriter(errOut));
        int secondExit = secondCli.execute("--workspace", ws.toString(), "--retry", "ghost-repo",
                ws.resolve("s.plan.json").toString());

        assertThat(secondExit).isEqualTo(4);
        assertThat(errOut.toString()).contains("unknown repo for --retry: ghost-repo")
                .contains("this run has: lib");
        assertThat(ws.resolve(".sdd/runs/SPEC-7-v1/lock")).doesNotExist();
    }
}
