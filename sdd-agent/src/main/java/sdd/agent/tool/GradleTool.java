package sdd.agent.tool;

import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Subprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * The agent's only path to the build: an env-scrubbed subprocess running an ALLOWLISTED Gradle
 * task with a per-repo JAVA_HOME and a hard timeout (design Component 3 guardrails). No generic
 * shell, no arbitrary tasks, no inherited secrets.
 */
public final class GradleTool implements BuildTool {
    static final Set<String> ALLOWED = Set.of("help", "compileJava", "classes", "testClasses",
            "assemble", "check", "test", "build");

    /** The allowlist, exposed read-only so callers (e.g. the orchestrator) can validate/report without duplicating it. */
    public static Set<String> allowedTasks() {
        return ALLOWED;
    }
    static final int MAX_OUTPUT = 8000;
    static final int MAX_FULL_OUTPUT = 200_000;

    private final Path repoRoot;
    private final Path javaHome;
    private final Duration timeout;
    private final java.util.List<String> extraArgs;
    private final Semaphore permits;

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout) {
        this(repoRoot, javaHome, timeout, java.util.List.of(), null);
    }

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout, java.util.List<String> extraArgs) {
        this(repoRoot, javaHome, timeout, extraArgs, null);
    }

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout, java.util.List<String> extraArgs,
                      Semaphore permits) {
        this.repoRoot = repoRoot;
        this.javaHome = javaHome;
        this.timeout = timeout;
        this.extraArgs = java.util.List.copyOf(extraArgs);
        this.permits = permits;
    }

    @Override
    public String toolName() {
        return "run_gradle";
    }

    /** Byte-identical to the string this tool has always advertised; see BuildTool's javadoc. */
    @Override
    public String taskDescription() {
        return "help|compileJava|classes|testClasses|assemble|check|test|build";
    }

    @Override
    public Set<String> tasks() {
        return ALLOWED;
    }

    /** Model-facing output, tail-capped at MAX_OUTPUT (the 4A behavior). */
    @Override
    public String run(String task) {
        return execute(task, false);
    }

    /**
     * The full build log, HEAD-preserving (capped at MAX_FULL_OUTPUT). javac prints root-cause
     * errors first, so the compactor — which scrapes head-first — must see the start of a long log,
     * not run()'s tail. Fed to OutputCompactor by the compacting Toolbox path and VerificationRunner.
     */
    @Override
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
        if (permits != null) {
            permits.acquireUninterruptibly();
        }
        try {
            try {
                java.util.List<String> command = new java.util.ArrayList<>();
                command.add("./gradlew");
                command.add(task);
                command.addAll(extraArgs);          // orchestrator-appended substitution flags (invisible to the model)
                command.add("--no-configuration-cache");
                command.add("--no-daemon");
                command.add("-q");
                Subprocess.Outcome outcome = Subprocess.run(command, repoRoot,
                        EnvPolicy.scrubbedJvm(javaHome), timeout,
                        Subprocess.KillPolicy.PROCESS_TREE, "sdd-agent-gradle");
                if (outcome.timedOut()) {
                    return "timed out after " + timeout.toSeconds() + "s";
                }
                String output = headPreserving ? headCap(outcome.output()) : tailCap(outcome.output());
                return "exit " + outcome.exitCode() + "\n" + output;
            } catch (IOException e) {
                throw new ToolException("gradle run failed: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ToolException("gradle run interrupted");
            }
        } finally {
            if (permits != null) {
                permits.release();
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
}
