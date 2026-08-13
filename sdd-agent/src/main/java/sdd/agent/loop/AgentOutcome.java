package sdd.agent.loop;

import java.util.List;

/** The terminal state of one agent attempt. summary carries the done summary or the stop reason.
 *  transcript is one JSON line per model call (design line 60): turn number, timestamp, finish reason,
 *  token usage, content, and the tool calls/results exchanged that turn. */
public record AgentOutcome(AgentResult result, String summary, int turns, long tokens,
                           List<String> events, List<String> transcript) {
    public AgentOutcome {
        events = List.copyOf(events);
        transcript = List.copyOf(transcript);
    }
}
