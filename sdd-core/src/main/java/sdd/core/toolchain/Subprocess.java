package sdd.core.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one build subprocess and returns what it printed. Extracted verbatim from the five sites
 * that each had their own copy: the agent's build tool, the japicmp jar build, the maven-local
 * publish, the Gate-2 rebuild and the Gate-1 propagation probe.
 *
 * <p>What is shared is the fiddly part — a temp log file, merged stderr, a bounded wait, a forced
 * kill, and cleanup that runs whatever happened. What is deliberately NOT shared is everything the
 * callers actually differ on: their command vectors, their {@link EnvPolicy}, their
 * {@link KillPolicy}, how they cap the output, and how they phrase their results. Those differences
 * are real and load-bearing, and folding them into one "sensible default" here would change
 * behavior at four call sites to make a fifth tidier.
 *
 * <p>Output is returned uncapped. Callers cap it themselves because they disagree about how: the
 * agent tail-caps model-facing output at 8k but head-caps a full log at 200k (javac prints
 * root-cause errors first), while the orchestrator-owned runners head-cap at 200k.
 */
public final class Subprocess {
    private Subprocess() {
    }

    /**
     * What to kill when the timeout expires.
     *
     * <p>{@link #PROCESS_TREE} walks {@code descendants()} before destroying the process itself, so
     * a timed-out build leaves nothing running. {@link #PROCESS_ONLY} destroys just the launched
     * process, which is what the Gate-1 probe has always done — a latent defect (a timed-out probe
     * can orphan whatever the wrapper spawned) that is pinned by test rather than fixed here, so
     * that fixing it is a deliberate change rather than a side effect of extracting this class.
     */
    public enum KillPolicy { PROCESS_TREE, PROCESS_ONLY }

    /**
     * @param exitCode the process exit code; meaningless when {@code timedOut}
     * @param timedOut true when the wait expired and the process was killed
     * @param output   merged stdout+stderr, uncapped
     */
    public record Outcome(int exitCode, boolean timedOut, String output) {
        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    /**
     * @param tempPrefix prefix for the temp log file, kept per-caller so a stray log left behind by
     *                   a hard crash still says which stage produced it
     */
    public static Outcome run(List<String> command, Path workingDir, EnvPolicy env,
                              Duration timeout, KillPolicy killPolicy, String tempPrefix)
            throws IOException, InterruptedException {
        Path log = null;
        try {
            log = Files.createTempFile(tempPrefix, ".log");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            env.applyTo(builder.environment());
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                if (killPolicy == KillPolicy.PROCESS_TREE) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                }
                process.destroyForcibly();
                return new Outcome(-1, true, "");
            }
            return new Outcome(process.exitValue(), false, Files.readString(log, StandardCharsets.UTF_8));
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }
}
