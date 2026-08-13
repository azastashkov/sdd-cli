package sdd.cli.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RebuildPassTest {
    @TempDir Path ws;

    private static void gradlewStub(FixtureRepo repo) throws Exception {
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
    }

    private static void writeSddYml(Path ws) throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
    }

    @Test
    void contractRecheckReadsCheckpointTreeAndAllReposAreRestored() throws Exception {
        // "lib" provides a contract, and its API source exists only on the checkpoint branch. If
        // the re-check ran on whatever branch "lib" started on (the bug this task fixes), the
        // fresh extraction would find nothing at all (NOT_EXTRACTABLE) instead of reading the run's
        // real interface.
        FixtureRepo lib = FixtureRepo.in(ws, "lib");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();
        String libOriginalBranch = RunGit.currentBranch(lib.path());   // "main"

        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        lib.file("src/main/java/com/acme/Api.java",
                "package com.acme;\npublic class Api { public int f(int x) { return x; } }\n");
        lib.commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), libOriginalBranch);   // as if the user returned it there after implement

        // "other" is a second repo in scope with no contract of its own — it exists to prove the
        // restore covers every repo checked out during the pass, not just the one with a contract.
        FixtureRepo other = FixtureRepo.in(ws, "other");
        gradlewStub(other);
        other.commit("base");
        String otherBase = other.headSha();
        String otherOriginalBranch = RunGit.currentBranch(other.path());

        String otherRunBranch = "sdd/SPEC-9-v1/other";
        RunGit.startBranch(other.path(), otherRunBranch, otherBase);
        other.file("B.java", "class B {}\n");
        other.commit("checkpoint");
        String otherCheckpoint = other.headSha();
        RunGit.checkout(other.path(), otherOriginalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('other', ?, 'LIBRARY')", other.path().toString());
            });
        }
        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);

        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("other"), "Api.f", null);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("other", "dependent", "X", "patch", otherBase)),
                List.of(List.of("lib"), List.of("other")), List.of(), List.of(contract), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok"),
                new RepoRun("other", RepoState.SUCCEEDED, otherRunBranch, otherCheckpoint, "ok")), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        // Nothing is recorded for c1 in the run — this test only cares that extraction reads the
        // checkpoint tree, not what the actualize/record comparison concludes.

        Map<String, Path> paths = Map.of("lib", lib.path(), "other", other.path());
        StringWriter errOut = new StringWriter();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib", "other"), plan, state, paths,
                config, runDir, store, true, new PrintWriter(errOut));

        assertThat(outcome.contracts()).hasSize(1);
        ContractRecheck.Finding finding = outcome.contracts().get(0);
        // (a) extraction happened while "lib" was sitting on its checkpoint branch, not wherever
        // it started — proof the call-site move actually fixed the bug.
        assertThat(finding.extractedFrom()).isEqualTo(libRunBranch);
        assertThat(finding.status()).isNotEqualTo(ContractRecheck.Status.NOT_EXTRACTABLE);

        // (b) every repo in scope is back where review found it.
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(libOriginalBranch);
        assertThat(RunGit.currentBranch(other.path())).isEqualTo(otherOriginalBranch);
        assertThat(outcome.restoreFailures()).isEmpty();
    }

    @Test
    void reposFilterRebuildsOnlyTheGivenSubset() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();
        String libOriginalBranch = RunGit.currentBranch(lib.path());
        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        lib.file("A.java", "class A { int x; }\n");
        lib.commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), libOriginalBranch);

        FixtureRepo other = FixtureRepo.in(ws, "other");
        gradlewStub(other);
        other.commit("base");
        String otherBase = other.headSha();
        String otherOriginalBranch = RunGit.currentBranch(other.path());
        String otherRunBranch = "sdd/SPEC-9-v1/other";
        RunGit.startBranch(other.path(), otherRunBranch, otherBase);
        other.file("B.java", "class B {}\n");
        other.commit("checkpoint");
        String otherCheckpoint = other.headSha();
        RunGit.checkout(other.path(), otherOriginalBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);

        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("other", "dependent", "X", "patch", otherBase)),
                List.of(List.of("lib"), List.of("other")), List.of(), List.of(), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok"),
                new RepoRun("other", RepoState.SUCCEEDED, otherRunBranch, otherCheckpoint, "ok")), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        Map<String, Path> paths = Map.of("lib", lib.path(), "other", other.path());
        StringWriter errOut = new StringWriter();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib"), plan, state, paths, config,
                runDir, store, false, new PrintWriter(errOut));

        // (c) only the repo named in the filter was rebuilt.
        assertThat(outcome.rebuilds()).containsOnlyKeys("lib");
        assertThat(outcome.contracts()).isEmpty();

        // "other" was never touched — it stayed on its original branch throughout.
        assertThat(RunGit.currentBranch(other.path())).isEqualTo(otherOriginalBranch);
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(libOriginalBranch);
    }

    @Test
    void whenContractRecheckThrowsEveryRepoIsStillRestored() throws Exception {
        // ContractRecheck.check now degrades gracefully for a non-git provider path (it can't
        // throw that way any more), but RunStore.readContract can still throw for a genuinely
        // broken run dir — e.g. a contract record path that is a directory instead of a file.
        // That's the one failure inside check() this test forces, to prove the call-site move
        // didn't weaken the rebuild pass's own estate-safety guarantee: the finally must still run
        // and restore every repo even when the re-check itself blows up, not just when a checkout
        // or a rebuild does.
        FixtureRepo lib = FixtureRepo.in(ws, "lib");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();
        String libOriginalBranch = RunGit.currentBranch(lib.path());
        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        lib.file("src/main/java/com/acme/Api.java",
                "package com.acme;\npublic class Api { public int f(int x) { return x; } }\n");
        lib.commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), libOriginalBranch);

        FixtureRepo other = FixtureRepo.in(ws, "other");
        gradlewStub(other);
        other.commit("base");
        String otherBase = other.headSha();
        String otherOriginalBranch = RunGit.currentBranch(other.path());
        String otherRunBranch = "sdd/SPEC-9-v1/other";
        RunGit.startBranch(other.path(), otherRunBranch, otherBase);
        other.file("B.java", "class B {}\n");
        other.commit("checkpoint");
        String otherCheckpoint = other.headSha();
        RunGit.checkout(other.path(), otherOriginalBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);

        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("other"), "Api.f", null);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("other", "dependent", "X", "patch", otherBase)),
                List.of(List.of("lib"), List.of("other")), List.of(), List.of(contract), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok"),
                new RepoRun("other", RepoState.SUCCEEDED, otherRunBranch, otherCheckpoint, "ok")), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        // RunStore.readContract does Files.readString(<contracts>/<sanitized-id>.md) — putting a
        // directory at that exact path makes it throw IOException ("Is a directory"), wrapped as
        // UncheckedIOException, uncaught anywhere in ContractRecheck.check.
        Files.createDirectories(runDir.resolve("contracts").resolve("c1.md"));

        Map<String, Path> paths = Map.of("lib", lib.path(), "other", other.path());
        StringWriter errOut = new StringWriter();

        assertThatThrownBy(() -> RebuildPass.run(Set.of("lib", "other"), plan, state, paths,
                config, runDir, store, true, new PrintWriter(errOut)))
                .isInstanceOf(RuntimeException.class);

        // The throw happened at the end of the try block, after both checkouts — the finally must
        // still have run and put both repos back where it found them.
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(libOriginalBranch);
        assertThat(RunGit.currentBranch(other.path())).isEqualTo(otherOriginalBranch);
    }
}
