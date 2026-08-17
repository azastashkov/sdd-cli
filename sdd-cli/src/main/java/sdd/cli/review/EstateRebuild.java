package sdd.cli.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Toolchain;
import sdd.core.ts.NodeLocator;
import sdd.core.toolchain.Subprocess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    private static final int MAX_LOG = 200_000;

    private final Duration timeout;
    private final Path gradleHome;

    public EstateRebuild() {
        this(Duration.ofMinutes(15));
    }

    public EstateRebuild(Duration timeout) {
        this(timeout, null);
    }

    public EstateRebuild(Duration timeout, Path gradleHome) {
        this.timeout = timeout;
        this.gradleHome = gradleHome;
    }

    public record Result(boolean ok, String log) {
    }

    /** A Gradle rebuild; kept so existing callers and their pinned behaviour are untouched. */
    public Result verify(Path repoRoot, Path javaHome, List<String> tasks, List<String> extraArgs) {
        return verify(repoRoot, Toolchain.GRADLE, javaHome, null, tasks, extraArgs);
    }

    /**
     * Re-runs a repo's verification tasks with whatever its toolchain requires.
     *
     * <p>Before this dispatched, Gate 2 answered every npm repo with "no gradle wrapper" and
     * counted it as a rebuild failure — so a mixed estate could not pass review at all, for a
     * reason that had nothing to do with the code under review.
     */
    public Result verify(Path repoRoot, Toolchain toolchain, Path javaHome, Path nodeHome,
                         List<String> tasks, List<String> extraArgs) {
        String missing = missingToolchain(repoRoot, toolchain);
        if (missing != null) {
            return new Result(false, missing);
        }
        String lastLog = "exit 0\n";
        for (String task : tasks) {
            Result single = runTask(repoRoot, toolchain, javaHome, nodeHome, task, extraArgs);
            lastLog = single.log();
            if (!single.ok()) {
                return single;
            }
        }
        return new Result(true, lastLog);
    }

    /** What this repo needs and does not have, or null when it can be built. */
    private String missingToolchain(Path repoRoot, Toolchain toolchain) {
        return switch (toolchain) {
            case GRADLE -> sdd.core.toolchain.GradleLauncher.resolve(repoRoot, gradleHome).problem();
            case NPM -> Files.isDirectory(repoRoot.resolve("node_modules"))
                    ? null : "node_modules is not installed in " + repoRoot
                            + " — run npm install (or npm ci) before sdd review";
            case UNKNOWN -> "cannot determine build system in " + repoRoot;
        };
    }

    private Result runTask(Path repoRoot, Toolchain toolchain, Path javaHome, Path nodeHome,
                           String task, List<String> extraArgs) {
        try {
            List<String> command = new ArrayList<>();
            EnvPolicy env;
            if (toolchain == Toolchain.NPM) {
                // No extra arguments, ever: npm appends passthrough args to the end of the whole
                // script string, where they land on the wrong command. Provider substitution for
                // npm is not done with flags.
                command.add(NodeLocator.npmExecutable(nodeHome));
                command.add("run");
                command.add(task);
                env = EnvPolicy.scrubbedNode(nodeHome);
            } else {
                command.add(sdd.core.toolchain.GradleLauncher.resolve(repoRoot, gradleHome).executable());
                command.add(task);
                command.addAll(extraArgs);
                command.add("--no-configuration-cache");
                command.add("--no-daemon");
                command.add("-q");
                env = EnvPolicy.scrubbedJvm(javaHome);
            }
            Subprocess.Outcome outcome = Subprocess.run(command, repoRoot, env, timeout,
                    Subprocess.KillPolicy.PROCESS_TREE, "sdd-rebuild");
            if (outcome.timedOut()) {
                return new Result(false, "timed out after " + timeout.toSeconds() + "s");
            }
            String output = outcome.output();
            if (output.length() > MAX_LOG) {
                output = output.substring(0, MAX_LOG);
            }
            return new Result(outcome.exitCode() == 0, "exit " + outcome.exitCode() + "\n" + output);
        } catch (IOException e) {
            return new Result(false, "rebuild failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        }
    }

}
