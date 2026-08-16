package sdd.cli.implement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Subprocess;

import java.time.Duration;
import java.util.List;

/**
 * Orchestrator-owned, run-scoped Maven-local publish (design line 61):
 * {@code ./gradlew publishToMavenLocal -Pversion=<planned> -Dmaven.repo.local=<runDir>/m2}.
 * Deliberately NOT GradleTool — publishToMavenLocal stays off the agent's allowlist; this runner is
 * never model-reachable (the GradleSmokeRunner privileged-subprocess precedent). Env-scrubbed like
 * GradleTool: a publish needs no ambient credentials. The log mirrors GradleTool's shape
 * ("exit N\n…" / "timed out after Ns") so InfraClassifier patterns apply unchanged.
 */
public final class MavenLocalPublisher {
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
        try {
            Files.createDirectories(m2Dir);
            Subprocess.Outcome outcome = Subprocess.run(
                    List.of("./gradlew", "publishToMavenLocal",
                            "-Pversion=" + version,
                            "-Dmaven.repo.local=" + m2Dir.toAbsolutePath(),
                            "--no-configuration-cache", "--no-daemon", "-q"),
                    repoRoot, EnvPolicy.scrubbedJvm(javaHome), timeout,
                    Subprocess.KillPolicy.PROCESS_TREE, "sdd-publish");
            if (outcome.timedOut()) {
                return new Result(false, "timed out after " + timeout.toSeconds() + "s");
            }
            String output = outcome.output();
            if (output.length() > MAX_LOG) {
                output = output.substring(0, MAX_LOG);
            }
            return new Result(outcome.exitCode() == 0, "exit " + outcome.exitCode() + "\n" + output);
        } catch (IOException e) {
            return new Result(false, "publish failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        }
    }

}
