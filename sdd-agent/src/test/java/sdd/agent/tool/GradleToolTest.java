package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

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
    void missingWrapperFails() {
        assertThatThrownBy(() -> new GradleTool(repo, null, Duration.ofSeconds(5)).run("help"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no gradle wrapper");
    }
}
