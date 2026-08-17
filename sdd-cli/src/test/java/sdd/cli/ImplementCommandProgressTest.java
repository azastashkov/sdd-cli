package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.progress.Progress;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;
import sdd.index.testing.RecordingProgress;
import sdd.index.testing.StopMarksSharedBuffer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImplementCommand}'s own progress wiring — the {@code implement} counterpart of {@code
 * IndexCommandProgressTest}, which this file deliberately mirrors: same {@code progressForTest}
 * seam, same reason (no test in this tree calls {@code SddCli.main}, design doc "Arming"), same
 * two properties worth proving independently of {@code OrchestratorTest} (which only proves
 * {@code Orchestrator} calls the {@link Progress} it is GIVEN correctly, not that {@link
 * ImplementCommand} actually threads one down and stops it at the right moment).
 */
class ImplementCommandProgressTest {
    @TempDir Path ws;

    private FixtureRepo repo(String name) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        return repo.commit("base");
    }

    private ImplementCommand commandForASingleRepoPlan(FixtureRepo lib) throws Exception {
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
                id: SPEC-101
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
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, lib.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "apply_edit",
                                "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));
        return cmd;
    }

    @Test
    void callThreadsProgressIntoOrchestrator() throws Exception {
        FixtureRepo lib = repo("lib");
        ImplementCommand cmd = commandForASingleRepoPlan(lib);
        RecordingProgress progress = new RecordingProgress();
        cmd.progressForTest = progress;

        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(new StringWriter(), true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        // Orchestrator.run's own event sequence (start/finish ordering, oldest-first collapsing)
        // is OrchestratorTest's job; here we only need proof ImplementCommand actually passed
        // this exact instance down into the Orchestrator it built — a one-repo "implement" phase.
        assertThat(progress.events()).startsWith("phase:implement:1");
        assertThat(progress.events()).contains("start:lib", "finish:lib");
    }

    /**
     * Mirrors {@code IndexCommandProgressTest.stopFiresBeforeTheReportPrintsNotJustEventually}:
     * {@link ImplementCommand#runPlan} must call {@link Progress#stop()} right after {@code
     * orchestrator.run} returns, not merely eventually via the method-wide {@code finally} — a
     * live renderer's last frame is an unterminated line sitting on the same stream the per-repo
     * report is about to start printing to.
     */
    @Test
    void stopFiresBeforeTheReportPrintsNotJustEventually() throws Exception {
        FixtureRepo lib = repo("lib");
        ImplementCommand cmd = commandForASingleRepoPlan(lib);
        StringWriter sharedOut = new StringWriter();
        cmd.progressForTest = new StopMarksSharedBuffer(sharedOut);

        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(sharedOut, true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        String text = sharedOut.toString();
        int stopIndex = text.indexOf("<<progress stopped>>");
        int firstReportLine = text.indexOf("lib: SUCCEEDED"); // the per-repo report's own first line
        assertThat(stopIndex).as("stop() must have fired at all").isGreaterThanOrEqualTo(0);
        assertThat(firstReportLine).as("and before the report's own first line").isGreaterThan(stopIndex);
    }
}
