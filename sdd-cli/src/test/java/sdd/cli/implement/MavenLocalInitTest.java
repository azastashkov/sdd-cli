package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenLocalInitTest {
    @TempDir Path ws;

    @Test
    void writesAnInitScriptPointingAtTheRunScopedM2() throws Exception {
        Path runDir = Files.createDirectories(ws.resolve("run"));

        Path script = MavenLocalInit.write(runDir);

        assertThat(script).isEqualTo(runDir.resolve("maven-local-init.gradle"));
        assertThat(Files.readString(script))
                .contains("allprojects")
                .contains("maven { url = uri('" + runDir.resolve("m2").toAbsolutePath() + "') }");
    }

    @Test
    void escapesQuotesInTheWorkspacePath() throws Exception {
        Path runDir = Files.createDirectories(ws.resolve("o'brien"));

        Path script = MavenLocalInit.write(runDir);

        String content = Files.readString(script);
        assertThat(content).contains("o\\'brien");
        assertThat(content).doesNotContain("uri('" + runDir.resolve("m2").toAbsolutePath() + "')");
    }
}
