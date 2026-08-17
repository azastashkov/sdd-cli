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
import sdd.index.testing.RecordingProgress;

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
    void aMidPassCheckoutFailureRoutesThroughProgressSuspendButStillReachesErr() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase)),
                List.of(List.of("lib")), List.of(), List.of(), List.of());

        // SUCCEEDED with a real root and a non-null branch that simply was never created in the
        // repo — unlike aProviderWithNoRunBranchOnRecordIsAlsoAStagingFailure's null-branch case
        // (caught by unstageable() before any checkout is attempted), this reaches RunGit.checkout
        // and makes IT throw: the one mid-loop warn: (RebuildPass.java's checkout try/catch)
        // ruling P4 (corrected) is about.
        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/never-created", "deadbeef",
                        "ok", null)), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        StringWriter errOut = new StringWriter();
        RecordingProgress progress = new RecordingProgress();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib"), plan, state,
                Map.of("lib", lib.path()), config, runDir, store, false,
                new PrintWriter(errOut), progress);

        assertThat(outcome.stagingFailures()).hasSize(1);
        assertThat(outcome.stagingFailures().get(0)).startsWith("lib: ");
        // Genuinely routed through Progress.suspend (not merely printed to err some other way,
        // which the next test alone could not rule out) ...
        assertThat(progress.events()).contains("suspend");
        // ... but the corrected ruling means the text still lands on err itself, unconditionally,
        // exactly as it did before Progress existed — suspend's default just runs the action.
        assertThat(errOut.toString())
                .contains("warn: could not stage lib at its checkpoint: ")
                .contains("verdicts for its consumers do not reflect this run's upstream code");
    }

    @Test
    void theSameMidPassWarningStillPrintsToErrVerbatimWhenProgressIsOff() throws Exception {
        // Progress.noOp() is exactly what --quiet and SDD_PROGRESS=off resolve to
        // (ProgressArming.factory) — this is the regression Fix 1 exists to close: an earlier
        // revision of this line went through Progress.note, which a no-op/stopped renderer may
        // legitimately drop, silently losing a substantive review finding whenever progress
        // reporting happened to be off. suspend()'s default has no such escape hatch.
        FixtureRepo lib = FixtureRepo.in(ws, "lib");
        gradlewStub(lib);
        lib.commit("base");
        String libBase = lib.headSha();

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase)),
                List.of(List.of("lib")), List.of(), List.of(), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/never-created", "deadbeef",
                        "ok", null)), null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        StringWriter errOut = new StringWriter();

        // Old 9-arg overload — no Progress argument at all, so RebuildPass falls back to
        // Progress.noOp() internally, exactly like --quiet/SDD_PROGRESS=off in production.
        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib"), plan, state,
                Map.of("lib", lib.path()), config, runDir, store, false,
                new PrintWriter(errOut));

        assertThat(outcome.stagingFailures()).hasSize(1);
        // Byte-identical to this task's starting point: the warning is on err, verbatim, whether
        // or not progress reporting happens to be on.
        assertThat(errOut.toString())
                .contains("warn: could not stage lib at its checkpoint: ")
                .contains("verdicts for its consumers do not reflect this run's upstream code");
    }

    /**
     * Fix 1's second gap: only the mid-loop checkout-failure warn was routed through
     * {@code Progress.suspend} before this task; the restore-failure warn in {@code run}'s
     * {@code finally} printed straight to {@code err} instead, which on a live TTY collided with
     * whatever frame the (unwired-for-review, but still ticking) renderer had just painted. This
     * pins the fix the same way {@code aMidPassCheckoutFailureRoutesThroughProgressSuspend...}
     * pins the original one: genuinely routed through {@link sdd.core.progress.Progress#suspend}.
     */
    @Test
    void aFailedRestoreRoutesThroughProgressSuspendButStillReachesErr() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib");
        lib.commit("base");
        String libBase = lib.headSha();
        String libOriginalBranch = RunGit.currentBranch(lib.path());   // "main"

        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        // gradlew, invoked by rebuild.verify while "lib" sits on its checkpoint branch, deletes
        // the ORIGINAL branch out from under the pass with a real git operation — the only way to
        // make the finally's restore checkout genuinely fail without corrupting git state by hand.
        Path gradlew = lib.path().resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\ngit -C " + lib.path() + " branch -D "
                + libOriginalBranch + "\nexit 0\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), libOriginalBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase)),
                List.of(List.of("lib")), List.of(), List.of(), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok", null)),
                null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        StringWriter errOut = new StringWriter();
        RecordingProgress progress = new RecordingProgress();

        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib"), plan, state,
                Map.of("lib", lib.path()), config, runDir, store, false,
                new PrintWriter(errOut), progress);

        assertThat(outcome.restoreFailures()).hasSize(1);
        assertThat(outcome.restoreFailures().get(0)).startsWith("lib: ");
        assertThat(progress.events()).contains("suspend");
        assertThat(errOut.toString()).contains("warn: could not restore lib to " + libOriginalBranch);
    }

    @Test
    void theSameFailedRestoreWarningStillPrintsToErrVerbatimWhenProgressIsOff() throws Exception {
        // Progress.noOp() is exactly what --quiet and SDD_PROGRESS=off resolve to
        // (ProgressArming.factory) — same regression Fix 1 closes for the restore-failure warn as
        // for the checkout-failure one above: suspend()'s default has no escape hatch to drop it.
        FixtureRepo lib = FixtureRepo.in(ws, "lib");
        lib.commit("base");
        String libBase = lib.headSha();
        String libOriginalBranch = RunGit.currentBranch(lib.path());   // "main"

        String libRunBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), libRunBranch, libBase);
        Path gradlew = lib.path().resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\ngit -C " + lib.path() + " branch -D "
                + libOriginalBranch + "\nexit 0\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props,
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("checkpoint");
        String libCheckpoint = lib.headSha();
        RunGit.checkout(lib.path(), libOriginalBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase)),
                List.of(List.of("lib")), List.of(), List.of(), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, libRunBranch, libCheckpoint, "ok", null)),
                null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        StringWriter errOut = new StringWriter();

        // Old 9-arg overload — no Progress argument at all, so RebuildPass falls back to
        // Progress.noOp() internally, exactly like --quiet/SDD_PROGRESS=off in production.
        RebuildPass.Outcome outcome = RebuildPass.run(Set.of("lib"), plan, state,
                Map.of("lib", lib.path()), config, runDir, store, false, new PrintWriter(errOut));

        assertThat(outcome.restoreFailures()).hasSize(1);
        assertThat(errOut.toString()).contains("warn: could not restore lib to " + libOriginalBranch);
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
    /**
     * The one test this task's brief calls out by name: a multi-repo pass where one repo fails to
     * check out and another's verify call throws must still emit a structurally-complete
     * start/finish bracket for every repo, plus the phase/detail events a live renderer needs to
     * show a per-repo line. "verify threw" is forced via a corrupted {@code
     * gradle-wrapper.properties} distribution version so large that {@code Integer.parseInt} inside
     * {@code GradleExtractor.jdkMajorFor} throws {@link NumberFormatException} while {@code
     * javaHomeFor} is evaluated as an argument to {@code rebuild.verify(...)} — inside RebuildPass's
     * own try, ahead of the actual subprocess call, so this pins the exact catch clause at issue
     * without needing a real Gradle failure to reach it.
     */
    @Test
    void multiRepoRebuildEmitsAFullStartFinishBracketEvenForFailingRepos() throws Exception {
        FixtureRepo unstaged = FixtureRepo.in(ws, "unstaged");
        unstaged.commit("base");
        String unstagedBase = unstaged.headSha();

        FixtureRepo checkoutFails = FixtureRepo.in(ws, "checkoutFails");
        gradlewStub(checkoutFails);
        checkoutFails.commit("base");
        String checkoutFailsBase = checkoutFails.headSha();

        FixtureRepo verifyThrows = FixtureRepo.in(ws, "verifyThrows");
        gradlewStub(verifyThrows);
        // Overwrite with a distribution version so large Integer.parseInt overflows inside
        // GradleExtractor.jdkMajorFor -> versionAtLeast, forcing javaHomeFor to throw.
        Files.writeString(verifyThrows.path().resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/"
                        + "gradle-99999999999999999999-bin.zip\n");
        verifyThrows.commit("base");
        String verifyThrowsBase = verifyThrows.headSha();
        String verifyThrowsOriginalBranch = RunGit.currentBranch(verifyThrows.path());
        String verifyThrowsRunBranch = "sdd/SPEC-9-v1/verifyThrows";
        RunGit.startBranch(verifyThrows.path(), verifyThrowsRunBranch, verifyThrowsBase);
        verifyThrows.file("A.java", "class A {}\n").commit("checkpoint");
        String verifyThrowsCheckpoint = verifyThrows.headSha();
        RunGit.checkout(verifyThrows.path(), verifyThrowsOriginalBranch);

        writeSddYml(ws);
        SddConfig config = ConfigLoader.load(ws);

        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("unstaged", "seed", "SEED", "minor", unstagedBase),
                        new PlanModel.PlanRepo("checkoutFails", "seed", "SEED", "minor", checkoutFailsBase),
                        new PlanModel.PlanRepo("verifyThrows", "seed", "SEED", "minor", verifyThrowsBase)),
                List.of(List.of("unstaged"), List.of("checkoutFails"), List.of("verifyThrows")),
                List.of(), List.of(), List.of());

        RunState state = new RunState("SPEC-9-v1", List.of(
                // Not SUCCEEDED -> the unstageable() branch, never reaches a checkout at all.
                new RepoRun("unstaged", RepoState.FAILED, null, null, "boom", null),
                // SUCCEEDED with a branch that was never created -> RunGit.checkout throws.
                new RepoRun("checkoutFails", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/never-created",
                        "deadbeef", "ok", null),
                new RepoRun("verifyThrows", RepoState.SUCCEEDED, verifyThrowsRunBranch,
                        verifyThrowsCheckpoint, "ok", null)),
                null, 0L);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", "{}", "");
        Map<String, Path> paths = Map.of("unstaged", unstaged.path(), "checkoutFails", checkoutFails.path(),
                "verifyThrows", verifyThrows.path());
        StringWriter errOut = new StringWriter();
        RecordingProgress progress = new RecordingProgress();

        RebuildPass.Outcome outcome = RebuildPass.run(
                Set.of("unstaged", "checkoutFails", "verifyThrows"), plan, state, paths, config,
                runDir, store, false, new PrintWriter(errOut), progress);

        // All three verdicts are failures, for the three different reasons under test.
        assertThat(outcome.stagingFailures()).hasSize(2);   // unstaged + checkoutFails
        assertThat(outcome.rebuilds().get("checkoutFails").ok()).isFalse();
        assertThat(outcome.rebuilds().get("verifyThrows").ok()).isFalse();
        assertThat(outcome.rebuilds().get("verifyThrows").log()).startsWith("verification threw: ");

        List<String> events = progress.events();
        // The phase bracketing the whole per-repo loop is sized to every repo in scope, not just
        // the ones that end up rebuilt.
        assertThat(events).contains("phase:rebuild:3");
        // Every repo gets a start/finish pair, in the SAME relative order the loop visits them,
        // regardless of which exit path it took out of the per-repo try.
        assertThat(events).containsSubsequence("start:unstaged", "finish:unstaged",
                "start:checkoutFails", "finish:checkoutFails",
                "start:verifyThrows", "finish:verifyThrows");
        // The repo that reached a real checkout attempt got a "checkout" detail before it failed.
        assertThat(events).containsSubsequence("start:checkoutFails", "detail:checkout",
                "finish:checkoutFails");
        // The repo whose verify() blew up still got its "verify" detail first.
        assertThat(events).containsSubsequence("start:verifyThrows", "detail:checkout",
                "detail:verify", "finish:verifyThrows");
        // The unstageable repo never reached a checkout at all -> no "checkout" detail between its
        // own start/finish.
        int unstagedStart = events.indexOf("start:unstaged");
        int unstagedFinish = events.indexOf("finish:unstaged");
        assertThat(events.subList(unstagedStart, unstagedFinish + 1)).doesNotContain("detail:checkout");
        // Every mid-pass warn: still routes through suspend, unconditionally, alongside the new
        // events — this task must not have disturbed that.
        assertThat(events).contains("suspend");
        assertThat(errOut.toString()).contains("warn: could not stage unstaged")
                .contains("warn: could not stage checkoutFails");
        // Restore is estate-wide, reported once after the loop (and after "contracts" would have
        // been, had recheckContracts been true here).
        assertThat(events).contains("phase:restore:0");
    }

    @Test
    void gateTwoResolvesTheSameVerificationTasksAsImplement() throws Exception {
        // The invariant that was actually broken. RebuildPass carried its own copy of the
        // resolution, so Gate 2 could verify something different from what the agent was held to —
        // and for an npm repo the copy resolved to `check`, a task npm cannot run at all.
        java.nio.file.Path repo = java.nio.file.Files.createDirectories(ws.resolve("web"));
        java.nio.file.Files.writeString(repo.resolve("package.json"),
                "{\"name\":\"web\",\"scripts\":{\"typecheck\":\"tsc --noEmit\",\"test\":\"vitest run\"}}");
        PlanModel plan = new PlanModel("SPEC-9", 1, "", "",
                List.of(new PlanModel.PlanRepo("web", "seed", "SEED", "none", "sha")),
                List.of(List.of("web")), List.of(), List.of(), List.of());
        SddConfig config = new SddConfig(ws, java.util.Map.of(), java.util.Map.of(), null,
                List.of(), java.util.Map.of(), List.of(), List.of(),
                sdd.core.config.RunSettings.defaults(), java.util.Map.of());

        List<String> viaGateTwo = RebuildPass.tasksFor(plan, config, "web", repo);
        List<String> viaImplement = sdd.cli.implement.VerificationTasks.resolve(
                sdd.core.toolchain.Toolchain.detect(repo), repo, List.of(), List.of());

        assertThat(viaGateTwo).isEqualTo(viaImplement).containsExactly("typecheck", "test");
    }

}
