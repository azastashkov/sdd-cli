package sdd.agent.loop;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sdd.agent.tool.FileTools;
import sdd.agent.tool.GradleTool;
import sdd.agent.tool.PathJail;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {
    @TempDir Path root;
    private Toolbox toolbox;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(root.resolve("A.java"), "class A {}\n");
        toolbox = new Toolbox(new FileTools(new PathJail(root)),
                new GradleTool(root, null, Duration.ofSeconds(5)));
    }

    private static ChatResponse call(String id, String tool, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, tool, args)), null), "tool_calls", new Usage(10, 5));
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(ChatMessage.assistant(content), "stop", new Usage(10, 5));
    }

    private AgentLoop loop(ScriptedChatModel model, AgentBudget budget, InstantSource clock) {
        return new AgentLoop(model, toolbox, budget, 80_000, clock);
    }

    @Test
    void readsThenEditsThenDoneSucceeds() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "{\"path\":\"A.java\"}"),
                call("2", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"added field\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "add a field to A", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.DONE);
        assertThat(outcome.summary()).isEqualTo("added field");
        assertThat(outcome.turns()).isEqualTo(3);
        assertThat(model.requests().get(1).messages()).anySatisfy(m ->
                assertThat(m.content()).contains("class A {}"));   // read result fed back
    }

    @Test
    void threeConsecutiveMalformedCallsTerminate() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "not json"),
                call("2", "frobnicate", "{}"),
                text("I think I should give up.")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.MALFORMED);
    }

    @Test
    void identicalActionRepeatedThreeTimesIsWedged() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "{\"path\":\"A.java\"}"),
                call("2", "read_file", "{\"path\":\"A.java\"}"),
                call("3", "read_file", "{\"path\":\"A.java\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.WEDGED);
    }

    @Test
    void turnBudgetTerminates() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "list_files", "{\"dir\":\".\"}")));

        AgentOutcome outcome = loop(model, new AgentBudget(1, Duration.ofMinutes(45), 1_500_000L),
                InstantSource.system()).run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.BUDGET_TURNS);
        assertThat(outcome.turns()).isEqualTo(1);
    }

    @Test
    void malformedDoneStrikesThenSucceedsOnRetry() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"maybe\",\"summary\":\"huh\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"ok now\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.DONE);
        assertThat(outcome.summary()).isEqualTo("ok now");
    }

    @Test
    void identicalGradleOutputTwiceIsWedged() throws Exception {
        Path gradlew = root.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho BUILD FAILED\nexit 1\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "run_gradle", "{\"task\":\"build\"}"),
                call("2", "run_gradle", "{\"task\":\"build\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.WEDGED);
    }

    @Test
    void contextExhaustedWhenOverCapWithNothingEvictable() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("thinking out loud"), "stop", new Usage(90_000, 5))));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.CONTEXT_EXHAUSTED);
    }

    @Test
    void wallClockBudgetTerminates() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "list_files", "{\"dir\":\".\"}"),
                call("2", "list_files", "{\"dir\":\".\"}")));
        Instant t0 = Instant.parse("2026-08-12T00:00:00Z");
        // advances 40 minutes per reading: turn 1 at +40min (under 45), terminate check on turn 2
        InstantSource clock = new InstantSource() {
            private int calls = 0;
            @Override public Instant instant() { return t0.plusSeconds(40L * 60 * calls++); }
        };

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), clock)
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.BUDGET_TIME);
    }
}
