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
                         int maxTokensSent, String contentExcerpt, int declarationsSent,
                         Fault fault) {
    }

    /**
     * How an attempt failed, as a value rather than as prose in {@code detail}.
     *
     * <p>Callers summarising a sweep have to group failures, and grouping them by sniffing the
     * message text is how a diagnostic starts lying: the per-count line said ARGUMENTS ONLY while
     * the verdict underneath still said "answering in prose", because the two read different
     * things. The distinctions are real and the remedies do not overlap — {@link #PROSE} means the
     * tier cannot drive an agent, {@link #ARGUMENTS_ONLY} means it can and the reply is merely
     * unaddressed, {@link #TRUNCATED} means raise max_tokens, {@link #TRANSPORT} means the request
     * never produced a reply.
     */
    public enum Fault { NONE, PROSE, ARGUMENTS_ONLY, UNDECLARED_NAME, TRUNCATED, TRANSPORT }

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
    /**
     * Deliberately does NOT name the tool.
     *
     * <p>It used to read "Call the report_status tool with status set to the single word: ok",
     * and that measured the wrong thing. Live against a real gateway the reply was repeatedly
     * {@code {"status": "ok"}} — the arguments alone, with no function name — which
     * {@link TextToolCalls} correctly refuses, since attributing an unnamed object among N
     * declared tools means guessing which one to RUN. But the model had complied: the instruction
     * already named the tool, so repeating it was redundant. The probe was handing out the
     * shortcut and then failing the model for taking it, and the resulting "prose rate" was
     * mostly its own.
     *
     * <p>A real turn names nothing — the model must select a tool and say which. So this asks for
     * the OUTCOME and leaves the selection where a real run leaves it.
     */
    private static final String USER =
            "Report that the system status is ok.";

    /**
     * What {@code AgentLoop} pushes into the window after a turn that answered in prose.
     *
     * <p>Duplicated from {@code AgentLoop.NUDGE} rather than imported, because that class lives in
     * {@code sdd-agent}, which depends on this module and not the reverse. {@code AgentLoopTest}
     * asserts the two are equal — if this string drifts, the measurement stops describing the loop
     * it is supposed to predict, which is the only way this probe can quietly become a lie.
     */
    public static final String NUDGE = "Call a tool or done — do not answer in prose.";

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
        return attempt(endpoint, model, specs(Math.max(1, declarations)),
                List.of(ChatMessage.system(SYSTEM), ChatMessage.user(USER))).result();
    }

    /**
     * A cold attempt, and — only when that answered in prose — the SAME retry the agent loop makes.
     *
     * <p>Exists to answer one question with a number instead of a guess: a gateway measured at ~15%
     * prose replies would give a 200-turn survey a ~44% chance of dying MALFORMED if those replies
     * were independent, but the loop does not retry blind. It appends the prose turn and
     * {@link #NUDGE} and asks again, so the real per-turn rate is conditional, not marginal, and
     * the honest survival number needs P(prose | nudged) rather than P(prose).
     *
     * <p>The follow-up request is byte-identical in shape to the loop's next turn: system, the
     * original instruction, the assistant's own prose, then the nudge as a user message — which is
     * what {@code ContextWindow.addWorkOrder} makes it.
     */
    public static Nudged probeNudged(ModelEndpoint endpoint, ChatModel model, int declarations) {
        List<ToolSpec> specs = specs(Math.max(1, declarations));
        List<ChatMessage> cold = List.of(ChatMessage.system(SYSTEM), ChatMessage.user(USER));
        Attempt first = attempt(endpoint, model, specs, cold);
        if (first.result().ok() || first.message() == null) {
            return new Nudged(first.result(), null);
        }
        List<ChatMessage> retry = List.of(cold.get(0), cold.get(1),
                first.message(), ChatMessage.user(NUDGE));
        return new Nudged(first.result(), attempt(endpoint, model, specs, retry).result());
    }

    /**
     * @param cold       the first attempt
     * @param afterNudge the retry, or null when the cold attempt already succeeded
     */
    public record Nudged(Result cold, Result afterNudge) {
        /** Prose first, a call second — the thing being measured. */
        public boolean recovered() {
            return !cold.ok() && afterNudge != null && afterNudge.ok();
        }
    }

    /** One request/response, and the assistant message it produced (null when nothing came back). */
    private record Attempt(Result result, ChatMessage message) {
    }

    private static Attempt attempt(ModelEndpoint endpoint, ChatModel model,
                                   List<ToolSpec> specs, List<ChatMessage> messages) {
        int maxTokens = endpoint.maxTokens();
        try {
            ChatResponse response = model.complete(new ChatRequest(endpoint.model(),
                    messages, specs, maxTokens, 0.15));

            List<ToolCall> calls = response.message().toolCalls();
            String content = response.message().content();
            if (!calls.isEmpty()) {
                ToolCall first = calls.get(0);
                String wrong = TOOL.equals(first.name())
                        ? "" : " (asked for " + TOOL + ", so it chose the wrong one of "
                                + specs.size() + ")";
                return new Attempt(new Result(true,
                        "returned a tool call: " + first.name() + "("
                                + excerpt(first.argumentsJson()) + ")" + wrong,
                        true, first.name(), first.argumentsJson(), response.finishReason(),
                        response.usage().completionTokens(), maxTokens, excerpt(content),
                        specs.size(), Fault.NONE), response.message());
            }
            boolean truncated = "length".equals(response.finishReason());
            String invented = undeclaredName(content, specs);
            Fault fault = truncated ? Fault.TRUNCATED
                    : invented != null ? Fault.UNDECLARED_NAME
                    : argumentsOnly(content) ? Fault.ARGUMENTS_ONLY : Fault.PROSE;
            String detail;
            if (truncated) {
                detail = "NO TOOL CALL, and the reply was truncated: spent "
                        + response.usage().completionTokens() + " of " + maxTokens
                        + " tokens — the budget ran out before a call was reached, so raise max_tokens";
            } else if (invented != null) {
                // The sharpest of the failure modes: the model DID call a tool, in a shape that
                // parses, naming something that was never offered. Nothing about the transport,
                // the declaration count or tool_calls: text will fix it, and accepting it would
                // mean running a tool nobody declared.
                detail = "NO TOOL CALL: the reply names '" + invented + "', which was NOT among "
                        + "the " + specs.size() + " declared tools — the model is INVENTING tool "
                        + "names rather than choosing from the declaration list. sdd refuses a "
                        + "name it never offered; a real run would fail the same way";
            } else if (argumentsOnly(content)) {
                // Worth its own sentence: it is NOT prose, and the fixes are opposite. The model
                // called a tool; the reply just does not say WHICH, and sdd will not guess among
                // declared tools because the guess would run one.
                detail = "NO TOOL CALL, but the reply is ARGUMENTS ONLY — a JSON object with no "
                        + "function name, which cannot be attributed among " + specs.size()
                        + " declared tools without guessing which one to run. The model complied; "
                        + "the call is unaddressed. Not the same fault as answering in prose";
            } else {
                detail = "NO TOOL CALL: the endpoint answered in prose with finish_reason="
                        + response.finishReason()
                        + " — this tier cannot drive sdd implement";
            }
            return new Attempt(new Result(false, detail, false, null, null,
                    response.finishReason(), response.usage().completionTokens(), maxTokens,
                    excerpt(content), specs.size(), fault), response.message());
        } catch (ModelException e) {
            return new Attempt(new Result(false, e.getMessage(), false, null, null, null, 0,
                    maxTokens, "", specs.size(), Fault.TRANSPORT), null);
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

    /**
     * The tool name a reply calls, when that name was never declared — otherwise null.
     *
     * <p>Reads the same key spellings {@code TextToolCalls} does, because the point is to explain
     * why THAT refused the reply. A live gateway produced
     * {@code {"function": "report_system_status", "parameters": {"status": "ok"}}} against a
     * declared {@code report_status}: a perfectly well-formed call to a tool that does not exist.
     * Reporting it as prose hid the one thing worth knowing.
     */
    private static String undeclaredName(String content, List<ToolSpec> specs) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(content.strip());
            if (!node.isObject()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode call =
                    node.path("function").isObject() ? node.get("function") : node;
            for (String key : new String[] {"name", "function"}) {
                com.fasterxml.jackson.databind.JsonNode value = call.path(key);
                if (value.isTextual() && !value.asText().isBlank()) {
                    String named = value.asText();
                    boolean declared = specs.stream().anyMatch(t -> t.name().equals(named));
                    return declared ? null : named;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether the whole reply is a JSON object that names no function — the "arguments only" shape.
     *
     * <p>Distinguished from prose because the remedies do not overlap: prose means the tier cannot
     * drive an agent at all, while this means it can and the reply is merely unaddressed.
     */
    private static boolean argumentsOnly(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(content.strip());
            return node.isObject() && !node.has("name") && !node.has("function")
                    && !node.has("tool") && node.size() > 0;
        } catch (Exception e) {
            return false;
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
