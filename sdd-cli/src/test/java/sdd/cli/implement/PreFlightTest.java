package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.RepoStep;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreFlightTest {
    @TempDir Path tmp;

    private RepoStep step(Path root) {
        return new RepoStep("lib", root, "s", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private void gradlew(Path root) throws Exception {
        Path g = root.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private PlanModel planWithBase(String base) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", base)),
                List.of(List.of("lib")), List.of(), List.of(), List.of());
    }

    @Test
    void passesOnACleanRepoAtBaseSha() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        gradlew(repo.path());
        repo.commit("with gradlew");
        String base = RunGit.head(repo.path());

        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase(base));

        assertThat(result.ok()).isTrue();
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void flagsDriftAndMissingWrapper() throws Exception {
        // A build file but no wrapper: unmistakably a Gradle repo that cannot be built, which is a
        // different complaint from a directory whose build system cannot be identified at all.
        FixtureRepo repo = FixtureRepo.in(tmp, "lib")
                .file("build.gradle", "plugins { id 'java' }\n")
                .file("A.java", "class A {}\n").commit("base");
        // no gradlew; base_sha points at a different sha
        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase("0000000000000000000000000000000000000000"));

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("gradle wrapper"))
                .anyMatch(p -> p.contains("HEAD"));
    }

    @Test
    void anNpmRepoNeedsItsDependenciesInstalledRatherThanAGradleWrapper() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib")
                .file("package.json", "{\"name\":\"web\",\"scripts\":{\"test\":\"vitest run\"}}\n")
                .commit("base");

        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase(RunGit.head(repo.path())));

        assertThat(result.ok()).isFalse();
        // sdd never runs npm install itself — it mutates the tree mid-run and can reach the
        // network at an arbitrary moment — so it refuses with something the operator can act on.
        assertThat(result.problems()).anyMatch(p -> p.contains("node_modules is not installed"))
                .noneMatch(p -> p.contains("gradle wrapper"));
    }

    @Test
    void anInstalledNpmRepoPassesPreFlight() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib")
                .file("package.json", "{\"name\":\"web\",\"scripts\":{\"test\":\"vitest run\"}}\n")
                .commit("base");
        java.nio.file.Files.createDirectories(repo.path().resolve("node_modules"));

        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase(RunGit.head(repo.path())));

        assertThat(result.problems()).noneMatch(p -> p.contains("gradle"))
                .noneMatch(p -> p.contains("node_modules"));
    }

    @Test
    void aRepoWithNoBuildAtAllSaysSoRatherThanBlamingGradle() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("README.md", "# docs\n").commit("base");

        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase(RunGit.head(repo.path())));

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("cannot determine build system"));
    }

    @Test
    void flagsAMissingBaseShaInThePlan() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        gradlew(repo.path());
        repo.commit("with gradlew");

        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase(""));

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("base SHA"));
    }
}
