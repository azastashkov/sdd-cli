package sdd.cli.review;

import java.util.ArrayList;
import java.util.List;

/**
 * What the rebuild verdicts in a {@code report.md} actually cover. The report's most-read line is
 * "Estate rebuild: …", and a bare boolean could only say "estate totals" or "skipped
 * (--no-rebuild)" — which made every report a decision re-rendered claim a flag the human never
 * passed, and made a redo's downstream-subtree re-verify read as an estate-wide total. Both are
 * claims the code does not know to be true, so the scope is carried explicitly instead.
 *
 * @param kind       what ran (or did not) across the whole estate in this invocation
 * @param reverified repos whose downstream subtree a redo re-verified on top of {@code kind} — a
 *                   strictly smaller, strictly newer set of verdicts than an estate pass
 */
public record RebuildScope(Kind kind, List<String> reverified) {
    public enum Kind {
        /** A full estate rebuild ran in this invocation. */
        ESTATE,
        /** The human explicitly passed {@code --no-rebuild}. */
        SKIPPED,
        /** No estate rebuild ran and none was asked for — a decision re-rendering the report. */
        NONE
    }

    public RebuildScope {
        reverified = List.copyOf(reverified);
    }

    public static RebuildScope estate() {
        return new RebuildScope(Kind.ESTATE, List.of());
    }

    public static RebuildScope skipped() {
        return new RebuildScope(Kind.SKIPPED, List.of());
    }

    public static RebuildScope none() {
        return new RebuildScope(Kind.NONE, List.of());
    }

    /** Records that {@code repo}'s transitive downstream subtree was re-verified after a redo.
     *  Idempotent, because one interactive session can redo the same repo twice. */
    public RebuildScope withReverifiedSubtreeOf(String repo) {
        if (reverified.contains(repo)) {
            return this;
        }
        List<String> merged = new ArrayList<>(reverified);
        merged.add(repo);
        return new RebuildScope(kind, merged);
    }
}
