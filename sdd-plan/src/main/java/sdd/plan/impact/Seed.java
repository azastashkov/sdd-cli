package sdd.plan.impact;

import java.util.Objects;

/** A repo proposed for the affected set, with its provenance ("touchpoint" | "fts" | "model"). */
public record Seed(String repo, String source, String detail) {
    public Seed {
        Objects.requireNonNull(repo);
        Objects.requireNonNull(source);
        Objects.requireNonNull(detail);
    }
}
