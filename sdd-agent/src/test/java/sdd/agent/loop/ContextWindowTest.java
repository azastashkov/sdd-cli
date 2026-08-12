package sdd.agent.loop;

import org.junit.jupiter.api.Test;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ToolCall;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowTest {

    private static ChatMessage assistantCall(String id, String tool) {
        return new ChatMessage("assistant", null, List.of(new ToolCall(id, tool, "{}")), null);
    }

    @Test
    void underCapEvictsNothing() {
        ContextWindow cw = new ContextWindow(80_000);
        cw.addSystem("sys");
        cw.addWorkOrder("do the thing");
        cw.addAssistant(assistantCall("c1", "read_file"));
        cw.addToolResult("c1", "read_file", "contents");

        assertThat(cw.evictIfOverCap(1000)).isZero();
        assertThat(cw.messages()).hasSize(4);
    }

    @Test
    void overCapStubsOnlyEvictableToolResultsPreservingTheRules() {
        ContextWindow cw = new ContextWindow(80_000);
        cw.addSystem("sys");
        cw.addWorkOrder("wo");
        // 3 reads (only the last 2 preserved), 2 gradle (only the last preserved), 1 edit (always)
        cw.addAssistant(assistantCall("r1", "read_file")); cw.addToolResult("r1", "read_file", "READ1");
        cw.addAssistant(assistantCall("g1", "run_gradle")); cw.addToolResult("g1", "run_gradle", "GRADLE1");
        cw.addAssistant(assistantCall("r2", "read_file")); cw.addToolResult("r2", "read_file", "READ2");
        cw.addAssistant(assistantCall("e1", "apply_edit")); cw.addToolResult("e1", "apply_edit", "EDIT1");
        cw.addAssistant(assistantCall("s1", "search")); cw.addToolResult("s1", "search", "SEARCH1");
        cw.addAssistant(assistantCall("r3", "read_file")); cw.addToolResult("r3", "read_file", "READ3");
        cw.addAssistant(assistantCall("g2", "run_gradle")); cw.addToolResult("g2", "run_gradle", "GRADLE2");

        int evicted = cw.evictIfOverCap(90_000);

        // evictable: READ1 (older than last-2 reads), GRADLE1 (older gradle), SEARCH1 → 3 stubbed
        assertThat(evicted).isEqualTo(3);
        List<String> toolContents = cw.messages().stream()
                .filter(m -> m.role().equals("tool")).map(ChatMessage::content).toList();
        assertThat(toolContents).contains("[evicted: read_file result]", "[evicted: run_gradle result]",
                "[evicted: search result]", "READ2", "READ3", "EDIT1", "GRADLE2")
                .doesNotContain("READ1", "GRADLE1", "SEARCH1");

        // second pass: nothing left evictable
        assertThat(cw.evictIfOverCap(90_000)).isZero();
    }
}
