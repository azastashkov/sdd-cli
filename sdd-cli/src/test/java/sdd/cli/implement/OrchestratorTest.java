package sdd.cli.implement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void kb() {
        db = Database.open(ws);
    }

    private static ChatResponse call(String id, String tool, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, tool, args)), null), "tool_calls", new Usage(10, 5));
    }

    private FixtureRepo repoWith(String name, String gradlewScript) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + gradlewScript + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo.commit("base");
    }

    private static RepoStep step(String repo, Path root) {
        return new RepoStep(repo, root, "edit A", List.of(), List.of("A.java"), List.of(), List.of(), List.of());
    }

    private static PlanModel plan(String libBase, String svcBase) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "CODE_CHANGE_LIKELY", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD")),   // svc consumes lib
                List.of(), List.of());
    }

    private static PlanModel planFor(String repo, String base) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo(repo, "seed", "SEED", "minor", base)),
                List.of(List.of(repo)), List.of(), List.of(), List.of());
    }

    private Orchestrator orchestrator(ChatModel coder, ChatModel escalation) {
        return orchestrator(coder, escalation, Map.of());
    }

    private Orchestrator orchestrator(ChatModel coder, ChatModel escalation,
                                      Map<String, RepoPropagation> propagation) {
        return new Orchestrator(new RepoStepRunner(db.jdbi()), coder, "qwen", escalation, "deepseek",
                repo -> RunnerSettings.defaults(null), new RunStore(InstantSource.fixed(Instant.EPOCH)),
                30_000_000L, propagation, new MavenLocalPublisher());
    }

    private Orchestrator orchestrator(ScriptedChatModel model) {
        return orchestrator(model, model);   // legacy tests: same script serves both attempts
    }

    @Test
    void runsBothReposAndCommitsCheckpoints() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        // each repo: apply_edit then done → verify (gradlew exit 0) → SUCCESS
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"lib done\"}"),
                call("3", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int y; }\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"svc done\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(Files.readString(runDir.resolve("state.json"))).contains("SUCCEEDED");
        assertThat(Files.readString(lib.path().resolve("A.java"))).contains("int x;");
        assertThat(Files.exists(runDir.resolve("lib/agent-events.jsonl"))).isTrue();   // events captured
    }

    @Test
    void failedUpstreamCascadesToDownstreamSkip() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 1");   // verify always fails → lib FAILED
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        // lib's attempt 1 consumes two `done` responses (two verify-fail cycles → VERIFY_FAILED), and
        // attempt 2 (escalated) consumes two more before lib is finally FAILED; svc is never reached (skipped)
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"try3\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"try4\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
    }

    @Test
    void secondAttemptEscalatesFromAHardResetTreeAndSucceeds() throws Exception {
        // Verification passes only when A.java contains the marker the ESCALATION model writes.
        FixtureRepo lib = repoWith("lib", "grep -q escalated A.java && exit 0 || exit 1");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ScriptedChatModel coder = new ScriptedChatModel(List.of(
                call("1", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int attempt1; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalation = new ScriptedChatModel(List.of(
                call("4", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int escalated; }\"}"),
                call("5", "done", "{\"result\":\"success\",\"summary\":\"escalated fix\"}")));

        Orchestrator.RunResult result = orchestrator(coder, escalation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        String a = Files.readString(lib.path().resolve("A.java"));
        assertThat(a).contains("escalated").doesNotContain("attempt1");   // attempt 1's edit was hard-reset away
        assertThat(escalation.requests().get(0).messages())
                .anyMatch(m -> m.content() != null && m.content().contains("previous attempt"));   // digest delivered
    }

    @Test
    void endpointOutagePausesTheRunAtExit3() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ChatModel dead = req -> {
            throw new ModelException("transport error: Connection refused", new java.io.IOException("refused"));
        };

        Orchestrator.RunResult result = orchestrator(dead, dead).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_ENDPOINT);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);   // walk stopped, not cascaded
        assertThat(result.state().pausedReason()).contains("endpoint");
        assertThat(Files.readString(runDir.resolve("state.json"))).contains("PAUSED_ENDPOINT").contains("pausedReason");
    }

    @Test
    void infraFailurePausesTheRunAtExit3() throws Exception {
        FixtureRepo lib = repoWith("lib", "echo 'Could not resolve com.acme:x'; exit 1");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_INFRA);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);
    }

    @Test
    void runTokenBudgetExhaustionPausesBeforeTheNextRepo() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}")));
        Orchestrator tight = new Orchestrator(new RepoStepRunner(db.jdbi()), model, "qwen", model, "deepseek",
                repo -> RunnerSettings.defaults(null), new RunStore(InstantSource.fixed(Instant.EPOCH)),
                10L, Map.of(), new MavenLocalPublisher());   // lib's single call spends 15 tokens > 10

        Orchestrator.RunResult result = tight.run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);
        assertThat(result.state().pausedReason()).contains("token budget");
    }

    @Test
    void appliesBumpEditsBeforeTheAgentAndCommitsThem() throws Exception {
        // Verify passes ONLY if the bump is already in the tree when the gate runs — this pins the
        // bump's placement (after startBranch, before the agent/verify), not just its eventual content.
        FixtureRepo lib = repoWith("lib", "grep -q 'com.acme:core:1.1.0' build.gradle && exit 0 || exit 1");
        lib.file("build.gradle", "dependencies { implementation \"com.acme:core:1.0.0\" }\n")
                .commit("build file");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib", new RepoPropagation(
                List.of(new RepoPropagation.BumpEdit("com.acme", "core", "1.0.0", "1.1.0")), null));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Files.readString(lib.path().resolve("build.gradle")))
                .contains("com.acme:core:1.1.0");
        assertThat(RunGit.isClean(lib.path())).isTrue();   // the bump edit rode the checkpoint commit
    }

    @Test
    void bumpsAreReappliedAfterTheEscalationReset() throws Exception {
        // Attempt 1 fails verify (marker absent); the escalation's edit adds the marker. The verify
        // gate ALSO requires the bumped pin — so this passes only if applyBumps runs again after the
        // escalation-path startBranch (the hard reset wiped attempt 1's bump).
        FixtureRepo lib = repoWith("lib",
                "grep -q escalated A.java && grep -q 'com.acme:core:1.1.0' build.gradle && exit 0 || exit 1");
        lib.file("build.gradle", "dependencies { implementation \"com.acme:core:1.0.0\" }\n")
                .commit("build file");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib", new RepoPropagation(
                List.of(new RepoPropagation.BumpEdit("com.acme", "core", "1.0.0", "1.1.0")), null));
        ScriptedChatModel coder = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalation = new ScriptedChatModel(List.of(
                call("3", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int escalated; }\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"escalated\"}")));

        Orchestrator.RunResult result = orchestrator(coder, escalation, propagation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Files.readString(lib.path().resolve("build.gradle")))
                .contains("com.acme:core:1.1.0");
        assertThat(RunGit.isClean(lib.path())).isTrue();
    }

    @Test
    void publishesAMavenLocalProviderAfterSuccess() throws Exception {
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *publishToMavenLocal*) echo \"$*\" > publish-args; exit 0 ;; *) exit 0 ;; esac");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Path m2 = runDir.resolve("m2");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib",
                new RepoPropagation(List.of(), new RepoPropagation.PublishSpec("2.0.0", m2)));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        String args = Files.readString(lib.path().resolve("publish-args"));
        assertThat(args).contains("-Pversion=2.0.0")
                .contains("-Dmaven.repo.local=" + m2.toAbsolutePath());
    }

    @Test
    void infraClassifiedPublishFailurePausesTheRun() throws Exception {
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *publishToMavenLocal*) echo 'Could not resolve com.acme:x'; exit 1 ;; *) exit 0 ;; esac");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib",
                new RepoPropagation(List.of(), new RepoPropagation.PublishSpec("2.0.0", runDir.resolve("m2"))));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_INFRA);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.PENDING);   // walk stopped
    }

    @Test
    void nonInfraPublishFailureFailsTheRepoAndCascades() throws Exception {
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *publishToMavenLocal*) echo 'no publishing plugin applied'; exit 1 ;; *) exit 0 ;; esac");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        Map<String, RepoPropagation> propagation = Map.of("lib",
                new RepoPropagation(List.of(), new RepoPropagation.PublishSpec("2.0.0", runDir.resolve("m2"))));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        Orchestrator.RunResult result = orchestrator(model, model, propagation)
                .run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().repos().stream()
                .filter(r -> r.repo().equals("lib")).findFirst().orElseThrow().detail())
                .contains("publish failed");
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
    }
}
