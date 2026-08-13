package sdd.cli.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EstateRebuildTest {
    @TempDir Path ws;

    private Path repoWith(String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve("lib"));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void runsEveryTaskWithSubstitutionFlagsAndPasses() throws Exception {
        Path repo = repoWith("echo \"$*\" >> calls; exit 0");

        EstateRebuild.Result result = new EstateRebuild().verify(repo, null,
                List.of("compileJava", "check"), List.of("--include-build", "/w/lib"));

        assertThat(result.ok()).isTrue();
        String calls = Files.readString(repo.resolve("calls"));
        assertThat(calls).contains("compileJava --include-build /w/lib").contains("check --include-build");
        assertThat(calls.lines()).hasSize(2);
    }

    @Test
    void stopsAtTheFirstFailingTask() throws Exception {
        Path repo = repoWith("echo \"$*\" >> calls; case \"$1\" in compileJava) exit 1 ;; *) exit 0 ;; esac");

        EstateRebuild.Result result = new EstateRebuild().verify(repo, null,
                List.of("compileJava", "check"), List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).startsWith("exit 1");
        assertThat(Files.readString(repo.resolve("calls")).lines()).hasSize(1);   // check never ran
    }

    @Test
    void missingWrapperFails() {
        EstateRebuild.Result result = new EstateRebuild()
                .verify(ws.resolve("nowhere"), null, List.of("check"), List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("no gradle wrapper");
    }
}
