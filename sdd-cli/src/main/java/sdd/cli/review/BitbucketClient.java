package sdd.cli.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import sdd.core.http.AtlassianException;
import sdd.core.http.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Bitbucket Data Center REST 1.0 client for the pull request Task 5's Gate 2 drives, built on
 * {@link RestClient} exactly like {@code sdd.plan.jira.JiraClient} and
 * {@code sdd.plan.confluence.ConfluenceClient} are — this class knows Bitbucket's resource shapes,
 * {@code RestClient} knows nothing about any of the three products.
 *
 * <p><b>{@code project} is used AS CONFIGURED, never lowercased.</b> Unlike {@link RemoteGit
 * #cloneUrl}'s SCM path (which the brief is explicit lowercases both segments — "the Data Center
 * convention"), the REST API's {@code {projectKey}} path parameter is the project's actual
 * configured key, which is conventionally uppercase (e.g. {@code TRADING}) and is NOT the same
 * string as the lowercase SCM path segment on a real Bitbucket Server instance. This split
 * treatment across the two classes is deliberate, not an inconsistency — see the Task 5 report's
 * "invented / least certain" section, since neither half has been verified against a live
 * instance yet.
 *
 * <p>{@code repo}, by contrast, IS always lowercased ({@link RemoteGit#repoSlug}) for both the SCM
 * clone URL and every REST path here — a Bitbucket repository slug is generated from the repo's
 * display name and is always lowercase, unlike a project key which the admin sets directly.
 */
public final class BitbucketClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final String project;

    public BitbucketClient(RestClient restClient, String project) {
        this.restClient = restClient;
        this.project = project;
    }

    /** One pull request, as much of it as this class's callers need. {@code version} is Bitbucket's
     *  optimistic-locking field — required on {@link #merge}/{@link #decline}/
     *  {@link #updateDescription}, and (least-certain detail — see the Task 5 report) assumed to be
     *  present on every PR-shaped response this class reads, create included. */
    public record PullRequest(int id, int version, String title, String description, String link) {
    }

    /**
     * {@code GET .../branches/default} (Task 5 brief §2 — Ruling R4: this lookup lives here, in
     * the REST client, not on {@link RemoteGit}). Reads {@code displayId} ({@code "main"}), not
     * {@code id} ({@code "refs/heads/main"}) — the unprefixed form is what {@link #create}'s
     * {@code toRef} and {@link RemoteGit} both expect a bare branch name to look like.
     *
     * <p>Task 8 correction (documentation review against Bitbucket Server REST 5.16.0): the
     * originally-implemented path was {@code .../default-branch}, a plausible-looking guess that
     * does not exist on a real Data Center instance and 404s every call — see
     * {@code api-verification-report.md} item 14. {@code branches/default} is the documented
     * path; {@code displayId} was already correct.
     */
    public String defaultBranch(String repo) {
        JsonNode node = restClient.get(path(repo) + "/branches/default");
        String displayId = node.path("displayId").asText(null);
        if (displayId == null || displayId.isBlank()) {
            throw new AtlassianException("Bitbucket default-branch response for " + repo
                    + " has no displayId: " + node);
        }
        return displayId;
    }

    /** Finds an OPEN pull request whose source is {@code branch}, so a {@code redo}'s next push
     *  updates the existing PR instead of {@link #create} opening a duplicate (brief §3.3). The
     *  {@code at=refs/heads/<branch>&direction=OUTGOING} filter is the brief's exact wording; the
     *  first result is returned (Bitbucket permits at most one open PR per source/target pair with
     *  the same repo, so filtering by source branch alone is not expected to return more than one
     *  — least-certain assumption, see the Task 5 report). */
    public Optional<PullRequest> findOpenBySourceBranch(String repo, String branch) {
        String ref = encode("refs/heads/" + branch);
        JsonNode node = restClient.get(path(repo) + "/pull-requests?at=" + ref
                + "&direction=OUTGOING&state=OPEN");
        for (JsonNode pr : node.path("values")) {
            return Optional.of(parsePr(pr));
        }
        return Optional.empty();
    }

    /** {@code POST .../pull-requests} — create. {@code fromRef}/{@code toRef} each carry the
     *  branch's fully-qualified ref id plus the repository they belong to (Bitbucket allows a
     *  cross-repo PR; {@code sdd} never opens one, but the field is required regardless) — this
     *  exact shape is one of the least-certain details in this task, see the report. */
    public PullRequest create(String repo, String title, String description, String sourceBranch,
            String targetBranch, List<String> reviewers) {
        ObjectNode body = JSON.createObjectNode();
        body.put("title", title);
        body.put("description", description);
        body.set("fromRef", ref(repo, sourceBranch));
        body.set("toRef", ref(repo, targetBranch));
        if (!reviewers.isEmpty()) {
            ArrayNode array = body.putArray("reviewers");
            for (String reviewer : reviewers) {
                array.addObject().putObject("user").put("name", reviewer);
            }
        }
        return parsePr(restClient.post(path(repo) + "/pull-requests", body));
    }

    /** {@code GET .../pull-requests/{id}} — re-reads one PR, chiefly for its CURRENT
     *  {@code version} immediately before {@link #merge}/{@link #decline} (Task 5 brief §4: the
     *  decision commands re-fetch rather than trust a possibly-stale recorded version). */
    public PullRequest get(String repo, int id) {
        return parsePr(restClient.get(path(repo) + "/pull-requests/" + id));
    }

    /** {@code PUT .../pull-requests/{id}} — update title/description. {@code existing.version()}
     *  must be the CURRENT server-side version (brief §2: Bitbucket's optimistic locking applies
     *  here too, not just to merge/decline) — callers that hold a possibly-stale
     *  {@link PullRequest} should {@link #get} first. */
    public PullRequest updateDescription(String repo, PullRequest existing, String title, String description) {
        ObjectNode body = JSON.createObjectNode();
        body.put("version", existing.version());
        body.put("title", title);
        body.put("description", description);
        return parsePr(restClient.put(path(repo) + "/pull-requests/" + existing.id(), body));
    }

    /** {@code POST .../pull-requests/{id}/merge?version=<v>} — squash-merge. {@code version} as a
     *  QUERY parameter, not a body field — the brief's exact shape; no body is sent. */
    public void merge(String repo, int id, int version) {
        try {
            restClient.postExpectingNoContent(path(repo) + "/pull-requests/" + id + "/merge?version=" + version, null);
        } catch (AtlassianException e) {
            throw conflictAware(e, "merge");
        }
    }

    /** {@code POST .../pull-requests/{id}/decline?version=<v>} — decline. Same query-parameter
     *  shape as {@link #merge}. */
    public void decline(String repo, int id, int version) {
        try {
            restClient.postExpectingNoContent(path(repo) + "/pull-requests/" + id + "/decline?version=" + version, null);
        } catch (AtlassianException e) {
            throw conflictAware(e, "decline");
        }
    }

    /** Turns a 409 (Bitbucket's optimistic-locking conflict — the {@code version} we sent no
     *  longer matches) into a message that says so in plain words, per the brief: "surface a
     *  conflict clearly rather than retrying blindly". Detected by matching {@code "HTTP 409"} in
     *  {@link RestClient}'s own fixed error-message format — {@link AtlassianException} carries no
     *  structured status code (see its javadoc: one exception per bounded context, no parsed detail
     *  beyond the message), so this is the only signal available without changing that class for
     *  Bitbucket alone. Any other failure (network, 5xx exhausted, auth) passes through unchanged. */
    private static AtlassianException conflictAware(AtlassianException e, String action) {
        String message = e.getMessage();
        if (message != null && message.contains("HTTP 409")) {
            return new AtlassianException("Bitbucket pull request has changed since it was last "
                    + "read (version conflict) — cannot " + action + ": " + message, e);
        }
        return e;
    }

    private String path(String repo) {
        return "/rest/api/1.0/projects/" + project + "/repos/" + RemoteGit.repoSlug(repo);
    }

    private ObjectNode ref(String repo, String branch) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", "refs/heads/" + branch);
        ObjectNode repository = node.putObject("repository");
        repository.put("slug", RemoteGit.repoSlug(repo));
        repository.putObject("project").put("key", project);
        return node;
    }

    private static PullRequest parsePr(JsonNode node) {
        int id = node.path("id").asInt();
        int version = node.path("version").asInt();
        String title = node.path("title").asText("");
        String description = node.path("description").asText("");
        JsonNode selfLinks = node.path("links").path("self");
        String link = selfLinks.isArray() && selfLinks.size() > 0
                ? selfLinks.get(0).path("href").asText(null) : null;
        return new PullRequest(id, version, title, description, link);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
