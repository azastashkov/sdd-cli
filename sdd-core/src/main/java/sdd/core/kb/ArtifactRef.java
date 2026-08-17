package sdd.core.kb;

import java.util.Objects;
import java.util.Optional;

/**
 * A Maven-coordinate reference as the KB stores it: {@code artifact.grp} / {@code artifact.name}
 * (and {@code dep_edge.to_grp} / {@code dep_edge.to_name}), written {@code "grp:name"} in
 * free-text and spec-authored values. One definition of what that string means, used by
 * {@link KbEntities#resolve} for {@link EntityKind#ARTIFACT} to resolve it against
 * {@code dep_edge}.
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
            // No usable colon. An npm package name is still a complete, unambiguous reference —
            // npm's namespace is flat and global — so `@azastashkov/web-sdk` and `react` resolve
            // under the constant "npm" group rather than being rejected for lacking a prefix a
            // human would have no reason to write. Anything not shaped like a package name (a bare
            // word with no slash, a leading colon, a trailing colon) stays unresolvable.
            return npmPackageName(value)
                    ? Optional.of(new ArtifactRef(NPM_GROUP, value))
                    : Optional.empty();
        }
        return Optional.of(new ArtifactRef(value.substring(0, colon), value.substring(colon + 1)));
    }

    /** The npm group, mirrored from the indexer's {@code NpmExtractor.NPM_GROUP}. */
    private static final String NPM_GROUP = "npm";

    /**
     * Deliberately narrow: only the scoped form {@code @scope/name}. An unscoped name like
     * {@code react} is indistinguishable from a repo name, a class name or an ordinary English
     * word, and treating every bare word as an artifact reference would make entity resolution
     * match entities nobody named. A scoped package's {@code @} and {@code /} make it
     * unmistakable.
     */
    private static boolean npmPackageName(String value) {
        return value.matches("@[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*");
    }
}
