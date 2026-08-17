package sdd.plan.approve;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Subprocess;

import java.time.Duration;
import java.util.List;

/**
 * Runs `./gradlew help --include-build <provider>` in the consumer repo (design M5: the
 * propagation mechanism per edge is chosen by a LIVE smoke test). The first subprocess in
 * this codebase — deliberately minimal: inherit env, capture merged output, hard timeout.
 */
public final class GradleSmokeRunner implements SmokeRunner {
    private final Duration timeout;
    private final Path gradleHome;

    public GradleSmokeRunner() {
        this(Duration.ofSeconds(120));
    }

    public GradleSmokeRunner(Duration timeout) {
        this(timeout, null);
    }

    public GradleSmokeRunner(Duration timeout, Path gradleHome) {
        this.timeout = timeout;
        this.gradleHome = gradleHome;
    }

    @Override
    public Result probe(Path consumerRepo, Path providerRepo) {
        var launcher = sdd.core.toolchain.GradleLauncher.resolve(consumerRepo, gradleHome);
        if (!launcher.found()) {
            return new Result(false, launcher.problem());
        }
        try {
            // EnvPolicy.INHERIT and KillPolicy.PROCESS_ONLY are this site's two deliberate
            // departures from every other build subprocess, and CommandShapeTest pins both.
            // Scrubbing the environment here would change which propagation mechanism Gate 1
            // records for every cross-repo edge in the estate; see EnvPolicy's javadoc.
            Subprocess.Outcome outcome = Subprocess.run(
                    List.of(launcher.executable(), "help",
                            "--include-build", providerRepo.toAbsolutePath().toString(),
                            "--no-configuration-cache", "-q"),
                    consumerRepo, EnvPolicy.INHERIT, timeout,
                    Subprocess.KillPolicy.PROCESS_ONLY, "sdd-smoke");
            if (outcome.timedOut()) {
                return new Result(false, "timed out after " + timeout.toSeconds() + "s");
            }
            if (outcome.exitCode() == 0) {
                return new Result(true, "");
            }
            String lastLine = "";
            for (String line : (Iterable<String>) outcome.output().lines()::iterator) {
                if (!line.isBlank()) {
                    lastLine = line.strip();
                }
            }
            return new Result(false, "exit " + outcome.exitCode() + ": " + lastLine);
        } catch (IOException e) {
            return new Result(false, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        }
    }
}
