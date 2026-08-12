package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradleToolExtraArgsTest {
    @TempDir Path repo;

    private void gradlew(String script) throws Exception {
        Path g = repo.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void appendsExtraArgsToTheGradleCommand() throws Exception {
        gradlew("echo \"$@\"; exit 0");   // echo the args the wrapper received
        GradleTool gradle = new GradleTool(repo, null, Duration.ofSeconds(5),
                List.of("--include-build", "/w/lib"));

        String out = gradle.run("check");

        assertThat(out).startsWith("exit 0")
                .contains("check")
                .contains("--include-build")
                .contains("/w/lib")
                .contains("--no-configuration-cache");   // guardrail flags still present
    }

    @Test
    void threeArgCtorAppendsNoExtraArgs() throws Exception {
        gradlew("echo \"$@\"; exit 0");
        GradleTool gradle = new GradleTool(repo, null, Duration.ofSeconds(5));

        String out = gradle.run("check");

        assertThat(out).startsWith("exit 0").contains("check").doesNotContain("--include-build");
    }
}
