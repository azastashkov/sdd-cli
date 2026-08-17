package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class MavenLocalPublisherTest {
    @TempDir Path ws;

    private Path repoWith(String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve("lib"));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void publishesWithPlannedVersionAndRunScopedRepo() throws Exception {
        Path repo = repoWith("echo \"$*\" > publish-args; exit 0");
        Path m2 = ws.resolve("run/m2");

        MavenLocalPublisher.Result result = new MavenLocalPublisher().publish(repo, null, "1.3.0", m2);

        assertThat(result.ok()).isTrue();
        String args = Files.readString(repo.resolve("publish-args"));
        assertThat(args).contains("publishToMavenLocal")
                .contains("-Pversion=1.3.0")
                .contains("-Dmaven.repo.local=" + m2.toAbsolutePath())
                .contains("--no-daemon");
        assertThat(Files.isDirectory(m2)).isTrue();   // created up front for the publish
    }

    @Test
    void failureReturnsAGradleShapedLogForInfraClassification() throws Exception {
        Path repo = repoWith("echo 'Could not resolve com.acme:x'; exit 1");

        MavenLocalPublisher.Result result = new MavenLocalPublisher()
                .publish(repo, null, "1.0.0", ws.resolve("m2"));

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).startsWith("exit 1").contains("Could not resolve");
    }

    @Test
    void noWrapperAndNoConfiguredGradleFailsWithoutRunningAnything() {
        // A missing wrapper alone no longer fails: GradleLauncher falls back to a configured
        // Gradle. Passing an empty gradle_home pins the both-missing case deterministically,
        // regardless of whether the test machine has gradle on its PATH.
        MavenLocalPublisher.Result result =
                new MavenLocalPublisher(java.time.Duration.ofSeconds(5), ws.resolve("none"))
                        .publish(ws.resolve("nowhere"), null, "1.0.0", ws.resolve("m2"));

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("gradle_home");
    }
}
