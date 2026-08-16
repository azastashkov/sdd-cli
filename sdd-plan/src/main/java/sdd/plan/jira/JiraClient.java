package sdd.plan.jira;

import com.fasterxml.jackson.databind.JsonNode;
import sdd.core.http.RestClient;
import sdd.plan.confluence.ConfluenceExtract;
import sdd.plan.source.SourceDoc;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Data Center Jira REST v2 client: one issue in, one {@link Issue} out — the extracted
 * description/comment text plus the raw key lists ({@code subtasks}, blocking links, remote
 * links) {@code JiraSpecSource} walks one level to decide what else to fetch. Deliberately does
 * NOT decide what to do with those keys (fetch them, cap them, dedupe them) — that policy is
 * {@code JiraSpecSource}'s job, since a single {@link #fetchIssue} call has no view of what has
 * already been fetched across a whole spec.
 *
 * <p>The "key insight" from the Task 3 brief: {@code expand=renderedFields} gives HTML for the
 * description and every comment body, which is exactly what {@code ConfluenceExtract.extract}
 * already parses — so this class needs no wiki-markup parser of its own, only an HTML feed into
 * that shared extractor.
 */
public final class JiraClient {
    private final RestClient restClient;
    private final String baseUrl;

    public JiraClient(RestClient restClient, String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    /**
     * One fetched issue: its own {@link SourceDoc} (kind {@code JIRA_ISSUE}), one
     * {@code JIRA_COMMENT} doc per rendered comment, and the raw material {@code JiraSpecSource}
     * needs to decide what to fetch next — subtask keys, blocking/depends-linked issue keys, and
     * every remote-link target URL (a Confluence link lands here far more often than inline in
     * the description on Data Center, per the brief).
     */
    public record Issue(SourceDoc issueDoc, List<SourceDoc> commentDocs, List<String> subtaskKeys,
                         List<String> linkedIssueKeys, List<String> remoteLinkUrls) {
    }

    private static final String ISSUE_FIELDS =
            "summary,description,issuelinks,subtasks,comment,status,updated";

    /**
     * Fetches one issue and its remote links. A 404 (or any other transport/auth failure)
     * propagates as-is — {@code JiraSpecSource} is the one place that knows whether this
     * particular key was the human-supplied root (404 there is a clean user error) or a
     * subtask/blocking link discovered one level down (404 there is merely a note), so the
     * translation happens there, not here.
     */
    public Issue fetchIssue(String key) {
        JsonNode root = restClient.get("/rest/api/2/issue/" + key + "?expand=renderedFields&fields=" + ISSUE_FIELDS);
        JsonNode fields = root.path("fields");
        JsonNode rendered = root.path("renderedFields");

        String summary = fields.path("summary").asText("");
        String updated = normalizeUpdated(fields.path("updated").asText(null));
        String issueUrl = baseUrl + "/browse/" + key;
        ConfluenceExtract.Extracted descExtract =
                ConfluenceExtract.extract(rendered.path("description").asText(""));
        SourceDoc issueDoc = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, key, issueUrl, summary, updated,
                descExtract.text(), descExtract.attachments());

        List<SourceDoc> commentDocs = new ArrayList<>();
        for (JsonNode comment : rendered.path("comment").path("comments")) {
            String commentId = comment.path("id").asText();
            ConfluenceExtract.Extracted body = ConfluenceExtract.extract(comment.path("body").asText(""));
            String commentUpdated = normalizeUpdated(comment.path("updated").asText(null));
            commentDocs.add(new SourceDoc(SourceDoc.Kind.JIRA_COMMENT, key + "-comment-" + commentId,
                    issueUrl + "#comment-" + commentId, null, commentUpdated, body.text(), body.attachments()));
        }

        List<String> subtaskKeys = new ArrayList<>();
        for (JsonNode subtask : fields.path("subtasks")) {
            subtaskKeys.add(subtask.path("key").asText());
        }

        List<String> linkedIssueKeys = new ArrayList<>();
        for (JsonNode link : fields.path("issuelinks")) {
            if (isBlockOrDependLink(link.path("type"))) {
                JsonNode target = link.has("inwardIssue") ? link.path("inwardIssue") : link.path("outwardIssue");
                if (!target.isMissingNode()) {
                    linkedIssueKeys.add(target.path("key").asText());
                }
            }
        }

        List<String> remoteLinkUrls = fetchRemoteLinks(key);

        return new Issue(issueDoc, commentDocs, subtaskKeys, linkedIssueKeys, remoteLinkUrls);
    }

    private List<String> fetchRemoteLinks(String key) {
        JsonNode arr = restClient.get("/rest/api/2/issue/" + key + "/remotelink");
        List<String> urls = new ArrayList<>();
        for (JsonNode entry : arr) {
            String url = entry.path("object").path("url").asText("");
            if (!url.isBlank()) {
                urls.add(url);
            }
        }
        return urls;
    }

    /** Matches "block"/"depend" case-insensitively against name/inward/outward, per the brief —
     *  a link type's outward phrasing ("blocks") and inward phrasing ("is blocked by") both
     *  contain the same substring, so checking all three catches either link direction without
     *  hand-listing every Data Center link-type name a project could configure. */
    private static boolean isBlockOrDependLink(JsonNode type) {
        return containsBlockOrDepend(type.path("name").asText(""))
                || containsBlockOrDepend(type.path("inward").asText(""))
                || containsBlockOrDepend(type.path("outward").asText(""));
    }

    private static boolean containsBlockOrDepend(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("block") || lower.contains("depend");
    }

    /** Data Center's default {@code fields.updated} shape is
     *  {@code yyyy-MM-dd'T'HH:mm:ss.SSSZ} (offset without a colon, e.g. {@code +0000}) — this is
     *  one of the least-certain details in the Task 3 report, since it depends on the instance's
     *  date-format configuration rather than being fixed by the API itself. Falls back to the ISO
     *  offset form, then to the raw value unmodified, rather than throwing: a Sources-bullet
     *  timestamp that is merely unnormalized is far better than a fetch that fails outright over
     *  a date format. */
    private static final DateTimeFormatter JIRA_UPDATED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

    static String normalizeUpdated(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw, JIRA_UPDATED_FORMAT).toInstant().toString();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(raw).toInstant().toString();
            } catch (DateTimeParseException e2) {
                return raw;
            }
        }
    }
}
