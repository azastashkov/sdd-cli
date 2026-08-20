package sdd.plan.openspec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything {@link OpenSpecChange} needs to render one repository's slice, in a shape neither
 * {@code PlanDocument} nor {@code PlanModel} owns.
 *
 * <p>Module-neutral on purpose: the plan-side types live in {@code sdd.plan.approve} and the
 * run-side ones in {@code sdd.cli.implement}, and the renderer must not depend on either — sdd-cli
 * depends on sdd-plan and not the reverse, so a renderer that took {@code PlanModel} could not be
 * tested from sdd-plan at all. Both callers build one of these.
 *
 * <p>Carries no clock and no filesystem handle. That is what lets the export be byte-identical on
 * a re-run, which is in turn what lets the writer decide "ours, unchanged" from "a human edited it"
 * by plain comparison.
 */
public record OpenSpecInput(
        String changeId,
        String repo,
        String annotation,
        List<String> siblingRepos,
        List<List<String>> order,
        String specId,
        int planVersion,
        String specTitle,
        String goal,
        String background,
        List<String> outOfScope,
        List<Item> constraints,
        List<Item> openQuestions,
        List<Item> covers,
        List<Item> acceptance,
        OpenSpecPlan plan,
        String subSpec,
        List<String> files,
        List<String> verification,
        String versionAction,
        List<Contract> provides,
        List<Contract> consumes,
        List<String> bumps,
        String baseSha,
        Map<String, String> requirementOwners) {

    /** An id-and-text pair, matching {@code SpecItem} without depending on it. */
    public record Item(String id, String text) {
    }

    /** One interface contract, flattened out of {@code plan.json}'s contract list. */
    public record Contract(String id, String kind, String provider, List<String> consumers,
                           String body, String compat, List<String> declared) {
        public Contract {
            consumers = List.copyOf(consumers);
            declared = List.copyOf(declared);
        }
    }

    public OpenSpecInput {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(repo, "repo");
        siblingRepos = List.copyOf(siblingRepos);
        order = List.copyOf(order);
        outOfScope = List.copyOf(outOfScope);
        constraints = List.copyOf(constraints);
        openQuestions = List.copyOf(openQuestions);
        covers = List.copyOf(covers);
        acceptance = List.copyOf(acceptance);
        files = List.copyOf(files);
        verification = List.copyOf(verification);
        provides = List.copyOf(provides);
        consumes = List.copyOf(consumes);
        bumps = List.copyOf(bumps);
        // NOT Map.copyOf: its iteration order is randomized per JVM instance by design, so the
        // Non-Goals list in design.md came out in a different order on every run. That breaks the
        // export's byte-determinism, and idempotence rule 4 decides "ours, unchanged" by byte
        // comparison — so on a --resume sdd would see its own output as differing and, under
        // rule 5, refuse to write anything at all. Found by re-rendering a real run.
        requirementOwners = Collections.unmodifiableMap(new LinkedHashMap<>(requirementOwners));
        plan = plan == null ? OpenSpecPlan.absent() : plan;
    }

    /** Whether this repo is in the change only to rebuild against something that changed. */
    public boolean rebuildOnly() {
        return "BUMP_REBUILD_ONLY".equals(annotation);
    }
}
