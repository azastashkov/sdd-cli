package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Precomputes all 4C-2b propagation work from KB + plan, deterministically and BEFORE the run lock
 * is taken (a planning failure aborts cleanly at exit 4). Publish: providers of MAVEN_LOCAL edges —
 * only those WITH plan steps; a step-less provider ships no change, so consumers keep resolving its
 * already-published artifact. Bumps: PINNED-shaped declarations (non-null, non-SNAPSHOT,
 * non-dynamic) onto stepped providers, on ALL mechanisms — under INCLUDE_BUILD the substitution
 * ignores the requested version, and the release runbook needs the new pin either way. BOM
 * declaration sites are deferred until the KB records declaration files. Known limitation:
 * --resume recomputes this from the live KB against the snapshot plan; re-indexing between
 * pause and resume can shift planned versions mid-run. Snapshotting the propagation map into
 * the run dir is 4C-3b work.
 */
public final class PropagationPlanner {
    private PropagationPlanner() {
    }

    public static Map<String, RepoPropagation> plan(Jdbi jdbi, PlanModel plan, Path runDir,
                                                    Map<String, String> plannedVersions,
                                                    List<String> problems) {
        Path m2 = runDir.resolve("m2");
        Map<String, RepoPropagation> result = new LinkedHashMap<>();
        for (PlanModel.PlanRepo repo : plan.repos()) {
            String name = repo.name();
            List<RepoPropagation.BumpEdit> bumps = new ArrayList<>();
            for (PlanModel.PlanEdge edge : plan.edges()) {
                if (!edge.fromRepo().equals(name) || plan.step(edge.toRepo()).isEmpty()) {
                    continue;   // outbound edges only; an unchanged provider needs no bump
                }
                String planned = plannedVersions.get(edge.toRepo());
                for (DeclaredDeps.Declared dep : DeclaredDeps.between(jdbi, name, edge.toRepo())) {
                    if (dep.declaredVersion() == null || snapshotOrDynamic(dep.declaredVersion())) {
                        continue;   // BOM (deferred) / SNAPSHOT / DYNAMIC — no pin to move
                    }
                    if (planned == null) {
                        problems.add(name + " pins " + dep.group() + ":" + dep.name() + ":"
                                + dep.declaredVersion() + " but no planned version is computable for "
                                + edge.toRepo() + " — re-index or fix the root-module version");
                        continue;
                    }
                    if (!planned.equals(dep.declaredVersion())) {
                        bumps.add(new RepoPropagation.BumpEdit(dep.group(), dep.name(),
                                dep.declaredVersion(), planned));
                    }
                }
            }
            RepoPropagation.PublishSpec publish = null;
            boolean providesMavenLocal = plan.edges().stream().anyMatch(e ->
                    e.toRepo().equals(name) && "MAVEN_LOCAL".equals(e.mechanism()));
            if (providesMavenLocal && plan.step(name).isPresent()) {
                String planned = plannedVersions.get(name);
                if (planned == null) {
                    problems.add(name + " must publish to the run-scoped m2 but no planned version is "
                            + "computable — re-index or fix the root-module version");
                } else {
                    publish = new RepoPropagation.PublishSpec(planned, m2);
                }
            }
            if (!bumps.isEmpty() || publish != null) {
                result.put(name, new RepoPropagation(bumps, publish));
            }
        }
        return result;
    }

    private static boolean snapshotOrDynamic(String version) {
        return version.endsWith("-SNAPSHOT") || version.contains("+")
                || version.startsWith("latest.") || version.startsWith("[") || version.startsWith("(");
    }
}
