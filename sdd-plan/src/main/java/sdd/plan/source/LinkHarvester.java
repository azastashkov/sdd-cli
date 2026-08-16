package sdd.plan.source;

import sdd.core.http.AtlassianException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers and fetches Confluence pages linked from Jira material — plain {@code http(s)://}
 * mentions in issue/comment text, plus every Jira remote link — up to {@code followDepth} levels
 * (links inside a FETCHED Confluence page are one level deeper than links found directly in
 * Jira) and {@code maxPages} total fetches. A visited set keyed on the RESOLVED page id (not the
 * raw URL) prevents a cycle or a same-page alias from being fetched twice.
 *
 * <p>Every link this class declines to follow — wrong host, unresolvable shape, over the depth
 * limit, over the page cap, or a fetch that failed — becomes one note on the result, naming the
 * URL and the reason. That is the Task 3 brief's own rule: "a cap the human cannot see is a lie".
 * A cycle is the one exception: it is not "left out" (its content already made it in via the
 * first visit), so it produces no note, only a silent skip.
 */
public final class LinkHarvester {
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[.,;:)\\]}'\"]+$");

    private final ConfluencePages confluencePages;
    private final String confluenceHost;
    private final int followDepth;
    private final int maxPages;

    public LinkHarvester(ConfluencePages confluencePages, String confluenceHost, int followDepth, int maxPages) {
        this.confluencePages = confluencePages;
        this.confluenceHost = confluenceHost;
        this.followDepth = followDepth;
        this.maxPages = maxPages;
    }

    public record Result(List<SourceDoc> pages, List<String> notes) {
    }

    private record Candidate(String url, int depth) {
    }

    /**
     * @param jiraDocs       every fetched Jira issue/comment {@link SourceDoc}, in fetch order —
     *                       their extracted text is scanned for bare {@code http(s)://} mentions,
     *                       all treated as depth 1 regardless of which document they came from.
     * @param remoteLinkUrls every Jira remote-link target URL across all fetched issues, also
     *                       depth 1 — appended after the text-harvested URLs. Data Center issues
     *                       very often carry the Confluence link this way rather than inline in
     *                       the description (see {@code JiraClient}'s javadoc), so this is not a
     *                       secondary source, it is frequently the primary one.
     */
    public Result harvest(List<SourceDoc> jiraDocs, List<String> remoteLinkUrls) {
        List<Candidate> seeds = new ArrayList<>();
        for (SourceDoc doc : jiraDocs) {
            for (String url : urlsIn(doc.text())) {
                seeds.add(new Candidate(url, 1));
            }
        }
        for (String url : remoteLinkUrls) {
            seeds.add(new Candidate(url, 1));
        }

        List<SourceDoc> pages = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> processedUrls = new HashSet<>();
        Set<String> visitedPageIds = new LinkedHashSet<>();
        Deque<Candidate> queue = new ArrayDeque<>(seeds);

        while (!queue.isEmpty()) {
            Candidate candidate = queue.poll();
            if (!processedUrls.add(candidate.url())) {
                continue;   // the exact same URL string was already processed (or queued and about
                            // to be) — one note per distinct link, not one per mention
            }
            String host = hostOf(candidate.url());
            if (host == null || !host.equalsIgnoreCase(confluenceHost)) {
                notes.add("wrong host: " + candidate.url());
                continue;
            }
            if (candidate.depth() > followDepth) {
                notes.add("over the depth limit (" + followDepth + "): " + candidate.url());
                continue;
            }
            String pageId;
            try {
                pageId = confluencePages.resolvePageId(candidate.url());
            } catch (AtlassianException e) {
                notes.add("unresolvable: " + candidate.url() + " (" + e.getMessage() + ")");
                continue;
            }
            if (pageId == null) {
                notes.add("unresolvable: " + candidate.url());
                continue;
            }
            if (!visitedPageIds.add(pageId)) {
                continue;   // cycle / alias: already fetched via a different URL, nothing left out
            }
            if (pages.size() >= maxPages) {
                notes.add("over the page cap (" + maxPages + "): " + candidate.url());
                continue;
            }
            SourceDoc page;
            try {
                page = confluencePages.fetchPage(pageId);
            } catch (AtlassianException e) {
                notes.add("fetch failed: " + candidate.url() + " (" + e.getMessage() + ")");
                continue;
            }
            pages.add(page);
            for (String nested : urlsIn(page.text())) {
                queue.add(new Candidate(nested, candidate.depth() + 1));
            }
        }
        return new Result(pages, notes);
    }

    /** Bare {@code http(s)://} mentions in plain text. This is intentionally text-based, not
     *  HTML-aware: {@code ConfluenceExtract} (upstream of every document this class ever sees)
     *  keeps an {@code <a>} element's visible text but drops its {@code href}, so a named link
     *  never survives into extracted text — only a URL pasted as its own visible text does. See
     *  the Task 3 report for why that is expected to matter less in practice than it sounds
     *  (Jira remote links are the primary channel on Data Center; see this class's own javadoc). */
    static List<String> urlsIn(String text) {
        List<String> urls = new ArrayList<>();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            urls.add(TRAILING_PUNCTUATION.matcher(m.group()).replaceAll(""));
        }
        return urls;
    }

    private static String hostOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
