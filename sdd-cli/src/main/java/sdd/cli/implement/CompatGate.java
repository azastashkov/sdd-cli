package sdd.cli.implement;

import java.util.List;

/**
 * What actually happened to a repo's compatibility gate.
 *
 * <p>Recorded because a gate that did not run was invisible: the repo still went {@code SUCCEEDED},
 * the report never mentioned it, and {@code sdd review} exited 0 — so a contract declaring
 * {@code binary-compatible} could pass a check that never happened. TypeScript makes that worse,
 * because "no node on this machine" is a far more ordinary reason to skip than "japicmp threw".
 *
 * <p>A structured record rather than a line scraped back out of the agent events. The events are
 * prose written for a human; deciding an exit code from them would be the console-scraping this
 * codebase refuses everywhere else.
 */
public record CompatGate(String compat, Outcome outcome, String detail) {

    public enum Outcome {
        /** The gate ran and found no incompatibility. */
        PASSED,
        /** The gate ran and found drift. The repo is FAILED, so this never reaches a review as a
         *  surprise — it is here so the report can say which gate failed it. */
        BROKEN,
        /** The gate could not run: a build failed, node was absent, the comparator threw. The repo
         *  may still be SUCCEEDED, which is exactly the case this record exists for. */
        SKIPPED
    }

    public CompatGate {
        detail = detail == null ? "" : detail;
    }

    /** The compat values a repo can declare, for the report's own vocabulary. */
    public static final List<String> VALUES = List.of("binary-compatible", "type-compatible");

    public boolean skipped() {
        return outcome == Outcome.SKIPPED;
    }
}
