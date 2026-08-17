package sdd.core.toolchain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class GradleLauncherTest {
    @TempDir Path repo;
    @TempDir Path gradleHome;

    private void wrapper() throws IOException {
        Path w = repo.resolve("gradlew");
        Files.writeString(w, "#!/bin/sh\n");
        Files.setPosixFilePermissions(w, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Path configuredGradle() throws IOException {
        Path bin = Files.createDirectories(gradleHome.resolve("bin"));
        Path g = bin.resolve("gradle");
        Files.writeString(g, "#!/bin/sh\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        return g;
    }

    @Test
    void anExecutableWrapperWins() throws IOException {
        wrapper();
        configuredGradle();

        // The wrapper pins the Gradle version the repo expects. Preferring a single configured
        // Gradle over it would silently run a 6.9 repo on 8.x.
        assertThat(GradleLauncher.resolve(repo, gradleHome).executable()).isEqualTo("./gradlew");
    }

    @Test
    void withoutAWrapperTheConfiguredGradleIsUsed() throws IOException {
        Path g = configuredGradle();

        assertThat(GradleLauncher.resolve(repo, gradleHome).executable()).isEqualTo(g.toString());
    }

    @Test
    void aNonExecutableWrapperIsNotAWrapper() throws IOException {
        Files.writeString(repo.resolve("gradlew"), "#!/bin/sh\n");   // present, not executable
        Path g = configuredGradle();

        assertThat(GradleLauncher.resolve(repo, gradleHome).executable()).isEqualTo(g.toString());
    }

    @Test
    void aConfiguredGradleHomeThatHoldsNoGradleIsAnErrorNotAQuietPathFallback() {
        // Same rule NodeLocator applies to node_home: a configured toolchain that is missing is
        // worth reporting, never a reason to silently run a different one.
        GradleLauncher.Resolution resolution = GradleLauncher.resolve(repo, gradleHome);

        assertThat(resolution.found()).isFalse();
        assertThat(resolution.problem()).contains("gradle_home").contains(gradleHome.toString());
    }

    @Test
    void withNoWrapperAndNoConfigurationTheProblemNamesBothLevers() {
        GradleLauncher.Resolution resolution = GradleLauncher.resolve(repo, null);

        if (!resolution.found()) {
            assertThat(resolution.problem())
                    .contains("gradlew").contains("gradle_home").contains(repo.toString());
        }
    }
}
