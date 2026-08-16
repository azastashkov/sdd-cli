package sdd.plan.source;

import java.util.List;
import java.util.Objects;

/**
 * One raw document feeding a {@link SourceBundle} — a Jira issue, a Jira comment, a Confluence
 * page, or a block of operator-supplied free text. Replaces the single-document
 * {@code ConfluenceExtract.Extracted} shape everywhere except inside {@code ConfluenceExtract}
 * itself: Task 3's Jira/Confluence REST fetchers, and today's Confluence export adapter, all
 * converge on this one type so {@link sdd.plan.confluence.ConfluenceNormalizer} has exactly one
 * ingestion shape to reason about regardless of how many documents — or what mix of kinds —
 * back a spec.
 */
public record SourceDoc(Kind kind, String id, String url, String title, String version,
                        String text, List<String> attachments) {
    /** Where a document came from. Drives both budget priority ({@link SourceBudget}) and the
     *  Task-3 conflict rule (Confluence wins over Jira). */
    public enum Kind { JIRA_ISSUE, JIRA_COMMENT, CONFLUENCE_PAGE, FREE_TEXT }

    public SourceDoc {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(id);
        Objects.requireNonNull(text);
        // url/title/version are deliberately NOT required: free text has no URL, and neither
        // Jira nor Confluence guarantees every field is populated before the document is dropped
        // for budget or before the model ever sees it.
        attachments = List.copyOf(attachments);
    }

    /**
     * "<title> (<url>)" for a human-facing reference — the header line in the normalizer's
     * prompt and the text of a budget-drop note. Falls back to {@code id} when title is blank
     * (free text has no natural title) and omits the parenthesised URL when there is none.
     */
    public String label() {
        String name = title == null || title.isBlank() ? id : title;
        return url == null || url.isBlank() ? name : name + " (" + url + ")";
    }
}
