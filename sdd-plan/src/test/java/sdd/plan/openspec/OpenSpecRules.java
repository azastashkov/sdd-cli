package sdd.plan.openspec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenSpec's validation rules, transcribed for tests.
 *
 * <p><b>Version: {@value OpenSpecChange#TARGET_VERSION}.</b> Everything here is a transcription of
 * rules enforced by someone else's code, so it proves conformance to what we <em>believe</em> the
 * rules are — not to the validator. The npx harness is what proves the latter. Keeping the
 * transcription in one file means a version bump is a one-file diff rather than a hunt.
 *
 * <p>The regexes are the ones OpenSpec actually applies, including their case-insensitivity: the
 * delta header and both requirement/scenario levels are matched with {@code /i}.
 */
final class OpenSpecRules {

    static final Pattern DELTA_HEADER =
            Pattern.compile("^##\\s+(ADDED|MODIFIED|REMOVED|RENAMED)\\s+Requirements\\s*$",
                    Pattern.CASE_INSENSITIVE);
    static final Pattern REQUIREMENT =
            Pattern.compile("^###\\s*Requirement:\\s*(.+)\\s*$", Pattern.CASE_INSENSITIVE);
    /** ANY level-4 header counts as a scenario to the parser, though the schema says to always
     *  write "#### Scenario:". Both are asserted. */
    static final Pattern SCENARIO = Pattern.compile("^####\\s+");
    static final Pattern SHALL_OR_MUST = Pattern.compile("\\b(SHALL|MUST)\\b");
    static final Pattern TASK_GROUP = Pattern.compile("^## (\\d+)\\. .+$");
    static final Pattern TASK_ITEM = Pattern.compile("^- \\[ \\] (\\d+)\\.(\\d+) .+$");
    /** A wall clock anywhere in the output would break the writer's byte-comparison idempotence. */
    static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private OpenSpecRules() {
    }

    record Requirement(String name, String body, List<String> scenarios) {
    }

    /** Every {@code ### Requirement:} block under a delta section, with its body and scenarios. */
    static List<Requirement> requirements(String deltaSpec) {
        List<Requirement> out = new ArrayList<>();
        String name = null;
        StringBuilder body = new StringBuilder();
        List<String> scenarios = new ArrayList<>();
        boolean inScenario = false;
        for (String line : deltaSpec.split("\n", -1)) {
            Matcher req = REQUIREMENT.matcher(line);
            if (req.matches()) {
                if (name != null) {
                    out.add(new Requirement(name, body.toString().strip(), List.copyOf(scenarios)));
                }
                name = req.group(1).strip();
                body.setLength(0);
                scenarios = new ArrayList<>();
                inScenario = false;
                continue;
            }
            if (name == null) {
                continue;
            }
            if (SCENARIO.matcher(line).find()) {
                scenarios.add(line.strip());
                inScenario = true;
                continue;
            }
            if (!inScenario) {
                body.append(line).append('\n');
            }
        }
        if (name != null) {
            out.add(new Requirement(name, body.toString().strip(), List.copyOf(scenarios)));
        }
        return out;
    }

    static Set<String> deltaSections(String deltaSpec) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : deltaSpec.split("\n", -1)) {
            Matcher m = DELTA_HEADER.matcher(line);
            if (m.matches()) {
                out.add(m.group(1).toUpperCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }
}
