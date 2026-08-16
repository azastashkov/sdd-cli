package sdd.plan.source;

import sdd.plan.confluence.SpecNormalizationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Bundle-level budget: {@code ConfluenceExtract.MAX_TEXT_CHARS} (300_000) is the per-document
 * cap already enforced at extraction time; this class enforces the SAME number as the cap on a
 * whole bundle's combined text. {@code ConfluenceExtract} must not be modified (it is reused
 * verbatim for Confluence REST bodies and Jira rendered-field HTML in later phases), and its
 * constant is package-private to {@code sdd.plan.confluence} by design, so the number is
 * duplicated here rather than the constant reused — keep the two in sync if either changes.
 * <p>
 * Over budget, whole documents are dropped — never truncated, since a partially-seen document
 * would silently misrepresent what the model was given — lowest priority first, until the
 * total fits. Every drop appends a note naming the document so a human can see what was left
 * out; a cap the human cannot see is a lie.
 * <p>
 * A Confluence document can never exceed {@code MAX_TOTAL_CHARS} on its own —
 * {@code ConfluenceExtract.extract} already rejects anything over that size — but a
 * {@code FREE_TEXT} document from {@code --text} never passes through {@code ConfluenceExtract}
 * and has no such cap. Dropping such a document (because it alone is over budget) can leave the
 * whole bundle empty, which would otherwise send the model an empty prompt with no visible
 * failure beyond a buried {@code [source]} Open Question. {@link #apply} instead fails loudly:
 * any single document whose own text already exceeds the budget is rejected up front, before any
 * dropping happens, naming the offending document and its size.
 */
public final class SourceBudget {

    static final int MAX_TOTAL_CHARS = 300_000;

    private SourceBudget() {
    }

    public static SourceBundle apply(SourceBundle bundle) {
        for (SourceDoc doc : bundle.docs()) {
            if (doc.text().length() > MAX_TOTAL_CHARS) {
                throw new SpecNormalizationException("document too large for the source budget: '"
                        + doc.label() + "' is " + doc.text().length() + " chars (limit "
                        + MAX_TOTAL_CHARS + ") — split it or drop it before ingesting");
            }
        }
        List<SourceDoc> kept = new ArrayList<>(bundle.docs());
        List<String> drops = new ArrayList<>();
        while (totalChars(kept) > MAX_TOTAL_CHARS && !kept.isEmpty()) {
            SourceDoc dropped = kept.remove(worstIndex(kept));
            drops.add("dropped for budget: " + dropped.label());
        }
        if (drops.isEmpty()) {
            return bundle;
        }
        List<String> notes = new ArrayList<>(bundle.notes());
        notes.addAll(drops);
        return new SourceBundle(kept, notes);
    }

    private static int totalChars(List<SourceDoc> docs) {
        int total = 0;
        for (SourceDoc doc : docs) {
            total += doc.text().length();
        }
        return total;
    }

    /** Lowest-priority document, tie-broken towards the later position in the list — the brief
     *  ranks kinds, not documents within a kind, so this tie-break is this class's own arbitrary
     *  but deterministic choice. */
    private static int worstIndex(List<SourceDoc> docs) {
        int worst = 0;
        for (int i = 1; i < docs.size(); i++) {
            if (priority(docs.get(i).kind()) >= priority(docs.get(worst).kind())) {
                worst = i;
            }
        }
        return worst;
    }

    /** Higher number drops first. FREE_TEXT (the operator typed it directly) and JIRA_ISSUE
     *  (the primary requirement record) rank highest; CONFLUENCE_PAGE is supporting design
     *  context; JIRA_COMMENT — discussion threads, least likely to state a requirement — is the
     *  most disposable. */
    private static int priority(SourceDoc.Kind kind) {
        return switch (kind) {
            case FREE_TEXT, JIRA_ISSUE -> 0;
            case CONFLUENCE_PAGE -> 1;
            case JIRA_COMMENT -> 2;
        };
    }
}
