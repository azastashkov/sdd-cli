package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.llm.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolboxTest {
    @TempDir Path root;
    private Toolbox toolbox;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(root.resolve("A.java"), "class A {}\n");
        toolbox = new Toolbox(new FileTools(new PathJail(root)),
                new GradleTool(root, null, Duration.ofSeconds(5)));
    }

    @Test
    void specsCoverAllSixToolsInAFixedOrder() {
        assertThat(toolbox.specs()).extracting(ToolSpec::name).containsExactly(
                "read_file", "list_files", "search", "apply_edit", "run_gradle", "done");
    }

    @Test
    void dispatchRoutesArgsToTheRightTool() {
        assertThat(toolbox.dispatch("read_file", "{\"path\": \"A.java\"}")).contains("class A {}");
        assertThat(toolbox.dispatch("apply_edit",
                "{\"path\": \"B.java\", \"search\": \"\", \"replace\": \"class B {}\\n\"}"))
                .isEqualTo("created B.java");
    }

    @Test
    void malformedAndUnknownCallsThrowMalformed_doneIsNotDispatchable() {
        assertThatThrownBy(() -> toolbox.dispatch("read_file", "not json"))
                .isInstanceOf(MalformedCallException.class);
        assertThatThrownBy(() -> toolbox.dispatch("read_file", "{}"))
                .isInstanceOf(MalformedCallException.class).hasMessageContaining("path");
        assertThatThrownBy(() -> toolbox.dispatch("frobnicate", "{}"))
                .isInstanceOf(MalformedCallException.class).hasMessageContaining("unknown tool");
        assertThatThrownBy(() -> toolbox.dispatch("done", "{\"result\":\"success\"}"))
                .isInstanceOf(MalformedCallException.class);
    }

    @Test
    void toolFailuresPropagateAsToolException() {
        assertThatThrownBy(() -> toolbox.dispatch("read_file", "{\"path\": \"nope.java\"}"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no such file");
    }

    // ---- single-tool mode -------------------------------------------------------------

    private Toolbox single() {
        return new Toolbox(new FileTools(new PathJail(root)),
                new GradleTool(root, null, java.time.Duration.ofSeconds(5)), null, true);
    }

    @Test
    void singleToolModeAdvertisesOneDeclarationCoveringEveryOperation() {
        assertThat(single().specs()).singleElement().satisfies(spec -> {
            assertThat(spec.name()).isEqualTo("sdd");
            assertThat(spec.parametersSchemaJson())
                    .contains("read_file").contains("apply_edit").contains("run_gradle")
                    .contains("done");
        });
    }

    @Test
    void aMultiplexedCallIsRoutedToItsOperationKeepingTheCallId() {
        sdd.core.llm.ToolCall routed = single().route(new sdd.core.llm.ToolCall(
                "call-9", "sdd", "{\"action\":\"read_file\",\"path\":\"A.java\"}"));

        assertThat(routed.name()).isEqualTo("read_file");
        assertThat(routed.id()).isEqualTo("call-9");
        assertThat(routed.argumentsJson()).contains("A.java").doesNotContain("action");
    }

    @Test
    void doneStillReachesTheLoopThroughTheMultiplexer() {
        // AgentLoop intercepts done by NAME; without translation the agent could never finish.
        assertThat(single().route(new sdd.core.llm.ToolCall("c", "sdd",
                "{\"action\":\"done\",\"result\":\"success\",\"summary\":\"ok\"}")).name())
                .isEqualTo("done");
    }

    @Test
    void theBuildToolIsNamedByToolchainInsideTheMultiplexedSchema() {
        // run_gradle vs run_npm varies per repo, so a hard-coded enum member would advertise a
        // tool the repo does not have.
        Toolbox npm = new Toolbox(new FileTools(new PathJail(root)),
                new NpmTool(root, null, java.time.Duration.ofSeconds(5)),
                null, true);

        assertThat(npm.specs().get(0).parametersSchemaJson()).contains("run_npm")
                .doesNotContain("run_gradle");
    }

    @Test
    void anOperationCalledDirectlyStillWorksInSingleToolMode() {
        sdd.core.llm.ToolCall direct = new sdd.core.llm.ToolCall("c", "read_file", "{}");
        assertThat(single().route(direct)).isEqualTo(direct);
    }
}
