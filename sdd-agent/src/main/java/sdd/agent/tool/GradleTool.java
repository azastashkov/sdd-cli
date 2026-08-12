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
    static final int MAX_FULL_OUTPUT = 200_000;
    private static final List<String> KEEP_ENV = List.of("PATH", "HOME", "LANG", "TMPDIR");

    private final Path repoRoot;
    private final Path javaHome;
    private final Duration timeout;
    private final java.util.List<String> extraArgs;

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout) {
        this(repoRoot, javaHome, timeout, java.util.List.of());
    }

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout, java.util.List<String> extraArgs) {
        this.repoRoot = repoRoot;
        this.javaHome = javaHome;
        this.timeout = timeout;
        this.extraArgs = java.util.List.copyOf(extraArgs);
    }

    /** Model-facing output, tail-capped at MAX_OUTPUT (the 4A behavior). */
    public String run(String task) {
        return execute(task, false);
    }

    /**
     * The full build log, HEAD-preserving (capped at MAX_FULL_OUTPUT). javac prints root-cause
     * errors first, so the compactor — which scrapes head-first — must see the start of a long log,
     * not run()'s tail. Fed to OutputCompactor by the compacting Toolbox path and VerificationRunner.
     */
    public String runFull(String task) {
        return execute(task, true);
    }

    private String execute(String task, boolean headPreserving) {
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
            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("./gradlew");
            command.add(task);
            command.addAll(extraArgs);          // orchestrator-appended substitution flags (invisible to the model)
            command.add("--no-configuration-cache");
            command.add("--no-daemon");
            command.add("-q");
            ProcessBuilder builder = new ProcessBuilder(command);
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
            output = headPreserving ? headCap(output) : tailCap(output);
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

    private static String tailCap(String output) {
        if (output.length() > MAX_OUTPUT) {
            return "... (head omitted)\n" + output.substring(output.length() - MAX_OUTPUT);
        }
        return output;
    }

    private static String headCap(String output) {
        if (output.length() > MAX_FULL_OUTPUT) {
            return output.substring(0, MAX_FULL_OUTPUT) + "\n... (tail omitted)";
        }
        return output;
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
