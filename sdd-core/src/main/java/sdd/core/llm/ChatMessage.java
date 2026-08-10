package sdd.core.llm;

import java.util.List;

public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public static ChatMessage system(String content) { return new ChatMessage("system", content, List.of(), null); }
    public static ChatMessage user(String content) { return new ChatMessage("user", content, List.of(), null); }
    public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content, List.of(), null); }
    public static ChatMessage tool(String toolCallId, String content) { return new ChatMessage("tool", content, List.of(), toolCallId); }
}
