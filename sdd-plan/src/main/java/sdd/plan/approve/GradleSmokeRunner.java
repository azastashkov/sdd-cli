package sdd.plan.approve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs `./gradlew help --include-build <provider>` in the consumer repo (design M5: the
 * propagation mechanism per edge is chosen by a LIVE smoke test). The first subprocess in
 * this codebase — deliberately minimal: inherit env, capture merged output, hard timeout.
 */
public final class GradleSmokeRunner implements SmokeRunner {
    private final Duration timeout;

    public GradleSmokeRunner() {
        this(Duration.ofSeconds(120));
    }

    public GradleSmokeRunner(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public Result probe(Path consumerRepo, Path providerRepo) {
        Path gradlew = consumerRepo.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, "no gradle wrapper in " + consumerRepo);
        }
        Path log = null;
        try {
            log = Files.createTempFile("sdd-smoke", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", "help",
                    "--include-build", providerRepo.toAbsolutePath().toString(),
                    "--no-configuration-cache", "-q"));
            builder.directory(consumerRepo.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(false, "timed out after " + timeout.toSeconds() + "s");
            }
            int exit = process.exitValue();
            if (exit == 0) {
                return new Result(true, "");
            }
            String lastLine = "";
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    lastLine = line.strip();
                }
            }
            return new Result(false, "exit " + exit + ": " + lastLine);
        } catch (IOException e) {
            return new Result(false, String.valueOf(e.getMessage()));
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
}
