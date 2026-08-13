package sdd.agent.run;

import java.util.List;

/** The terminal state of one repo-step attempt. verificationOutput is the compacted gate result (or "");
 *  tokens is the prompt+completion total across every model call of the attempt. */
public record StepOutcome(StepResult result, String summary, List<String> events,
                          String verificationOutput, long tokens) {
    public StepOutcome {
        events = List.copyOf(events);
    }
}
