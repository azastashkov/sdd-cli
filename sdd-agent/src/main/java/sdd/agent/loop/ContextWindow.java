package sdd.agent.loop;

import sdd.core.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * The agent's conversation history with usage-based eviction (design Component 3): when the
 * endpoint reports prompt_tokens over the soft cap, the oldest tool results that are not
 * load-bearing (not an edit, not the last build, not a recent read) are replaced with stubs.
 * No model summarization; deterministic given the same history and token count.
 */
public final class ContextWindow {
    private final int softCapTokens;
    private final List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        ChatMessage message;
        final String toolName;   // null unless this is a tool-result entry
        boolean stubbed;

        Entry(ChatMessage message, String toolName) {
            this.message = message;
            this.toolName = toolName;
        }
    }

    public ContextWindow(int softCapTokens) {
        this.softCapTokens = softCapTokens;
    }

    public void addSystem(String content) {
        entries.add(new Entry(ChatMessage.system(content), null));
    }

    public void addWorkOrder(String content) {
        entries.add(new Entry(ChatMessage.user(content), null));
    }

    public void addAssistant(ChatMessage message) {
        entries.add(new Entry(message, null));
    }

    public void addToolResult(String toolCallId, String toolName, String content) {
        entries.add(new Entry(ChatMessage.tool(toolCallId, content), toolName));
    }

    public List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(e.message);
        }
        return out;
    }

    public int evictIfOverCap(int promptTokens) {
        if (promptTokens <= softCapTokens) {
            return 0;
        }
        int lastGradle = lastIndexOfTool("run_gradle");
        List<Integer> latestReads = latestIndicesOfTool("read_file", 2);
        int evicted = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.toolName == null || e.stubbed) {
                continue;
            }
            boolean preserve = e.toolName.equals("apply_edit")
                    || (e.toolName.equals("run_gradle") && i == lastGradle)
                    || (e.toolName.equals("read_file") && latestReads.contains(i));
            if (!preserve) {
                e.message = ChatMessage.tool(e.message.toolCallId(), "[evicted: " + e.toolName + " result]");
                e.stubbed = true;
                evicted++;
            }
        }
        return evicted;
    }

    private int lastIndexOfTool(String toolName) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (toolName.equals(entries.get(i).toolName)) {
                return i;
            }
        }
        return -1;
    }

    private List<Integer> latestIndicesOfTool(String toolName, int count) {
        List<Integer> found = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && found.size() < count; i--) {
            if (toolName.equals(entries.get(i).toolName)) {
                found.add(i);
            }
        }
        return found;
    }
}
