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
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        // no gradlew; base_sha points at a different sha
        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase("0000000000000000000000000000000000000000"));

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("gradle wrapper"))
                .anyMatch(p -> p.contains("HEAD"));
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
