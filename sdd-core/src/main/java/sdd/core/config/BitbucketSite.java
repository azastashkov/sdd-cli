package sdd.core.config;

import java.util.List;

/**
 * Bitbucket needs two things no Jira/Confluence site has: the project key its repos live under
 * (Task 4/5's PR machinery is scoped to one project, and {@code sdd doctor}'s second Bitbucket
 * probe — {@code GET /rest/api/1.0/projects/{project}} — needs it too) and a default reviewer
 * list for PRs {@code sdd} opens. Composes an {@link AtlassianSite} rather than duplicating
 * {@code baseUrl}/{@code token}/{@code tokenVar}/{@code timeout}/{@code tokenError} onto a fourth
 * near-identical record.
 *
 * <p>{@code gitUsername} is for the git push alone, never for REST. Bitbucket Data Center's HTTP
 * personal-access-token auth was believed to carry the identity in the token itself, so sdd sent
 * the placeholder {@code x-token-auth} — and a live instance answered {@code not authorized}
 * before the push, on 2026-08-23. The instance checks it. Null keeps the placeholder, which is what
 * every host that genuinely ignores the username still accepts.
 */
public record BitbucketSite(AtlassianSite site, String project, List<String> defaultReviewers,
                           String gitUsername) {
    public BitbucketSite {
        defaultReviewers = List.copyOf(defaultReviewers);
    }

    /** Pre-{@code gitUsername} shape, so every existing construction site compiles untouched. */
    public BitbucketSite(AtlassianSite site, String project, List<String> defaultReviewers) {
        this(site, project, defaultReviewers, null);
    }
}
