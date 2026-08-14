package sdd.core.kb;

import java.util.List;
import java.util.Objects;

/**
 * The result of resolving a free-text or spec-authored value against the KB for a given
 * {@link EntityKind}. {@link #repos()} is the distinct, sorted set of repos any match belongs
 * to — this is the one true definition sdd plan's touchpoint resolution has always returned.
 * {@link #matches()} is additive: the specific rows that matched, for citation.
 */
public record Resolution(EntityKind kind, String value, List<EntityMatch> matches) {
    public Resolution {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(value);
        matches = List.copyOf(matches);
    }

    public List<String> repos() {
        return matches.stream().map(EntityMatch::repo).distinct().sorted().toList();
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }
}
