package sdd.agent.loop;

import java.util.List;

/** The terminal state of one agent attempt. summary carries the done summary or the stop reason. */
public record AgentOutcome(AgentResult result, String summary, int turns, long tokens,
                           List<String> events) {
    public AgentOutcome {
        events = List.copyOf(events);
    }
}
