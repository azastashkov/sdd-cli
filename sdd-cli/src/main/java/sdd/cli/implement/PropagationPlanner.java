package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;
import sdd.core.toolchain.Mechanism;

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
 * declaration sites are deferred until the KB records declaration files. --resume reads the
 * frozen snapshot from the run dir (propagation.json); live recomputation happens only for
 * pre-4C-3b run dirs.
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
            // npm: what this repo must pack for its consumers, and what must be overlaid into it
            // before it builds. The two are computed together because they are the two halves of
            // the same substitution, seen from either end of an edge.
            Path npmStore = runDir.resolve("npm-store");
            List<RepoPropagation.PackSpec> packs = new ArrayList<>();
            List<RepoPropagation.OverlaySpec> overlays = new ArrayList<>();
            boolean providesOverlay = plan.edges().stream().anyMatch(e ->
                    e.toRepo().equals(name) && Mechanism.of(e.mechanism()) == Mechanism.NPM_OVERLAY);
            if (providesOverlay && plan.step(name).isPresent()) {
                String planned = plannedVersions.get(name);
                for (NpmPackages.Publishable publishable : NpmPackages.publishableOf(jdbi, name)) {
                    if (planned == null) {
                        problems.add(name + " must be packed for its npm consumers but no planned "
                                + "version is computable — re-index or set a version on its package");
                        break;
                    }
                    packs.add(new RepoPropagation.PackSpec(publishable.packageName(),
                            publishable.packageDir(), planned, npmStore));
                    // The overlay installs the provider's own code, not its runtime dependencies.
                    // A provider that has any will fail at import time in a consumer whose tree
                    // does not already contain them — reported here, before a run, rather than
                    // discovered as a MODULE_NOT_FOUND halfway through one.
                    List<String> runtimeDeps = NpmPackages.runtimeDependencies(publishable.packageDir());
                    if (!runtimeDeps.isEmpty()) {
                        problems.add("warning: " + name + "'s package " + publishable.packageName()
                                + " declares runtime dependencies (" + String.join(", ", runtimeDeps)
                                + ") — the run-scoped overlay does not install them, so a consumer"
                                + " build may fail to resolve them");
                    }
                }
            }
            for (PlanModel.PlanEdge edge : plan.edges()) {
                if (!edge.fromRepo().equals(name)
                        || Mechanism.of(edge.mechanism()) != Mechanism.NPM_OVERLAY
                        || plan.step(edge.toRepo()).isEmpty()) {
                    continue;   // an unchanged provider ships nothing to overlay
                }
                for (NpmPackages.Publishable publishable : NpmPackages.publishableOf(jdbi, edge.toRepo())) {
                    overlays.add(new RepoPropagation.OverlaySpec(publishable.packageName(),
                            edge.toRepo(), npmStore));
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
            if (!bumps.isEmpty() || publish != null || !packs.isEmpty() || !overlays.isEmpty()) {
                result.put(name, new RepoPropagation(bumps, publish, packs, overlays));
            }
        }
        return result;
    }

    private static boolean snapshotOrDynamic(String version) {
        return version.endsWith("-SNAPSHOT") || version.contains("+")
                || version.startsWith("latest.") || version.startsWith("[") || version.startsWith("(");
    }
}
