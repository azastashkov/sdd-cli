package sdd.cli.review;

import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.BitbucketSite;
import sdd.core.config.ConfigException;
import sdd.core.http.HttpClients;
import sdd.core.http.RestClient;

import java.net.http.HttpClient;

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
        BitbucketSite bitbucket = requireBitbucket(atlassian);
        AtlassianSite site = bitbucket.site();
        if (site.tokenError() != null) {
            throw new ConfigException(site.tokenError());
        }
        HttpClient httpClient = HttpClients.build(atlassian.tls(), atlassian.proxy());
        RestClient restClient = new RestClient("Bitbucket", site.baseUrl(), site.token(), site.tokenVar(),
                site.timeout(), httpClient);
        return new BitbucketClient(restClient, bitbucket.project());
    }

    static BitbucketSite requireBitbucket(AtlassianConfig atlassian) {
        if (atlassian == null || atlassian.bitbucket() == null) {
            throw new ConfigException("atlassian.pull_requests is true but no atlassian.bitbucket is configured");
        }
        return atlassian.bitbucket();
    }
}
