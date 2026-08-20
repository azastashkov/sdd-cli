package sdd.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sdd.agent.tool.MalformedCallException;
import sdd.agent.tool.ToolException;
import sdd.agent.tool.Tools;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.HttpChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.ToolCall;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

/**
 * The native tool-call loop (design Component 3, single attempt): drive the ChatModel, dispatch
 * its tool calls through the Toolbox, feed results back, and stop on done or on a budget /
 * malformed / wedge / context-exhaustion condition. No retries, no verification — the caller
 * (Phase 4B/4C) owns those.
 */
public final class AgentLoop {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STRIKES = 3;
    private static final int WEDGE_REPEAT = 3;

    private final ChatModel model;
    private final Tools toolbox;
    private final AgentBudget budget;
    private final int contextSoftCap;
    private final InstantSource clock;
    private final ContextWindow.Retention retention;
    /**
     * Receives each turn's transcript line as that turn completes, or null.
     *
     * <p>{@link AgentOutcome} carries the whole transcript, but only on a RETURN. A transport
     * failure leaves {@code run} by exception, and everything the run had learned by then —
     * including the turns that would explain the failure — goes with it. This is how a caller
     * keeps what happened when there is no outcome to read it from.
     */
    private final java.util.function.Consumer<String> onTurn;

    public AgentLoop(ChatModel model, Tools toolbox, AgentBudget budget, int contextSoftCap,
                     InstantSource clock) {
        this(model, toolbox, budget, contextSoftCap, clock, ContextWindow.Retention.IMPLEMENT);
    }

    /**
     * @param retention which tool results survive eviction — see {@link ContextWindow.Retention}.
     *                  A coding agent's evidence is on disk; an explorer's is only in the window.
     */
    public AgentLoop(ChatModel model, Tools toolbox, AgentBudget budget, int contextSoftCap,
                     InstantSource clock, ContextWindow.Retention retention) {
        this(model, toolbox, budget, contextSoftCap, clock, retention, null);
    }

    /** @param onTurn each turn's transcript line as it completes — see the field javadoc */
    public AgentLoop(ChatModel model, Tools toolbox, AgentBudget budget, int contextSoftCap,
                     InstantSource clock, ContextWindow.Retention retention,
                     java.util.function.Consumer<String> onTurn) {
        this.model = model;
        this.toolbox = toolbox;
        this.budget = budget;
        this.contextSoftCap = contextSoftCap;
        this.clock = clock;
        this.retention = retention;
        this.onTurn = onTurn;
    }

    public AgentOutcome run(String systemPrompt, String workOrder, String modelName, int maxTokensPerCall) {
        ContextWindow window = new ContextWindow(contextSoftCap, retention);
        window.addSystem(systemPrompt);
        window.addWorkOrder(workOrder);
        List<String> events = new ArrayList<>();
        // One ObjectNode per model call (design line 60's turn transcript); mutated in place as
        // tool_results arrive, then serialized to JSON lines at whichever outcome() call returns.
        List<ObjectNode> transcript = new ArrayList<>();
        Instant start = clock.instant();
        long tokens = 0;
        int turns = 0;
        int strikes = 0;
        String lastSignature = null;
        int sameSignature = 0;
        String lastBuildOutput = null;
        boolean evictedOnFourHundred = false;
        boolean warnedSynthesizedIds = false;

        while (true) {
            if (turns >= budget.maxTurns()) {
                return outcome(AgentResult.BUDGET_TURNS, "turn budget reached", turns, tokens, events, transcript);
            }
            if (!clock.instant().isBefore(start.plus(budget.maxWall()))) {
                return outcome(AgentResult.BUDGET_TIME, "time budget reached", turns, tokens, events, transcript);
            }
            if (tokens >= budget.maxTokens()) {
                return outcome(AgentResult.BUDGET_TOKENS, "token budget reached", turns, tokens, events, transcript);
            }

            // Flush the previous turn BEFORE the call that may never return: a line emitted
            // after the failure is a line the caller never sees.
            emitLastTurn(transcript);
            ChatResponse response;
            try {
                response = model.complete(new ChatRequest(modelName, window.messages(),
                        toolbox.specs(), maxTokensPerCall, 0.0));
            } catch (ModelException e) {
                // Partial spend must reach the run-budget accounting. ADD to any tokens the
                // exception already carries — overwriting would zero an upstream carry.
                tokens += e.tokensSoFar();
                if (e.statusCode() == 400) {
                    // Design line 71: HTTP 400 → one eviction retry → CONTEXT_EXHAUSTED. The first
                    // 400 in this run forces a full eviction and retries the SAME request (the window
                    // rebuilds it on the next loop iteration); any later 400 — immediately on retry or
                    // further into the run — means eviction didn't help, so give up cleanly instead of
                    // throwing and aborting the whole run.
                    if (!evictedOnFourHundred) {
                        evictedOnFourHundred = true;
                        window.evictAll();
                        events.add("turn " + (turns + 1)
                                + ": endpoint rejected oversized request — evicted and retried");
                        continue;
                    }
                    return outcome(AgentResult.CONTEXT_EXHAUSTED,
                            "context exhausted (endpoint rejected oversized request)", turns, tokens, events,
                            transcript);
                }
                emitLastTurn(transcript);
                throw e.withTokens(tokens);
            }
            turns++;
            tokens += response.usage().promptTokens() + response.usage().completionTokens();
            ObjectNode turnEntry = newTurnEntry(turns, response);
            transcript.add(turnEntry);

            if (window.evictIfOverCap(response.usage().promptTokens()) == 0
                    && response.usage().promptTokens() > contextSoftCap) {
                return outcome(AgentResult.CONTEXT_EXHAUSTED, "context exhausted", turns, tokens, events, transcript);
            }

            ChatMessage message = response.message();
            if (message.toolCalls().isEmpty()) {
                window.addAssistant(message);
                window.addWorkOrder("Call a tool or done — do not answer in prose.");
                events.add("turn " + turns + ": no tool call");
                if (++strikes >= MAX_STRIKES) {
                    // "no tool calls" alone names a symptom shared by two unrelated causes: an
                    // endpoint that cannot return tool_calls at all, and a model that spent its
                    // budget before reaching them. What it answered WITH separates them, and
                    // without it the reader's next move is to go read the transcript to learn
                    // what this line could have said.
                    return outcome(AgentResult.MALFORMED, malformedDetail(response), turns, tokens,
                            events, transcript);
                }
                continue;
            }

            window.addAssistant(message);
            // The endpoint did not return structured tool_calls — the call arrived in the older
            // `function_call` field, or as plain content — so HttpChatModel had to invent the id
            // this loop pairs results by. It works here, but nothing has verified the gateway
            // accepts an id it never issued. Say so once, in the record a reader actually opens,
            // rather than leaving it to be inferred from the transcript.
            if (!warnedSynthesizedIds && message.toolCalls().stream()
                    .anyMatch(c -> c.id().startsWith(HttpChatModel.SYNTHETIC_CALL_ID_PREFIX))) {
                warnedSynthesizedIds = true;
                events.add("turn " + turns + ": endpoint did not return structured tool_calls — "
                        + "tool-call ids are synthesized and unverified against this gateway");
            }
            for (ToolCall raw : message.toolCalls()) {
                // The tool set gets to say what this call means before anything else looks at
                // it, so a multiplexed declaration is invisible to done interception, the wedge
                // detector and the transcript alike.
                ToolCall call = toolbox.route(raw);
                if (call.name().equals("done")) {
                    AgentOutcome done = tryDone(call, turns, tokens, events, transcript);
                    if (done != null) {
                        return done;
                    }
                    addToolResult(window, turnEntry, call.id(), "done",
                            "malformed done — provide result and summary");
                    if (++strikes >= MAX_STRIKES) {
                        return outcome(AgentResult.MALFORMED, "malformed done", turns, tokens, events, transcript);
                    }
                    continue;
                }

                String signature = call.name() + "\n" + call.argumentsJson();
                sameSignature = signature.equals(lastSignature) ? sameSignature + 1 : 1;
                lastSignature = signature;
                if (sameSignature >= WEDGE_REPEAT) {
                    return outcome(AgentResult.WEDGED, "identical action repeated", turns, tokens, events,
                            transcript);
                }

                try {
                    String result = toolbox.dispatch(call.name(), call.argumentsJson());
                    addToolResult(window, turnEntry, call.id(), call.name(), result);
                    // Re-pinned after every call so the digest reflects the latest state; the pin
                    // replaces rather than accumulates, and survives eviction and evictAll.
                    String digest = toolbox.digest();
                    if (digest != null) {
                        window.setPinned(digest);
                    }
                    strikes = 0;
                    if (call.name().equals(toolbox.buildToolName())) {
                        // Live-smoke false positive: a PASSING build's compacted output is
                        // inherently low-entropy ("exit 0 (task)") and identical on every
                        // successful verification, so an agent that verifies twice in a row was
                        // being wedged for doing the right thing. Only compare-and-wedge when the
                        // CURRENT result represents a build FAILURE — design line 59's actual
                        // intent is catching a re-run of the same broken build with no changes.
                        // lastBuildOutput still updates unconditionally so a later failure is
                        // compared against the right baseline.
                        if (!isPassingBuildResult(result) && result.equals(lastBuildOutput)) {
                            return outcome(AgentResult.WEDGED, "identical build output", turns, tokens, events,
                                    transcript);
                        }
                        lastBuildOutput = result;
                    }
                } catch (MalformedCallException e) {
                    addToolResult(window, turnEntry, call.id(), call.name(), "malformed call: " + e.getMessage());
                    events.add("turn " + turns + ": malformed " + call.name());
                    if (++strikes >= MAX_STRIKES) {
                        return outcome(AgentResult.MALFORMED, e.getMessage(), turns, tokens, events, transcript);
                    }
                } catch (ToolException e) {
                    addToolResult(window, turnEntry, call.id(), call.name(), "error: " + e.getMessage());
                    strikes = 0;
                } catch (RuntimeException e) {
                    // Defense-in-depth: no future tool bug should be able to escape run()
                    // unhandled and break the OpenAI tool-call pairing. Treat an unexpected
                    // crash like a legitimate ToolException failure the model can react to.
                    addToolResult(window, turnEntry, call.id(), call.name(), "error: " + e.getMessage());
                    strikes = 0;
                }
            }
        }
    }

    /** The compacted build result's header line is "exit N ..." (OutputCompactor) or, uncompacted,
     *  "exit N\n..." (GradleTool/NpmTool.run) — either way "exit 0" as the first line means the
     *  build passed. */
    private static boolean isPassingBuildResult(String result) {
        int newline = result.indexOf('\n');
        String firstLine = newline >= 0 ? result.substring(0, newline) : result;
        return firstLine.startsWith("exit 0");
    }

    private AgentOutcome tryDone(ToolCall call, int turns, long tokens, List<String> events,
                                 List<ObjectNode> transcript) {
        if (call.argumentsJson() == null) {
            return null;
        }
        try {
            JsonNode args = JSON.readTree(call.argumentsJson());
            String result = args.path("result").asText();
            String summary = args.path("summary").asText();
            if (result.equals("success")) {
                return outcome(AgentResult.DONE, summary, turns, tokens, events, transcript);
            }
            if (result.equals("blocked")) {
                return outcome(AgentResult.BLOCKED, summary, turns, tokens, events, transcript);
            }
            return null;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    /** Builds this turn's transcript entry with everything known right after the model responds:
     *  finish reason, token usage, content, and the tool calls it made. tool_results starts empty and
     *  is filled in by {@link #addToolResult} as each call is dispatched. */
    /** How many characters of the model's prose to quote — enough to tell a refusal from
     *  reasoning, short enough to sit on a terminal line. */
    private static final int DETAIL_EXCERPT = 200;

    /** Why the model answered in prose, in the terms that distinguish the causes. */
    private static String malformedDetail(ChatResponse response) {
        String content = response.message().content();
        String said = content == null || content.isBlank()
                ? "(empty content)"
                : content.strip().replace('\n', ' ');
        if (said.length() > DETAIL_EXCERPT) {
            said = said.substring(0, DETAIL_EXCERPT) + "…";
        }
        return "no tool calls after " + MAX_STRIKES + " turns"
                + " (finish_reason=" + response.finishReason()
                + ", last completion " + response.usage().completionTokens() + " tokens)"
                + " — model answered: " + said;
    }

    private ObjectNode newTurnEntry(int turn, ChatResponse response) {
        ObjectNode node = JSON.createObjectNode();
        node.put("turn", turn);
        node.put("at", clock.instant().toString());
        node.put("finish", response.finishReason());
        node.put("prompt_tokens", response.usage().promptTokens());
        node.put("completion_tokens", response.usage().completionTokens());
        node.put("content", cap(response.message().content()));
        ArrayNode calls = node.putArray("tool_calls");
        for (ToolCall call : response.message().toolCalls()) {
            ObjectNode c = calls.addObject();
            c.put("name", call.name());
            c.put("args", cap(call.argumentsJson()));
        }
        node.putArray("tool_results");
        return node;
    }

    /** Feeds the result back to the model AND mirrors it into the current turn's transcript entry —
     *  every call site of this method is a 1:1 replacement for a former window.addToolResult call. */
    private static void addToolResult(ContextWindow window, ObjectNode turnEntry, String callId, String name,
                                      String result) {
        window.addToolResult(callId, name, result);
        ObjectNode r = ((ArrayNode) turnEntry.get("tool_results")).addObject();
        r.put("name", name);
        r.put("result", cap(result));
    }

    private static final int MAX_TRANSCRIPT_FIELD_CHARS = 2000;

    private static String cap(String text) {
        if (text == null || text.length() <= MAX_TRANSCRIPT_FIELD_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TRANSCRIPT_FIELD_CHARS) + "…(truncated)";
    }

    private int emitted;

    /** Emits every completed-but-unemitted turn line. Idempotent: each line goes out once. */
    private void emitLastTurn(List<ObjectNode> transcript) {
        if (onTurn == null) {
            return;
        }
        while (emitted < transcript.size()) {
            onTurn.accept(writeLine(transcript.get(emitted++)));
        }
    }

    private AgentOutcome outcome(AgentResult result, String summary, int turns, long tokens,
                                 List<String> events, List<ObjectNode> transcript) {
        emitLastTurn(transcript);
        return new AgentOutcome(result, summary, turns, tokens, events, writeLines(transcript));
    }

    private static List<String> writeLines(List<ObjectNode> transcript) {
        List<String> lines = new ArrayList<>(transcript.size());
        for (ObjectNode node : transcript) {
            lines.add(writeLine(node));
        }
        return lines;
    }

    private static String writeLine(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A tree already built from primitives/strings via ObjectMapper never actually fails to
            // serialize; this is defense-in-depth so a transcript line is never silently dropped.
            return "{}";
        }
    }
}
