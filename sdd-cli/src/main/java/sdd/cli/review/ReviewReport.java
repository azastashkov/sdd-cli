package sdd.cli.review;

import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.Scheduler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders the Gate-2 review report (design line 67): estate-wide state, per-repo diffstats, human
 * decisions and rebuild verdicts, contract drift, checkpoint drift, staging/branch-restore problems,
 * the propagation mechanisms the estate was built with, and the release runbook. Sections follow
 * {@code CurationReport}'s idiom — omitted entirely when there is nothing to say.
 *
 * <p>The governing rule for every line below: <b>no section may state or imply something the code
 * does not know to be true.</b> A verdict computed against an unstaged provider is printed with its
 * caveat attached rather than alone; a rebuild that did not run says so instead of borrowing
 * {@code --no-rebuild}'s wording; a contract extracted from an unknown position says "unknown".
 * This document is what a human hands a colleague to decide whether the estate is safe to ship.
 */
public final class ReviewReport {
    private ReviewReport() {
    }

    public static String render(String runId, PlanModel plan, RunState state,
                                Map<String, RunGit.DiffStat> diffStats,
                                Map<String, EstateRebuild.Result> rebuilds,
                                List<String> notLocallyVerified, List<String> stagingFailures,
                                List<String> restoreFailures, List<String> diffFailures,
                                List<ContractRecheck.Finding> contracts,
                                Map<String, DecisionRecord> decisions,
                                RunContext.Checkpoints checkpoints,
                                String runbook, RebuildScope rebuild) {
        Map<String, RepoRun> byName = new LinkedHashMap<>();
        for (RepoRun run : state.repos()) {
            byName.put(run.repo(), run);
        }
        Set<String> unstaged = unstagedRepos(plan, stagingFailures);
        // Every verdict below an unstaged repo was computed against that repo's pre-run tree.
        Map<String, String> voidedBy = voidedBy(plan, unstaged);

        StringBuilder md = new StringBuilder();
        md.append("# Review report\n\n");
        md.append("Run: ").append(runId).append("\n\n");

        appendSummary(md, plan, state, rebuilds, rebuild, contracts, decisions, unstaged,
                checkpoints.drift());
        // Both of these invalidate what the Repos section says, so they precede it: a reader who
        // meets "rebuild: OK" first has already formed a verdict by the time the caveat arrives.
        appendStagingFailures(md, stagingFailures);
        appendCheckpointDrift(md, checkpoints.drift());
        appendRepos(md, plan, byName, diffStats, rebuilds, notLocallyVerified, decisions, unstaged,
                voidedBy, checkpoints.branchGone());
        appendRebuildFailures(md, rebuilds);
        appendContracts(md, contracts, unstaged);
        appendRestoreFailures(md, restoreFailures);
        appendDiffFailures(md, diffFailures);
        appendPropagation(md, plan);

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
                                      Map<String, EstateRebuild.Result> rebuilds,
                                      RebuildScope rebuild, List<ContractRecheck.Finding> contracts,
                                      Map<String, DecisionRecord> decisions, Set<String> unstaged,
                                      List<String> checkpointDrift) {
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
        appendRebuildLine(md, rebuilds, rebuild);
        appendContractLine(md, plan, contracts, unstaged, rebuild);
        appendDecisionCounts(md, plan, decisions);
        if (!checkpointDrift.isEmpty()) {
            md.append("- Checkpoint drift: ").append(checkpointDrift.size())
                    .append(checkpointDrift.size() == 1 ? " repo has moved off its checkpoint"
                            : " repos have moved off their checkpoints")
                    .append(" — see Checkpoint drift\n");
        }
        md.append("- Exit codes: 0 = every repo SUCCEEDED, every rebuild passed, every repo was "
                + "staged at its checkpoint and restored, and no branch drifted; "
                + "2 = a repo is not SUCCEEDED, a rebuild/checkout failed, a repo could not be "
                + "staged at its checkpoint (which voids every verdict downstream of it), or a "
                + "branch has moved off the checkpoint this report describes — or, from "
                + "sdd review approve/reject/redo (which re-renders this same report), the decision "
                + "was refused by a cross-repo invariant, or approve applied the decision but the "
                + "squash itself failed; "
                + "4 = no report could be produced (unusable input, no run directory, or the run's "
                + "lock is still held)\n");
        md.append('\n');
    }

    /** The most-read line in the document, and the one with the most ways to lie — see
     *  {@link RebuildScope}. */
    private static void appendRebuildLine(StringBuilder md, Map<String, EstateRebuild.Result> rebuilds,
                                          RebuildScope rebuild) {
        long failed = rebuilds.values().stream().filter(r -> !r.ok()).count();
        String totals = (rebuilds.size() - failed) + " passed, " + failed + " failed";
        List<String> reverified = rebuild.reverified();
        // A switch EXPRESSION, not a statement with a default -> throw: RebuildScope.Kind is
        // exhaustively covered by name, so a fourth kind fails the build instead of only this
        // report at runtime.
        String line = switch (rebuild.kind()) {
            case ESTATE -> totals + (reverified.isEmpty() ? "" : " (estate-wide, with "
                    + subtreePhrase(reverified) + " re-verified again after a redo)");
            case SKIPPED -> "skipped (--no-rebuild)" + (reverified.isEmpty() ? "" : "; only "
                    + subtreePhrase(reverified) + " was re-verified after a redo: " + totals);
            case NONE -> reverified.isEmpty()
                    ? "not re-run in this invocation — this report carries no rebuild "
                            + "verdicts (run sdd review for a fresh estate rebuild)"
                    : "not re-run for the whole estate; only " + subtreePhrase(reverified)
                            + " was re-verified after a redo: " + totals;
        };
        md.append("- Estate rebuild: ").append(line).append('\n');
    }

    private static String subtreePhrase(List<String> repos) {
        return repos.size() == 1 ? repos.get(0) + "'s downstream subtree"
                : "the downstream subtrees of " + String.join(", ", repos);
    }

    private static void appendContractLine(StringBuilder md, PlanModel plan,
                                           List<ContractRecheck.Finding> contracts,
                                           Set<String> unstaged, RebuildScope rebuild) {
        if (plan.contracts().isEmpty()) {
            md.append("- Contract re-check: no contracts in this plan\n");
            return;
        }
        if (contracts.isEmpty()) {
            // The plan HAS contracts — "no contracts in this plan" would be flatly false, and so
            // would a "0 mismatches" clean bill of health for checks that never ran.
            md.append("- Contract re-check: none of this plan's ").append(plan.contracts().size())
                    .append(" contracts were re-checked in this invocation\n");
            return;
        }
        long nonMatches = contracts.stream()
                .filter(f -> f.status() != ContractRecheck.Status.MATCHES).count();
        md.append("- Contract re-check: ").append(contracts.size()).append(" checked, ")
                .append(nonMatches).append(" mismatch").append(nonMatches == 1 ? "" : "es");
        // "N checked, 0 mismatches" reads as a clean bill of health, so it may never stand alone
        // when the trees it compared were not the trees this run produced.
        List<String> unstagedProviders = contracts.stream().map(ContractRecheck.Finding::provider)
                .distinct().filter(unstaged::contains).toList();
        if (!unstagedProviders.isEmpty()) {
            md.append(" — UNRELIABLE: ").append(String.join(", ", unstagedProviders))
                    .append(unstagedProviders.size() == 1 ? " could not be staged at its checkpoint,"
                            : " could not be staged at their checkpoints,")
                    .append(unstagedProviders.size() == 1 ? " so its contracts" : " so their contracts")
                    .append(" were extracted from pre-run code");
        } else if (rebuild.kind() != RebuildScope.Kind.ESTATE) {
            md.append(" — no rebuild staged the estate first, so each provider was read on whatever "
                    + "branch it was checked out at");
        }
        md.append('\n');
        appendConformanceCounts(md, contracts);
    }

    /** The second, independent axis (design amendment 2026-08-14): not "did the implementation
     *  change since the run recorded it" but "does it match what Gate 1 approved". Counted
     *  separately from the mismatch total above — a {@code MATCHES}-status finding can still be
     *  {@code DIVERGED_FROM_PLAN}, so this line is the only place that count is visible at all. */
    private static void appendConformanceCounts(StringBuilder md, List<ContractRecheck.Finding> contracts) {
        Map<ContractRecheck.Conformance, Long> counts = contracts.stream()
                .collect(Collectors.groupingBy(ContractRecheck.Finding::conformance, Collectors.counting()));
        md.append("- Plan conformance: ")
                .append(counts.getOrDefault(ContractRecheck.Conformance.DECLARED_MET, 0L)).append(" met, ")
                .append(counts.getOrDefault(ContractRecheck.Conformance.DIVERGED_FROM_PLAN, 0L)).append(" diverged, ")
                .append(counts.getOrDefault(ContractRecheck.Conformance.NOT_DECLARED, 0L)).append(" undeclared, ")
                .append(counts.getOrDefault(ContractRecheck.Conformance.NOT_COMPARABLE, 0L)).append(" not comparable")
                .append('\n');
    }

    private static void appendDecisionCounts(StringBuilder md, PlanModel plan,
                                             Map<String, DecisionRecord> decisions) {
        md.append(decisionsSummaryLine(plan, decisions)).append('\n');
    }

    /** The report's {@code "- Decisions: N approved, N rejected, N redo, N pending"} line (no
     *  trailing newline) — the one place this wording is built, so {@code sdd status} can print it
     *  identically rather than hand-syncing a second copy of the format string. */
    public static String decisionsSummaryLine(PlanModel plan, Map<String, DecisionRecord> decisions) {
        Map<Decision, Integer> counts = new LinkedHashMap<>();
        for (String repo : Scheduler.sequence(plan.order())) {
            counts.merge(decisionOf(decisions, repo), 1, Integer::sum);
        }
        return "- Decisions: " + counts.getOrDefault(Decision.APPROVED, 0)
                + " approved, " + counts.getOrDefault(Decision.REJECTED, 0)
                + " rejected, " + counts.getOrDefault(Decision.REDO, 0)
                + " redo, " + counts.getOrDefault(Decision.PENDING, 0)
                + " pending";
    }

    private static void appendRepos(StringBuilder md, PlanModel plan, Map<String, RepoRun> byName,
                                    Map<String, RunGit.DiffStat> diffStats,
                                    Map<String, EstateRebuild.Result> rebuilds,
                                    List<String> notLocallyVerified,
                                    Map<String, DecisionRecord> decisions, Set<String> unstaged,
                                    Map<String, String> voidedBy, Set<String> branchGone) {
        md.append("## Repos\n\n");
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoRun run = byName.get(repo);
            RepoState repoState = run == null ? null : run.state();
            md.append("- **").append(repo).append("**: ").append(repoState == null ? "UNKNOWN" : repoState);
            md.append(", decision: ").append(decisionOf(decisions, repo));
            String reason = decisions.containsKey(repo) ? decisions.get(repo).reason() : "";
            if (reason != null && !reason.isBlank()) {
                md.append(" (").append(reason).append(')');
            }
            if (run != null && run.checkpointSha() != null) {
                md.append(", checkpoint ").append(run.checkpointSha());
            }
            if (branchGone.contains(repo)) {
                // The runbook below still names this branch. Say it is gone here rather than let a
                // human discover it at merge time.
                md.append(", run branch ").append(run == null ? "?" : run.branch())
                        .append(" no longer exists");
            }
            RunGit.DiffStat stat = diffStats.get(repo);
            if (stat != null) {
                md.append(", ").append(stat.filesChanged()).append(" files changed (+")
                        .append(stat.insertions()).append("/-").append(stat.deletions()).append(')');
            }
            if (unstaged.contains(repo)) {
                md.append(", not staged at its checkpoint — see Staging failures");
            }
            if (notLocallyVerified.contains(repo)) {
                md.append(", not locally verified (all verification tasks excluded)");
            } else if (rebuilds.containsKey(repo)) {
                EstateRebuild.Result result = rebuilds.get(repo);
                md.append(", rebuild: ").append(result.ok() ? "OK" : "FAILED");
                // The caveat travels WITH the verdict: a reader scanning per-repo lines must not be
                // able to take an OK at face value and never reach the staging section.
                if (voidedBy.containsKey(repo)) {
                    md.append(" (UNRELIABLE — upstream ").append(voidedBy.get(repo))
                            .append(" was not staged at its checkpoint)");
                }
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

    private static void appendContracts(StringBuilder md, List<ContractRecheck.Finding> contracts,
                                        Set<String> unstaged) {
        // Either axis can make a finding worth a human's attention: a non-MATCHES status (the
        // original condition), OR a MATCHES status that still diverges from what Gate 1 approved —
        // the trading-product-a case (Tier shipped where the contract said Optional<Tier>) is
        // Status.MATCHES precisely because the implementation has been wrong since implement time.
        // Without the second half of this OR, that finding would fold into "0 mismatches" with no
        // detail section at all. NOT_COMPARABLE joins it for the same reason: ContractRecheck has
        // already computed a specific, correct reason (malformed declaration, nothing extracted to
        // compare) and staying silent on a MATCHES-status finding would throw that reason away,
        // leaving only a directionless tally in the Summary — a human can't act on "1 not
        // comparable" alone. NOT_DECLARED deliberately does NOT get the same treatment: it is the
        // expected default for every pre-5C plan, so opening the section for it would make the
        // common case noisy while flagging nothing actionable.
        List<ContractRecheck.Finding> notable = contracts.stream()
                .filter(f -> f.status() != ContractRecheck.Status.MATCHES
                        || f.conformance() == ContractRecheck.Conformance.DIVERGED_FROM_PLAN
                        || f.conformance() == ContractRecheck.Conformance.NOT_COMPARABLE)
                .toList();
        if (notable.isEmpty()) {
            return;
        }
        md.append("## Contract re-check\n\n");
        for (ContractRecheck.Finding finding : notable) {
            md.append("- `").append(finding.contractId()).append("` (").append(finding.provider())
                    .append(", ").append(finding.kind()).append("): ").append(finding.status());
            if (finding.detail() != null && !finding.detail().isBlank()) {
                md.append(" — ").append(finding.detail());
            }
            // Recorded precisely so a human can adjudicate a DRIFTED finding: it says which branch
            // the fresh extraction read. Never guessed — an unknown position says so.
            String from = finding.extractedFrom();
            md.append(" (extracted from: ")
                    .append(from == null || from.isBlank() ? "unknown" : from).append(')');
            if (unstaged.contains(finding.provider())) {
                md.append(" — pre-run code: ").append(finding.provider())
                        .append(" could not be staged at its checkpoint");
            }
            // DECLARED_MET is the clean outcome on this axis — silent, the same idiom as a MATCHES
            // status never being spelled out. Every other value is stated explicitly: NOT_DECLARED
            // must never read as a pass, and NOT_COMPARABLE must never read as either a pass or a
            // failure, so both are named rather than left for the reader to infer from silence.
            if (finding.conformance() != ContractRecheck.Conformance.DECLARED_MET) {
                md.append(", conformance: ").append(finding.conformance());
            }
            md.append('\n');
            // Only DIVERGED_FROM_PLAN ever populates this (Finding's own invariant) — named
            // individually rather than joined into one line so a human can act on each separately.
            for (String missing : finding.missing()) {
                md.append("  - declared but not found: ").append(missing).append('\n');
            }
        }
        md.append('\n');
    }

    /** Louder than the other failure sections because it invalidates OTHER repos' verdicts: a repo
     *  that could not be put on its checkpoint was composed into its consumers' builds as whatever
     *  pre-run code it was sitting on, so a rebuild "OK" for a consumer proves nothing. */
    private static void appendStagingFailures(StringBuilder md, List<String> stagingFailures) {
        if (stagingFailures.isEmpty()) {
            return;
        }
        md.append("## Staging failures\n\n");
        md.append("These repos could not be checked out at their checkpoint, so every rebuild "
                + "verdict for a repo that consumes them was computed against pre-run code and "
                + "cannot be trusted:\n\n");
        for (String failure : stagingFailures) {
            md.append("- ").append(failure).append('\n');
        }
        md.append('\n');
    }

    /** A branch that has moved since the run recorded it means the diffs, the diffstats and the
     *  runbook below describe a tree nobody can check out by name any more. */
    private static void appendCheckpointDrift(StringBuilder md, List<String> checkpointDrift) {
        if (checkpointDrift.isEmpty()) {
            return;
        }
        md.append("## Checkpoint drift\n\n");
        md.append("These run branches no longer point at the checkpoint this report was built "
                + "from — whoever moved them did so outside this run:\n\n");
        for (String drift : checkpointDrift) {
            md.append("- ").append(drift).append('\n');
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

    /** Design line 61: the Gate-2 report records the mechanism each dependency was propagated by —
     *  a release decision on a SNAPSHOT/INCLUDE_BUILD edge is a different decision from the same
     *  edge propagated as a published version. */
    private static void appendPropagation(StringBuilder md, PlanModel plan) {
        if (plan.edges().isEmpty()) {
            return;
        }
        md.append("## Propagation\n\n");
        md.append("Consumer -> provider, with the mode and mechanism the estate was built with:\n\n");
        for (PlanModel.PlanEdge edge : plan.edges()) {
            md.append("- ").append(edge.fromRepo()).append(" -> ").append(edge.toRepo())
                    .append(": ").append(edge.mode()).append('/').append(edge.mechanism()).append('\n');
        }
        md.append('\n');
    }

    private static Decision decisionOf(Map<String, DecisionRecord> decisions, String repo) {
        DecisionRecord record = decisions.get(repo);
        return record == null ? Decision.PENDING : record.decision();
    }

    /** {@code stagingFailures} entries are {@code "<repo>: <message>"} — the same convention
     *  {@code InteractiveReview} matches on when it replaces a subset's stale findings. */
    private static Set<String> unstagedRepos(PlanModel plan, List<String> stagingFailures) {
        Set<String> unstaged = new LinkedHashSet<>();
        for (String repo : Scheduler.sequence(plan.order())) {
            if (stagingFailures.stream().anyMatch(failure -> failure.startsWith(repo + ":"))) {
                unstaged.add(repo);
            }
        }
        return unstaged;
    }

    /** Repo -> the unstaged upstream whose pre-run tree its verdict was actually computed against.
     *  A repo that is itself unstaged is left out: its own line already says so, and naming it as
     *  a victim of its own failure reads as two problems where there is one. */
    private static Map<String, String> voidedBy(PlanModel plan, Set<String> unstaged) {
        Map<String, String> voided = new LinkedHashMap<>();
        for (String up : unstaged) {
            for (String down : Decisions.transitiveDownstream(up, plan)) {
                if (!unstaged.contains(down)) {
                    voided.putIfAbsent(down, up);
                }
            }
        }
        return voided;
    }
}
