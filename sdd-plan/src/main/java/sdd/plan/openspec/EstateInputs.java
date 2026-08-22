package sdd.plan.openspec;

import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@link OpenSpecInput} per drafted repo, built at PLAN time from the draft.
 *
 * <p>{@code OpenSpecInputs.forPlan} in sdd-cli does the same job from an approved {@code plan.json},
 * for the per-repo export written into each repository at implement time. This is the same shape
 * one gate earlier, so the workspace can carry an OpenSpec change of its own before anything is
 * approved — and, more usefully, so the root's spec deltas can be rendered by
 * {@link OpenSpecChange} rather than by a second implementation of a grammar the real OpenSpec CLI
 * validates. A transcribed copy of that grammar would drift from the one that is checked.
 *
 * <p>Two fields are necessarily absent here and both are absent on purpose. {@code bumps} is
 * propagation, which is probed live at approve; nothing the root renders reads it. {@code baseSha}
 * comes from the knowledge base rather than from a git call, because the plan is made against the
 * indexed state and the export already says so in those words.
 */
public final class EstateInputs {

    private EstateInputs() {
    }

    public static List<OpenSpecInput> forDraft(NormalizedSpec spec, ImpactResult result,
            List<ExecutionOrder.Unit> order, PlanDrafter.Draft draft, int planVersion,
            Map<String, String> baseShas) {
        String changeId = ChangeId.of(spec.id(), planVersion);
        List<String> siblings = draft.steps().stream().map(PlanDrafter.DraftStep::repo).toList();
        List<List<String>> orderLists = order.stream().map(ExecutionOrder.Unit::repos).toList();
        Map<String, String> owners = requirementOwners(draft);
        List<String> acceptanceIds = spec.acceptance().stream().map(SpecItem::id).toList();

        List<OpenSpecInput> inputs = new ArrayList<>();
        for (PlanDrafter.DraftStep step : draft.steps()) {
            inputs.add(new OpenSpecInput(
                    changeId, step.repo(), annotationOf(result, step.repo()), siblings, orderLists,
                    spec.id(), planVersion, spec.title(), spec.goal(), spec.background(),
                    spec.outOfScope(), items(spec.constraints()), items(spec.openQuestions()),
                    covered(spec, step.covers()), items(spec.acceptance()),
                    OpenSpecPlan.parse(step.openspec(), step.covers(), acceptanceIds),
                    step.subSpec(), step.files(), step.verification(), step.versionAction(),
                    contracts(draft, step.providesContracts()),
                    contracts(draft, step.consumesContracts()),
                    List.of(), baseShas.getOrDefault(step.repo(), ""), owners));
        }
        return List.copyOf(inputs);
    }

    /**
     * Requirement id to the FIRST repo covering it, in step order.
     *
     * <p>A {@code LinkedHashMap}, and never {@code Map.copyOf}: that randomises iteration order per
     * JVM instance, which has broken this export's byte-determinism twice already — once here and
     * once in {@code OpenSpecPlan}. {@code OpenSpecInput}'s own constructor re-wraps it the same way.
     */
    private static Map<String, String> requirementOwners(PlanDrafter.Draft draft) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (PlanDrafter.DraftStep step : draft.steps()) {
            for (String requirement : step.covers()) {
                owners.putIfAbsent(requirement, step.repo());
            }
        }
        return owners;
    }

    private static String annotationOf(ImpactResult result, String repo) {
        for (AffectedRepo affected : result.affected()) {
            if (affected.repo().equals(repo)) {
                return affected.annotation();
            }
        }
        return "";
    }

    private static List<OpenSpecInput.Item> items(List<SpecItem> source) {
        return source.stream().map(i -> new OpenSpecInput.Item(i.id(), i.text())).toList();
    }

    /** The spec requirements this step covers, in the spec's order rather than the step's. */
    private static List<OpenSpecInput.Item> covered(NormalizedSpec spec, List<String> ids) {
        return spec.requirements().stream()
                .filter(r -> ids.contains(r.id()))
                .map(r -> new OpenSpecInput.Item(r.id(), r.text()))
                .toList();
    }

    private static List<OpenSpecInput.Contract> contracts(PlanDrafter.Draft draft, List<String> ids) {
        List<OpenSpecInput.Contract> out = new ArrayList<>();
        for (String id : ids) {
            for (PlanDrafter.DraftContract contract : draft.contracts()) {
                if (contract.id().equals(id)) {
                    out.add(new OpenSpecInput.Contract(contract.id(), contract.kind(),
                            contract.provider(), contract.consumers(), contract.body(),
                            contract.compat(), contract.declared()));
                }
            }
        }
        return out;
    }
}
