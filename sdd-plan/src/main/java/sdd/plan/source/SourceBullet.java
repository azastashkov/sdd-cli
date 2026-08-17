package sdd.plan.source;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Task 4's parser half of this class: the distinct Jira issue keys named by every root
     * {@code jira} bullet in a spec's {@code sources()}, in first-appearance order — the set of
     * issues {@code sdd plan approve} and {@code sdd review} comment back on.
     *
     * <p>Deliberately reads only the {@code jira} shape, never {@code jira-comment}: a comment
     * bullet's key is the same issue whose own root bullet is rendered alongside it (see
     * {@link #render}/{@code JiraSpecSource.addIssue}, which always adds an issue's doc and its
     * comment docs together), so counting {@code jira-comment} keys too would only ever repeat a
     * key already collected here, never surface a new one — {@code SourceBudget} dropping the root
     * doc while keeping one of its comments is the one scenario where that could differ, and is
     * accepted here as a rare, disclosed edge case rather than reason to also scan comment bullets.
     * A line is matched on {@code startsWith("jira ")} (with the trailing space): that alone
     * excludes {@code jira-comment} lines, since {@code "jira-comment ...".startsWith("jira ")} is
     * false — no separate first-token dispatch needed.
     */
    public static List<String> jiraIssueKeys(List<String> sources) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : sources) {
            if (line != null && line.startsWith("jira ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length >= 2 && !parts[1].isBlank()) {
                    keys.add(parts[1]);
                }
            }
        }
        return List.copyOf(keys);
    }

    /**
     * Task 5's parser half of this class: the URL of each root {@code jira} bullet's issue, keyed
     * by issue key, in first-appearance order — what {@code sdd review}'s pull-request description
     * links back to. A second, independent walk of {@code sources()} rather than folding the URL
     * into {@link #jiraIssueKeys}'s return shape: every existing caller of that method wants only
     * the keys (a Jira comment poster has no use for a URL), and changing its return type to carry
     * one would touch Task 4's call sites for a value only Task 5 needs.
     *
     * <p>{@code split(" ", 5)} — one more field than {@link #jiraIssueKeys}'s {@code split(" ", 3)}
     * needs, since a root bullet is {@code "jira <KEY> updated <ISO-8601 UTC> <url>"}: five
     * space-separated fields, the url being the fifth and last. Splitting on the FULL grammar
     * (rather than finding the last space) trusts {@link #render}'s own format exactly, the same
     * "one place" discipline this whole class exists for.
     */
    public static Map<String, String> jiraIssueUrls(List<String> sources) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (String line : sources) {
            if (line != null && line.startsWith("jira ")) {
                String[] parts = line.split(" ", 5);
                if (parts.length >= 5 && !parts[1].isBlank()) {
                    urls.putIfAbsent(parts[1], parts[4]);
                }
            }
        }
        // Collections.unmodifiableMap, not Map.copyOf: Map.copyOf's iteration order is explicitly
        // unspecified, which would silently break this method's own "first-appearance order"
        // guarantee — the same reason jiraIssueKeys above returns List.copyOf of a LinkedHashSet
        // rather than any order-erasing collector.
        return java.util.Collections.unmodifiableMap(urls);
    }
}
