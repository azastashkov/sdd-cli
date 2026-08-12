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
}
