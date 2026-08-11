package sdd.plan.spec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Semantic completeness checks. Structural grammar is SpecParser's job — this judges a parsed spec. */
public final class SpecValidator {
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
        return problems;
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
