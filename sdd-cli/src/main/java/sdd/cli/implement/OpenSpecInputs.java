package sdd.cli.implement;

import sdd.plan.openspec.ChangeId;
import sdd.plan.openspec.OpenSpecInput;
import sdd.plan.openspec.OpenSpecPlan;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one {@link OpenSpecInput} per repo that has a step, from the frozen plan and the spec.
 *
 * <p>Everything here comes from artifacts the run dir already holds — {@code plan.json},
 * {@code spec.md} and {@code propagation.json} — all of which {@code ImplementCommand} re-reads
 * from snapshots on {@code --resume}. Nothing is re-derived from the live workspace, so a resumed
 * run exports what the original run would have.
 *
 * <p>Repos with no step are deliberately absent from the result. A bom site or a rebuild-only
 * dependent the drafter skipped is never branched, never reset to its base commit and never
 * committed, so writing into its tree would touch a repository this run does not own.
 */
public final class OpenSpecInputs {

    private OpenSpecInputs() {
    }

    public static Map<String, OpenSpecInput> forPlan(PlanModel plan, NormalizedSpec spec,
                                                     Map<String, RepoPropagation> propagation) {
        String changeId = ChangeId.of(plan.specId(), plan.planVersion());
        List<String> siblings = plan.steps().stream().map(PlanModel.PlanStep::repo).toList();
        Map<String, String> owners = requirementOwners(plan);
        List<String> acceptanceIds = spec.acceptance().stream().map(SpecItem::id).toList();

        Map<String, OpenSpecInput> out = new LinkedHashMap<>();
        for (PlanModel.PlanStep step : plan.steps()) {
            out.put(step.repo(), new OpenSpecInput(
                    changeId,
                    step.repo(),
                    annotationOf(plan, step.repo()),
                    siblings,
                    plan.order(),
                    plan.specId(),
                    plan.planVersion(),
                    spec.title(),
                    spec.goal(),
                    spec.background(),
                    spec.outOfScope(),
                    items(spec.constraints()),
                    items(spec.openQuestions()),
                    covered(spec, step.covers()),
                    items(spec.acceptance()),
                    OpenSpecPlan.parse(step.openspec(), step.covers(), acceptanceIds),
                    step.subSpec(),
                    step.files(),
                    step.verification(),
                    step.versionAction(),
                    contracts(plan, step.provides()),
                    contracts(plan, step.consumes()),
                    bumps(propagation.get(step.repo())),
                    baseShaOf(plan, step.repo()),
                    owners));
        }
        return out;
    }

    /** Which repo covers each requirement, so a repo's design.md can name what is NOT its job. */
    private static Map<String, String> requirementOwners(PlanModel plan) {
        Map<String, String> owners = new LinkedHashMap<>();
        for (PlanModel.PlanStep step : plan.steps()) {
            step.covers().forEach(id -> owners.putIfAbsent(id, step.repo()));
        }
        return owners;
    }

    private static String annotationOf(PlanModel plan, String repo) {
        return plan.repos().stream().filter(r -> r.name().equals(repo))
                .map(PlanModel.PlanRepo::annotation).findFirst().orElse("");
    }

    private static String baseShaOf(PlanModel plan, String repo) {
        return plan.repos().stream().filter(r -> r.name().equals(repo))
                .map(PlanModel.PlanRepo::baseSha).findFirst().orElse("");
    }

    private static List<OpenSpecInput.Item> items(List<SpecItem> source) {
        return source.stream().map(i -> new OpenSpecInput.Item(i.id(), i.text())).toList();
    }

    /** The spec's requirement items this step covers, in spec order, skipping ids it does not have. */
    private static List<OpenSpecInput.Item> covered(NormalizedSpec spec, List<String> covers) {
        List<OpenSpecInput.Item> out = new ArrayList<>();
        for (SpecItem requirement : spec.requirements()) {
            if (covers.contains(requirement.id())) {
                out.add(new OpenSpecInput.Item(requirement.id(), requirement.text()));
            }
        }
        return out;
    }

    private static List<OpenSpecInput.Contract> contracts(PlanModel plan, List<String> ids) {
        List<OpenSpecInput.Contract> out = new ArrayList<>();
        for (PlanModel.PlanContract contract : plan.contracts()) {
            if (ids.contains(contract.id())) {
                out.add(new OpenSpecInput.Contract(contract.id(), contract.kind(),
                        contract.provider(), contract.consumers(), contract.body(),
                        contract.compat(), contract.declared()));
            }
        }
        return out;
    }

    /** The planned pins, phrased for a human — the only concrete thing a rebuild-only repo does. */
    private static List<String> bumps(RepoPropagation propagation) {
        if (propagation == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (RepoPropagation.BumpEdit bump : propagation.bumps()) {
            out.add("Update `" + bump.group() + ":" + bump.name() + "` from `" + bump.oldVersion()
                    + "` to `" + bump.newVersion() + "`.");
        }
        return out;
    }
}
