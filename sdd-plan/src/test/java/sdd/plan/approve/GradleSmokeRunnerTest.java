package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GradleSmokeRunnerTest {
    @TempDir Path dir;

    private Path consumerWith(String script) throws Exception {
        Path consumer = Files.createDirectories(dir.resolve("consumer"));
        Path gradlew = consumer.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return consumer;
    }

    @Test
    void noWrapperAndNoConfiguredGradleFailsWithoutRunningAnything() throws Exception {
        Path consumer = Files.createDirectories(dir.resolve("bare"));
        // A missing wrapper alone no longer fails: GradleLauncher falls back to a configured
        // Gradle. Passing an empty gradle_home pins the both-missing case deterministically,
        // regardless of whether the test machine has gradle on its PATH.

        SmokeRunner.Result result = new GradleSmokeRunner(java.time.Duration.ofSeconds(5),
                dir.resolve("none")).probe(consumer, dir);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("gradle_home");
    }

    @Test
    void exitZeroIsOkNonZeroCarriesLastOutputLine() throws Exception {
        assertThat(new GradleSmokeRunner().probe(consumerWith("exit 0"), dir).ok()).isTrue();

        SmokeRunner.Result failed = new GradleSmokeRunner()
                .probe(consumerWith("echo first\necho substitution failed\nexit 7"), dir);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.detail()).isEqualTo("exit 7: substitution failed");
    }

    @Test
    void hangingBuildTimesOut() throws Exception {
        SmokeRunner.Result result = new GradleSmokeRunner(Duration.ofSeconds(2))
                .probe(consumerWith("sleep 30"), dir);

        assertThat(result.ok()).isFalse();
        assertThat(result.detail()).contains("timed out after 2s");
    }
}
