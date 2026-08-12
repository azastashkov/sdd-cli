package sdd.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.agent.tool.MalformedCallException;
import sdd.agent.tool.ToolException;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
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
        Instant start = clock.instant();
        long tokens = 0;
        int turns = 0;
        int strikes = 0;
        String lastSignature = null;
        int sameSignature = 0;
        String lastGradleOutput = null;

        while (true) {
            if (turns >= budget.maxTurns()) {
                return outcome(AgentResult.BUDGET_TURNS, "turn budget reached", turns, tokens, events);
            }
            if (!clock.instant().isBefore(start.plus(budget.maxWall()))) {
                return outcome(AgentResult.BUDGET_TIME, "time budget reached", turns, tokens, events);
            }
            if (tokens >= budget.maxTokens()) {
                return outcome(AgentResult.BUDGET_TOKENS, "token budget reached", turns, tokens, events);
            }

            ChatResponse response = model.complete(new ChatRequest(modelName, window.messages(),
                    toolbox.specs(), maxTokensPerCall, 0.0));
            turns++;
            tokens += response.usage().promptTokens() + response.usage().completionTokens();

            if (window.evictIfOverCap(response.usage().promptTokens()) == 0
                    && response.usage().promptTokens() > contextSoftCap) {
                return outcome(AgentResult.CONTEXT_EXHAUSTED, "context exhausted", turns, tokens, events);
            }

            ChatMessage message = response.message();
            if (message.toolCalls().isEmpty()) {
                window.addAssistant(message);
                window.addWorkOrder("Call a tool or done — do not answer in prose.");
                events.add("turn " + turns + ": no tool call");
                if (++strikes >= MAX_STRIKES) {
                    return outcome(AgentResult.MALFORMED, "no tool calls", turns, tokens, events);
                }
                continue;
            }

            window.addAssistant(message);
            for (ToolCall call : message.toolCalls()) {
                if (call.name().equals("done")) {
                    AgentOutcome done = tryDone(call, turns, tokens, events);
                    if (done != null) {
                        return done;
                    }
                    window.addToolResult(call.id(), "done", "malformed done — provide result and summary");
                    if (++strikes >= MAX_STRIKES) {
                        return outcome(AgentResult.MALFORMED, "malformed done", turns, tokens, events);
                    }
                    continue;
                }

                String signature = call.name() + "\n" + call.argumentsJson();
                sameSignature = signature.equals(lastSignature) ? sameSignature + 1 : 1;
                lastSignature = signature;
                if (sameSignature >= WEDGE_REPEAT) {
                    return outcome(AgentResult.WEDGED, "identical action repeated", turns, tokens, events);
                }

                try {
                    String result = toolbox.dispatch(call.name(), call.argumentsJson());
                    window.addToolResult(call.id(), call.name(), result);
                    strikes = 0;
                    if (call.name().equals("run_gradle")) {
                        if (result.equals(lastGradleOutput)) {
                            return outcome(AgentResult.WEDGED, "identical build output", turns, tokens, events);
                        }
                        lastGradleOutput = result;
                    }
                } catch (MalformedCallException e) {
                    window.addToolResult(call.id(), call.name(), "malformed call: " + e.getMessage());
                    events.add("turn " + turns + ": malformed " + call.name());
                    if (++strikes >= MAX_STRIKES) {
                        return outcome(AgentResult.MALFORMED, e.getMessage(), turns, tokens, events);
                    }
                } catch (ToolException e) {
                    window.addToolResult(call.id(), call.name(), "error: " + e.getMessage());
                    strikes = 0;
                }
            }
        }
    }

    private AgentOutcome tryDone(ToolCall call, int turns, long tokens, List<String> events) {
        try {
            JsonNode args = JSON.readTree(call.argumentsJson());
            String result = args.path("result").asText();
            String summary = args.path("summary").asText();
            if (result.equals("success")) {
                return outcome(AgentResult.DONE, summary, turns, tokens, events);
            }
            if (result.equals("blocked")) {
                return outcome(AgentResult.BLOCKED, summary, turns, tokens, events);
            }
            return null;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    private static AgentOutcome outcome(AgentResult result, String summary, int turns, long tokens,
                                        List<String> events) {
        return new AgentOutcome(result, summary, turns, tokens, events);
    }
}
