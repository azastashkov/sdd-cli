package sdd.core.http;

import java.net.URI;

/**
 * {@code URI.create(url).getHost()}, with the one fallback every caller in this codebase wants: an
 * unparseable or host-less URL prints as itself rather than throwing or producing {@code null}.
 * Extracted (Fix 6, Task 8 review) from three near-identical private copies that had independently
 * accreted in {@code AtlassianProbe} (TLS-failure diagnostics), {@code DiagnosticHeader} (the
 * config-summary section), and {@code sdd.cli.review.BitbucketClients} (git-push diagnostics) —
 * all three only ever want "the host, or the original string if that's not knowable", so one
 * utility replaces all three rather than three copies silently drifting apart on which exception
 * types they each happened to catch.
 */
public final class UrlHosts {
    private UrlHosts() {
    }

    public static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (IllegalArgumentException | NullPointerException e) {
            return String.valueOf(url);
        }
    }
}
