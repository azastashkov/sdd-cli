package sdd.agent.run;

import sdd.agent.tool.GradleTool;

/**
 * The independent deterministic gate the runner applies on `done` (design M7): run an allowlisted
 * Gradle task and read the compacted result. Prose acceptance items from plan.json are NOT run
 * here — they ride in the work order and surface as human-confirmed.
 */
public final class VerificationRunner {
    private final GradleTool gradle;
    private final OutputCompactor compactor;

    public record Verdict(boolean passed, String output) {
    }

    public VerificationRunner(GradleTool gradle, OutputCompactor compactor) {
        this.gradle = gradle;
        this.compactor = compactor;
    }

    public Verdict verify(String task) {
        String compacted = compactor.compact(gradle.runFull(task), task);
        return new Verdict(compacted.startsWith("exit 0"), compacted);
    }
}
