package sdd.cli.review;

import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.Scheduler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the Gate-2 review report (design line 67): estate-wide state, per-repo diffstats and
 * rebuild verdicts, contract drift, branch-restore problems, and the release runbook. Sections
 * follow {@code CurationReport}'s idiom — omitted entirely when there is nothing to say.
 */
public final class ReviewReport {
    private ReviewReport() {
    }

    public static String render(String runId, PlanModel plan, RunState state,
                                Map<String, RunGit.DiffStat> diffStats,
                                Map<String, EstateRebuild.Result> rebuilds,
                                List<String> notLocallyVerified, List<String> restoreFailures,
                                List<String> diffFailures,
                                List<ContractRecheck.Finding> contracts, String runbook, boolean rebuilt) {
        Map<String, RepoRun> byName = new LinkedHashMap<>();
        for (RepoRun run : state.repos()) {
            byName.put(run.repo(), run);
        }

        StringBuilder md = new StringBuilder();
        md.append("# Review report\n\n");
        md.append("Run: ").append(runId).append("\n\n");

        appendSummary(md, plan, state, rebuilds, rebuilt, contracts);
        appendRepos(md, plan, byName, diffStats, rebuilds, notLocallyVerified);
        appendRebuildFailures(md, rebuilds);
        appendContracts(md, contracts);
        appendRestoreFailures(md, restoreFailures);
        appendDiffFailures(md, diffFailures);

        md.append("## Release runbook\n\n");
        md.append(runbook);
        if (!runbook.endsWith("\n")) {
            md.append('\n');
        }
        md.append('\n');

        md.append("---\n\n_Generated: ").append(Instant.now()).append("_\n\n");
        md.append("Per-repo diffs: .sdd/runs/").append(runId).append("/review/<repo>.diff\n");
        return md.toString();
    }

    private static void appendSummary(StringBuilder md, PlanModel plan, RunState state,
                                      Map<String, EstateRebuild.Result> rebuilds, boolean rebuilt,
                                      List<ContractRecheck.Finding> contracts) {
        md.append("## Summary\n\n");
        Map<RepoState, Integer> counts = new LinkedHashMap<>();
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoState repoState = state.stateOf(repo);
            counts.merge(repoState, 1, Integer::sum);
        }
        for (Map.Entry<RepoState, Integer> entry : counts.entrySet()) {
            md.append("- ").append(entry.getKey() == null ? "UNKNOWN" : entry.getKey())
                    .append(": ").append(entry.getValue()).append('\n');
        }
        md.append("- Total tokens spent: ").append(state.tokensSpent()).append('\n');
        if (rebuilt) {
            long failed = rebuilds.values().stream().filter(r -> !r.ok()).count();
            md.append("- Estate rebuild: ").append(rebuilds.size() - failed).append(" passed, ")
                    .append(failed).append(" failed\n");
        } else {
            md.append("- Estate rebuild: skipped (--no-rebuild)\n");
        }
        if (contracts.isEmpty()) {
            md.append("- Contract re-check: no contracts in this plan\n");
        } else {
            long nonMatches = contracts.stream()
                    .filter(f -> f.status() != ContractRecheck.Status.MATCHES).count();
            md.append("- Contract re-check: ").append(contracts.size()).append(" checked, ")
                    .append(nonMatches).append(" mismatch").append(nonMatches == 1 ? "" : "es").append('\n');
        }
        md.append("- Exit codes: 0 = every repo SUCCEEDED and no rebuild failed; "
                + "2 = a repo is not SUCCEEDED or a rebuild/checkout failed; "
                + "4 = no report could be produced\n");
        md.append('\n');
    }

    private static void appendRepos(StringBuilder md, PlanModel plan, Map<String, RepoRun> byName,
                                    Map<String, RunGit.DiffStat> diffStats,
                                    Map<String, EstateRebuild.Result> rebuilds,
                                    List<String> notLocallyVerified) {
        md.append("## Repos\n\n");
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoRun run = byName.get(repo);
            RepoState repoState = run == null ? null : run.state();
            md.append("- **").append(repo).append("**: ").append(repoState == null ? "UNKNOWN" : repoState);
            if (run != null && run.checkpointSha() != null) {
                md.append(", checkpoint ").append(run.checkpointSha());
            }
            RunGit.DiffStat stat = diffStats.get(repo);
            if (stat != null) {
                md.append(", ").append(stat.filesChanged()).append(" files changed (+")
                        .append(stat.insertions()).append("/-").append(stat.deletions()).append(')');
            }
            if (notLocallyVerified.contains(repo)) {
                md.append(", not locally verified (all verification tasks excluded)");
            } else if (rebuilds.containsKey(repo)) {
                EstateRebuild.Result result = rebuilds.get(repo);
                md.append(", rebuild: ").append(result.ok() ? "OK" : "FAILED");
            }
            if (run != null && run.detail() != null && !run.detail().isBlank()) {
                md.append(" — ").append(run.detail());
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static void appendRebuildFailures(StringBuilder md, Map<String, EstateRebuild.Result> rebuilds) {
        List<Map.Entry<String, EstateRebuild.Result>> failures = rebuilds.entrySet().stream()
                .filter(e -> !e.getValue().ok()).toList();
        if (failures.isEmpty()) {
            return;
        }
        md.append("## Rebuild failures\n\n");
        for (Map.Entry<String, EstateRebuild.Result> entry : failures) {
            String log = entry.getValue().log();
            String excerpt = log == null ? "" : log.substring(0, Math.min(400, log.length()));
            md.append("### ").append(entry.getKey()).append("\n\n```\n").append(excerpt).append("\n```\n\n");
        }
    }

    private static void appendContracts(StringBuilder md, List<ContractRecheck.Finding> contracts) {
        List<ContractRecheck.Finding> nonMatches = contracts.stream()
                .filter(f -> f.status() != ContractRecheck.Status.MATCHES).toList();
        if (nonMatches.isEmpty()) {
            return;
        }
        md.append("## Contract re-check\n\n");
        for (ContractRecheck.Finding finding : nonMatches) {
            md.append("- `").append(finding.contractId()).append("` (").append(finding.provider())
                    .append(", ").append(finding.kind()).append("): ").append(finding.status());
            if (finding.detail() != null && !finding.detail().isBlank()) {
                md.append(" — ").append(finding.detail());
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static void appendRestoreFailures(StringBuilder md, List<String> restoreFailures) {
        if (restoreFailures.isEmpty()) {
            return;
        }
        md.append("## Branch restore failures\n\n");
        md.append("These repos were left off their original branch/commit and need human action:\n\n");
        for (String failure : restoreFailures) {
            md.append("- ").append(failure).append('\n');
        }
        md.append('\n');
    }

    private static void appendDiffFailures(StringBuilder md, List<String> diffFailures) {
        if (diffFailures.isEmpty()) {
            return;
        }
        md.append("## Diff failures\n\n");
        for (String failure : diffFailures) {
            md.append("- ").append(failure).append('\n');
        }
        md.append('\n');
    }
}
