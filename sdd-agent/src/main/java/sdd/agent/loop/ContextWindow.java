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
    /**
     * Which results are worth keeping when the window overflows — a property of what the agent is
     * DOING, not of the window.
     *
     * <p>{@link #IMPLEMENT} is the original policy, unchanged: preserve every edit forever, the
     * last build, and the two most recent reads, and stub everything else in one pass. That is
     * survivable for a coding agent because its edits are on disk and it can be told to re-read
     * them.
     *
     * <p>{@link #EXPLORE} inverts both halves, because an explorer's evidence IS its search results
     * and it has no on-disk artifact to recover from. It never evicts a recorded finding, keeps the
     * latest reads and searches, and evicts oldest-first only until back under the cap — a single
     * overflow must not erase a survey that took sixty turns to build.
     */
    public enum Retention { IMPLEMENT, EXPLORE }

    /** How many characters one token is worth when sizing an eviction. Deliberately a constant and
     *  not a tokenizer: the caller already knows the real prompt_tokens, so this only has to be
     *  proportionate, and a real tokenizer here would make eviction depend on a model's vocabulary. */
    private static final int CHARS_PER_TOKEN = 4;

    private final int softCapTokens;
    private final Retention retention;
    private final List<Entry> entries = new ArrayList<>();
    private Entry pinned;

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
        this(softCapTokens, Retention.IMPLEMENT);
    }

    public ContextWindow(int softCapTokens, Retention retention) {
        this.softCapTokens = softCapTokens;
        this.retention = retention;
    }

    /**
     * Sets a single always-last, never-evicted entry — the explorer's running digest of what it has
     * established. Replaces any previous pin rather than appending, so it cannot accumulate.
     *
     * <p>It survives {@link #evictAll}, which is the point: after an HTTP 400 has wiped every tool
     * result, this is the only thing left telling the agent what it already knows.
     */
    public void setPinned(String content) {
        pinned = new Entry(ChatMessage.user(content), null);
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
        entries.add(new Entry(ChatMessage.tool(toolCallId, toolName, content), toolName));
    }

    public List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>(entries.size() + 1);
        for (Entry e : entries) {
            out.add(e.message);
        }
        if (pinned != null) {
            out.add(pinned.message);
        }
        return out;
    }

    public int evictIfOverCap(int promptTokens) {
        if (promptTokens <= softCapTokens) {
            return 0;
        }
        if (retention == Retention.EXPLORE) {
            return evictOldestFirst((promptTokens - softCapTokens) * CHARS_PER_TOKEN);
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
                e.message = ChatMessage.tool(e.message.toolCallId(), e.toolName,
                    "[evicted: " + e.toolName + " result]");
                e.stubbed = true;
                evicted++;
            }
        }
        return evicted;
    }

    /**
     * Oldest-first eviction, stopping as soon as enough has been freed — the EXPLORE policy.
     *
     * <p>Never touches a {@code record_finding} result, and keeps the two most recent
     * {@code read_file} and {@code search_code} results so the agent does not lose the thing it is
     * currently reasoning about.
     */
    private int evictOldestFirst(int charsToFree) {
        List<Integer> keep = new ArrayList<>(latestIndicesOfTool("read_file", 2));
        keep.addAll(latestIndicesOfTool("search_code", 2));
        int evicted = 0;
        int freed = 0;
        for (int i = 0; i < entries.size() && freed < charsToFree; i++) {
            Entry e = entries.get(i);
            if (e.toolName == null || e.stubbed
                    || e.toolName.equals("record_finding") || keep.contains(i)) {
                continue;
            }
            int was = e.message.content() == null ? 0 : e.message.content().length();
            e.message = ChatMessage.tool(e.message.toolCallId(), e.toolName,
                    "[evicted: " + e.toolName + " result]");
            e.stubbed = true;
            evicted++;
            freed += was;
        }
        return evicted;
    }

    /**
     * Force-evicts every not-yet-stubbed tool result, ignoring evictIfOverCap's preserve rules
     * (apply_edit, last build, latest reads). Used once per HTTP 400 (design line 71): the endpoint
     * has already rejected the request as oversized, so a partial, rule-respecting eviction is not
     * enough — every evictable tool result must go.
     */
    public int evictAll() {
        int evicted = 0;
        for (Entry e : entries) {
            if (e.toolName == null || e.stubbed) {
                continue;
            }
            e.message = ChatMessage.tool(e.message.toolCallId(), e.toolName,
                    "[evicted: " + e.toolName + " result]");
            e.stubbed = true;
            evicted++;
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
