package sdd.cli.implement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrator-owned, run-scoped Maven-local publish (design line 61):
 * {@code ./gradlew publishToMavenLocal -Pversion=<planned> -Dmaven.repo.local=<runDir>/m2}.
 * Deliberately NOT GradleTool — publishToMavenLocal stays off the agent's allowlist; this runner is
 * never model-reachable (the GradleSmokeRunner privileged-subprocess precedent). Env-scrubbed like
 * GradleTool: a publish needs no ambient credentials. The log mirrors GradleTool's shape
 * ("exit N\n…" / "timed out after Ns") so InfraClassifier patterns apply unchanged.
 */
public final class MavenLocalPublisher {
    private static final List<String> KEEP_ENV = List.of("PATH", "HOME", "LANG", "TMPDIR");
    private static final int MAX_LOG = 200_000;

    private final Duration timeout;

    public MavenLocalPublisher() {
        this(Duration.ofMinutes(10));
    }

    public MavenLocalPublisher(Duration timeout) {
        this.timeout = timeout;
    }

    public record Result(boolean ok, String log) {
    }

    public Result publish(Path repoRoot, Path javaHome, String version, Path m2Dir) {
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, "no gradle wrapper in " + repoRoot);
        }
        Path log = null;
        try {
            Files.createDirectories(m2Dir);
            log = Files.createTempFile("sdd-publish", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", "publishToMavenLocal",
                    "-Pversion=" + version,
                    "-Dmaven.repo.local=" + m2Dir.toAbsolutePath(),
                    "--no-configuration-cache", "--no-daemon", "-q"));
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
            return new Result(false, "publish failed: " + e.getMessage());
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
