package sdd.plan.source;

/**
 * Renders one line of {@code NormalizedSpec.sources()} — the "## Sources" provenance section —
 * from a fetched {@link SourceDoc}. The Task 3 brief requires "keep parsing and rendering of a
 * Sources bullet in ONE place so the two can never disagree", because Task 4 parses these lines
 * back to find which Jira issues to comment on and Task 5 links them from a pull request: this
 * class is that one place. Task 3 itself only ever renders (nothing downstream of it parses a
 * bullet back yet), but the grammar below is written to be Task 4's parse target, not just
 * today's output.
 *
 * <p>Grammar, one line per fetched document (never for {@link SourceDoc.Kind#FREE_TEXT} — that
 * document was typed by the operator, not fetched, so it has no provenance to record):
 * <pre>
 * jira &lt;KEY&gt; updated &lt;ISO-8601 UTC&gt; &lt;url&gt;
 * jira-comment &lt;KEY&gt; &lt;commentId&gt; updated &lt;ISO-8601 UTC&gt; &lt;url&gt;
 * confluence &lt;pageId&gt; v&lt;version&gt; "&lt;title&gt;" &lt;url&gt;
 * </pre>
 * The two "jira*" shapes are this class's own invention — the brief's example only shows one
 * jira line (a root issue) and one confluence line. A comment still needs a bullet ("every
 * fetched document contributes one bullet"), so {@code jira-comment} was chosen as a third,
 * clearly-distinguished first token rather than overloading {@code jira} with an extra optional
 * field, so a future parser can dispatch on the first token alone. See the Task 3 report's
 * "invented fixtures / least-certain API details" section.
 *
 * <p>{@code jira-comment}'s KEY and commentId are recovered from {@code doc.id()}, which
 * {@code JiraClient} constructs as {@code <KEY>-comment-<commentId>} for exactly this purpose —
 * one convention shared by the writer (JiraClient) and this reader, rather than a fourth field
 * added to {@link SourceDoc} for a value only this renderer needs.
 */
public final class SourceBullet {
    private SourceBullet() {
    }

    public static String render(SourceDoc doc) {
        return switch (doc.kind()) {
            case JIRA_ISSUE -> "jira " + doc.id() + " updated " + or(doc.version(), "unknown") + " " + or(doc.url(), "");
            case JIRA_COMMENT -> renderComment(doc);
            case CONFLUENCE_PAGE -> "confluence " + doc.id() + " v" + or(doc.version(), "?")
                    + " \"" + or(doc.title(), doc.id()) + "\" " + or(doc.url(), "");
            case FREE_TEXT -> throw new IllegalArgumentException(
                    "FREE_TEXT document '" + doc.id() + "' was never fetched and has no Sources bullet");
        };
    }

    private static String renderComment(SourceDoc doc) {
        int split = doc.id().indexOf("-comment-");
        String key = split < 0 ? doc.id() : doc.id().substring(0, split);
        String commentId = split < 0 ? "?" : doc.id().substring(split + "-comment-".length());
        return "jira-comment " + key + " " + commentId + " updated " + or(doc.version(), "unknown")
                + " " + or(doc.url(), "");
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
