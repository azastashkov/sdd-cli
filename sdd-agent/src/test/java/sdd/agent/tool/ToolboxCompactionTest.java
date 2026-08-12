package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.OutputCompactor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ToolboxCompactionTest {
    @TempDir Path repo;

    private void gradlew(String script) throws Exception {
        Path g = repo.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Toolbox compactingToolbox() {
        return new Toolbox(new FileTools(new PathJail(repo)),
                new GradleTool(repo, null, Duration.ofSeconds(10)),
                new OutputCompactor(repo));
    }

    @Test
    void runGradleResultIsCompactedWhenACompactorIsProvided() throws Exception {
        gradlew("echo '/r/A.java:3: error: bad'; exit 1");

        String result = compactingToolbox().dispatch("run_gradle", "{\"task\":\"compileJava\"}");

        assertThat(result).startsWith("exit 1").contains("A.java:3: error: bad")
                .doesNotContain("/r/A.java");   // compactor shortened the path
    }

    @Test
    void headJavacErrorSurvivesALogLongerThanTheTailCap() throws Exception {
        // >8000 chars with the root-cause error at the HEAD: run()'s tail-cap would drop it;
        // the compacting path uses runFull()'s head-preserving cap, so the compactor still sees it.
        gradlew("echo '/r/Root.java:1: error: first cause'\n"
                + "i=0; while [ $i -lt 400 ]; do echo 'noise noise noise noise noise noise noise'; i=$((i+1)); done\n"
                + "exit 1");

        String result = compactingToolbox().dispatch("run_gradle", "{\"task\":\"compileJava\"}");

        assertThat(result).contains("Root.java:1: error: first cause");
    }
}
