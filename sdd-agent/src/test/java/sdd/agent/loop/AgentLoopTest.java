package sdd.agent.loop;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sdd.agent.tool.FileTools;
import sdd.agent.tool.GradleTool;
import sdd.agent.tool.PathJail;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
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
    void nullDoneArgumentsIsTreatedAsMalformedInsteadOfCrashingTheLoop() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", null),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"ok now\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.DONE);
        assertThat(outcome.summary()).isEqualTo("ok now");
    }

    @Test
    void http400EvictsAndRetriesOnceThenSucceeds() {
        int[] calls = {0};
        ChatModel model = req -> {
            calls[0]++;
            if (calls[0] == 1) {
                throw new ModelException("too long", 400);
            }
            return new ChatResponse(new ChatMessage("assistant", null,
                    List.of(new ToolCall("1", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")), null),
                    "tool_calls", new Usage(10, 5));
        };

        AgentOutcome outcome = new AgentLoop(model, toolbox, AgentBudget.defaults(), 80_000,
                InstantSource.system()).run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.DONE);
        assertThat(outcome.events()).anySatisfy(e ->
                assertThat(e).contains("endpoint rejected oversized request").contains("evicted and retried"));
    }

    @Test
    void repeatedHttp400EndsInContextExhaustedWithoutThrowing() {
        ChatModel model = req -> {
            throw new ModelException("too long", 400);
        };

        AgentOutcome outcome = new AgentLoop(model, toolbox, AgentBudget.defaults(), 80_000,
                InstantSource.system()).run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.CONTEXT_EXHAUSTED);
        assertThat(outcome.events()).anySatisfy(e ->
                assertThat(e).contains("endpoint rejected oversized request").contains("evicted and retried"));
    }

    @Test
    void non400ModelExceptionStillPropagates() {
        ChatModel model = req -> {
            throw new ModelException("HTTP 401: x", 401);
        };
        AgentLoop loop = new AgentLoop(model, toolbox, AgentBudget.defaults(), 80_000, InstantSource.system());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> loop.run("sys", "wo", "qwen", 4096))
                .isInstanceOf(ModelException.class)
                .satisfies(e -> assertThat(((ModelException) e).statusCode()).isEqualTo(401));
    }

    @Test
    void transcriptRecordsOneLinePerModelCallWithToolCallsAndResults() {
        Instant t0 = Instant.parse("2026-08-13T00:00:00Z");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "{\"path\":\"A.java\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.fixed(t0))
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.transcript()).hasSize(2);
        assertThat(outcome.transcript().get(0))
                .contains("\"turn\":1")
                .contains("\"at\":\"2026-08-13T00:00:00Z\"")
                .contains("\"finish\":\"tool_calls\"")
                .contains("\"prompt_tokens\":10")
                .contains("\"completion_tokens\":5")
                .contains("\"content\":null")
                .contains("\"name\":\"read_file\"")
                .contains("\"args\":\"{\\\"path\\\":\\\"A.java\\\"}\"")
                .contains("\"tool_results\"")
                .contains("class A {}");
        assertThat(outcome.transcript().get(1))
                .contains("\"turn\":2")
                .contains("\"name\":\"done\"")
                .contains("\"tool_results\":[]");   // done never produces a tool result on success
    }

    @Test
    void longContentArgsAndResultsAreTruncatedInTheTranscript() throws Exception {
        Files.writeString(root.resolve("Big.txt"), "x".repeat(3000));
        String longRegex = "a".repeat(2500);
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "{\"path\":\"Big.txt\"}"),
                call("2", "search", "{\"regex\":\"" + longRegex + "\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.transcript().get(0)).contains("…(truncated)");   // read_file's huge tool_result
        assertThat(outcome.transcript().get(1)).contains("…(truncated)");   // search's huge args
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
