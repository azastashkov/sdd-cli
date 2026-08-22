package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The implement agent says what it is doing, the way the explore agent always has.
 *
 * <p>Before this, a repo could run for minutes behind a spinner: a verification build makes no
 * model call while it runs, so nothing reached the screen for exactly as long as the slow thing
 * took, which is indistinguishable from a hang.
 */
class ToolboxTraceTest {

    @TempDir Path repo;

    private static final class StubBuild implements BuildTool {
        @Override public String toolName() { return "run_gradle"; }
        @Override public String taskDescription() { return "Run one allowlisted Gradle task."; }
        @Override public java.util.Set<String> tasks() { return java.util.Set.of(":lib:test"); }
        @Override public String run(String task) { return "BUILD SUCCESSFUL"; }
        @Override public String runFull(String task) { return "BUILD SUCCESSFUL"; }
    }

    private Toolbox toolbox(List<String> lines) {
        return new Toolbox(new FileTools(new PathJail(repo), java.util.Optional.empty()),
                new StubBuild(), null, false, lines::add);
    }

    @Test
    void announcesEachCallBeforeItRunsAndTimesItAfter() throws Exception {
        Files.writeString(repo.resolve("A.java"), "class A {}\n");
        List<String> lines = new ArrayList<>();

        toolbox(lines).dispatch("read_file", "{\"path\":\"A.java\"}");

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("read_file A.java ...");
        assertThat(lines.get(1)).matches("  → \\d+ lines?, \\d+ms");
    }

    /** The subject is what the call is ABOUT — a bare tool name says nothing useful. */
    @Test
    void theSubjectNamesThePathTheTaskOrThePattern() throws Exception {
        Files.writeString(repo.resolve("A.java"), "class A {}\n");
        List<String> lines = new ArrayList<>();
        Toolbox toolbox = toolbox(lines);

        toolbox.dispatch("run_gradle", "{\"task\":\":lib:test\"}");
        toolbox.dispatch("list_files", "{\"dir\":\".\"}");
        toolbox.dispatch("search", "{\"regex\":\"class\"}");

        assertThat(lines).filteredOn(l -> !l.startsWith("  →"))
                .containsExactly("run_gradle :lib:test ...", "list_files . ...", "search /class/ ...");
    }

    /** Null sink, no behaviour change: every pre-existing construction site keeps working. */
    @Test
    void withNoSinkNothingIsTracedAndTheResultIsUnchanged() throws Exception {
        Files.writeString(repo.resolve("A.java"), "class A {}\n");

        String withSink = toolbox(new ArrayList<>()).dispatch("read_file", "{\"path\":\"A.java\"}");
        String without = new Toolbox(new FileTools(new PathJail(repo), java.util.Optional.empty()),
                new StubBuild(), null, false).dispatch("read_file", "{\"path\":\"A.java\"}");

        assertThat(withSink).isEqualTo(without);
    }

    /** A trace must never be the thing that fails a run: unparseable args still get a line. */
    @Test
    void aCallWithUnreadableArgumentsIsStillAnnouncedRatherThanCrashingInTheTrace() {
        List<String> lines = new ArrayList<>();

        try {
            toolbox(lines).dispatch("read_file", "not json");
        } catch (RuntimeException expected) {
            // the dispatch itself may reject it; the trace must not be what threw
        }

        assertThat(lines).isNotEmpty();
        assertThat(lines.get(0)).startsWith("read_file");
    }
}
