package sdd.cli.review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import sdd.core.toolchain.EnvPolicy;
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
        try {
            List<String> command = new ArrayList<>();
            command.add("./gradlew");
            command.add(task);
            command.addAll(extraArgs);
            command.add("--no-configuration-cache");
            command.add("--no-daemon");
            command.add("-q");
            Subprocess.Outcome outcome = Subprocess.run(command, repoRoot,
                    EnvPolicy.scrubbedJvm(javaHome), timeout,
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
