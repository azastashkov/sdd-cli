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
                List.of("other"), "Api.f", null, List.of());
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("other", "dependent", "X", "patch", otherBase)),
                List.of(List.of("lib"), List.of("other")), List.of(), List.of(contract), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok", null),
                new RepoRun("other", RepoState.SUCCEEDED, otherRunBranch, otherCheckpoint, "ok", null)), null, 0L);

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
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok", null),
                new RepoRun("other", RepoState.SUCCEEDED, otherRunBranch, otherCheckpoint, "ok", null)), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        Map<String, Path> paths = Map.of("lib", lib.path(), "other", other.path());
        StringWriter errOut = new StringWriter();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib"), plan, state, paths, config,
                runDir, store, false, new PrintWriter(errOut));

        // (c) only the repo named in the filter was REBUILT. "other" is still staged at its
        // checkpoint during the pass (see stagesEveryRepo… below) — it just gets no verdict.
        assertThat(outcome.rebuilds()).containsOnlyKeys("lib");
        assertThat(outcome.contracts()).isEmpty();

        // Both repos are back where the pass found them.
        assertThat(RunGit.currentBranch(other.path())).isEqualTo(otherOriginalBranch);
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(libOriginalBranch);
    }

    @Test
    void stagesEveryRepoAtItsCheckpointEvenOutsideTheRebuildSubset() throws Exception {
        // The bug this pins: a consumer's verification composes its provider through
        // --include-build, which points at the provider's LIVE WORKING TREE. Staging only the
        // rebuild subset therefore verified "svc" against whatever pre-run code "lib" happened to
        // be sitting on — a green verdict that means nothing to the human reading it.
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();
        String libOriginalBranch = RunGit.currentBranch(lib.path());
        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        lib.file("A.java", "class A { int x; }\n").commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), libOriginalBranch);

        // svc's stub records what "lib"'s working tree looked like AT THE MOMENT svc was verified —
        // the only way to observe staging from inside the pass rather than after its finally.
        FixtureRepo svc = FixtureRepo.in(ws, "svc");
        Path witness = ws.resolve("observed-upstream.txt");
        Path svcGradlew = svc.path().resolve("gradlew");
        Files.writeString(svcGradlew, "#!/bin/sh\ncat " + lib.path().resolve("A.java")
                + " > " + witness + "\nexit 0\n");
        Files.setPosixFilePermissions(svcGradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path svcProps = svc.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(svcProps.getParent());
        Files.writeString(svcProps,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        svc.commit("base");
        String svcBase = svc.headSha();
        String svcOriginalBranch = RunGit.currentBranch(svc.path());
        String svcRunBranch = "sdd/SPEC-9-v1/svc";
        RunGit.startBranch(svc.path(), svcRunBranch, svcBase);
        svc.file("S.java", "class S {}\n").commit("checkpoint");
        String svcCheckpoint = svc.headSha();
        RunGit.checkout(svc.path(), svcOriginalBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);

        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok", null),
                new RepoRun("svc", RepoState.SUCCEEDED, svcRunBranch, svcCheckpoint, "ok", null)), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        Map<String, Path> paths = Map.of("lib", lib.path(), "svc", svc.path());
        StringWriter errOut = new StringWriter();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("svc"), plan, state, paths, config,
                runDir, store, false, new PrintWriter(errOut));

        // "lib" is outside the subset, so it gets no verdict — but svc was verified against lib's
        // CHECKPOINT tree, not the pre-run one it was parked on.
        assertThat(outcome.rebuilds()).containsOnlyKeys("svc");
        assertThat(outcome.rebuilds().get("svc").ok()).isTrue();
        assertThat(Files.readString(witness)).contains("int x;");

        // And the provider staged only as an upstream tree is restored exactly like the rest.
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(libOriginalBranch);
        assertThat(RunGit.currentBranch(svc.path())).isEqualTo(svcOriginalBranch);
        assertThat(Files.readString(lib.path().resolve("A.java"))).isEqualTo("class A {}\n");
        assertThat(outcome.restoreFailures()).isEmpty();
    }

    @Test
    void aRepoWithEveryVerificationTaskExcludedIsStillStagedAsAnUpstreamTree() throws Exception {
        // The "not locally verified" guard used to short-circuit BEFORE the checkout, so such a
        // repo was never staged. It is a provider like any other — its consumers compose its
        // working tree through --include-build, and ContractRecheck assumes every repo is sitting
        // on its checkpoint — so it must be staged even though it will never be verified itself.
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();
        String libOriginalBranch = RunGit.currentBranch(lib.path());
        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        lib.file("A.java", "class A { int x; }\n").commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), libOriginalBranch);

        FixtureRepo svc = FixtureRepo.in(ws, "svc");
        Path witness = ws.resolve("observed-upstream.txt");
        Path svcGradlew = svc.path().resolve("gradlew");
        Files.writeString(svcGradlew, "#!/bin/sh\ncat " + lib.path().resolve("A.java")
                + " > " + witness + "\nexit 0\n");
        Files.setPosixFilePermissions(svcGradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path svcProps = svc.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(svcProps.getParent());
        Files.writeString(svcProps,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        svc.commit("base");
        String svcBase = svc.headSha();
        String svcOriginalBranch = RunGit.currentBranch(svc.path());
        String svcRunBranch = "sdd/SPEC-9-v1/svc";
        RunGit.startBranch(svc.path(), svcRunBranch, svcBase);
        svc.file("S.java", "class S {}\n").commit("checkpoint");
        String svcCheckpoint = svc.headSha();
        RunGit.checkout(svc.path(), svcOriginalBranch);

        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                verification_exclusions:
                  lib: [check]
                """);
        SddConfig config = ConfigLoader.load(ws);

        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok", null),
                new RepoRun("svc", RepoState.SUCCEEDED, svcRunBranch, svcCheckpoint, "ok", null)), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        StringWriter errOut = new StringWriter();

        // The FULL-review shape: every repo is in scope, lib simply has nothing runnable.
        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib", "svc"), plan, state,
                Map.of("lib", lib.path(), "svc", svc.path()), config, runDir, store, false,
                new PrintWriter(errOut));

        assertThat(outcome.notLocallyVerified()).containsExactly("lib");
        assertThat(outcome.rebuilds()).containsOnlyKeys("svc");
        assertThat(Files.readString(witness)).contains("int x;");   // staged despite being unverifiable
        assertThat(outcome.stagingFailures()).isEmpty();
        assertThat(RunGit.currentBranch(lib.path())).isEqualTo(libOriginalBranch);
        assertThat(Files.readString(lib.path().resolve("A.java"))).isEqualTo("class A {}\n");
    }

    /**
     * A provider that cannot be staged AT ALL — because the run never left it SUCCEEDED, or because
     * no run branch was ever recorded for it — is not a repo the pass may quietly skip.
     * {@code Propagation.includeBuildArgs} composes every INCLUDE_BUILD provider's path
     * unconditionally, whatever its run state, so the consumer below is verified against that
     * provider's PRE-RUN working tree. That is the same finding a failed checkout produces, and it
     * has to reach {@code stagingFailures} for the same reason: {@code unstagedRepos} →
     * {@code voidedBy} → the exit code all key off that list, and a verdict computed over pre-run
     * upstream code must never read as a clean pass.
     */
    @Test
    void aProviderThatIsNotSucceededIsRecordedAsAStagingFailureRatherThanSilentlySkipped()
            throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();
        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        lib.file("A.java", "class A { int x; }\n").commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), "main");   // parked on pre-run code, exactly as after a failure

        FixtureRepo svc = FixtureRepo.in(ws, "svc");
        Path witness = ws.resolve("observed-upstream.txt");
        Path svcGradlew = svc.path().resolve("gradlew");
        Files.writeString(svcGradlew, "#!/bin/sh\ncat " + lib.path().resolve("A.java")
                + " > " + witness + "\nexit 0\n");
        Files.setPosixFilePermissions(svcGradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path svcProps = svc.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(svcProps.getParent());
        Files.writeString(svcProps,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        svc.commit("base");
        String svcBase = svc.headSha();
        String svcRunBranch = "sdd/SPEC-9-v1/svc";
        RunGit.startBranch(svc.path(), svcRunBranch, svcBase);
        svc.file("S.java", "class S {}\n").commit("checkpoint");
        String svcCheckpoint = svc.headSha();

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
        // lib FAILED with its branch still on record: RebuildPass's state filter skips it before it
        // ever looks at the branch, so nothing stages it and nothing said so.
        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.FAILED, libRunBranch, libCheckpoint, "boom", null),
                new RepoRun("svc", RepoState.SUCCEEDED, svcRunBranch, svcCheckpoint, "ok", null)), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        StringWriter errOut = new StringWriter();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("svc"), plan, state,
                Map.of("lib", lib.path(), "svc", svc.path()), config, runDir, store, false,
                new PrintWriter(errOut));

        // svc really was built against lib's pre-run tree — the verdict is worthless...
        assertThat(Files.readString(witness)).doesNotContain("int x;");
        // ...so the pass must say so, in the "<repo>: " shape unstagedRepos/replaceForRepos parse.
        assertThat(outcome.stagingFailures())
                .containsExactly("lib: not SUCCEEDED in this run, composed from its working tree");
        assertThat(errOut.toString()).contains("could not stage lib");
        assertThat(outcome.rebuilds()).containsOnlyKeys("svc");   // svc still gets its verdict
    }

    @Test
    void aProviderWithNoRunBranchOnRecordIsAlsoAStagingFailure() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();

        FixtureRepo svc = FixtureRepo.in(ws, "svc");
        gradlewStub(svc);
        svc.commit("base");
        String svcBase = svc.headSha();
        String svcRunBranch = "sdd/SPEC-9-v1/svc";
        RunGit.startBranch(svc.path(), svcRunBranch, svcBase);
        svc.file("S.java", "class S {}\n").commit("checkpoint");
        String svcCheckpoint = svc.headSha();

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
        // SUCCEEDED but branchless — a hand-edited or truncated state.json. The pass used to skip
        // it just as silently as the FAILED case above.
        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, null, null, "ok", null),
                new RepoRun("svc", RepoState.SUCCEEDED, svcRunBranch, svcCheckpoint, "ok", null)), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        StringWriter errOut = new StringWriter();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("svc"), plan, state,
                Map.of("lib", lib.path(), "svc", svc.path()), config, runDir, store, false,
                new PrintWriter(errOut));

        assertThat(outcome.stagingFailures()).containsExactly("lib: no run branch on record");
        assertThat(errOut.toString()).contains("could not stage lib");
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
                List.of("other"), "Api.f", null, List.of());
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("other", "dependent", "X", "patch", otherBase)),
                List.of(List.of("lib"), List.of("other")), List.of(), List.of(contract), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok", null),
                new RepoRun("other", RepoState.SUCCEEDED, otherRunBranch, otherCheckpoint, "ok", null)), null, 0L);

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
