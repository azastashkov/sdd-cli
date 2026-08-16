package sdd.cli.review;

import sdd.cli.implement.CompatGate;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repos that DECLARED a compatibility guarantee and whose gate never reached a verdict.
 *
 * <p>Before this, such a repo was indistinguishable from one that passed: it still went
 * {@code SUCCEEDED}, the report never mentioned the gate, and {@code sdd review} exited 0. So a
 * plan could declare {@code binary-compatible}, have the baseline build fail for an unrelated
 * reason, and be signed off as compatible on the strength of a check that never ran. TypeScript
 * makes it likelier still, because "no node on this machine" is an ordinary condition rather than
 * an exceptional one.
 *
 * <p>Its own type rather than another {@code List<String>} on {@link ReportInputs}: that record's
 * javadoc records four same-typed lists sitting side by side in a positional constructor, where a
 * transposition compiles cleanly and misfiles one failure kind as another. A distinct type is what
 * that note asks for.
 */
public final class SkippedGates {

    /**
     * @param repo   the repo that declared the guarantee
     * @param compat which guarantee — {@code binary-compatible} or {@code type-compatible}
     * @param detail why the gate could not reach a verdict, as recorded during the run
     */
    public record Skipped(String repo, String compat, String detail) {
    }

    private SkippedGates() {
    }

    /**
     * @param declared per repo, the compat values its provided contracts declare — taken from the
     *                 PLAN, so a gate that was never even attempted is caught alongside one that
     *                 was attempted and abandoned
     */
    public static List<Skipped> of(PlanModel plan, RunState state, RunStore store, Path runDir) {
        Map<String, Set<String>> declared = declaredBy(plan);
        List<Skipped> skipped = new ArrayList<>();
        declared.forEach((repo, compats) -> {
            if (state.stateOf(repo) != RepoState.SUCCEEDED) {
                // A repo that did not succeed already fails the review on its own account, and its
                // gate legitimately never ran. Reporting it here would bury the real skips.
                return;
            }
            List<CompatGate> gates = store.readCompatGates(runDir, repo);
            for (String compat : compats) {
                CompatGate gate = gates.stream()
                        .filter(g -> g.compat().equals(compat)).findFirst().orElse(null);
                if (gate == null) {
                    // No record at all. For a run made before this file existed that is simply an
                    // absence of evidence, so it is reported as unknown rather than asserted as a
                    // skip — but it is still reported, because a declared guarantee with nothing
                    // behind it is exactly what this section exists to surface.
                    skipped.add(new Skipped(repo, compat,
                            "no gate outcome was recorded for this repo"));
                } else if (gate.skipped()) {
                    skipped.add(new Skipped(repo, compat, gate.detail()));
                }
            }
        });
        return List.copyOf(skipped);
    }

    private static Map<String, Set<String>> declaredBy(PlanModel plan) {
        Map<String, Set<String>> declared = new java.util.LinkedHashMap<>();
        for (PlanModel.PlanContract contract : plan.contracts()) {
            if (contract.compat() != null && !contract.compat().isBlank()) {
                declared.computeIfAbsent(contract.provider(), r -> new LinkedHashSet<>())
                        .add(contract.compat());
            }
        }
        return declared;
    }
}
