package sdd.core.kb;

import java.util.Objects;
import java.util.Optional;

/**
 * A Maven-coordinate reference as the KB stores it: {@code artifact.grp} / {@code artifact.name}
 * (and {@code dep_edge.to_grp} / {@code dep_edge.to_name}), written {@code "grp:name"} in
 * free-text and spec-authored values. One definition of what that string means, shared by
 * {@link KbEntities#resolve} for {@link EntityKind#ARTIFACT} and by {@code sdd explain}'s
 * {@code dep_edge} lookup, so the two can never disagree about which values are addressable.
 */
public record ArtifactRef(String grp, String name) {
    public ArtifactRef {
        Objects.requireNonNull(grp);
        Objects.requireNonNull(name);
    }

    /**
     * Splits at the first colon, or empty when the value names no artifact: no colon at all, a
     * leading colon (empty group) or a trailing colon (empty name). Both halves must be non-empty
     * because both are matched against {@code NOT NULL} columns — a query on an empty one could
     * only ever return nothing, so it is a value that cannot be resolved rather than one that
     * resolves to nothing.
     *
     * <p>Returning {@link Optional} rather than a nullable pair leaves each caller its own empty
     * case: resolution answers "no matches", an evidence collector still emits its (empty) titled
     * section so the reader sees the question was asked.
     */
    public static Optional<ArtifactRef> parse(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new ArtifactRef(value.substring(0, colon), value.substring(colon + 1)));
    }
}
