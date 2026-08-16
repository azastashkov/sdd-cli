package sdd.cli.review;

import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.Scheduler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The human terminal half of Gate 2 (design line 67): walk every repo in {@link Scheduler#sequence}
 * order whose decision is still PENDING, print its report line, and prompt approve/reject/redo/view
 * diff/skip/quit — dispatching to the SAME {@link Decisions} methods {@link DecisionCommand}'s
 * subcommands use, and reusing (not duplicating) the same follow-up work: approve's
 * {@link DecisionCommand#squashAndRecord squash}, redo's
 * {@link DecisionCommand#redoFollowUp downstream re-verify}. A human walking this loop and a script
 * calling {@code sdd review approve}/{@code redo} leave the estate — and the report, and the exit
 * code — in identical shape. Persists after EVERY decision, not batched at the end — a crash or an
 * early {@code q} must lose nothing already decided — and re-renders {@code report.md} once the walk
 * ends, but only when at least one decision was actually recorded (a walk that only viewed diffs and
 * quit changes nothing and must not touch the report). Driven entirely by the injected
 * reader/writer; never touches {@code System.in} itself, which is what makes it testable without a
 * terminal.
 */
public final class InteractiveReview {
    static final String PROMPT = "[a]pprove / [r]eject / re[d]o / [v]iew diff / [s]kip / [q]uit: ";
    private static final String REASON_PROMPT = "reason (optional, press enter to skip): ";

    private InteractiveReview() {
    }

    /**
     * Everything the loop needs beyond the reader/writer: the run itself; the raw workspace and
     * plan path redo's {@code --retry} line needs ({@link RunContext} doesn't carry them — it is
     * built from an already-resolved runId); and the rebuild/contract data the CALLER already
     * computed for this same process's report. An interactive session is part of the SAME
     * {@code sdd review} invocation that just ran the estate rebuild (or explicitly skipped it with
     * {@code --no-rebuild}) — unlike a standalone {@code sdd review approve}, which has no better
     * data to hand — so the loop's own final re-render must carry that data, and the
     * {@link RebuildScope} describing it, through rather than quietly overwriting a real estate
     * rebuild with "not re-run". A redo inside the walk then narrows that scope as it goes.
     */
    public record Context(RunContext run, Path workspace, Path planJsonPath,
                          RebuildPass.Outcome baseline, RebuildScope baselineScope) {
    }

    /** Returns the worst exit code any follow-up demanded (0 if the walk found nothing to complain
     *  about) — a squash refusal or a failed downstream re-verify must not be silently swallowed by
     *  {@code sdd review --interactive}'s own exit code. Callers are expected to have already
     *  refused (exit 4) while the run's lock is held; this method does not check it itself. */
    public static int run(BufferedReader in, PrintWriter out, PrintWriter err, Context ctx)
            throws IOException {
        RunContext run = ctx.run();
        Decisions decisions = new Decisions(run.store().readDecisions(run.runDir()));

        Map<String, EstateRebuild.Result> rebuilds = new LinkedHashMap<>(ctx.baseline().rebuilds());
        List<String> notLocallyVerified = new ArrayList<>(ctx.baseline().notLocallyVerified());
        List<String> stagingFailures = new ArrayList<>(ctx.baseline().stagingFailures());
        List<String> restoreFailures = new ArrayList<>(ctx.baseline().restoreFailures());
        // Grows with every redo: the merged rebuilds map below stops being purely whatever the
        // caller's pass produced, and the report has to say which parts are newer than the rest.
        RebuildScope scope = ctx.baselineScope();

        boolean anyDecision = false;
        int worst = 0;

        walk:
        for (String repo : Scheduler.sequence(run.plan().order())) {
            if (decisions.of(repo) != Decision.PENDING) {
                continue;
            }
            while (true) {
                out.println(reportLine(run, repo));
                out.print(PROMPT);
                out.flush();
                String line = in.readLine();
                if (line == null) {
                    break walk;   // stdin closed — treat exactly like 'q'
                }
                switch (line.strip().toLowerCase(Locale.ROOT)) {
                    case "a" -> {
                        DecisionCommand.Applied applied = DecisionCommand.applyWithRetry(run, repo,
                                (d, r) -> d.approve(repo, r.plan(), r.state()));
                        if (!applied.outcome().applied()) {
                            out.println("refused: " + applied.outcome().message());
                            continue;   // reprompt the SAME repo — nothing changed
                        }
                        decisions = applied.decisions();
                        record(out, run, repo, applied.before(), decisions, applied.outcome());
                        anyDecision = true;
                        DecisionCommand.Followup followup =
                                DecisionCommand.squashAndRecord(run, repo, out, err);
                        followup.trailer().forEach(out::println);
                        worst = Math.max(worst, followup.exitCode());
                        // A squash whose restore failed strands the repo off its original branch.
                        // A scripted `sdd review approve` puts that in its report; the walk's own
                        // final re-render must carry it too, or the same failure is visible from
                        // one entry point and silent from the other. It also supersedes whatever
                        // the caller's own pass said about THIS repo, hence replaceForRepos.
                        if (followup.rebuild() != null) {
                            replaceForRepos(restoreFailures, List.of(repo),
                                    followup.rebuild().restoreFailures());
                        }
                        continue walk;
                    }
                    case "r" -> {
                        String reason = promptReason(in, out);
                        DecisionCommand.Applied applied = DecisionCommand.applyWithRetry(run, repo,
                                (d, r) -> d.reject(repo, r.plan(), reason));
                        decisions = applied.decisions();
                        record(out, run, repo, applied.before(), decisions, applied.outcome());
                        anyDecision = true;
                        // Task 5: same decline DecisionCommand.Reject.followUp triggers for a
                        // scripted "sdd review reject" — reject never refuses (Decisions.reject
                        // always applies), so there is no refusal branch to gate this on the way
                        // approve's squash has one.
                        BitbucketDecisions.afterReject(run, repo, out, err);
                        continue walk;
                    }
                    case "d" -> {
                        String reason = promptReason(in, out);
                        DecisionCommand.Applied applied = DecisionCommand.applyWithRetry(run, repo,
                                (d, r) -> d.redo(repo, r.plan(), reason));
                        decisions = applied.decisions();
                        record(out, run, repo, applied.before(), decisions, applied.outcome());
                        anyDecision = true;
                        DecisionCommand.Followup followup = DecisionCommand.redoFollowUp(run, repo,
                                false, ctx.workspace(), ctx.planJsonPath(), out, err);
                        followup.trailer().forEach(out::println);
                        worst = Math.max(worst, followup.exitCode());
                        RebuildPass.Outcome subset = followup.rebuild();
                        if (subset != null) {
                            List<String> downstream = Decisions.transitiveDownstream(repo, run.plan());
                            scope = scope.withReverifiedSubtreeOf(repo);
                            rebuilds.putAll(subset.rebuilds());
                            notLocallyVerified.removeAll(downstream);
                            notLocallyVerified.addAll(subset.notLocallyVerified());
                            replaceForRepos(stagingFailures, downstream, subset.stagingFailures());
                            replaceForRepos(restoreFailures, downstream, subset.restoreFailures());
                        }
                        continue walk;
                    }
                    case "v" -> printDiff(out, run, repo);
                    case "s" -> {
                        continue walk;   // leave PENDING, move to the next repo
                    }
                    case "q" -> {
                        break walk;
                    }
                    default -> out.println("unrecognized option: " + line);
                }
            }
        }

        if (anyDecision) {
            out.println("review written: " + run.writeReport(run.collectDiffs(), rebuilds,
                    notLocallyVerified, stagingFailures, restoreFailures, ctx.baseline().contracts(),
                    scope));
        }
        return worst;
    }

    /** Drops every {@code "<repo>: ..."} line whose repo is in {@code repos}, then appends the
     *  fresh ones — a redo's re-verify replaces its downstream subset's stale staging/restore
     *  findings rather than accumulating duplicates across a session with more than one redo.
     *  Staging is estate-wide even when the rebuild is not, so {@code fresh} also re-reports repos
     *  OUTSIDE the subset (a FAILED repo anywhere in the run, for one); those are re-stated, not
     *  new, so an identical line is dropped rather than listed twice. */
    private static void replaceForRepos(List<String> lines, List<String> repos, List<String> fresh) {
        lines.removeIf(line -> repos.stream().anyMatch(r -> line.startsWith(r + ":")));
        fresh.stream().filter(line -> !lines.contains(line)).forEach(lines::add);
    }

    private static String promptReason(BufferedReader in, PrintWriter out) throws IOException {
        out.print(REASON_PROMPT);
        out.flush();
        String line = in.readLine();
        return line == null ? "" : line.strip();
    }

    /** The decision itself is already persisted by {@link DecisionCommand#applyWithRetry} before
     *  this runs (a crash right after that call must not lose the verdict a human just gave) — this
     *  appends its event and any downstream downgrade events, and prints the same lines
     *  {@code DecisionCommand} prints. {@code before} comes from the SAME attempt that succeeded,
     *  not assumed to be PENDING: a retry re-reads fresh state, and what {@code repo} held going
     *  into that winning attempt is what actually transitioned. */
    private static void record(PrintWriter out, RunContext run, String repo, Decision before,
                               Decisions decisions, Decisions.Outcome outcome) {
        Decision after = decisions.of(repo);
        run.store().appendEvent(run.runDir(), repo, before, after, decisions.reasonOf(repo));
        for (String downgraded : outcome.downgraded()) {
            run.store().appendEvent(run.runDir(), downgraded, Decision.APPROVED, Decision.PENDING,
                    "upstream " + repo + " is " + after);
        }
        out.println(outcome.message());
        if (!outcome.downgraded().isEmpty()) {
            out.println("downgraded to PENDING (re-decide): " + String.join(", ", outcome.downgraded()));
        }
    }

    private static void printDiff(PrintWriter out, RunContext run, String repo) throws IOException {
        Path diffFile = run.store().reviewDir(run.runDir()).resolve(repo + ".diff");
        if (Files.exists(diffFile)) {
            String content = Files.readString(diffFile);
            out.println(content.isEmpty() ? "(no changes)" : content);
        } else {
            out.println("(no diff available for " + repo + ")");
        }
    }

    private static String reportLine(RunContext run, String repo) {
        RepoRun repoRun = run.byName().get(repo);
        RepoState state = repoRun == null ? null : repoRun.state();
        StringBuilder line = new StringBuilder();
        line.append(repo).append(": ").append(state == null ? "UNKNOWN" : state);
        if (repoRun != null && repoRun.checkpointSha() != null) {
            line.append(", checkpoint ").append(Shas.shortSha(repoRun.checkpointSha()));
        }
        return line.toString();
    }
}
