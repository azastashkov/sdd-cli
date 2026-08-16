package sdd.agent.run;

import sdd.agent.tool.BuildTool;

/**
 * The independent deterministic gate the runner applies on `done` (design M7): run an allowlisted
 * build task and read the compacted result. Prose acceptance items from plan.json are NOT run
 * here — they ride in the work order and surface as human-confirmed.
 */
public final class VerificationRunner {
    private final BuildTool build;
    private final OutputCompactor compactor;
    /** Which vocabulary an infra failure is recognised in; the two ecosystems share almost none. */
    private final sdd.core.toolchain.Toolchain toolchain;

    public record Verdict(boolean passed, String output, boolean infra) {
    }

    public VerificationRunner(BuildTool build, OutputCompactor compactor) {
        this(build, compactor, sdd.core.toolchain.Toolchain.GRADLE);
    }

    public VerificationRunner(BuildTool build, OutputCompactor compactor,
                              sdd.core.toolchain.Toolchain toolchain) {
        this.build = build;
        this.compactor = compactor;
        this.toolchain = toolchain;
    }

    public Verdict verify(String task) {
        String raw = build.runFull(task);
        String compacted = compactor.compact(raw, task);
        boolean passed = compacted.startsWith("exit 0");
        return new Verdict(passed, compacted, !passed && InfraClassifier.isInfra(raw, toolchain));
    }
}
