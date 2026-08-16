package sdd.core.config;

/**
 * The optional {@code atlassian:} block in {@code sdd.yml} — requirement ingestion (Jira issues,
 * Confluence pages) and Bitbucket source control for a closed corporate network. Absent entirely
 * (null on {@link SddConfig#atlassian()}) when {@code sdd.yml} has no {@code atlassian:} key, and
 * {@code sdd doctor}'s output must not change at all in that case — this is opt-in plumbing, not a
 * new requirement on every estate.
 *
 * <p>{@code tls} and {@code proxy} are shared by every configured site (one corporate CA, one
 * forward proxy), so they sit at this level rather than being repeated under {@code jira}/
 * {@code confluence}/{@code bitbucket}. Each site itself is independently optional — see
 * {@link AtlassianSite}'s javadoc.
 *
 * <p>{@code followDepth}/{@code maxPages}/{@code maxLinkedIssues} bound how far Task 3's ingestion
 * walks (linked issues, paginated collections) so a misconfigured Jira project with thousands of
 * linked issues cannot make {@code sdd plan} run away. {@code writeBack} and {@code pullRequests}
 * gate write access — off by default, since a tool that can silently start commenting on tickets
 * or opening PRs needs an explicit opt-in.
 */
public record AtlassianConfig(
        AtlassianTls tls,
        AtlassianProxy proxy,
        AtlassianSite jira,
        AtlassianSite confluence,
        BitbucketSite bitbucket,
        int followDepth,
        int maxPages,
        int maxLinkedIssues,
        WriteBack writeBack,
        boolean pullRequests) {}
