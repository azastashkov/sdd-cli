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
 *
 * <p><b>Declaration count is its own failure mode, so it is its own argument.</b> Some gateways
 * have a function-calling path that degrades as the number of declarations grows, and measurement
 * against one showed an identical request succeeding 20/20 with a single declaration, 13/20 with
 * six and 0/20 with nine — fast, uniform HTTP 500s rather than timeouts. That ceiling also MOVED
 * within a single day. A probe that only ever sends one declaration cannot see any of this, which
 * is exactly the blind spot {@code sdd explore} walks into: it advertises ten by default and
 * twelve with both optional tools. {@link #probe(ModelEndpoint, ChatModel, int)} sends a chosen
 * number so the ceiling can be found deliberately instead of inferred from whether a survey
 * happened to survive.
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
                         int maxTokensSent, String contentExcerpt, int declarationsSent) {
    }

    private static final int EXCERPT = 200;
    private static final String TOOL = "report_status";

    /**
     * Names for the decoy declarations that pad a count probe.
     *
     * <p>Deliberately identifier-shaped and schema-bearing rather than {@code tool_1..tool_n}: what
     * degrades on these gateways is the payload the declaration set produces, and a set of bare
     * names is a smaller and differently-shaped payload than the real one. These approximate the
     * explorer's own declarations rather than mirroring them — {@code ExploreTools} lives in
     * {@code sdd-agent}, which depends on this module and not the other way round, and importing a
     * live tool set into a diagnostic would mean constructing a jail and a database to ask a
     * question about HTTP.
     */
    private static final List<String> DECOYS = List.of(
            "list_repos", "list_files", "read_file", "search_code", "search_symbols",
            "who_references", "kb_resolve", "propose_touchpoint", "record_finding",
            "git_history", "ask_user_question", "apply_edit", "run_gradle", "list_modules",
            "describe_module", "find_callers", "resolve_symbol", "list_endpoints");
    private static final String DECOY_SCHEMA = """
            {"type":"object","properties":{\
            "path":{"type":"string","description":"Repository-relative path to operate on"},\
            "query":{"type":"string","description":"What to look for"}},"required":["path"]}""";
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
        return probe(endpoint, model, 1);
    }

    /**
     * The same question, asked while {@code declarations} tools are on the wire.
     *
     * <p>The real tool is always advertised FIRST and is the one the instruction names, so a
     * failure is about carrying the set rather than about choosing within it. Picking a decoy is
     * reported but still counts as {@code ok}: the endpoint emitted a tool call, which is the
     * transport question this probe exists to answer.
     *
     * @param declarations how many tool declarations to send, at least one
     */
    public static Result probe(ModelEndpoint endpoint, ChatModel model, int declarations) {
        int maxTokens = endpoint.maxTokens();
        List<ToolSpec> specs = specs(Math.max(1, declarations));
        try {
            ChatResponse response = model.complete(new ChatRequest(endpoint.model(),
                    List.of(ChatMessage.system(SYSTEM), ChatMessage.user(USER)),
                    specs, maxTokens, 0.15));

            List<ToolCall> calls = response.message().toolCalls();
            String content = response.message().content();
            if (!calls.isEmpty()) {
                ToolCall first = calls.get(0);
                String wrong = TOOL.equals(first.name())
                        ? "" : " (asked for " + TOOL + ", so it chose the wrong one of "
                                + specs.size() + ")";
                return new Result(true,
                        "returned a tool call: " + first.name() + "("
                                + excerpt(first.argumentsJson()) + ")" + wrong,
                        true, first.name(), first.argumentsJson(), response.finishReason(),
                        response.usage().completionTokens(), maxTokens, excerpt(content),
                        specs.size());
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
                    response.usage().completionTokens(), maxTokens, excerpt(content), specs.size());
        } catch (ModelException e) {
            return new Result(false, e.getMessage(), false, null, null, null, 0, maxTokens, "",
                    specs.size());
        }
    }

    /** The real tool first, then as many decoys as the count asks for. */
    private static List<ToolSpec> specs(int declarations) {
        List<ToolSpec> specs = new java.util.ArrayList<>();
        specs.add(new ToolSpec(TOOL, "Report a one-word status.", SCHEMA));
        for (int i = 0; specs.size() < declarations; i++) {
            // Past the pool, keep generating rather than repeating: two declarations with the same
            // name is a malformed request, and would fail for a reason that is not the count.
            String name = i < DECOYS.size() ? DECOYS.get(i) : "inspect_target_" + i;
            specs.add(new ToolSpec(name,
                    "Inspect part of the estate and return what it holds.", DECOY_SCHEMA));
        }
        return List.copyOf(specs);
    }

    private static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        String oneLine = text.strip().replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= EXCERPT ? oneLine : oneLine.substring(0, EXCERPT) + "…";
    }
}
