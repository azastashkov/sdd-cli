package sdd.agent.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.tool.GradleTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationRunnerTest {
    @TempDir Path repo;

    private void wrapper(String script) throws Exception {
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private VerificationRunner runner() {
        return new VerificationRunner(new GradleTool(repo, null, Duration.ofSeconds(5)),
                new OutputCompactor(repo));
    }

    @Test
    void passesOnAGreenGate() throws Exception {
        wrapper("echo BUILD SUCCESSFUL; exit 0");

        VerificationRunner.Verdict verdict = runner().verify("check");

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.output()).startsWith("exit 0");
    }

    @Test
    void failsAndCarriesTheCompactedFailure() throws Exception {
        wrapper("echo '/r/A.java:9: error: nope'; exit 1");

        VerificationRunner.Verdict verdict = runner().verify("check");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.output()).startsWith("exit 1").contains("A.java:9: error: nope");
    }
}
