package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.db.Database;
import sdd.core.progress.Progress;
import sdd.core.testing.FixtureRepo;
import sdd.index.testing.RecordingProgress;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReviewCommand}'s own progress wiring — the {@code review} counterpart of {@code
 * IndexCommandProgressTest}/{@code ImplementCommandProgressTest}, which this file mirrors: same
 * {@code progressForTest} seam, same reason (design doc "Arming": no test in this tree calls
 * {@code SddCli.main}). {@code RebuildPassTest} already proves {@code RebuildPass} itself routes
 * the mid-pass checkout-failure warn through {@link Progress#note}; this file proves {@link
 * ReviewCommand#call} actually threads a real {@link Progress} down into {@code RebuildPass.run}
 * and stops it before the report prints — the one part {@code RebuildPassTest}, which calls
 * {@code RebuildPass.run} directly, cannot see.
 */
class ReviewCommandProgressTest {
    @TempDir Path ws;

    private static final class StopMarksSharedBuffer implements Progress {
        private final StringWriter shared;

        StopMarksSharedBuffer(StringWriter shared) {
            this.shared = shared;
        }

        @Override public void phase(String name, int total) { }
        @Override public void start(String item) { }
        @Override public void finish(String item) { }
        @Override public void detail(String text) { }
        @Override public void note(String text) { }

        @Override
        public void stop() {
            shared.append("<<progress stopped>>\n");
        }
    }

    private void writeSpecAndConfig(String specId) throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), ("""
                ---
                id: %s
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """).formatted(specId));
    }

    @Test
    void callThreadsProgressIntoRebuildPass() throws Exception {
        // A SUCCEEDED repo whose recorded run branch was never actually created — RebuildPass's
        // mid-pass checkout try/catch throws, and (RebuildPassTest already proves, directly
        // against RebuildPass.run) that warn is routed through Progress.note. Here we only need
        // proof this command actually hands RebuildPass a real Progress rather than always
        // resolving Progress.noOp() regardless of what progressForTest was set to.
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        lib.commit("base");
        String baseSha = lib.headSha();

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        writeSpecAndConfig("SPEC-9");
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        RunState state = new RunState("SPEC-9-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/never-created", "deadbeef",
                        "ok", null)), null, 0L);
        store.writeState(runDir, state);

        ReviewCommand cmd = new ReviewCommand();
        RecordingProgress progress = new RecordingProgress();
        cmd.progressForTest = progress;
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(new StringWriter(), true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(progress.events()).anyMatch(e -> e.startsWith("note:warn: could not stage lib "
                + "at its checkpoint: "));
    }

    @Test
    void stopFiresBeforeTheReportPrintsNotJustEventually() throws Exception {
        FixtureRepo lib = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = lib.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = lib.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        lib.commit("base");
        String baseSha = lib.headSha();
        String originalBranch = RunGit.currentBranch(lib.path());

        String runBranch = "sdd/SPEC-9-v1/lib";
        RunGit.startBranch(lib.path(), runBranch, baseSha);
        lib.file("A.java", "class A { int x; }\n");
        lib.commit("checkpoint");
        String checkpointSha = lib.headSha();
        RunGit.checkout(lib.path(), originalBranch);

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        writeSpecAndConfig("SPEC-9");
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, baseSha);
        Files.writeString(ws.resolve("s.plan.json"), planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, "SPEC-9-v1", planJson, Files.readString(ws.resolve("s.md")));
        store.releaseLock(runDir);
        RunState state = new RunState("SPEC-9-v1",
                List.of(new RepoRun("lib", RepoState.SUCCEEDED, runBranch, checkpointSha, "ok", null)), null, 15L);
        store.writeState(runDir, state);

        ReviewCommand cmd = new ReviewCommand();
        StringWriter sharedOut = new StringWriter();
        cmd.progressForTest = new StopMarksSharedBuffer(sharedOut);
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(sharedOut, true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        String text = sharedOut.toString();
        int stopIndex = text.indexOf("<<progress stopped>>");
        int firstReportLine = text.indexOf("review written: ");
        assertThat(stopIndex).as("stop() must have fired at all").isGreaterThanOrEqualTo(0);
        assertThat(firstReportLine).as("and before the report's own first line").isGreaterThan(stopIndex);
    }
}
