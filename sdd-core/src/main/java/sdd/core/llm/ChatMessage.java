package sdd.core.llm;

import java.util.List;

/**
 * {@code reasoningContent}: a reasoning model's thinking for THIS turn, kept beside the answer
 * rather than inside it. Only {@link WireFormat#GIGACHAT} reads or writes it — there it is a real
 * message field that the endpoint returns and that a client sends back with the assistant turn it
 * belongs to. Null everywhere else, including on every message sdd itself composes: sdd never
 * authors reasoning, it only carries back what a reply gave it.
 *
 * <p>{@code name}: on a tool result, which tool produced it. Null everywhere else. Only
 * {@link WireFormat#GIGACHAT} reads it — GigaChat pairs a function result to its call by NAME, not
 * by an id, and measured against that gateway a result without one is refused outright.
 *
 * <p>Note this is NOT the same channel as {@link ReasoningContent}, which strips {@code <think>}
 * tags out of {@code content}. That is what thinking looks like when it arrives inline; this is
 * what it looks like when the endpoint separates it. Both are live, because which one an endpoint
 * uses is the endpoint's choice.
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                          String reasoningContent, String name) {

    /** Pre-{@code name} 5-argument shape. */
    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
            String reasoningContent) {
        this(role, content, toolCalls, toolCallId, reasoningContent, null);
    }

    /** Pre-{@code reasoningContent} 4-argument shape, kept so every existing construction site
     *  (main and test) keeps compiling untouched — the same delegating idiom {@code ModelEndpoint}
     *  uses for {@code apiKeyError}/{@code tls}. */
    public ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, null);
    }

    public static ChatMessage system(String content) { return new ChatMessage("system", content, List.of(), null); }
    public static ChatMessage user(String content) { return new ChatMessage("user", content, List.of(), null); }
    public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content, List.of(), null); }
    public static ChatMessage tool(String toolCallId, String content) { return tool(toolCallId, null, content); }

    /** A tool result that also remembers WHICH tool produced it. GigaChat's function-result
     *  message is keyed by name rather than by a call id, so a wire that speaks it needs this;
     *  every other wire ignores it. */
    public static ChatMessage tool(String toolCallId, String name, String content) {
        return new ChatMessage("tool", content, List.of(), toolCallId, null, name);
    }
}
