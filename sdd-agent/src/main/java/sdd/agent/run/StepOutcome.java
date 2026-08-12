package sdd.agent.run;

import java.util.List;

/** The terminal state of one repo-step attempt. verificationOutput is the compacted gate result (or ""). */
public record StepOutcome(StepResult result, String summary, List<String> events,
                          String verificationOutput) {
    public StepOutcome {
        events = List.copyOf(events);
    }
}
