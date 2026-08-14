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
 * that confidently misfiled one failure kind as another. Named components make that transposition
 * a compile error at every call site that uses this record's accessors instead of a raw list.
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
