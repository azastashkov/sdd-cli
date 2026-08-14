package sdd.cli.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One labelled group of {@link Fact}s inside {@link Evidence}. {@code title} is the human-facing
 * heading; {@code source} is the KB table or view the facts came from (e.g. {@code "rest_endpoint"},
 * {@code "fts_symbol (bm25)"}), which {@code EvidenceRenderer} (Task 6) prints as a {@code [source]}
 * tag so the output is honest about where every claim was fetched from.
 *
 * <p>{@code facts} is what is actually shown — possibly capped, with a trailing marker fact when
 * it is. {@code totalCount} is the true, uncapped count, so a reader always knows how much more
 * there was. Truncation is never silent: see {@link #capped}.
 */
public record Section(String title, String source, List<Fact> facts, int totalCount) {
    /** Default per-section cap for facts that are not individually-named code members. */
    public static final int DEFAULT_LIMIT = 25;

    /** Cap for "member-style" lists — individual code-level entities (types, symbols, usages). */
    public static final int MEMBER_LIMIT = 40;

    public Section {
        Objects.requireNonNull(title);
        Objects.requireNonNull(source);
        facts = List.copyOf(facts);
    }

    /** A section whose facts never need capping (already known to be small and bounded). */
    public static Section of(String title, String source, List<Fact> facts) {
        return new Section(title, source, facts, facts.size());
    }

    /**
     * Builds a section from a possibly-long fact list, capping it at {@code limit} and — when
     * capped — appending an explicit {@code "+N more (showing L of M)"} marker fact. Truncation
     * must always be stated, never silent: a reader (or the narrator) sees exactly how much of
     * the true total is in front of them.
     */
    public static Section capped(String title, String source, List<Fact> allFacts, int limit) {
        int total = allFacts.size();
        if (total <= limit) {
            return new Section(title, source, allFacts, total);
        }
        List<Fact> shown = new ArrayList<>(allFacts.subList(0, limit));
        shown.add(new Fact("+" + (total - limit) + " more (showing " + limit + " of " + total + ")"));
        return new Section(title, source, shown, total);
    }
}
