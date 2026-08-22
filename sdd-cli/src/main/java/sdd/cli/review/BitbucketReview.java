package sdd.cli.review;

import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.Scheduler;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.BitbucketSite;
import sdd.core.diagnostics.Failures;
import sdd.plan.source.SourceBullet;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Task 5 brief §3: {@code sdd review} pushes every SUCCEEDED repo's run branch to Bitbucket and
 * opens (or updates) the pull request that drives the human review. Same discipline as {@code
 * sdd.cli.JiraWriteBack} (Task 4's write-back, read before writing this): best-effort, PER REPO,
 * and called strictly AFTER {@code report.md} is durably written — a Bitbucket outage can never
 * cost a human the review they just paid for, and one repo's Bitbucket failure must not stop the
 * push/PR attempt for the rest. {@link #run} therefore never throws and never changes {@code sdd
 * review}'s exit code; every failure prints {@code "  warn: bitbucket: <detail>"} instead.
 *
 * <p><b>Only when {@code atlassian.pull_requests: true}.</b> That is off by default (see
 * {@link AtlassianConfig#pullRequests()}), and with it {@code sdd review} must be byte-for-byte its
 * pre-Task-5 self — no push, no REST call, no changed output — which is why that check is the very
 * first thing {@link #run} does, before any config or credential is even touched.
 */
public final class BitbucketReview {
    private BitbucketReview() {
    }

    /** One repo's PR-worthy prose plus the little metadata {@code openOrUpdate} needs but does not
     *  otherwise have a home for — read once per {@link #run} call, not once per repo, since it
     *  comes from the same {@code spec.md} every repo in this run shares. */
    private record SpecInfo(String title, String jiraUrl) {
    }

    public static void run(RunContext run, ReportInputs reportInputs, PrintWriter out, PrintWriter err) {
        AtlassianConfig atlassian = run.config().atlassian();
        if (atlassian == null || !atlassian.pullRequests()) {
            return;
        }
        BitbucketSite bitbucket;
        BitbucketClient client;
        try {
            bitbucket = BitbucketClients.requireBitbucket(atlassian);
            client = BitbucketClients.rest(atlassian, run.diagnostics());
        } catch (RuntimeException e) {
            // Gate review I3: this used to print e.getMessage() and nothing else — for common
            // JGit/JDK failures with a null message that is literally the word "null" on a human's
            // terminal, AND it left the diagnostics file (built for exactly this: a remote reader
            // debugging a first-contact failure) with zero lines about it.
            if (run.diagnostics() != null) {
                run.diagnostics().failure("bitbucket: setup", e);
            }
            err.println("  warn: bitbucket: " + Failures.message(e));
            return;
        }

        SpecInfo specInfo = readSpecInfo(run);
        String title = run.plan().specId() + (specInfo.title() == null ? "" : ": " + specInfo.title());

        for (String repo : Scheduler.sequence(run.plan().order())) {
            RepoRun repoRun = run.byName().get(repo);
            if (repoRun == null || repoRun.state() != RepoState.SUCCEEDED) {
                continue;
            }
            try {
                pushAndOpen(run, reportInputs, repo, repoRun, client, atlassian, bitbucket, title,
                        specInfo.jiraUrl(), out, err);
            } catch (RuntimeException e) {
                if (run.diagnostics() != null) {
                    run.diagnostics().failure("bitbucket: " + repo, e);
                }
                err.println("  warn: bitbucket: " + repo + ": " + Failures.message(e));
            }
        }
    }

    private static void pushAndOpen(RunContext run, ReportInputs in, String repo, RepoRun repoRun,
            BitbucketClient client, AtlassianConfig atlassian, BitbucketSite bitbucket, String title,
            String jiraUrl, PrintWriter out, PrintWriter err) {
        Path root = run.paths().get(repo);
        if (root == null || repoRun.branch() == null) {
            err.println("  warn: bitbucket: " + repo + ": no repo path or run branch on record");
            return;
        }
        String cloneUrl = RemoteGit.cloneUrl(bitbucket.site().baseUrl(), bitbucket.project(), repo);
        BitbucketClients.push(run.diagnostics(), root, repoRun.branch(), cloneUrl, BitbucketClients.gitUsername(bitbucket),
                bitbucket.site().token(), atlassian.tls(), atlassian.proxy());
        openOrUpdate(client, run, in, repo, repoRun, root, bitbucket.defaultReviewers(), title, jiraUrl,
                out, err);
    }

    /**
     * Everything AFTER a successful push: the base-ancestry check, finding-or-creating the PR, and
     * recording it in {@code state.json}. Package-private and given an already-built
     * {@link BitbucketClient} — the one seam this class needs to be testable against WireMock
     * without a working git push in front of it (this codebase has no Mockito; a real client
     * pointed at a real WireMock server IS the test double). {@link #run} wires this to a real push
     * for actual use; a test can call this directly with a local checkout that never had anything
     * pushed to it at all, since nothing here reads the remote — only {@link BitbucketClient} and
     * local git.
     */
    static void openOrUpdate(BitbucketClient client, RunContext run, ReportInputs in, String repo,
            RepoRun repoRun, Path root, List<String> reviewers, String title, String jiraUrl,
            PrintWriter out, PrintWriter err) {
        String defaultBranch = client.defaultBranch(repo);
        checkAncestry(root, repo, run.plan().repo(repo).map(PlanModel.PlanRepo::baseSha).orElse(null),
                defaultBranch, err);

        String description = renderDescription(repo, in, run, jiraUrl);

        Optional<BitbucketClient.PullRequest> existing = client.findOpenBySourceBranch(repo, repoRun.branch());
        BitbucketClient.PullRequest pr = existing.isPresent()
                ? client.updateDescription(repo, existing.get(), title, description)
                : client.create(repo, title, description, repoRun.branch(), defaultBranch, reviewers);
        out.println((existing.isPresent() ? "updated" : "opened") + " PR #" + pr.id() + " for " + repo
                + (pr.link() != null ? ": " + pr.link() : ""));

        run.state().set(repo, repoRun.state(), repoRun.branch(), repoRun.checkpointSha(), repoRun.detail(),
                repoRun.failureCode(), pr.id(), pr.link());
        run.store().writeState(run.runDir(), run.state());
    }

    /** Task 5 brief §3's base-ancestry check: {@code plan.json} records no remote and no base
     *  branch, only {@code base_sha} — so this is the earliest point a REAL default-branch name is
     *  known to compare against. Resolved LOCALLY ({@link RunGit#branchHead}), not via a fetch:
     *  {@code sdd} operates on existing local checkouts (design doc line 9), and the ordinary case
     *  is that the default branch is already checked out locally too. When it is not (no local ref
     *  by that name), the check is silently skipped — best-effort, and there is nothing more to
     *  compare against without a fetch this class deliberately does not perform. */
    private static void checkAncestry(Path root, String repo, String baseSha, String defaultBranch,
            PrintWriter err) {
        if (baseSha == null) {
            return;
        }
        try {
            String defaultHead = RunGit.branchHead(root, defaultBranch);
            if (!defaultHead.isEmpty() && !RunGit.isAncestor(root, baseSha, defaultHead)) {
                err.println("  warn: " + repo + ": base_sha is not an ancestor of " + defaultBranch
                        + "; PR diff will be noisy");
            }
        } catch (RuntimeException e) {
            // Best-effort: a check that itself fails (unreadable repo, corrupt ref) must not block
            // the push/PR it was only ever meant to add a caveat to.
        }
    }

    /** Task 5 brief §3: "that repo's rendered findings plus the run id, the spec id, and a link to
     *  the source Jira issue". {@link ReviewReport#renderRepo} is the exact same line {@code
     *  report.md}'s Repos section carries for this repo — the extraction the brief calls for
     *  specifically so the two artifacts can never disagree. */
    private static String renderDescription(String repo, ReportInputs in, RunContext run, String jiraUrl) {
        StringBuilder description = new StringBuilder();
        description.append(ReviewReport.renderRepo(repo, in)).append("\n\n");
        description.append("Run: ").append(run.runId()).append('\n');
        description.append("Spec: ").append(run.plan().specId()).append('\n');
        if (jiraUrl != null) {
            description.append("Jira: ").append(jiraUrl).append('\n');
        }
        return description.toString();
    }

    /** Reads {@code spec.md} (written once at {@code sdd implement} time — see
     *  {@code RunStore.create}) for the PR title's spec title and the source Jira issue's URL.
     *  Fails SILENTLY, same as {@code ReviewCommand.commentOnJiraSources}'s identical read: an
     *  unreadable/unparseable {@code spec.md} at this point can only happen in a test fixture, not
     *  real use, and this is called after {@code report.md} is already on disk either way. */
    private static SpecInfo readSpecInfo(RunContext run) {
        try {
            String specText = Files.readString(run.runDir().resolve("spec.md"));
            NormalizedSpec parsed = SpecParser.parse(specText);
            List<String> jiraKeys = SourceBullet.jiraIssueKeys(parsed.sources());
            String jiraUrl = jiraKeys.isEmpty() ? null
                    : SourceBullet.jiraIssueUrls(parsed.sources()).get(jiraKeys.get(0));
            return new SpecInfo(parsed.title(), jiraUrl);
        } catch (RuntimeException | IOException e) {
            return new SpecInfo(null, null);
        }
    }
}
