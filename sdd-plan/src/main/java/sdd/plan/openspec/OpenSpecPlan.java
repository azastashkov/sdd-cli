package sdd.plan.openspec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code - openspec:} sublist of one repo step, parsed.
 *
 * <p>Two decisions the drafter proposes, a human corrects at Gate 1, and {@code plan.json} freezes:
 * which OpenSpec <em>capability</em> this repo's slice belongs to, and which acceptance criteria
 * verify which requirement.
 *
 * <pre>
 * - openspec:
 *   - capability: tier-resolution
 *   - R1 -&gt; A1, A3
 *   - R2 -&gt; none
 * </pre>
 *
 * <p><b>Why the allocation has to be written down at all.</b> OpenSpec rejects an {@code ADDED}
 * requirement with no scenario, and sdd's spec format has no link between an {@code R} item and the
 * {@code A} items that verify it — {@code SpecParser} parses both as flat {@code SpecItem} lists.
 * Worse, the {@code A} items never reach {@code plan.json} at all: {@code RepoStepResolver} fills
 * {@code RepoStep.acceptanceChecks} from the step's {@code verification} entries, not from
 * acceptance criteria. So the allocation cannot be recovered downstream and must travel explicitly.
 *
 * <p>Pure value type, mirroring {@code sdd.core.contract.DeclaredContract}: it collects
 * {@link #problems()} rather than throwing, so {@code PlanValidator} can report every malformed line
 * at once with the rest of Gate 1's verdict. A malformed block is only reachable by a human edit,
 * it is one line to fix, and silently coercing it at implement time is exactly the failure this
 * codebase keeps writing comments about.
 */
public record OpenSpecPlan(String capability, Map<String, List<String>> acceptanceFor,
                           List<String> problems) {

    private static final Pattern CAPABILITY = Pattern.compile("capability:\\s*(.+)");
    private static final Pattern ALLOCATION = Pattern.compile("(R[1-9][0-9]*)\\s*->\\s*(.+)");
    private static final Pattern ACCEPTANCE_ID = Pattern.compile("A[1-9][0-9]*");

    /** The literal a human writes to say "this requirement has no acceptance criterion". */
    private static final String NONE = "none";

    public OpenSpecPlan {
        // Insertion-ordered, NOT Map.copyOf. render() iterates this map straight into plan.md, and
        // plan.md is SHA-hashed by `sdd plan approve` — an unordered map would emit the allocation
        // lines in an order that is not a function of the plan, moving the hash for no reason and
        // making a regenerated plan un-diffable against its .bak.
        acceptanceFor = Collections.unmodifiableMap(new LinkedHashMap<>(acceptanceFor));
        problems = List.copyOf(problems);
    }

    /** The shape a step with no {@code - openspec:} block has: absent, not malformed. */
    public static OpenSpecPlan absent() {
        return new OpenSpecPlan(null, Map.of(), List.of());
    }

    public boolean isAbsent() {
        return capability == null && acceptanceFor.isEmpty();
    }

    /**
     * Parses the block's lines, already stripped of their {@code "  - "} prefix by the section
     * reader.
     *
     * @param covers      the step's requirement ids; an allocation for anything else is a problem,
     *                    because it means the human edited one list and not the other
     * @param knownAccept every acceptance id in the spec, for the same reason
     */
    public static OpenSpecPlan parse(List<String> lines, List<String> covers,
                                     List<String> knownAccept) {
        String capability = null;
        Map<String, List<String>> allocation = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> coversSet = new LinkedHashSet<>(covers);
        Set<String> acceptSet = new LinkedHashSet<>(knownAccept);

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher cap = CAPABILITY.matcher(line);
            if (cap.matches()) {
                if (capability != null) {
                    problems.add("openspec: capability declared more than once");
                    continue;
                }
                String value = cap.group(1).strip();
                // Coerced, not rejected: a human typing "Tier Resolution" meant a capability, and
                // failing Gate 1 over letter case helps nobody. The coercion is visible in the
                // rendered plan.md on the next regeneration.
                capability = Kebab.of(value);
                if (!value.equals(capability)) {
                    problems.add("openspec: capability '" + value + "' is not a legal OpenSpec path"
                            + " segment — write '" + capability + "'");
                }
                continue;
            }
            Matcher alloc = ALLOCATION.matcher(line);
            if (alloc.matches()) {
                String requirement = alloc.group(1);
                if (!coversSet.contains(requirement)) {
                    problems.add("openspec: " + requirement + " is allocated acceptance criteria but"
                            + " this step does not cover it");
                    continue;
                }
                if (allocation.containsKey(requirement)) {
                    problems.add("openspec: " + requirement + " is allocated more than once");
                    continue;
                }
                allocation.put(requirement, acceptanceIds(alloc.group(2), requirement, acceptSet,
                        problems));
                continue;
            }
            problems.add("openspec: unrecognised line '" + line + "' — expected 'capability: <name>'"
                    + " or 'R1 -> A1, A2'");
        }
        return new OpenSpecPlan(capability, allocation, problems);
    }

    private static List<String> acceptanceIds(String rhs, String requirement, Set<String> known,
                                              List<String> problems) {
        String value = rhs.strip();
        if (value.equalsIgnoreCase(NONE) || value.equals("-")) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String token : value.split("[,\\s]+")) {
            String id = token.strip();
            if (id.isEmpty()) {
                continue;
            }
            if (!ACCEPTANCE_ID.matcher(id).matches()) {
                problems.add("openspec: '" + id + "' allocated to " + requirement
                        + " is not an acceptance id (expected A1, A2, …, or 'none')");
                continue;
            }
            if (!known.contains(id)) {
                problems.add("openspec: " + id + " allocated to " + requirement
                        + " is not in the spec's Acceptance Criteria");
                continue;
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** The block's lines as plan.md renders them, for round-tripping through {@code plan.json}. */
    public List<String> render() {
        List<String> lines = new ArrayList<>();
        if (capability != null) {
            lines.add("capability: " + capability);
        }
        acceptanceFor.forEach((requirement, ids) ->
                lines.add(requirement + " -> " + (ids.isEmpty() ? NONE : String.join(", ", ids))));
        return List.copyOf(lines);
    }
}
