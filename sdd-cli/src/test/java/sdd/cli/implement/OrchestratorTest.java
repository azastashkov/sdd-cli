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
                30_000_000L, propagation, new MavenLocalPublisher(), new JarBuilder());
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
        // observability: lib's apply_edit + done turns and its one applied edit land on disk too
        String libTranscript = Files.readString(runDir.resolve("lib/transcript.jsonl"));
        assertThat(libTranscript).contains("\"turn\":1").contains("\"name\":\"apply_edit\"")
                .contains("\"turn\":2").contains("\"name\":\"done\"");
        String libEdits = Files.readString(runDir.resolve("lib/edits.jsonl"));
        assertThat(libEdits).contains("\"path\":\"A.java\"").contains("\"action\":\"edit\"");
        assertThat(Files.exists(runDir.resolve("svc/transcript.jsonl"))).isTrue();
        assertThat(Files.exists(runDir.resolve("svc/edits.jsonl"))).isTrue();
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
        FixtureRepo lib = repoWith("lib", "echo 'Could not resolve com.acme:x'; echo 'Connection refused'; exit 1");
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
                10L, Map.of(), new MavenLocalPublisher(), new JarBuilder());   // lib's single call spends 15 tokens > 10

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
                "case \"$*\" in *publishToMavenLocal*) echo 'Could not resolve com.acme:x'; "
                        + "echo 'Connection refused'; exit 1 ;; *) exit 0 ;; esac");
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

    private static PlanModel planTwoIndependent(String aBase, String bBase) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("alib", "seed", "SEED", "minor", aBase),
                        new PlanModel.PlanRepo("blib", "seed", "SEED", "minor", bBase)),
                List.of(List.of("alib"), List.of("blib")),
                List.of(),   // no edges: both units land in one layer
                List.of(), List.of());
    }

    @Test
    void independentReposRunConcurrentlyWithinALayer() throws Exception {
        // Each verify stub writes its start marker then waits (max ~8s) for the OTHER repo's
        // marker. Sequential execution can never satisfy the first repo; parallel satisfies both.
        String scriptA = "touch " + ws.resolve("a-started") + "; i=0; "
                + "while [ ! -f " + ws.resolve("b-started") + " ] && [ $i -lt 80 ]; do sleep 0.1; i=$((i+1)); done; "
                + "[ -f " + ws.resolve("b-started") + " ]";
        String scriptB = "touch " + ws.resolve("b-started") + "; i=0; "
                + "while [ ! -f " + ws.resolve("a-started") + " ] && [ $i -lt 80 ]; do sleep 0.1; i=$((i+1)); done; "
                + "[ -f " + ws.resolve("a-started") + " ]";
        FixtureRepo alib = repoWith("alib", scriptA);
        FixtureRepo blib = repoWith("blib", scriptB);
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("alib", step("alib", alib.path()),
                "blib", step("blib", blib.path()));
        // Thread-safe model double: every call is an identical done() — safe under concurrency.
        ChatModel parallelSafe = req -> call("x", "done", "{\"result\":\"success\",\"summary\":\"ok\"}");

        Orchestrator.RunResult result = orchestrator(parallelSafe, parallelSafe)
                .run(runDir, planTwoIndependent(alib.headSha(), blib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("alib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("blib")).isEqualTo(RepoState.SUCCEEDED);
    }

    private static PlanModel planSharedIncludeBuildProvider(String libBase, String aBase, String bBase) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svcA", "dependent", "CODE_CHANGE_LIKELY", "patch", aBase),
                        new PlanModel.PlanRepo("svcB", "dependent", "CODE_CHANGE_LIKELY", "patch", bBase)),
                List.of(List.of("lib"), List.of("svcA"), List.of("svcB")),
                List.of(new PlanModel.PlanEdge("svcA", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
                        new PlanModel.PlanEdge("svcB", "lib", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
    }

    @Test
    void consumersSharingAnIncludeBuildProviderNeverOverlap() throws Exception {
        // Inverse of independentReposRunConcurrentlyWithinALayer: svcA/svcB share lib's checkout via
        // --include-build, so the merged unit must SERIALIZE them. Each verify stub raises its own
        // "running" flag, sleeps, and records an overlap marker if it ever sees the other's flag.
        String scriptA = "touch " + ws.resolve("svcA.running") + "; "
                + "[ -f " + ws.resolve("svcB.running") + " ] && touch " + ws.resolve("overlap") + "; "
                + "sleep 0.3; "
                + "[ -f " + ws.resolve("svcB.running") + " ] && touch " + ws.resolve("overlap") + "; "
                + "rm -f " + ws.resolve("svcA.running") + "; exit 0";
        String scriptB = "touch " + ws.resolve("svcB.running") + "; "
                + "[ -f " + ws.resolve("svcA.running") + " ] && touch " + ws.resolve("overlap") + "; "
                + "sleep 0.3; "
                + "[ -f " + ws.resolve("svcA.running") + " ] && touch " + ws.resolve("overlap") + "; "
                + "rm -f " + ws.resolve("svcB.running") + "; exit 0";
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svcA = repoWith("svcA", scriptA);
        FixtureRepo svcB = repoWith("svcB", scriptB);
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()),
                "svcA", step("svcA", svcA.path()), "svcB", step("svcB", svcB.path()));
        ChatModel parallelSafe = req -> call("x", "done", "{\"result\":\"success\",\"summary\":\"ok\"}");

        Orchestrator.RunResult result = orchestrator(parallelSafe, parallelSafe)
                .run(runDir, planSharedIncludeBuildProvider(lib.headSha(), svcA.headSha(), svcB.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("svcA")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("svcB")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(Files.exists(ws.resolve("overlap"))).isFalse();   // serialized by the merged unit
    }

    @Test
    void budgetPauseWritesARunLevelEventLine() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}")));
        Orchestrator tight = new Orchestrator(new RepoStepRunner(db.jdbi()), model, "qwen", model,
                "deepseek", repo -> RunnerSettings.defaults(null),
                new RunStore(InstantSource.fixed(Instant.EPOCH)), 10L, Map.of(), new MavenLocalPublisher(),
                new JarBuilder());

        Orchestrator.RunResult result = tight.run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(Files.readString(runDir.resolve("events.jsonl")))
                .contains("\"run\":\"pause\"").contains("token budget");
    }

    @Test
    void partialTokensFromAnEndpointFailureCountAgainstTheBudget() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ChatModel dead = req -> {
            throw new ModelException("transport error: refused", new java.io.IOException("x"))
                    .withTokens(12345L);
        };

        Orchestrator.RunResult result = orchestrator(dead, dead)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().tokensSpent()).isEqualTo(12345L);
    }

    @Test
    void anInfraFailureOnTheEscalatedAttemptStillPausesTheRun() throws Exception {
        // Attempt 1: two verify-fail cycles (plain exit 1 while the marker is absent) -> VERIFY_FAILED.
        // Attempt 2: the escalation writes the marker; with it present the stub emits an
        // infra-classified failure — twice (retry-once) -> StepResult.INFRA -> PAUSED_INFRA.
        FixtureRepo lib = repoWith("lib",
                "if grep -q escalated A.java 2>/dev/null; then echo 'Could not resolve com.acme:x'; "
                        + "echo 'Connection refused'; exit 1; else exit 1; fi");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ScriptedChatModel coderScript = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalationScript = new ScriptedChatModel(List.of(
                call("3", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int escalated; }\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"escalated\"}")));

        Orchestrator.RunResult result = orchestrator(coderScript, escalationScript)
                .run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.PAUSED_INFRA);
    }

    @Test
    void escalationIsDeniedWhenTheBudgetIsAlreadySpent() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 1");   // verify always fails
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()));
        ScriptedChatModel coderScript = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));
        ScriptedChatModel escalationScript = new ScriptedChatModel(List.of());   // must never be consumed
        Orchestrator tight = new Orchestrator(new RepoStepRunner(db.jdbi()), coderScript, "qwen",
                escalationScript, "deepseek", repo -> RunnerSettings.defaults(null),
                new RunStore(InstantSource.fixed(Instant.EPOCH)), 20L, Map.of(), new MavenLocalPublisher(),
                new JarBuilder());
        // attempt 1 spends 2 calls x 15 tokens = 30 > 20: the escalation gate must refuse.

        Orchestrator.RunResult result = tight.run(runDir, planFor("lib", lib.headSha()), steps);

        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);   // no attempt 2
        assertThat(escalationScript.requests()).isEmpty();
        // Single-repo plan: no later start-gate fires after the FAILED transition, so the run ends
        // PARTIAL — the denied escalation, not a pause, is what this test pins.
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    void aResumedWalkReskipsDownstreamOfAPersistedFailure() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        RunState persisted = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.FAILED, "sdd/S-v1/lib", null, "VERIFY_FAILED: x"),
                new RepoRun("svc", RepoState.PENDING, null, null, "")), null, 0L);
        ScriptedChatModel model = new ScriptedChatModel(List.of());   // nothing may run

        Orchestrator.RunResult result = orchestrator(model)
                .run(runDir, plan(lib.headSha(), svc.headSha()), steps, persisted);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
        assertThat(model.requests()).isEmpty();
    }

    private static PlanModel planWithContract(String libBase, String svcBase, String compat) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "CODE_CHANGE_LIKELY", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "NONE")),
                List.of(new PlanModel.PlanContract("c1", "java-api", "lib", List.of("svc"),
                        "Api.f(int): int", compat)),
                List.of(new PlanModel.PlanStep("lib", List.of(), "minor", List.of("c1"), List.of(),
                                List.of(), List.of(), "provider step"),
                        new PlanModel.PlanStep("svc", List.of(), "patch", List.of(), List.of("c1"),
                                List.of(), List.of(), "consumer step")));
    }

    @Test
    void binaryIncompatibleDriftFailsTheProviderBeforeConsumersStart() throws Exception {
        // Baseline jar (built at branch start, tree at base) has f(int): int; the candidate jar the
        // stub plants after the agent "worked" has f(int): long — japicmp must fail lib, cascade svc.
        Path jars = Files.createDirectories(ws.resolve("prebuilt"));
        Path baselineJar = TestJars.jar(jars, "base.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path brokenJar = TestJars.jar(jars, "broken.jar", "Api",
                "public class Api { public long f(int x) { return x; } }");
        // First assemble (baseline) copies base.jar; every later assemble copies broken.jar.
        // NOTE: escalation would re-run startBranch+clean, deleting .baseline-done — these scripts stay
        // on attempt 1 (done -> verify exit 0)
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *assemble*) mkdir -p build/libs; "
                        + "if [ -f .baseline-done ]; then cp " + brokenJar + " build/libs/lib-1.0.jar; "
                        + "else cp " + baselineJar + " build/libs/lib-1.0.jar; touch .baseline-done; fi; exit 0 ;; "
                        + "*) exit 0 ;; esac");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of(
                "lib", new RepoStep("lib", lib.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()),
                "svc", new RepoStep("svc", svc.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}")));

        Orchestrator.RunResult result = orchestrator(model)
                .run(runDir, planWithContract(lib.headSha(), svc.headSha(), "binary-compatible"), steps);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().repos().stream()
                .filter(r -> r.repo().equals("lib")).findFirst().orElseThrow().detail())
                .contains("binary-incompatible");
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
    }

    @Test
    void compatibleDriftPassesTheGateAndActualizesContracts() throws Exception {
        Path jars = Files.createDirectories(ws.resolve("prebuilt"));
        Path baselineJar = TestJars.jar(jars, "base.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path grownJar = TestJars.jar(jars, "grown.jar", "Api",
                "public class Api { public int f(int x) { return x; } public int g() { return 1; } }");
        // NOTE: escalation would re-run startBranch+clean, deleting .baseline-done — these scripts stay
        // on attempt 1 (done -> verify exit 0)
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *assemble*) mkdir -p build/libs; "
                        + "if [ -f .baseline-done ]; then cp " + grownJar + " build/libs/lib-1.0.jar; "
                        + "else cp " + baselineJar + " build/libs/lib-1.0.jar; touch .baseline-done; fi; exit 0 ;; "
                        + "*) exit 0 ;; esac");
        // lib's tree carries real source so actualization has a surface to extract:
        lib.file("src/main/java/com/acme/Api.java",
                        "package com.acme;\npublic class Api { public int f(int x) { return x; } }\n")
                .commit("api source");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of(
                "lib", new RepoStep("lib", lib.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()),
                "svc", new RepoStep("svc", svc.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"svc ok\"}")));

        Orchestrator.RunResult result = orchestrator(model)
                .run(runDir, planWithContract(lib.headSha(), svc.headSha(), "binary-compatible"), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(new RunStore(InstantSource.fixed(Instant.EPOCH)).readContract(runDir, "c1"))
                .contains("com.acme.Api");
    }

    @Test
    void vanishedBaselineJarIsEventedNotSilentlySkipped() throws Exception {
        // Baseline build plants two module jars (lib + gone); the candidate build (post-"success")
        // plants only lib — gone's module/artifact was deleted, the maximal breaking change. The
        // ratified interpretation (e) requires an event even though nothing failed the gate.
        Path jars = Files.createDirectories(ws.resolve("prebuilt"));
        Path baselineJar = TestJars.jar(jars, "base.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path goneJar = TestJars.jar(jars, "gone.jar", "Gone",
                "public class Gone { public int f(int x) { return x; } }");
        // NOTE: escalation would re-run startBranch+clean, deleting .baseline-done — these scripts stay
        // on attempt 1 (done -> verify exit 0)
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *assemble*) mkdir -p build/libs; "
                        + "if [ -f .baseline-done ]; then rm -f build/libs/gone-1.0.jar; "
                        + "cp " + baselineJar + " build/libs/lib-1.0.jar; "
                        + "else cp " + baselineJar + " build/libs/lib-1.0.jar; "
                        + "cp " + goneJar + " build/libs/gone-1.0.jar; touch .baseline-done; fi; exit 0 ;; "
                        + "*) exit 0 ;; esac");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of(
                "lib", new RepoStep("lib", lib.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()),
                "svc", new RepoStep("svc", svc.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"svc ok\"}")));

        Orchestrator.RunResult result = orchestrator(model)
                .run(runDir, planWithContract(lib.headSha(), svc.headSha(), "binary-compatible"), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Files.readString(runDir.resolve("lib/agent-events.jsonl")))
                .contains("japicmp skipped for gone-1.0.jar: no matching candidate jar");
    }

    @Test
    void consumersReceiveActualizedContractsInTheirWorkOrder() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        lib.file("src/main/java/com/acme/Api.java",
                        "package com.acme;\npublic class Api { public int f(int x) { return x; } }\n")
                .commit("api source");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        // CRITICAL: contractDigest iterates step.consumes() — the hand-built svc step MUST carry the
        // ContractRef (in production RepoStepResolver fills it from plan.json; unit tests must too).
        Map<String, RepoStep> steps = Map.of(
                "lib", new RepoStep("lib", lib.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()),
                "svc", new RepoStep("svc", svc.path(), "x", List.of(), List.of(), List.of(),
                        List.of(new sdd.agent.run.ContractRef("c1", "java-api", "lib", List.of("svc"),
                                "Api.f(int): int")), List.of()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"svc ok\"}")));

        orchestrator(model).run(runDir,
                planWithContract(lib.headSha(), svc.headSha(), null), steps);   // no japicmp gate

        // svc's FIRST request (its work order) must contain the actualized section with lib's real API.
        String svcWorkOrder = model.requests().get(1).messages().stream()
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", String::concat);
        assertThat(svcWorkOrder).contains("Actualized contracts").contains("com.acme.Api");
    }
}
