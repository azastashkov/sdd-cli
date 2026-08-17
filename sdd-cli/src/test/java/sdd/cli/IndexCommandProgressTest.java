package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.progress.Progress;
import sdd.index.testing.RecordingProgress;
import sdd.index.testing.StopMarksSharedBuffer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IndexCommand}'s own progress wiring: the {@code progressForTest} seam (mirrors {@code
 * DoctorCommand.clockForTest}/{@code PlanCommand.plannerForTest}), exercised directly rather than
 * through {@code SddCli.main} — no test in this tree calls {@code main} (design doc, "Arming"),
 * so this is the only way to observe that {@link IndexCommand#call()} both threads a {@link
 * Progress} down into {@code IndexService.run} and stops it before the report prints, not merely
 * "eventually" via the method-wide {@code finally}.
 *
 * <p>{@code RecordingProgress}/{@code StopMarksSharedBuffer} are {@code sdd-index}'s own test
 * fixtures ({@code sdd.index.testing}, {@code src/testFixtures}), reused here rather than
 * duplicated: {@code sdd-cli}'s {@code build.gradle.kts} already declares
 * {@code testImplementation(testFixtures(project(":sdd-index")))}, so no new build wiring was
 * needed — an earlier revision of this file kept a private duplicate of each under the mistaken
 * belief that dependency did not exist (a Task 3 review caught {@code StopMarksSharedBuffer}
 * having quietly regrown that exact duplicate, once per command-progress test file).
 */
class IndexCommandProgressTest {
    @TempDir Path ws;

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """;
    }

    private int run(IndexCommand command, String... args) {
        CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(new StringWriter(), true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        return cli.execute(args);
    }

    @Test
    void callThreadsProgressIntoIndexService() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        IndexCommand command = new IndexCommand();
        RecordingProgress progress = new RecordingProgress();
        command.progressForTest = progress;

        int code = run(command, "--workspace", ws.toString(), "--no-cards");

        assertThat(code).isEqualTo(0); // empty workspace: no repos, nothing to fail
        // IndexService.run's own event sequence is IndexServiceProgressTest's job; here we only
        // need proof the command actually passed this exact instance down (an empty-workspace
        // "index" phase with total 0).
        assertThat(progress.events()).startsWith("phase:index:0");
    }

    /**
     * The ordering property a bare "stop is the last {@code Progress} event" assertion cannot
     * prove: {@link IndexCommand#call()} must call {@link Progress#stop()} right after {@code
     * IndexService.run} returns, before the report block prints its first line — not merely
     * before the method returns via the outer {@code finally}. A live renderer's last frame is
     * an unterminated line sitting on the terminal; printing the report before erasing it
     * garbles both.
     */
    @Test
    void stopFiresBeforeTheReportPrintsNotJustEventually() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        IndexCommand command = new IndexCommand();
        StringWriter sharedOut = new StringWriter();
        command.progressForTest = new StopMarksSharedBuffer(sharedOut);

        CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(sharedOut, true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        int code = cli.execute("--workspace", ws.toString(), "--no-cards");

        assertThat(code).isEqualTo(0);
        String text = sharedOut.toString();
        int stopIndex = text.indexOf("<<progress stopped>>");
        int firstReportLine = text.indexOf("link:"); // the report block's first printed line
        assertThat(stopIndex).as("stop() must have fired at all").isGreaterThanOrEqualTo(0);
        assertThat(firstReportLine).as("and before the report's own first line").isGreaterThan(stopIndex);
    }

    @Test
    void stopIsCalledEvenWhenConfigLoadFailsBeforeIndexServiceEverRuns() {
        // no sdd.yml written: ConfigLoader.load fails before IndexService is even constructed
        IndexCommand command = new IndexCommand();
        RecordingProgress progress = new RecordingProgress();
        command.progressForTest = progress;

        int code = run(command, "--workspace", ws.toString());

        assertThat(code).isEqualTo(1);
        assertThat(progress.events()).containsExactly("stop");
    }
}
