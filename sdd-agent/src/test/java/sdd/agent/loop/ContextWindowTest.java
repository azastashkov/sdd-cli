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

    @Test
    void exploreRetentionEvictsOldestFirstAndStopsOnceUnderCap() {
        // The implement policy stubs everything non-preserved in one pass. That is survivable for a
        // coding agent because its edits persist on disk; an explorer has no artifact to recover
        // from, so one overflow must not erase the whole survey.
        ContextWindow cw = new ContextWindow(1_000, ContextWindow.Retention.EXPLORE);
        cw.addSystem("sys");
        cw.addWorkOrder("wo");
        for (int i = 1; i <= 6; i++) {
            cw.addAssistant(assistantCall("s" + i, "search_code"));
            cw.addToolResult("s" + i, "search_code", "X".repeat(4_000));   // ~1000 tokens each
        }

        // 1200 tokens over cap -> ~4800 chars -> two entries, not six.
        int evicted = cw.evictIfOverCap(2_200);

        assertThat(evicted).isEqualTo(2);
        List<String> tools = cw.messages().stream()
                .filter(m -> m.role().equals("tool")).map(ChatMessage::content).toList();
        assertThat(tools.get(0)).isEqualTo("[evicted: search_code result]");
        assertThat(tools.get(1)).isEqualTo("[evicted: search_code result]");
        assertThat(tools.get(2)).startsWith("XXXX");
    }

    @Test
    void exploreRetentionNeverEvictsARecordedFinding() {
        // Findings are the explorer's entire product. Losing one to eviction loses work that no
        // later turn can reconstruct, because nothing was written to disk.
        ContextWindow cw = new ContextWindow(10, ContextWindow.Retention.EXPLORE);
        cw.addSystem("sys");
        cw.addAssistant(assistantCall("f1", "record_finding"));
        cw.addToolResult("f1", "record_finding", "FINDING1");
        cw.addAssistant(assistantCall("s1", "search_code"));
        cw.addToolResult("s1", "search_code", "S".repeat(8_000));

        cw.evictIfOverCap(100_000);

        assertThat(cw.messages().stream().map(ChatMessage::content).toList()).contains("FINDING1");
    }

    @Test
    void exploreRetentionKeepsTheLatestReadsAndSearches() {
        ContextWindow cw = new ContextWindow(10, ContextWindow.Retention.EXPLORE);
        cw.addSystem("sys");
        for (int i = 1; i <= 3; i++) {
            cw.addAssistant(assistantCall("r" + i, "read_file"));
            cw.addToolResult("r" + i, "read_file", "READ" + i);
        }

        cw.evictIfOverCap(100_000);

        List<String> tools = cw.messages().stream()
                .filter(m -> m.role().equals("tool")).map(ChatMessage::content).toList();
        assertThat(tools).contains("READ2", "READ3").doesNotContain("READ1");
    }

    @Test
    void aPinnedDigestSurvivesEvenEvictAll() {
        // After an HTTP 400 forces evictAll, the agent must still see what it has established --
        // it re-reads its own notes instead of re-doing its own work.
        ContextWindow cw = new ContextWindow(80_000, ContextWindow.Retention.EXPLORE);
        cw.addSystem("sys");
        cw.addAssistant(assistantCall("s1", "search_code"));
        cw.addToolResult("s1", "search_code", "SEARCH1");
        cw.setPinned("## Findings so far (1)\n- redis channel — repo/File.java:1");

        cw.evictAll();

        List<ChatMessage> messages = cw.messages();
        assertThat(messages.get(messages.size() - 1).content()).contains("Findings so far (1)");
        assertThat(messages.stream().map(ChatMessage::content).toList())
                .doesNotContain("SEARCH1");
    }

    @Test
    void aReplacedPinDoesNotAccumulate() {
        ContextWindow cw = new ContextWindow(80_000, ContextWindow.Retention.EXPLORE);
        cw.addSystem("sys");
        cw.setPinned("first");
        cw.setPinned("second");

        List<String> contents = cw.messages().stream().map(ChatMessage::content).toList();
        assertThat(contents).contains("second").doesNotContain("first");
        assertThat(cw.messages()).hasSize(2);
    }

    @Test
    void evictAllStubsEveryToolResultUnconditionallyIgnoringThePreserveRules() {
        ContextWindow cw = new ContextWindow(80_000);
        cw.addSystem("sys");
        cw.addWorkOrder("wo");
        cw.addAssistant(assistantCall("r1", "read_file")); cw.addToolResult("r1", "read_file", "READ1");
        cw.addAssistant(assistantCall("e1", "apply_edit")); cw.addToolResult("e1", "apply_edit", "EDIT1");
        cw.addAssistant(assistantCall("g1", "run_gradle")); cw.addToolResult("g1", "run_gradle", "GRADLE1");

        int evicted = cw.evictAll();

        // unlike evictIfOverCap, apply_edit and the last run_gradle are NOT preserved
        assertThat(evicted).isEqualTo(3);
        List<String> toolContents = cw.messages().stream()
                .filter(m -> m.role().equals("tool")).map(ChatMessage::content).toList();
        assertThat(toolContents).containsExactly("[evicted: read_file result]",
                "[evicted: apply_edit result]", "[evicted: run_gradle result]");

        // idempotent: nothing left to evict on a second pass
        assertThat(cw.evictAll()).isZero();
    }
}
