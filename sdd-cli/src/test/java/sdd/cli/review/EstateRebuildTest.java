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

    private Path npmRepo(String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve("web"));
        Files.writeString(repo.resolve("package.json"),
                "{\"name\":\"web\",\"scripts\":{\"test\":\"vitest run\"}}");
        Files.createDirectories(repo.resolve("node_modules"));
        Path bin = Files.createDirectories(ws.resolve("nodehome/bin"));
        Path npm = bin.resolve("npm");
        Files.writeString(npm, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(npm, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void anNpmRepoIsVerifiedRatherThanFailedForLackingAGradleWrapper() throws Exception {
        // Gate 2 used to answer every npm repo with "no gradle wrapper" and count it as a rebuild
        // failure, so a mixed estate could not pass review at all — for a reason that had nothing
        // to do with the code under review.
        Path repo = npmRepo("for a in \"$@\"; do echo \"$a\" >> args.out; done; exit 0");

        EstateRebuild.Result result = new EstateRebuild().verify(repo,
                sdd.core.toolchain.Toolchain.NPM, null, ws.resolve("nodehome"),
                List.of("test"), List.of());

        assertThat(result.ok()).as("%s", result.log()).isTrue();
        assertThat(Files.readAllLines(repo.resolve("args.out"))).containsExactly("run", "test");
    }

    @Test
    void substitutionFlagsAreNeverPassedToNpm() throws Exception {
        // npm appends passthrough arguments to the end of the WHOLE script string, so a flag meant
        // for the build lands on the last command in a chained script instead.
        Path repo = npmRepo("for a in \"$@\"; do echo \"$a\" >> args.out; done; exit 0");

        new EstateRebuild().verify(repo, sdd.core.toolchain.Toolchain.NPM, null,
                ws.resolve("nodehome"), List.of("test"), List.of("--include-build", "/w/lib"));

        assertThat(Files.readAllLines(repo.resolve("args.out"))).containsExactly("run", "test");
    }

    @Test
    void anNpmRepoWithoutInstalledDependenciesSaysSo() throws Exception {
        Path repo = Files.createDirectories(ws.resolve("web"));
        Files.writeString(repo.resolve("package.json"), "{\"name\":\"web\"}");

        EstateRebuild.Result result = new EstateRebuild().verify(repo,
                sdd.core.toolchain.Toolchain.NPM, null, null, List.of("test"), List.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).contains("node_modules is not installed").contains("npm install");
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
        // Pin the full argv contract — task, then extraArgs, then the three fixed flags in that
        // order — since InfraClassifier and the log-shape conventions depend on it.
        assertThat(calls.lines()).allSatisfy(line ->
                assertThat(line).endsWith("--include-build /w/lib --no-configuration-cache --no-daemon -q"));
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
