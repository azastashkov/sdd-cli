package sdd.plan.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import sdd.core.http.AtlassianException;
import sdd.core.http.RestClient;
import sdd.plan.source.ConfluencePages;
import sdd.plan.source.SourceDoc;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data Center Confluence REST client: fetching one page by id, and resolving the several URL
 * shapes Data Center actually produces down to that id. {@code body.storage.value} is
 * storage-format XHTML, which is exactly what {@code ConfluenceExtract.extract} already parses
 * (the same reason {@link sdd.plan.jira.JiraClient} needs no separate parser for rendered Jira
 * HTML) — this class passes it straight in, unchanged.
 */
public final class ConfluenceClient implements ConfluencePages {
    private static final Pattern PAGES_ID = Pattern.compile("/pages/(\\d+)(?:/|$)");
    private static final Pattern TINY_LINK = Pattern.compile("^/x/[A-Za-z0-9_-]+$");
    private static final Pattern DISPLAY = Pattern.compile("^/display/([^/]+)/(.+)$");
    /** Defensive only — not specified by the brief. A tiny link is documented to redirect once
     *  to a canonical URL; this bounds the re-resolve recursion so a misbehaving/looping server
     *  cannot turn one resolution into an unbounded chain of requests. */
    private static final int MAX_REDIRECT_HOPS = 5;

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final String token;
    private final String baseUrl;

    /**
     * @param httpClient the same {@code HttpClients.build(...)}-constructed client the caller's
     *                    {@code RestClient} was built with — needed only for tiny-link resolution,
     *                    the one case where a 3xx response's {@code Location} header must be read
     *                    directly. {@link RestClient} cannot expose that: it treats every non-2xx,
     *                    non-401/403/429/5xx status (a redirect included) as a hard failure, by
     *                    design, so it has no way to hand back a header from a 3xx response.
     * @param token       the same bearer token {@code RestClient} carries privately — duplicated
     *                    here (rather than adding a getter to {@code RestClient} for one caller)
     *                    because a tiny link's own auth requirement is a Confluence-specific
     *                    detail, not something the generic HTTP layer should know about.
     * @param baseUrl     used only to render {@link #fetchPage}'s absolute, canonical
     *                    {@code viewpage.action} URL for provenance (the Sources bullet, the
     *                    doc's {@code label()}) — resolution itself needs no base URL since every
     *                    URL {@link #resolvePageId} is handed is already absolute.
     */
    public ConfluenceClient(RestClient restClient, HttpClient httpClient, String token, String baseUrl) {
        this.restClient = restClient;
        this.httpClient = httpClient;
        this.token = token;
        this.baseUrl = baseUrl;
    }

    public SourceDoc fetchPage(String pageId) {
        JsonNode root = restClient.get("/rest/api/content/" + pageId + "?expand=body.storage,version,space");
        String html = root.path("body").path("storage").path("value").asText("");
        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(html);
        String title = root.path("title").asText(null);
        String version = root.path("version").path("number").asText(null);
        return new SourceDoc(SourceDoc.Kind.CONFLUENCE_PAGE, pageId, canonicalUrl(pageId), title, version,
                extracted.text(), extracted.attachments());
    }

    /** The URL form every fetched page's Sources bullet and {@code label()} use — the one shape
     *  the Task 3 brief's own example bullet shows, and the one every Data Center install
     *  understands regardless of which of the several equivalent URL shapes a human originally
     *  pasted. */
    private String canonicalUrl(String pageId) {
        return baseUrl + "/pages/viewpage.action?pageId=" + pageId;
    }

    /**
     * Resolves a Confluence URL (any of the Data Center shapes named in the Task 3 brief) to a
     * page id, or null when the URL is not a recognisable Confluence page reference at all — the
     * caller ({@code LinkHarvester}) turns that into an "unresolvable" note rather than a failure,
     * since one bad link must not abort the whole ingestion. A network failure during resolution
     * (title search, tiny-link redirect) surfaces as {@link AtlassianException} for the same
     * caller to catch and note.
     */
    public String resolvePageId(String url) {
        URI uri = parse(url);
        if (uri == null) {
            return null;
        }
        String queryPageId = queryParam(uri, "pageId");
        if (queryPageId != null) {
            return queryPageId;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        Matcher pages = PAGES_ID.matcher(path);
        if (pages.find()) {
            return pages.group(1);
        }
        if (TINY_LINK.matcher(path).matches()) {
            return resolveTinyLink(uri, MAX_REDIRECT_HOPS);
        }
        Matcher display = DISPLAY.matcher(path);
        if (display.matches()) {
            return resolveByTitleSearch(display.group(1), decode(display.group(2)));
        }
        return null;
    }

    private String resolveTinyLink(URI uri, int hopsLeft) {
        if (hopsLeft <= 0) {
            return null;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<Void> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new AtlassianException("transport error resolving tiny link " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlassianException("interrupted resolving tiny link " + uri, e);
        }
        int status = response.statusCode();
        if (status < 300 || status >= 400) {
            return null;   // not a redirect: this "tiny link" shape did not behave like one
        }
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null) {
            return null;
        }
        URI resolved = uri.resolve(location);
        String queryPageId = queryParam(resolved, "pageId");
        if (queryPageId != null) {
            return queryPageId;
        }
        Matcher pages = PAGES_ID.matcher(resolved.getPath() == null ? "" : resolved.getPath());
        if (pages.find()) {
            return pages.group(1);
        }
        if (TINY_LINK.matcher(resolved.getPath() == null ? "" : resolved.getPath()).matches()) {
            return resolveTinyLink(resolved, hopsLeft - 1);
        }
        return resolvePageId(resolved.toString());
    }

    private String resolveByTitleSearch(String spaceKey, String title) {
        String path = "/rest/api/content?spaceKey=" + encode(spaceKey) + "&title=" + encode(title) + "&expand=version";
        JsonNode results = restClient.get(path).path("results");
        return results.isArray() && results.size() > 0 ? results.get(0).path("id").asText(null) : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static URI parse(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String queryParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(name)) {
                return eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return null;
    }
}
