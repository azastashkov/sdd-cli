package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.progress.Progress;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IndexCommand}'s own progress wiring: the {@code progressForTest} seam (mirrors {@code
 * DoctorCommand.clockForTest}/{@code PlanCommand.plannerForTest}), exercised directly rather than
 * through {@code SddCli.main} — no test in this tree calls {@code main} (design doc, "Arming"),
 * so this is the only way to observe that {@link IndexCommand#call()} both threads a {@link
 * Progress} down into {@code IndexService.run} and always calls {@link Progress#stop()} — success
 * or early failure — via the {@code try}/{@code finally} the design doc requires.
 *
 * <p>{@code RecordingProgress} is duplicated here rather than shared with {@code sdd-index}'s
 * test double of the same shape ({@code sdd.index.testing.RecordingProgress}): sharing it would
 * need a new {@code testFixtures} dependency from {@code sdd-cli} onto {@code sdd-index} for a
 * dozen lines of test-only code with no production consequence either way. Disclosed here rather
 * than left silent.
 */
class IndexCommandProgressTest {
    @TempDir Path ws;

    private static final class RecordingProgress implements Progress {
        final List<String> events = new ArrayList<>();

        @Override
        public void phase(String name, int total) {
            events.add("phase:" + name + ":" + total);
        }

        @Override
        public void start(String item) {
            events.add("start:" + item);
        }

        @Override
        public void finish(String item) {
            events.add("finish:" + item);
        }

        @Override
        public void detail(String text) {
            events.add("detail:" + text);
        }

        @Override
        public void note(String text) {
            events.add("note:" + text);
        }

        @Override
        public void stop() {
            events.add("stop");
        }
    }

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
    void callThreadsProgressIntoIndexServiceAndStopsItOnSuccess() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        IndexCommand command = new IndexCommand();
        RecordingProgress progress = new RecordingProgress();
        command.progressForTest = progress;

        int code = run(command, "--workspace", ws.toString(), "--no-cards");

        assertThat(code).isEqualTo(0); // empty workspace: no repos, nothing to fail
        // IndexService.run's own event sequence is IndexServiceProgressTest's job; here we only
        // need proof the command actually passed this exact instance down (an empty-workspace
        // "index" phase with total 0) and always stops it last.
        assertThat(progress.events).startsWith("phase:index:0");
        assertThat(progress.events).last().isEqualTo("stop");
    }

    @Test
    void stopIsCalledEvenWhenConfigLoadFailsBeforeIndexServiceEverRuns() {
        // no sdd.yml written: ConfigLoader.load fails before IndexService is even constructed
        IndexCommand command = new IndexCommand();
        RecordingProgress progress = new RecordingProgress();
        command.progressForTest = progress;

        int code = run(command, "--workspace", ws.toString());

        assertThat(code).isEqualTo(1);
        assertThat(progress.events).containsExactly("stop");
    }
}
