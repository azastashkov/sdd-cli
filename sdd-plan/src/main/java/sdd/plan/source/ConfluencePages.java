package sdd.plan.source;

/**
 * The page-fetch/resolve seam {@link LinkHarvester} needs from Confluence — implemented by
 * {@code sdd.plan.confluence.ConfluenceClient} in production. There is no Mockito in this repo,
 * so a small interface is the test seam instead (the same shape as {@code ChatModel} for the LLM
 * and {@code RestClient.Sleeper} for backoff): {@code LinkHarvesterTest} implements this by hand
 * rather than mocking the concrete REST client.
 */
public interface ConfluencePages {
    /**
     * Resolves a URL to a page id, or null when the URL is not a recognisable Confluence page
     * reference. May throw {@code sdd.core.http.AtlassianException} on a network failure hit
     * while resolving (a title search, a tiny-link redirect) — {@link LinkHarvester} turns either
     * outcome into an "unresolvable" note rather than propagating it, since one bad link must
     * never abort the rest of ingestion.
     */
    String resolvePageId(String url);

    /** Fetches one page's content. Throws {@code AtlassianException} on failure (404, exhausted
     *  retries, transport error, ...) — {@link LinkHarvester} turns that into a "fetch failed"
     *  note, same reasoning as {@link #resolvePageId}. */
    SourceDoc fetchPage(String pageId);
}
