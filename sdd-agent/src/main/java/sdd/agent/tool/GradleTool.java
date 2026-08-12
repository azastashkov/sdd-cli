package sdd.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The agent's only path to the build: an env-scrubbed subprocess running an ALLOWLISTED Gradle
 * task with a per-repo JAVA_HOME and a hard timeout (design Component 3 guardrails). No generic
 * shell, no arbitrary tasks, no inherited secrets.
 */
public final class GradleTool {
    static final Set<String> ALLOWED = Set.of("help", "compileJava", "classes", "testClasses",
            "assemble", "check", "test", "build");
    static final int MAX_OUTPUT = 8000;
    private static final List<String> KEEP_ENV = List.of("PATH", "HOME", "LANG", "TMPDIR");

    private final Path repoRoot;
    private final Path javaHome;
    private final Duration timeout;

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout) {
        this.repoRoot = repoRoot;
        this.javaHome = javaHome;
        this.timeout = timeout;
    }

    public String run(String task) {
        if (!ALLOWED.contains(task)) {
            throw new ToolException("gradle task not allowed: " + task);
        }
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            throw new ToolException("no gradle wrapper in " + repoRoot);
        }
        Path log = null;
        try {
            log = Files.createTempFile("sdd-agent-gradle", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", task,
                    "--no-configuration-cache", "--no-daemon", "-q"));
            builder.directory(repoRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            scrubEnvironment(builder.environment());
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return "timed out after " + timeout.toSeconds() + "s";
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (output.length() > MAX_OUTPUT) {
                output = "... (head omitted)\n" + output.substring(output.length() - MAX_OUTPUT);
            }
            return "exit " + process.exitValue() + "\n" + output;
        } catch (IOException e) {
            throw new ToolException("gradle run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("gradle run interrupted");
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

    private void scrubEnvironment(Map<String, String> env) {
        Map<String, String> keep = new java.util.HashMap<>();
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
