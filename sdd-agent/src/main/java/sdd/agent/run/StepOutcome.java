package sdd.agent.run;

import java.util.List;

/** The terminal state of one repo-step attempt. verificationOutput is the compacted gate result (or "");
 *  tokens is the prompt+completion total across every model call of the attempt. transcript is one JSON
 *  line per model call across every loop.run() cycle of the attempt (design line 60); edits is one JSON
 *  line per edit the agent successfully applied. */
public record StepOutcome(StepResult result, String summary, List<String> events,
                          String verificationOutput, long tokens, List<String> transcript,
                          List<String> edits) {
    public StepOutcome {
        events = List.copyOf(events);
        transcript = List.copyOf(transcript);
        edits = List.copyOf(edits);
    }
}
