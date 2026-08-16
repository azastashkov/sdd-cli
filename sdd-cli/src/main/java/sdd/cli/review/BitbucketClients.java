package sdd.cli.review;

import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.AtlassianProxy;
import sdd.core.config.AtlassianTls;
import sdd.core.config.BitbucketSite;
import sdd.core.config.ConfigException;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.http.HttpClients;
import sdd.core.http.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * Builds a {@link BitbucketClient} from {@code atlassian.bitbucket}, or throws — the
 * deferred-credential idiom copied from {@code PlanCommand.atlassianRestClient} /
 * {@code sdd.cli.JiraWriteBack.buildClient}: an unset {@code ${VAR}} token does not fail config
 * loading, so it is raised here instead, the point a {@link RestClient} for Bitbucket is actually
 * about to be built. Shared by {@link BitbucketReview} (opening/updating PRs from {@code sdd
 * review}) and {@link BitbucketDecisions} (merge/decline from the decision commands) so the two
 * do not each grow their own copy of "how do I even talk to Bitbucket".
 */
final class BitbucketClients {
    private BitbucketClients() {
    }

    /**
     * The username {@link RemoteGit#push} sends alongside the Bitbucket PAT over git-over-HTTP.
     * {@link sdd.core.config.AtlassianSite} carries no username field (Task 5's brief never asks
     * for one), and Bitbucket Data Center's HTTP Personal Access Token authentication is carried
     * entirely by the token itself — the username is not independently checked, so any non-empty
     * placeholder works. {@code "x-token-auth"} mirrors the convention several other git hosts use
     * for the same "the token IS the identity" shape. This is one of the least-certain details in
     * this task — see the Task 5 report; if a live instance turns out to check the username after
     * all, this is the one place to change it.
     */
    static final String GIT_USERNAME = "x-token-auth";

    /** Throws {@link ConfigException} when {@code atlassian.pull_requests} is on but
     *  {@code atlassian.bitbucket} is not configured, or its token is unresolvable — every caller
     *  wraps this in its own best-effort try/catch (see {@link BitbucketReview}/
     *  {@link BitbucketDecisions}), so throwing here (rather than returning an {@code Optional}) is
     *  fine: it is never allowed to propagate past that catch. */
    static BitbucketClient rest(AtlassianConfig atlassian) {
        return rest(atlassian, null);
    }

    /** Same as {@link #rest(AtlassianConfig)}, plus an optional {@link DiagnosticWriter} (nullable)
     *  every request the returned client makes reports to — Task 8, threaded from {@code
     *  RunContext#diagnostics()} by both real call sites. A separate overload, not a nullable
     *  parameter added to the existing one, so {@code BitbucketReviewTest}/{@code
     *  InteractiveReviewBitbucketTest} keep compiling unchanged. */
    static BitbucketClient rest(AtlassianConfig atlassian, DiagnosticWriter diagnostics) {
        BitbucketSite bitbucket = requireBitbucket(atlassian);
        AtlassianSite site = bitbucket.site();
        if (site.tokenError() != null) {
            throw new ConfigException(site.tokenError());
        }
        HttpClient httpClient = HttpClients.build(atlassian.tls(), atlassian.proxy());
        RestClient restClient = new RestClient("Bitbucket", site.baseUrl(), site.token(), site.tokenVar(),
                site.timeout(), httpClient, diagnostics);
        return new BitbucketClient(restClient, bitbucket.project());
    }

    static BitbucketSite requireBitbucket(AtlassianConfig atlassian) {
        if (atlassian == null || atlassian.bitbucket() == null) {
            throw new ConfigException("atlassian.pull_requests is true but no atlassian.bitbucket is configured");
        }
        return atlassian.bitbucket();
    }

    /**
     * {@link RemoteGit#push}, plus B3's "Git push outcomes" diagnostic (remote host, ref, whether
     * force-with-lease held, the JGit failure message on failure) — shared by {@link
     * BitbucketReview} and {@link BitbucketDecisions} so the one logging shape covers both push
     * sites. Rethrows exactly what {@link RemoteGit#push} threw, unchanged, so the existing
     * best-effort catch at each call site behaves identically to before Task 8 — this method only
     * OBSERVES the outcome, it never changes it.
     */
    static void push(DiagnosticWriter diagnostics, Path repo, String branch, String cloneUrl, String username,
            String pat, AtlassianTls tls, AtlassianProxy proxy) {
        String host = hostOf(cloneUrl);
        String ref = "refs/heads/" + branch;
        try {
            RemoteGit.push(repo, branch, cloneUrl, username, pat, tls, proxy);
            if (diagnostics != null) {
                diagnostics.gitPush(host, ref, true, null);
            }
        } catch (RuntimeException e) {
            if (diagnostics != null) {
                // Unknown, not false: a rejected push can fail before the lease is even evaluated
                // (auth, network) as easily as because the lease itself did not hold — see
                // DiagnosticWriter#gitPush's javadoc.
                diagnostics.gitPush(host, ref, null, e.getMessage());
            }
            throw e;
        }
    }

    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
