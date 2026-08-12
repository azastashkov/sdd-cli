package sdd.cli.implement;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the "invisible" Gradle substitution flags for a consumer repo (design line 61, primary path):
 * one {@code --include-build <provider checkout>} per repo transitively reachable from the consumer by
 * following only INCLUDE_BUILD edges. plan.json edge direction is {@code from_repo = consumer,
 * to_repo = provider} (same as {@code Scheduler.upstreams}), so a repo's direct providers are the
 * {@code toRepo} of edges where it is the {@code fromRepo}. The transitive closure is required, not just
 * direct providers: Gradle composite substitution is global across a composite, so if a direct provider
 * itself has an INCLUDE_BUILD provider (a 3-tier chain), that provider's provider must be included too, or
 * the direct provider builds inside the consumer's composite against a stale binary of its own dependency.
 * A MAVEN_LOCAL (or other non-INCLUDE_BUILD) hop breaks the chain at that point. MAVEN_LOCAL / NONE edges
 * and the version-bump path are 4C-2b.
 */
public final class Propagation {
    private Propagation() {
    }

    public static List<String> includeBuildArgs(String repo, List<PlanModel.PlanEdge> edges,
                                                Map<String, Path> repoPaths) {
        Set<String> visited = new LinkedHashSet<>();
        visited.add(repo);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(repo);
        Set<String> providers = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (PlanModel.PlanEdge edge : edges) {
                if (edge.fromRepo().equals(current) && "INCLUDE_BUILD".equals(edge.mechanism())) {
                    providers.add(edge.toRepo());
                    if (visited.add(edge.toRepo())) {
                        queue.add(edge.toRepo());
                    }
                }
            }
        }

        Set<Path> providerPaths = new LinkedHashSet<>();
        for (String provider : providers) {
            Path path = repoPaths.get(provider);
            if (path != null) {
                providerPaths.add(path);
            }
        }

        List<String> args = new ArrayList<>();
        for (Path provider : providerPaths) {
            args.add("--include-build");
            args.add(provider.toAbsolutePath().toString());
        }
        return args;
    }
}
