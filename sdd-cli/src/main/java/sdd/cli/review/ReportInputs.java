package sdd.cli.review;

import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;

import java.util.List;
import java.util.Map;

/**
 * Everything {@link ReviewReport#render} needs to build {@code report.md}, as named components
 * rather than a positional parameter list. The point is the four {@code List<String>} components
 * below: {@code notLocallyVerified}, {@code stagingFailures}, {@code restoreFailures} and
 * {@code diffFailures} sat side by side in {@code render}'s old signature with nothing but
 * argument order distinguishing them, so a transposition compiled cleanly and produced a report
 * that confidently misfiled one failure kind as another. Naming them fixes that for every READER:
 * {@code ReviewReport} and its helpers now take {@code inputs.stagingFailures()} rather than
 * whichever list arrived third, so a mix-up inside the renderer no longer compiles.
 *
 * <p>What it does NOT fix is the construction site. This record's canonical constructor still takes
 * the same four {@code List<String>} positionally, and {@link RunContext#writeReport} — the one
 * caller that builds it — takes three of them positionally in turn from its own three callers. The
 * transposition hazard is narrowed to those frames, not eliminated; a builder or per-kind wrapper
 * types would be what eliminates it. See the phase 5C-2 plan's Known carried items.
 *
 * @param runId              the run this report describes
 * @param plan               the frozen plan the run was executed against
 * @param state              per-repo run state
 * @param diffStats          per-repo diffstat, keyed by repo
 * @param rebuilds           per-repo estate-rebuild verdicts, keyed by repo
 * @param notLocallyVerified repos whose verification tasks were all excluded from this run
 * @param stagingFailures    repos that could not be checked out at their checkpoint
 * @param restoreFailures    repos left off their original branch/commit after the run
 * @param diffFailures       repos whose per-repo diff could not be produced
 * @param contracts          contract re-check findings
 * @param decisions          human decisions recorded for this run, keyed by repo
 * @param checkpoints        checkpoint drift and gone-branch findings, read fresh off the estate's git
 * @param runbook            the rendered release runbook
 * @param rebuild            what the rebuild verdicts above actually cover
 */
public record ReportInputs(String runId, PlanModel plan, RunState state,
                           Map<String, RunGit.DiffStat> diffStats,
                           Map<String, EstateRebuild.Result> rebuilds,
                           List<String> notLocallyVerified, List<String> stagingFailures,
                           List<String> restoreFailures, List<String> diffFailures,
                           List<ContractRecheck.Finding> contracts,
                           Map<String, DecisionRecord> decisions,
                           RunContext.Checkpoints checkpoints,
                           String runbook, RebuildScope rebuild) {
    public ReportInputs {
        notLocallyVerified = List.copyOf(notLocallyVerified);
        stagingFailures = List.copyOf(stagingFailures);
        restoreFailures = List.copyOf(restoreFailures);
        diffFailures = List.copyOf(diffFailures);
        contracts = List.copyOf(contracts);
    }
}
