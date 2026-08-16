package sdd.plan.spec;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Ref-shape dispatch for the SpecSource seam. Classification is by SHAPE ALONE — no config, no
 * network, no filesystem access — so `sdd plan` can route a ref before anything downstream
 * (a fetcher, a file read) has run. A ref that is neither recognisably Jira nor Confluence
 * classifies as {@link SpecRefKind#MARKDOWN} and fails later as a missing file; that is the
 * honest outcome for a ref this class does not recognise, rather than a confusing dispatch error
 * here.
 */
public final class SpecSources {
    /** e.g. "PROJ-123": one or more leading uppercase-alnum/underscore project-key characters
     *  starting with a letter, a dash, then a number with no leading zero. */
    private static final Pattern JIRA_KEY = Pattern.compile("[A-Z][A-Z0-9_]*-[1-9][0-9]*");
    private static final Pattern BROWSE_PATH = Pattern.compile("/browse/" + JIRA_KEY.pattern() + "(?:/.*)?$");

    private SpecSources() {
    }

    public static SpecRefKind classify(String ref) {
        String lower = ref.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")) {
            return SpecRefKind.CONFLUENCE_EXPORT;
        }
        if (JIRA_KEY.matcher(ref).matches()) {
            return SpecRefKind.JIRA;
        }
        String path = httpPath(ref);
        if (path != null) {
            if (BROWSE_PATH.matcher(path).find()) {
                return SpecRefKind.JIRA;
            }
            if (path.contains("/pages/") || path.contains("/display/") || path.contains("/x/")) {
                return SpecRefKind.CONFLUENCE_PAGE;
            }
        }
        return SpecRefKind.MARKDOWN;
    }

    public static boolean isConfluenceExport(String ref) {
        return classify(ref) == SpecRefKind.CONFLUENCE_EXPORT;
    }

    /** The path of an http(s) URL, recognised by shape alone (no DNS, no connection). Returns
     *  null for anything that is not a well-formed http(s) URL, so a malformed ref falls through
     *  to MARKDOWN instead of this method throwing. */
    private static String httpPath(String ref) {
        URI uri;
        try {
            uri = new URI(ref);
        } catch (URISyntaxException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return null;
        }
        return uri.getPath();
    }
}
