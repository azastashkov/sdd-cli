package sdd.core.config;

import java.util.List;

/**
 * Bitbucket needs two things no Jira/Confluence site has: the project key its repos live under
 * (Task 4/5's PR machinery is scoped to one project, and {@code sdd doctor}'s second Bitbucket
 * probe — {@code GET /rest/api/1.0/projects/{project}} — needs it too) and a default reviewer
 * list for PRs {@code sdd} opens. Composes an {@link AtlassianSite} rather than duplicating
 * {@code baseUrl}/{@code token}/{@code tokenVar}/{@code timeout}/{@code tokenError} onto a fourth
 * near-identical record.
 */
public record BitbucketSite(AtlassianSite site, String project, List<String> defaultReviewers) {
    public BitbucketSite {
        defaultReviewers = List.copyOf(defaultReviewers);
    }
}
