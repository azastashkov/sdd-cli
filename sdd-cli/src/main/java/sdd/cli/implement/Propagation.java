package sdd.cli.implement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes the "invisible" Gradle substitution flags for a consumer repo (design line 61, primary path):
 * one {@code --include-build <provider checkout>} per INCLUDE_BUILD inbound edge. plan.json edge direction
 * is {@code from_repo = consumer, to_repo = provider} (same as {@code Scheduler.upstreams}), so a repo's
 * providers are the {@code toRepo} of edges where it is the {@code fromRepo}. MAVEN_LOCAL / NONE edges and
 * the version-bump path are 4C-2b.
 */
public final class Propagation {
    private Propagation() {
    }

    public static List<String> includeBuildArgs(String repo, List<PlanModel.PlanEdge> edges,
                                                Map<String, Path> repoPaths) {
        List<String> args = new ArrayList<>();
        for (PlanModel.PlanEdge edge : edges) {
            if (edge.fromRepo().equals(repo) && "INCLUDE_BUILD".equals(edge.mechanism())) {
                Path provider = repoPaths.get(edge.toRepo());
                if (provider != null) {
                    args.add("--include-build");
                    args.add(provider.toAbsolutePath().toString());
                }
            }
        }
        return args;
    }
}
