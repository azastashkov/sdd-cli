package sdd.core.llm;

import sdd.core.config.ModelEndpoint;

import java.util.List;

/**
 * Asks a model tier to call a tool, and reports whether it could.
 *
 * <p>{@code sdd implement} drives an agent loop that is entirely tool-driven: a turn that answers
 * in prose is a wasted turn, and three consecutive ones end the attempt as {@code MALFORMED}. When
 * every tier of the escalation ladder fails that way the cause is not model capability — it is
 * either an endpoint that cannot return {@code tool_calls} at all (a gateway that strips them, a
 * model without function calling) or a budget spent before the call is reached. Those need opposite
 * fixes and {@link CompletionProbe} cannot tell them apart, because a tier can complete perfectly
 * well and still never emit a tool call.
 *
 * <p>The tool offered is deliberately trivial and the instruction leaves no room for a prose
 * answer, so a reply without {@code tool_calls} is evidence about the endpoint rather than about
 * the difficulty of the request.
 */
public final class ToolCallProbe {

    /**
     * @param calledTool whether the reply carried any {@code tool_calls} — the whole question
     * @param toolName the function the model chose, or null when it called nothing
     * @param arguments the arguments it sent, so a reader can see a malformed-but-present call
     *     (which is a different fault from no call at all)
     * @param contentExcerpt what it said instead, when it answered in prose
     */
    public record Result(boolean ok, String detail, boolean calledTool, String toolName,
                         String arguments, String finishReason, int completionTokens,
                         int maxTokensSent, String contentExcerpt) {
    }

    private static final int EXCERPT = 200;
    private static final String TOOL = "report_status";
    private static final String SCHEMA = """
            {"type":"object","properties":{"status":{"type":"string",\
            "description":"the single word ok"}},"required":["status"]}""";
    private static final String SYSTEM =
            "You drive tools. Never answer in prose; always call a tool.";
    private static final String USER =
            "Call the report_status tool with status set to the single word: ok";

    private ToolCallProbe() {
    }

    public static Result probe(ModelEndpoint endpoint, ChatModel model) {
        int maxTokens = endpoint.maxTokens();
        try {
            ChatResponse response = model.complete(new ChatRequest(endpoint.model(),
                    List.of(ChatMessage.system(SYSTEM), ChatMessage.user(USER)),
                    List.of(new ToolSpec(TOOL, "Report a one-word status.", SCHEMA)),
                    maxTokens, 0.15));

            List<ToolCall> calls = response.message().toolCalls();
            String content = response.message().content();
            if (!calls.isEmpty()) {
                ToolCall first = calls.get(0);
                return new Result(true,
                        "returned a tool call: " + first.name() + "(" + excerpt(first.argumentsJson()) + ")",
                        true, first.name(), first.argumentsJson(), response.finishReason(),
                        response.usage().completionTokens(), maxTokens, excerpt(content));
            }
            boolean truncated = "length".equals(response.finishReason());
            String detail = truncated
                    ? "NO TOOL CALL, and the reply was truncated: spent "
                            + response.usage().completionTokens() + " of " + maxTokens
                            + " tokens — the budget ran out before a call was reached, so raise max_tokens"
                    : "NO TOOL CALL: the endpoint answered in prose with finish_reason="
                            + response.finishReason()
                            + " — this tier cannot drive sdd implement";
            return new Result(false, detail, false, null, null, response.finishReason(),
                    response.usage().completionTokens(), maxTokens, excerpt(content));
        } catch (ModelException e) {
            return new Result(false, e.getMessage(), false, null, null, null, 0, maxTokens, "");
        }
    }

    private static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        String oneLine = text.strip().replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= EXCERPT ? oneLine : oneLine.substring(0, EXCERPT) + "…";
    }
}
