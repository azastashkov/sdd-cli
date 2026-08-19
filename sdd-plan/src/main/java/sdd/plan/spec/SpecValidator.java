package sdd.plan.spec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Semantic completeness checks. Structural grammar is SpecParser's job — this judges a parsed spec. */
public final class SpecValidator {
    /** {@code <repo>/<path>:<line>} — at least one slash before the colon, so a bare
     *  {@code Foo.java:42} with no repo does not pass as a citation. */
    private static final Pattern CITATION = Pattern.compile("[\\w.-]+/[\\w./-]+:\\d+");

    private SpecValidator() {
    }

    public static List<String> problems(NormalizedSpec spec) {
        List<String> problems = new ArrayList<>();
        requireNonBlank(problems, "id", spec.id());
        requireNonBlank(problems, "title", spec.title());
        requireNonBlank(problems, "owner", spec.owner());
        requireNonBlank(problems, "status", spec.status());
        if (spec.goal().isBlank()) {
            problems.add("Goal section is empty");
        }
        if (spec.requirements().isEmpty()) {
            problems.add("Requirements: at least one R item is required");
        }
        if (spec.acceptance().isEmpty()) {
            problems.add("Acceptance Criteria: at least one A item is required");
        }
        checkItems(problems, "Requirements", "R", spec.requirements());
        checkItems(problems, "Acceptance Criteria", "A", spec.acceptance());
        checkItems(problems, "Constraints", "C", spec.constraints());
        checkItems(problems, "Open Questions", "Q", spec.openQuestions());
        checkEvidence(problems, spec.evidence());
        return problems;
    }

    /**
     * Every Evidence bullet must carry a {@code <repo>/<path>:<line>} citation.
     *
     * <p>This is a gate PROBLEM, not a parse error, deliberately: a human hand-editing a bullet
     * must still be able to round-trip the file, and the reviewer is the one who decides whether
     * an uncited claim stays. But it must be flagged, because Evidence exists precisely so a
     * reader can check it — an uncited bullet is an unverifiable claim wearing the same clothes
     * as a checkable one, which is the failure this section is meant to prevent rather than cause.
     */
    private static void checkEvidence(List<String> problems, List<String> evidence) {
        for (int i = 0; i < evidence.size(); i++) {
            if (!CITATION.matcher(evidence.get(i)).find()) {
                problems.add("Evidence: E" + (i + 1)
                        + " has no <repo>/<path>:<line> citation — '" + evidence.get(i) + "'");
            }
        }
    }

    private static void requireNonBlank(List<String> problems, String field, String value) {
        if (value.isBlank()) {
            problems.add("front matter: " + field + " is blank");
        }
    }

    private static void checkItems(List<String> problems, String section, String prefix,
                                   List<SpecItem> items) {
        Set<String> seen = new HashSet<>();
        Pattern shape = Pattern.compile(prefix + "[1-9][0-9]*");
        for (SpecItem item : items) {
            if (!shape.matcher(item.id()).matches()) {
                problems.add(section + ": id '" + item.id() + "' must match " + prefix + "<number>");
            } else if (!seen.add(item.id())) {
                problems.add(section + ": duplicate id '" + item.id() + "'");
            }
            if (item.text().isBlank()) {
                problems.add(section + ": " + item.id() + " has no text");
            }
        }
    }
}
