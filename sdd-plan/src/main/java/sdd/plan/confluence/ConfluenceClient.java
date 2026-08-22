package sdd.plan.confluence;

import com.fasterxml.jackson.databind.JsonNode;
import sdd.core.diagnostics.AtlassianWireDump;
import sdd.core.diagnostics.DiagnosticWriter;
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
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private final Duration timeout;
    /** The host every {@link #resolvePageId}/{@link #resolveTinyLink} request is checked against
     *  before it is ever sent — see those methods' javadoc. Derived from {@code baseUrl} rather
     *  than accepted as a separate constructor parameter: the two can never disagree this way. */
    private final String confluenceHost;
    /** Gate re-review Fix 1: {@code hostMatches} originally compared HOST only. {@code
     *  LinkHarvester}'s URL regex is {@code https?://\S+}, so any Jira commenter could plant a
     *  plain {@code http://} link to the configured host in an issue or comment — same host,
     *  matches every existing check, but downgrades the bearer token to cleartext (and, with
     *  {@code atlassian.proxy} configured, straight through that proxy's access logs). Checked
     *  alongside {@link #confluenceHost} in {@link #hostMatches} so a scheme downgrade fails
     *  closed exactly like a host mismatch does. */
    private final String confluenceScheme;
    /** Gate re-review Fix 1's other half: an explicit or scheme-default port mismatch (e.g. a
     *  candidate URL naming a non-standard port on the right host and scheme) is rejected the same
     *  way — see {@link #effectivePort}. */
    private final int confluencePort;
    /**
     * The context path {@code baseUrl} is served under — {@code "/confluence"} for an install at
     * {@code https://wiki.corp.local/confluence}, {@code ""} at a host root. Derived from
     * {@code baseUrl} rather than configured separately, for the same reason
     * {@link #confluenceHost} is: the two can never disagree this way.
     *
     * <p>Every shape-matching pattern below anchors at the start of the path, so without this the
     * whole class silently assumed a root install. {@code /pages/} happened to survive on
     * {@code find()}, which made the failure worse rather than better: page-id URLs kept working
     * while every tiny link and every {@code /display/} URL came back "unresolvable", which reads
     * like a broken link rather than a misread base URL.
     */
    private final String basePath;
    private final DiagnosticWriter diagnostics;
    /** Set only when {@code SDD_ATLASSIAN_DUMP} is configured — see {@link #wireDump}. */
    private AtlassianWireDump wireDump;

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
     * @param baseUrl     used to render {@link #fetchPage}'s absolute, canonical
     *                    {@code viewpage.action} URL for provenance (the Sources bullet, the
     *                    doc's {@code label()}) — resolution itself needs no base URL since every
     *                    URL {@link #resolvePageId} is handed is already absolute — AND (Gate
     *                    review C1) as the source of {@link #confluenceHost}, the one host every
     *                    request this class ever sends over {@code httpClient} directly (bypassing
     *                    {@code restClient}'s own base-URL pinning) is checked against.
     * @param timeout     the same per-request timeout the caller's {@code RestClient} was built
     *                    with (the configured site's {@code atlassian.confluence.timeout_seconds}).
     *                    Task 3 review Fix 3: the tiny-link redirect request bypasses
     *                    {@code RestClient} (see {@code httpClient}'s own doc above) and so does
     *                    not inherit {@code RestClient.execute}'s {@code .timeout(timeout)} call
     *                    for free — without this, a hung {@code /x/AbCd} or a proxy that silently
     *                    drops the connection would block {@code sdd plan} indefinitely instead of
     *                    degrading to an "unresolvable" note.
     */
    public ConfluenceClient(RestClient restClient, HttpClient httpClient, String token, String baseUrl,
            Duration timeout) {
        this(restClient, httpClient, token, baseUrl, timeout, null);
    }

    /** Same as the five-argument constructor, plus an optional {@link DiagnosticWriter} (nullable
     *  — every diagnostics call below is then a no-op, matching {@code RestClient}'s own contract)
     *  that the raw tiny-link redirect request reports to (Gate review I7). That request bypasses
     *  {@code restClient} entirely (see {@code httpClient}'s doc above), which is exactly why it was
     *  invisible to diagnostics before this: {@code restClient}'s own requests were already logged,
     *  this one specifically was not. A separate overload, not a nullable parameter added to the
     *  five-argument one, so every pre-existing call site (every {@link ConfluenceClientTest} case)
     *  keeps compiling unchanged. */
    public ConfluenceClient(RestClient restClient, HttpClient httpClient, String token, String baseUrl,
            Duration timeout, DiagnosticWriter diagnostics) {
        this.restClient = restClient;
        this.httpClient = httpClient;
        this.token = token;
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        URI baseUri = safeUri(baseUrl);
        this.confluenceHost = baseUri == null || baseUri.getHost() == null
                ? null : baseUri.getHost().toLowerCase(Locale.ROOT);
        this.confluenceScheme = baseUri == null || baseUri.getScheme() == null
                ? null : baseUri.getScheme().toLowerCase(Locale.ROOT);
        this.confluencePort = effectivePort(baseUri);
        this.basePath = normalizeBasePath(baseUri);
        this.diagnostics = diagnostics;
    }

    /**
     * Records the tiny-link probe to {@code dump}.
     *
     * <p>This class makes exactly one request that bypasses {@code restClient}, for the one reason
     * {@code RestClient} cannot serve it: reading a {@code Location} header off a 3xx. That is also
     * the single most likely thing a corporate proxy interferes with, so a dump wired only into
     * {@code RestClient} would be blind to precisely the exchange it is most needed for. The probe
     * uses {@code BodyHandlers.discarding()}, so headers are the entire record — which is why this
     * dump captures response headers at all.
     */
    public ConfluenceClient wireDump(AtlassianWireDump dump) {
        this.wireDump = dump;
        return this;
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

    /**
     * One page's attachments: what is there, what type it is, how big, and where to fetch it.
     *
     * <p>{@code version} is carried because it is what makes a description cache correct. A diagram
     * re-uploaded under the same filename is a different image, and a cache keyed on the name alone
     * would serve a description of the previous one indefinitely — silently, and in a document
     * somebody is about to treat as a requirement.
     *
     * <p>{@code downloadPath} comes from the listing's own {@code _links.download} rather than being
     * constructed. Data Center's download URLs carry query parameters (a version stamp, a
     * modification date) that a hand-built path would omit.
     */
    public record Attachment(String id, String filename, String mediaType, long bytes,
                             String version, String downloadPath) {

        private static final Set<String> SENDABLE =
                Set.of("image/jpeg", "image/png", "image/tiff", "image/bmp");

        /** 15 Mb per image, and these four types — both documented limits of the model file API. */
        public boolean isSendableImage() {
            return SENDABLE.contains(mediaType) && bytes <= 15L * 1024 * 1024;
        }
    }

    /**
     * Every attachment on a page, in the order Confluence lists them.
     *
     * <p>Bounded by {@code limit}: a real page in this estate carried 26, and the endpoint pages its
     * results, so an unbounded walk is an unbounded number of requests against a corporate instance.
     * Truncation is silent here by design — the caller knows how many it asked for and decides what
     * to say about it, and this class has no writer to say it to.
     */
    public List<Attachment> listAttachments(String pageId, int limit) {
        JsonNode root = restClient.get(
                "/rest/api/content/" + pageId + "/child/attachment?limit=" + limit);
        List<Attachment> attachments = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String download = item.path("_links").path("download").asText("");
            attachments.add(new Attachment(
                    item.path("id").asText(""),
                    item.path("title").asText(""),
                    item.path("metadata").path("mediaType").asText(""),
                    item.path("extensions").path("fileSize").asLong(0),
                    item.path("version").path("number").asText(""),
                    download));
        }
        return List.copyOf(attachments);
    }

    /** The bytes of one attachment, straight from the listing's own download link. */
    public byte[] download(Attachment attachment) {
        return restClient.getBytes(attachment.downloadPath());
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
     *
     * <p><b>Gate review C1: rejects a wrong-host URL itself, before any request.</b> {@code
     * LinkHarvester} already checks a candidate's host before ever calling this method — but that
     * was defence in depth, not the guarantee: a second caller ({@code PlanCommand}, resolving a
     * direct {@code CONFLUENCE_PAGE} ref, e.g. {@code sdd plan https://evil.example/x/AbCd}) had no
     * such check, and without one HERE the Confluence PAT this instance carries would be sent
     * straight to whatever host a human (or a malicious ref) named on the command line. Checked
     * first, before {@code queryPageId}/{@code PAGES_ID}/etc even look at the URL, so a wrong host
     * is indistinguishable from "not a recognisable Confluence page reference at all" — the same
     * null this method already returns for that case — and never reaches a network call.
     */
    public String resolvePageId(String url) {
        URI uri = parse(url);
        if (uri == null || !hostMatches(uri)) {
            return null;
        }
        String queryPageId = queryParam(uri, "pageId");
        if (queryPageId != null) {
            return queryPageId;
        }
        // RAW path, not getPath(): URI.getPath() has already percent-decoded, and the second
        // decode below would then read a decoded "+" (from %2B) as a space and reject a decoded
        // "%" (from %25) outright — the first losing the page silently, the second throwing
        // IllegalArgumentException, which LinkHarvester does not catch and which therefore ended
        // the whole run. Decoding exactly once, at the one place a title is needed, fixes both.
        String path = relativePath(uri.getRawPath());
        if (path == null) {
            return null;
        }
        Matcher pages = PAGES_ID.matcher(path);
        if (pages.find()) {
            return pages.group(1);
        }
        if (TINY_LINK.matcher(path).matches()) {
            return resolveTinyLink(uri, MAX_REDIRECT_HOPS);
        }
        Matcher display = DISPLAY.matcher(path);
        if (display.matches()) {
            String space = decode(display.group(1));
            String title = decode(display.group(2));
            return space == null || title == null
                    ? null
                    : resolveByTitleSearch(space, title);
        }
        return null;
    }

    /**
     * The given raw path with the install's context path removed, or null when it does not sit
     * under that context path at all.
     *
     * <p>Rejecting rather than tolerating is deliberate and matches {@link #hostMatches}: a URL on
     * the configured host but outside the configured install is some other application, and
     * resolving it would send this instance's bearer token there.
     */
    private String relativePath(String rawPath) {
        String p = rawPath == null ? "" : rawPath;
        if (basePath.isEmpty()) {
            return p;
        }
        if (p.equals(basePath)) {
            return "";
        }
        return p.startsWith(basePath + "/") ? p.substring(basePath.length()) : null;
    }

    /** A base URL's path, trailing slash removed; "" at a host root. */
    private static String normalizeBasePath(URI baseUri) {
        String p = baseUri == null || baseUri.getRawPath() == null ? "" : baseUri.getRawPath();
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Gate review C1's second vector: the redirect chain re-sends the bearer header to {@code
     * uri.resolve(location)} on every hop — {@code hopsLeft - 1} recursion below — so without a
     * host check ON EVERY HOP (not just the first, which {@link #resolvePageId} already covers) a
     * single off-host {@code Location} anywhere in the chain would hand the token to that host. The
     * hop is refused outright (a {@code null}, exactly like any other "not a recognisable shape"
     * outcome) rather than sent unauthenticated: an unauthenticated request still leaks that a tiny
     * link exists and where it currently points, which is not this class's call to make once the
     * host is no longer the one it was configured for.
     */
    private String resolveTinyLink(URI uri, int hopsLeft) {
        if (hopsLeft <= 0 || !hostMatches(uri)) {
            return null;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(timeout);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        long startNanos = System.nanoTime();
        HttpResponse<Void> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw logTinyLinkFailure(
                    new AtlassianException("transport error resolving tiny link " + uri + ": " + e.getMessage(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw logTinyLinkFailure(new AtlassianException("interrupted resolving tiny link " + uri, e));
        }
        int status = response.statusCode();
        logTinyLinkRequest(uri, status, startNanos, response);
        if (wireDump != null) {
            wireDump.record("GET", uri.toString(), null, status, response.headers().map(), null);
        }
        if (status < 300 || status >= 400) {
            return null;   // not a redirect: this "tiny link" shape did not behave like one
        }
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null) {
            return null;
        }
        URI resolved = uri.resolve(location);
        if (!hostMatches(resolved)) {
            return null;
        }
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

    /** Gate review I7: the one request in this class that bypasses {@code restClient} (see the
     *  constructor's {@code httpClient} doc) was, for that exact reason, invisible to diagnostics —
     *  a hung or response-rewriting corporate proxy on a {@code /x/} link produced only a buried
     *  "unresolvable" Open Questions note and zero lines in the file built to debug exactly that.
     *  A no-op when this instance has no {@link DiagnosticWriter} (every pre-Task-8 caller, every
     *  {@link ConfluenceClientTest} case), matching {@code RestClient}'s own contract. */
    private void logTinyLinkRequest(URI uri, int status, long startNanos, HttpResponse<Void> response) {
        if (diagnostics == null) {
            return;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        String contentType = response.headers().firstValue("Content-Type").orElse(null);
        diagnostics.httpRequest("Confluence", "GET", uri.getPath(), status, durationMs, 1, false, contentType, null);
    }

    /** Same rationale as {@link #logTinyLinkRequest}, for the case where no response ever arrives
     *  at all (the hang this whole method exists to bound) — the one shape a per-request line
     *  cannot represent (there is no status), so the full failure goes to the file instead, exactly
     *  like {@code RestClient.logFailure} does for its own transport errors. Returns {@code ex}
     *  unchanged so the throw site stays a single expression. */
    private AtlassianException logTinyLinkFailure(AtlassianException ex) {
        if (diagnostics != null) {
            diagnostics.failure("Confluence tiny-link GET", ex);
        }
        return ex;
    }

    /** Gate review C1 / Gate re-review Fix 1: {@code true} only when {@code uri}'s host, scheme,
     *  AND (explicit-or-scheme-default) port all match the configured Confluence site — the one
     *  check every request this class sends directly over {@code httpClient} (as opposed to
     *  through {@code restClient}, which is base-URL-pinned and cannot be redirected off it) must
     *  pass before the bearer token is attached. Host-only used to be enough to pass this check
     *  while still downgrading the token to a plaintext {@code http://} request to the SAME host —
     *  not a pin bypass (the token never reached another host), but squarely the kind of leak C1
     *  was about. A {@code null}/unparseable field on either side never matches (fails closed, not
     *  open, in the "base URL had no host/scheme" case, which cannot legitimately occur). */
    private boolean hostMatches(URI uri) {
        String host = uri.getHost();
        String scheme = uri.getScheme();
        return host != null && host.equalsIgnoreCase(confluenceHost)
                && scheme != null && scheme.equalsIgnoreCase(confluenceScheme)
                && effectivePort(uri) == confluencePort;
    }

    private static URI safeUri(String url) {
        try {
            return URI.create(url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code uri}'s explicit port, or the scheme's well-known default (443 for https, 80 for
     *  http) when none was written — so {@code https://host/x} and {@code https://host:443/x} are
     *  correctly treated as the same port, and a bare {@code http://host/x} (implicit port 80)
     *  still fails {@link #hostMatches} against an {@code https} (implicit port 443) configured
     *  site. {@code -1} (never a real port) for a null URI or an unrecognised scheme, so a
     *  malformed/unparseable base URL fails closed rather than accidentally matching another
     *  malformed candidate. */
    private static int effectivePort(URI uri) {
        if (uri == null) {
            return -1;
        }
        int port = uri.getPort();
        if (port != -1) {
            return port;
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }

    private String resolveByTitleSearch(String spaceKey, String title) {
        // type=page&status=current narrow the search to what a /display/ URL can actually name.
        // Without them a blogpost, or a trashed page still carrying the title, can be returned --
        // and the old code took results[0] with no tie-break at all, so which one won was whatever
        // the server happened to list first.
        String path = "/rest/api/content?spaceKey=" + encode(spaceKey) + "&title=" + encode(title)
                + "&type=page&status=current&expand=version";
        JsonNode results = restClient.get(path).path("results");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        // Prefer an exact title match before falling back to the first result: Confluence title
        // search is not guaranteed to be exact, and silently resolving "Order API spec (old)" when
        // the human asked for "Order API spec" produces a spec built from the wrong page, which
        // reads as a content problem rather than a resolution one.
        for (JsonNode result : results) {
            if (title.equals(result.path("title").asText(null))) {
                return result.path("id").asText(null);
            }
        }
        return results.get(0).path("id").asText(null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Percent-decodes one path segment, or null when it is not decodable.
     *
     * <p>Null rather than a throw: a malformed escape in a URL somebody pasted into a Jira
     * description is an unresolvable link, which the caller already knows how to note. Letting
     * {@code IllegalArgumentException} out made it a run-ending failure instead, because
     * {@code LinkHarvester} catches only {@code AtlassianException}.
     */
    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
