package sdd.agent.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoStepRunnerTest {
    @TempDir Path ws;
    private Database db;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/lib','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.A','CLASS',1,'A.java')");
        });
        repoRoot = Files.createDirectories(ws.resolve("lib-core"));
        Files.writeString(repoRoot.resolve("A.java"), "class A {}\n");
    }

    private void gradlew(String script) throws Exception {
        Path g = repoRoot.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static ChatResponse call(String id, String tool, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, tool, args)), null), "tool_calls", new Usage(10, 5));
    }

    private static RepoStep step(Path root) {
        return new RepoStep("lib-core", root, "Add a field to A.", List.of("R1: x"),
                List.of("A.java"), List.of(), List.of(), List.of());
    }

    private StepOutcome run(ScriptedChatModel model) {
        return new RepoStepRunner(db.jdbi())
                .run(step(repoRoot), model, "qwen", RunnerSettings.defaults(null));
    }

    @Test
    void editThenDoneThenGreenVerificationSucceeds() throws Exception {
        gradlew("exit 0");   // the agent's run_gradle AND the verification gate both pass
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"added x\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);
        assertThat(outcome.summary()).isEqualTo("added x");
        assertThat(Files.readString(repoRoot.resolve("A.java"))).contains("int x;");
    }

    @Test
    void twoDoneVerifyFailCyclesEndInVerifyFailed() throws Exception {
        gradlew("echo '/r/A.java:1: error: broken'; exit 1");   // verification always fails
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try 1\"}"),   // cycle 1 → verify fails → re-loop
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try 2\"}")));  // cycle 2 → verify fails → VERIFY_FAILED

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.VERIFY_FAILED);
        assertThat(outcome.verificationOutput()).contains("A.java:1: error: broken");
    }

    @Test
    void contextExhaustionRestartsOnceWithADigest() throws Exception {
        gradlew("exit 0");
        // turn 1 = a huge-promptToken content-only response (over the 80k cap, nothing evictable) → CONTEXT_EXHAUSTED;
        // after the restart, the second loop does apply_edit then done → verify green → SUCCESS
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("thinking"), "stop", new Usage(90_000, 5)),
                call("2", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int y; }\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"after restart\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);
        // the restarted loop saw a re-orientation digest in its work order (index-independent:
        // search every request's messages so a change to the exhaustion path's call count can't break it)
        assertThat(model.requests()).anySatisfy(req ->
                assertThat(req.messages()).anySatisfy(m ->
                        assertThat(m.content()).contains("ran out of context")));
    }

    @Test
    void blockedAgentSurfacesBlocked() throws Exception {
        gradlew("exit 0");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"blocked\",\"summary\":\"need a decision\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.BLOCKED);
        assertThat(outcome.summary()).isEqualTo("need a decision");
    }
}
