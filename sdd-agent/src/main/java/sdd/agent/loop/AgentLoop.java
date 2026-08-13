package sdd.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sdd.agent.tool.MalformedCallException;
import sdd.agent.tool.ToolException;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatMessage;
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
    private final Toolbox toolbox;
    private final AgentBudget budget;
    private final int contextSoftCap;
    private final InstantSource clock;

    public AgentLoop(ChatModel model, Toolbox toolbox, AgentBudget budget, int contextSoftCap,
                     InstantSource clock) {
        this.model = model;
        this.toolbox = toolbox;
        this.budget = budget;
        this.contextSoftCap = contextSoftCap;
        this.clock = clock;
    }

    public AgentOutcome run(String systemPrompt, String workOrder, String modelName, int maxTokensPerCall) {
        ContextWindow window = new ContextWindow(contextSoftCap);
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
        String lastGradleOutput = null;
        boolean evictedOnFourHundred = false;

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
                    return outcome(AgentResult.MALFORMED, "no tool calls", turns, tokens, events, transcript);
                }
                continue;
            }

            window.addAssistant(message);
            for (ToolCall call : message.toolCalls()) {
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
                    strikes = 0;
                    if (call.name().equals("run_gradle")) {
                        if (result.equals(lastGradleOutput)) {
                            return outcome(AgentResult.WEDGED, "identical build output", turns, tokens, events,
                                    transcript);
                        }
                        lastGradleOutput = result;
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

    private static AgentOutcome outcome(AgentResult result, String summary, int turns, long tokens,
                                        List<String> events, List<ObjectNode> transcript) {
        return new AgentOutcome(result, summary, turns, tokens, events, writeLines(transcript));
    }

    private static List<String> writeLines(List<ObjectNode> transcript) {
        List<String> lines = new ArrayList<>(transcript.size());
        for (ObjectNode node : transcript) {
            try {
                lines.add(JSON.writeValueAsString(node));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // A tree already built from primitives/strings via ObjectMapper never actually fails to
                // serialize; this is defense-in-depth so a transcript line is never silently dropped.
                lines.add("{}");
            }
        }
        return lines;
    }
}
