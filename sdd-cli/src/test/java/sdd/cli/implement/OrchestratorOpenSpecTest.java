package sdd.cli.implement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.RepoStep;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.openspec.OpenSpecInput;
import sdd.plan.openspec.OpenSpecPlan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The export reaching a real checkpoint. Asserting it is committed rather than merely present on
 * disk is the point: {@code RunGit.commitAll} is JGit's {@code AddCommand}, which honours
 * {@code .gitignore}, so "the file exists" and "the file is in the change a human reviews" are
 * genuinely different claims.
 */
class OrchestratorOpenSpecTest {

    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void kb() {
        db = Database.open(ws);
    }

    private static ChatResponse call(String id, String name, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, name, args)), null), "tool_calls", new Usage(10, 5));
    }

    private static PlanModel plan(String base) {
        return new PlanModel("SPEC-T", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", base)),
                List.of(List.of("lib")), List.of(), List.of(),
                List.of(new PlanModel.PlanStep("lib", List.of("R1"), "minor", List.of(), List.of(),
                        List.of("A.java"), List.of("./gradlew test"), "Do it.",
                        List.of("capability: tier-resolution", "R1 -> A1"))));
    }

    private static OpenSpecInput input() {
        return new OpenSpecInput("spec-t-v1", "lib", "SEED", List.of("lib"), List.of(List.of("lib")),
                "SPEC-T", 1, "Title",
                "A goal comfortably longer than fifty characters so the why check has no work to do.",
                "", List.of(), List.of(), List.of(),
                List.of(new OpenSpecInput.Item("R1", "Expose an invalidate entry point.")),
                List.of(new OpenSpecInput.Item("A1", "The next resolution returns the new tier.")),
                new OpenSpecPlan("tier-resolution", Map.of("R1", List.of("A1")), List.of()),
                "Do it.", List.of("A.java"), List.of("./gradlew test"), "minor",
                List.of(), List.of(), List.of(), "aaaa", Map.of());
    }

    private Orchestrator orchestrator(ScriptedChatModel model) {
        Orchestrator o = new Orchestrator(new sdd.agent.run.RepoStepRunner(db.jdbi()),
                List.of(new Orchestrator.ModelTier(model, "m")),
                repo -> sdd.agent.run.RunnerSettings.defaults(null),
                new RunStore(InstantSource.fixed(Instant.EPOCH)), 30_000_000L, Map.of(),
                new MavenLocalPublisher(), new JarBuilder());
        o.openSpecInputs(Map.of("lib", input()));
        return o;
    }

    private FixtureRepo repoWith(String name, String gradlewScript) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + gradlewScript + "\n");
        Files.setPosixFilePermissions(g,
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo.commit("base");
    }

    private static RepoStep step(String repo, Path root) {
        return new RepoStep(repo, root, "Do it.", List.of(), List.of("A.java"), List.of(), List.of(),
                List.of());
    }

    @Test
    void aSucceedingRepoHasTheExportInsideItsCheckpointCommit() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        // Captured BEFORE the run: after it, HEAD is the checkpoint, so re-reading headSha() at
        // assertion time would diff the checkpoint against itself and pass on an empty diff.
        String base = lib.headSha();
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "SPEC-T-v1", "{}");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "apply_edit",
                        "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(base),
                Map.of("lib", step("lib", lib.path())));

        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        String sha = result.state().repos().get(0).checkpointSha();
        // Committed, not merely written: the diff is what a Gate-2 reviewer and a foreign agent see.
        assertThat(RunGit.diff(lib.path(), base, sha))
                .contains("openspec/changes/spec-t-v1/proposal.md")
                .contains("openspec/changes/spec-t-v1/specs/tier-resolution/spec.md")
                .contains("openspec/changes/spec-t-v1/tasks.md");
    }

    @Test
    void theRunKeepsItsOwnCopyEvenWhenTheRepoFails() throws Exception {
        // Hook A runs at branch start, so a FAILED repo still leaves a slice a human can hand over
        // by hand — while the tree copy, which only lands on SUCCESS, is correctly absent.
        FixtureRepo lib = repoWith("lib", "exit 1");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "SPEC-T-v1", "{}");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"blocked\",\"summary\":\"cannot\"}"),
                call("2", "done", "{\"result\":\"blocked\",\"summary\":\"cannot\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(lib.headSha()),
                Map.of("lib", step("lib", lib.path())));

        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(runDir.resolve("lib/openspec/changes/spec-t-v1/proposal.md")).exists();
        assertThat(lib.path().resolve("openspec")).doesNotExist();
    }

    @Test
    void aRunWithNoExportConfiguredWritesNone() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "SPEC-T-v1", "{}");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"done\"}")));
        Orchestrator o = new Orchestrator(new sdd.agent.run.RepoStepRunner(db.jdbi()),
                List.of(new Orchestrator.ModelTier(model, "m")),
                repo -> sdd.agent.run.RunnerSettings.defaults(null),
                new RunStore(InstantSource.fixed(Instant.EPOCH)), 30_000_000L, Map.of(),
                new MavenLocalPublisher(), new JarBuilder());

        o.run(runDir, plan(lib.headSha()), Map.of("lib", step("lib", lib.path())));

        assertThat(lib.path().resolve("openspec")).doesNotExist();
        assertThat(runDir.resolve("lib/openspec")).doesNotExist();
    }
}
