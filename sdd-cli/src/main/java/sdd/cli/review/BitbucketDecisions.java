package sdd.cli.review;

import sdd.cli.implement.RepoRun;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.BitbucketSite;
import sdd.core.diagnostics.Failures;

import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Task 5 brief §4: the human decision commands drive the Bitbucket pull request the corresponding
 * {@code sdd review} opened. Same best-effort discipline as {@link BitbucketReview} (and Task 4's
 * {@code sdd.cli.JiraWriteBack}, the established pattern both of these follow): never throws, never
 * changes the caller's exit code, prints {@code "  warn: bitbucket: ..."} on failure. A no-op,
 * silently, whenever {@code atlassian.pull_requests} is off or this repo never had a PR recorded —
 * the ordinary case for an estate that never turned Task 5 on.
 *
 * <p><b>Ordering is the crux of this task (brief §4), and the call sites — not this class — are
 * where it is enforced.</b> {@link #afterApprove} is reachable ONLY from
 * {@link DecisionCommand#squashAndRecord}'s two ALREADY-APPLIED branches — never from the branch
 * where {@code SquashApprove} refused (dirty tree, or the branch moved off its checkpoint). A
 * refused local squash therefore reaches this class not at all, which is the single most important
 * property this task adds: see {@code DecisionCommand}'s test suite for the assertion. The inverse
 * order (merge before squash) could leave Bitbucket holding a merge the local checkpoint does not
 * know about — exactly the drift {@code RunContext#checkpoints} exists to detect.
 *
 * <p>{@link #afterReject} has no such ordering hazard — {@code decisions.json} already recorded
 * REJECTED by the time {@link DecisionCommand#call} reaches {@code followUp}, and declining a PR
 * has no local git side effect to get out of order with.
 */
final class BitbucketDecisions {
    private BitbucketDecisions() {
    }

    /** Force-pushes the squashed branch, then merges its PR — brief §4's table: "force-push the
     *  squashed branch, then merge the PR". Push happens first so the PR merges the exact single
     *  commit the human reviewed (brief's closing note), not whatever the run branch's last
     *  checkpoint push left behind. On any failure — push, or the merge itself — local state is
     *  already correct (the squash and its {@code state.json} write-back already happened before
     *  this was ever called); this only warns and tells the human to merge in the UI. */
    static void afterApprove(RunContext run, String repo, Path root, PrintWriter out, PrintWriter err) {
        AtlassianConfig atlassian = run.config().atlassian();
        if (atlassian == null || !atlassian.pullRequests()) {
            gate2(run, repo, "merge not attempted (pull_requests off)");
            return;
        }
        // Re-read AFTER squashAndRecord's own state.json write-back — same RunState instance, so
        // this sees the checkpoint that write-back just set, and the prId/prUrl it preserved.
        RepoRun repoRun = run.byName().get(repo);
        if (repoRun == null || repoRun.prId() == null || repoRun.branch() == null) {
            gate2(run, repo, "merge not attempted (no PR recorded for this repo)");
            return;
        }
        try {
            BitbucketSite bitbucket = BitbucketClients.requireBitbucket(atlassian);
            BitbucketClient client = BitbucketClients.rest(atlassian, run.diagnostics());
            String cloneUrl = RemoteGit.cloneUrl(bitbucket.site().baseUrl(), bitbucket.project(), repo);
            BitbucketClients.push(run.diagnostics(), root, repoRun.branch(), cloneUrl, BitbucketClients.GIT_USERNAME,
                    bitbucket.site().token(), atlassian.tls(), atlassian.proxy());
            gate2(run, repo, "merge attempted");
            merge(client, repo, repoRun.prId(), out, err);
            gate2(run, repo, "merge succeeded");
        } catch (RuntimeException e) {
            // Gate review I3: getMessage() alone is null for plenty of JGit/JDK failures — degrade
            // to the exception's simple name instead of printing the literal word "null", and give
            // the diagnostics file the full cause chain this catch used to swallow entirely.
            String detail = Failures.message(e);
            if (run.diagnostics() != null) {
                run.diagnostics().failure("bitbucket: merge " + repo, e);
            }
            gate2(run, repo, "merge failed: " + detail);
            err.println("  warn: bitbucket: could not merge PR for " + repo
                    + " — merge it manually in the Bitbucket UI: " + detail);
        }
    }

    /** The merge half alone, given an already-built {@link BitbucketClient} — the seam a test uses
     *  to verify merge behaviour (re-fetch for the current {@code version}, then merge with it)
     *  without a working git push in front of it; {@link #afterApprove} wires the two together for
     *  real use. Re-fetches rather than trusting {@code repoRun}'s recorded version, per the brief:
     *  "read it from the PR you just fetched". */
    static void merge(BitbucketClient client, String repo, int prId, PrintWriter out, PrintWriter err) {
        BitbucketClient.PullRequest current = client.get(repo, prId);
        client.merge(repo, prId, current.version());
        out.println("merged PR #" + prId);
    }

    /** Task 8 B3's Gate-2 decision events — a no-op when {@code run} has no diagnostics writer
     *  (every existing test's directly-built {@link RunContext}). See {@code DecisionCommand}'s
     *  {@code squashAndRecord} for the squash/checkpoint half of the same per-repo event stream;
     *  this class contributes only the merge/decline half, which is why the ordering guarantee this
     *  class's own javadoc describes (a refused squash never reaches this class at all) is directly
     *  visible from the log: no "merge" line for a repo means {@link #afterApprove} was never
     *  called for it. */
    private static void gate2(RunContext run, String repo, String event) {
        if (run.diagnostics() != null) {
            run.diagnostics().gate2(repo, event);
        }
    }

    /** Declines the PR — brief §4's table: {@code review reject} declines, with no local git side
     *  effect (the run branch is left as-is). */
    static void afterReject(RunContext run, String repo, PrintWriter out, PrintWriter err) {
        AtlassianConfig atlassian = run.config().atlassian();
        if (atlassian == null || !atlassian.pullRequests()) {
            return;
        }
        RepoRun repoRun = run.byName().get(repo);
        if (repoRun == null || repoRun.prId() == null) {
            return;
        }
        try {
            decline(BitbucketClients.rest(atlassian, run.diagnostics()), repo, repoRun.prId(), out, err);
        } catch (RuntimeException e) {
            if (run.diagnostics() != null) {
                run.diagnostics().failure("bitbucket: decline " + repo, e);
            }
            err.println("  warn: bitbucket: could not decline PR for " + repo + ": " + Failures.message(e));
        }
    }

    /** The decline half alone — same test seam as {@link #merge}. */
    static void decline(BitbucketClient client, String repo, int prId, PrintWriter out, PrintWriter err) {
        BitbucketClient.PullRequest current = client.get(repo, prId);
        client.decline(repo, prId, current.version());
        out.println("declined PR #" + prId);
    }
}
