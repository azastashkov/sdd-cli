package sdd.plan.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import sdd.core.http.RestClient;
import sdd.plan.confluence.ConfluenceExtract;
import sdd.plan.source.SourceDoc;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.net.URI;
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
 * that shared extractor. It does run one extra, independent jsoup pass over that same HTML
 * ({@link #hrefsIn}) to recover {@code <a href>} targets that {@code ConfluenceExtract} discards —
 * see that method's javadoc.
 */
public final class JiraClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;

    public JiraClient(RestClient restClient, String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    /**
     * One fetched issue: its own {@link SourceDoc} (kind {@code JIRA_ISSUE}), one
     * {@code JIRA_COMMENT} doc per rendered comment, and the raw material {@code JiraSpecSource}
     * needs to decide what to fetch next — subtask keys, blocking/depends-linked issue keys,
     * every remote-link target URL, and every {@code <a href>} target harvested directly from the
     * rendered description/comment HTML (before it is handed to {@code ConfluenceExtract}, which
     * keeps an anchor's visible text but drops its {@code href} — see {@link #hrefsIn}'s javadoc).
     * The brief's headline case — a person linking a spec with a named hyperlink in the
     * description, e.g. {@code <a href="...">see the spec</a>} — depends on this list, not on
     * {@code remoteLinkUrls}: a remote link is a separate, deliberate UI action, not what most
     * people do when writing a description.
     */
    public record Issue(SourceDoc issueDoc, List<SourceDoc> commentDocs, List<String> subtaskKeys,
                         List<String> linkedIssueKeys, List<String> remoteLinkUrls, List<String> hrefUrls) {
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
        String descriptionHtml = rendered.path("description").asText("");
        ConfluenceExtract.Extracted descExtract = ConfluenceExtract.extract(descriptionHtml);
        SourceDoc issueDoc = new SourceDoc(SourceDoc.Kind.JIRA_ISSUE, key, issueUrl, summary, updated,
                descExtract.text(), descExtract.attachments());

        List<SourceDoc> commentDocs = new ArrayList<>();
        List<String> hrefUrls = new ArrayList<>(hrefsIn(descriptionHtml, baseUrl));
        for (JsonNode comment : rendered.path("comment").path("comments")) {
            String commentId = comment.path("id").asText();
            String commentHtml = comment.path("body").asText("");
            ConfluenceExtract.Extracted body = ConfluenceExtract.extract(commentHtml);
            String commentUpdated = normalizeUpdated(comment.path("updated").asText(null));
            commentDocs.add(new SourceDoc(SourceDoc.Kind.JIRA_COMMENT, key + "-comment-" + commentId,
                    issueUrl + "#comment-" + commentId, null, commentUpdated, body.text(), body.attachments()));
            hrefUrls.addAll(hrefsIn(commentHtml, baseUrl));
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

        return new Issue(issueDoc, commentDocs, subtaskKeys, linkedIssueKeys, remoteLinkUrls, hrefUrls);
    }

    /**
     * Task 4: {@code POST /rest/api/2/issue/{key}/comment} with {@code {"body": "<text>"}} — the
     * Data Center v2 add-comment endpoint. {@code body} is plain text handed straight through: DC
     * accepts wiki markup in this field, but the Task 4 brief is explicit that {@code sdd}'s own
     * write-back comments make no attempt at rich formatting, so there is nothing here to escape
     * or transform. Uses {@link RestClient#postExpectingNoContent} even though Jira actually
     * replies 201 with the created comment resource: no caller needs that body (the point of
     * commenting is the side effect on the issue, not anything in the response), and that method's
     * own javadoc already covers "a body the caller does not need", not just 204 endpoints.
     *
     * <p>Failures (network, 4xx/5xx, an expired PAT) propagate as {@link AtlassianException} —
     * this method does not decide what "best-effort" means; the Task 4 brief is explicit that a
     * failed comment must never affect {@code sdd plan approve}/{@code sdd review}'s exit code, so
     * that policy belongs to the caller (the one place that knows whether {@code plan.json}/
     * {@code report.md} already landed on disk), not here.
     */
    public void comment(String key, String body) {
        restClient.postExpectingNoContent("/rest/api/2/issue/" + key + "/comment",
                JSON.createObjectNode().put("body", body));
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

    /**
     * Task 3 review Fix 2 (Critical, ruled): a SECOND, independent jsoup pass over the same raw
     * rendered HTML {@code ConfluenceExtract.extract} also receives — {@code href}s, not visible
     * text. {@code ConfluenceExtract} keeps an {@code <a>} element's visible text but drops its
     * {@code href} (it was built for Confluence storage-format content, not for preserving inbound
     * hyperlinks), so a named link — {@code <a href="https://confluence...">see the spec</a>}, the
     * ordinary way a person links a spec from a description — would otherwise vanish before
     * {@code LinkHarvester} ever sees any text to scan, with no note at all. This method adds no
     * {@code ConfluenceExtract.extract} call site and does not touch {@code ConfluenceExtract.java}:
     * it uses jsoup directly, the same library {@code ConfluenceExtract} itself is built on.
     *
     * <p>Only absolute {@code http(s)://} hrefs are kept — a relative href has no host, so
     * {@code LinkHarvester}'s same-host filter could never confirm it points at Confluence anyway;
     * collecting it would just be an unresolvable-shaped note for a link that was never a
     * candidate in the first place. Callers combine this with {@link #remoteLinkUrls} before
     * handing everything to {@code LinkHarvester}; {@code LinkHarvester}'s own exact-URL dedup
     * means one link mentioned in both places (or twice in one place) still yields one fetch.
     */
    static List<String> hrefsIn(String html, String baseUrl) {
        List<String> hrefs = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return hrefs;
        }
        URI base = baseUrl == null ? null : safeUri(baseUrl);
        for (Element a : Jsoup.parse(html).select("a[href]")) {
            String href = a.attr("href").strip();
            if (href.startsWith("http://") || href.startsWith("https://")) {
                hrefs.add(href);
                continue;
            }
            // A relative href used to be dropped here, on the reasoning that it has no host. True,
            // but it has an implied one, and Data Center commonly serves Jira and Confluence on a
            // SINGLE host under two context paths and renders same-origin links relatively. So the
            // spec everybody links from the ticket arrived as "/confluence/pages/..." and vanished
            // -- with no note, because LinkHarvester never received a candidate to decline.
            //
            // Resolving against Jira's own base URL is the correct reading of what a relative href
            // in Jira-rendered HTML means, and it cannot invent a cross-host link: URI.resolve
            // keeps the base's scheme, host and port, so whether the result is a Confluence page at
            // all is still decided downstream by LinkHarvester's host check.
            //
            // Fragments, mailto:, javascript: and the like carry no page to fetch and are skipped
            // rather than resolved.
            if (base == null || href.isEmpty() || href.startsWith("#") || href.contains(":")) {
                continue;
            }
            URI resolved = safeUri(href);
            if (resolved == null) {
                continue;
            }
            hrefs.add(base.resolve(resolved).toString());
        }
        return hrefs;
    }

    private static URI safeUri(String value) {
        try {
            return new URI(value);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
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
