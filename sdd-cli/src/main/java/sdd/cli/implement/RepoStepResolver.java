package sdd.cli.implement;

import sdd.agent.run.ContractRef;
import sdd.agent.run.RepoStep;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the approved {@link PlanModel} into per-repo {@link RepoStep}s (4B's runner input). This is
 * the join the module boundary forces into sdd-cli: contract-id strings → full {@link ContractRef}
 * from the top-level contracts[]; requirement-id strings → "R1: text" from the re-parsed spec;
 * repo names → filesystem paths from the KB.
 */
public final class RepoStepResolver {
    private RepoStepResolver() {
    }

    public static Map<String, RepoStep> resolve(PlanModel plan, NormalizedSpec spec,
                                                Map<String, Path> repoPaths) {
        Map<String, PlanModel.PlanContract> contracts = new LinkedHashMap<>();
        for (PlanModel.PlanContract c : plan.contracts()) {
            contracts.put(c.id(), c);
        }
        Map<String, String> reqText = new LinkedHashMap<>();
        for (SpecItem item : spec.requirements()) {
            reqText.put(item.id(), item.text());
        }
        Map<String, RepoStep> steps = new LinkedHashMap<>();
        for (String repo : flatten(plan.order())) {
            PlanModel.PlanStep step = plan.step(repo).orElse(null);
            if (step == null) {
                continue;   // a repo in order with no step (e.g. bom site) — nothing to run
            }
            Path root = repoPaths.get(repo);
            if (root == null) {
                throw new IllegalStateException("repo " + repo + " is not in the knowledge base");
            }
            List<String> requirements = step.covers().stream()
                    .map(id -> id + ": " + reqText.getOrDefault(id, "(requirement text unavailable)"))
                    .toList();
            steps.put(repo, new RepoStep(repo, root, step.subSpec(), requirements, step.files(),
                    refs(step.provides(), contracts), refs(step.consumes(), contracts),
                    step.verification()));
        }
        return steps;
    }

    private static List<ContractRef> refs(List<String> ids, Map<String, PlanModel.PlanContract> contracts) {
        return ids.stream().map(id -> {
            PlanModel.PlanContract c = contracts.get(id);
            if (c == null) {
                throw new IllegalStateException("plan.json references undefined contract: " + id);
            }
            return new ContractRef(c.id(), c.kind(), c.provider(), c.consumers(), c.body());
        }).toList();
    }

    private static List<String> flatten(List<List<String>> order) {
        return order.stream().flatMap(List::stream).toList();
    }
}
