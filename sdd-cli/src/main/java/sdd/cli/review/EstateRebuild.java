package sdd.cli.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrator-owned, run-scoped estate rebuild (design line 66): re-run each affected repo's
 * verification tasks against the checkpointed working trees to prove the estate still builds
 * green as a whole. Deliberately NOT GradleTool — arbitrary task lists stay off the agent's
 * allowlist; this runner is never model-reachable (the GradleSmokeRunner privileged-subprocess
 * precedent, mirrored from {@code MavenLocalPublisher}). Env-scrubbed like GradleTool: a rebuild
 * needs no ambient credentials. The log mirrors GradleTool's shape ("exit N\n…" / "timed out after
 * Ns") so InfraClassifier patterns apply unchanged.
 */
public final class EstateRebuild {
    private static final List<String> KEEP_ENV = List.of("PATH", "HOME", "LANG", "TMPDIR");
    private static final int MAX_LOG = 200_000;

    private final Duration timeout;

    public EstateRebuild() {
        this(Duration.ofMinutes(15));
    }

    public EstateRebuild(Duration timeout) {
        this.timeout = timeout;
    }

    public record Result(boolean ok, String log) {
    }

    public Result verify(Path repoRoot, Path javaHome, List<String> tasks, List<String> extraArgs) {
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, "no gradle wrapper in " + repoRoot);
        }
        String lastLog = "exit 0\n";
        for (String task : tasks) {
            Result single = runTask(repoRoot, javaHome, task, extraArgs);
            lastLog = single.log();
            if (!single.ok()) {
                return single;
            }
        }
        return new Result(true, lastLog);
    }

    private Result runTask(Path repoRoot, Path javaHome, String task, List<String> extraArgs) {
        Path log = null;
        try {
            log = Files.createTempFile("sdd-rebuild", ".log");
            List<String> command = new ArrayList<>();
            command.add("./gradlew");
            command.add(task);
            command.addAll(extraArgs);
            command.add("--no-configuration-cache");
            command.add("--no-daemon");
            command.add("-q");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(repoRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            scrub(builder.environment(), javaHome);
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(false, "timed out after " + timeout.toSeconds() + "s");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (output.length() > MAX_LOG) {
                output = output.substring(0, MAX_LOG);
            }
            return new Result(process.exitValue() == 0, "exit " + process.exitValue() + "\n" + output);
        } catch (IOException e) {
            return new Result(false, "rebuild failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
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

    private static void scrub(Map<String, String> env, Path javaHome) {
        Map<String, String> keep = new HashMap<>();
        for (String name : KEEP_ENV) {
            String value = System.getenv(name);
            if (value != null) {
                keep.put(name, value);
            }
        }
        env.clear();
        env.putAll(keep);
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome.toAbsolutePath().toString());
        }
    }
}
