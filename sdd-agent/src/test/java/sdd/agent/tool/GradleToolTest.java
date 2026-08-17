package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradleToolTest {
    @TempDir Path repo;

    private void wrapper(String script) throws Exception {
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void allowedTasksExposesTheAllowlistReadOnly() {
        assertThat(GradleTool.allowedTasks()).contains("check").doesNotContain("publishToMavenLocal");
    }

    @Test
    void disallowedTaskNeverRuns() {
        assertThatThrownBy(() -> new GradleTool(repo, null, Duration.ofSeconds(5)).run("publishToMavenLocal"))
                .isInstanceOf(ToolException.class).hasMessageContaining("not allowed");
    }

    @Test
    void runsAllowedTaskAndReturnsExitAndOutput() throws Exception {
        wrapper("echo building; echo done; exit 0");

        String result = new GradleTool(repo, null, Duration.ofSeconds(5)).run("compileJava");

        assertThat(result).startsWith("exit 0\n").contains("building").contains("done");
    }

    @Test
    void scrubsEnvironmentButKeepsJavaHomeWhenProvided() throws Exception {
        wrapper("echo JH=$JAVA_HOME; echo LEAK=$SDD_SECRET");
        // set a secret in the inherited env via a wrapper that reads it — since ProcessBuilder
        // starts from the JVM env, the scrub must drop SDD_SECRET while JAVA_HOME is injected.
        Path fakeJdk = Files.createDirectory(repo.resolve("jdk"));

        String result = new GradleTool(repo, fakeJdk, Duration.ofSeconds(5)).run("help");

        assertThat(result).contains("JH=" + fakeJdk).contains("LEAK=\n");   // secret scrubbed to empty
    }

    @Test
    void timesOutAndReportsIt() throws Exception {
        wrapper("sleep 30");

        String result = new GradleTool(repo, null, Duration.ofSeconds(2)).run("test");

        assertThat(result).contains("timed out after 2s");
    }

    @Test
    void missingWrapperFallsBackToTheConfiguredGradle() throws Exception {
        // A missing wrapper used to be fatal here, which is what turned "this repo builds with the
        // gradle on my machine" into a hard stop. It now resolves through GradleLauncher, so the
        // wrapper still wins when present and a configured Gradle covers the repos without one.
        Path gradleHome = Files.createDirectories(repo.resolve("fake-gradle"));
        Path bin = Files.createDirectories(gradleHome.resolve("bin"));
        Path gradle = bin.resolve("gradle");
        Files.writeString(gradle, "#!/bin/sh\necho fallback-ran\n");
        Files.setPosixFilePermissions(gradle,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        String out = new GradleTool(repo, null, Duration.ofSeconds(30), List.of(), null, gradleHome)
                .run("help");

        assertThat(out).contains("fallback-ran");
    }

    @Test
    void neitherAWrapperNorAConfiguredGradleFailsAndNamesBothLevers() {
        Path emptyGradleHome = repo.resolve("nothing-here");

        assertThatThrownBy(() -> new GradleTool(repo, null, Duration.ofSeconds(5), List.of(), null,
                emptyGradleHome).run("help"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("gradle_home");
    }
}
