package sdd.cli.implement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure scheduling logic over the plan's pre-grouped order + dependency edges. Sequential this phase;
 *  parallel-within-level is 4C-3. */
public final class Scheduler {
    private Scheduler() {
    }

    public static List<String> sequence(List<List<String>> order) {
        return order.stream().flatMap(List::stream).toList();
    }

    public static Set<String> upstreams(String repo, List<PlanModel.PlanEdge> edges) {
        // Real plan.json edge direction (PlanJson.java:73-104): from_repo = consumer, to_repo = provider.
        // A repo's upstream providers are the to_repo of edges where THIS repo is the consuming from_repo.
        Set<String> up = new LinkedHashSet<>();
        for (PlanModel.PlanEdge edge : edges) {
            if (edge.fromRepo().equals(repo)) {
                up.add(edge.toRepo());
            }
        }
        return up;
    }

    public static boolean blockedByUpstream(String repo, List<PlanModel.PlanEdge> edges, RunState state) {
        // Transitive upstream closure: a repo is blocked iff ANY provider it (transitively) depends on
        // is FAILED or SKIPPED_UPSTREAM_FAILED. Walking the closure (not just direct upstreams) propagates
        // a failure through step-less intermediate repos (bom/bump-only sites not tracked in RunState).
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(upstreams(repo, edges));
        while (!queue.isEmpty()) {
            String up = queue.poll();
            if (!visited.add(up)) {
                continue;
            }
            RepoState s = state.stateOf(up);
            if (s == RepoState.FAILED || s == RepoState.SKIPPED_UPSTREAM_FAILED) {
                return true;
            }
            queue.addAll(upstreams(up, edges));
        }
        return false;
    }

    /** Layered batching of the plan's order units for parallel-within-level execution (4C-3b).
     *  plan.order() is a valid but SERIALIZED topo order (one unit per entry except SCC cycles);
     *  true parallelism is recomputed here from the edges: a layer is every unit whose provider
     *  units have all been placed in earlier layers. Units are atomic — a multi-member cycle unit
     *  is scheduled as one entry and executed internally sequentially by the orchestrator.
     *  Within a layer, units whose INCLUDE_BUILD closures intersect are MERGED into one unit:
     *  two same-layer consumers composing the same provider checkout via {@code --include-build}
     *  would otherwise write that provider's build/.gradle state concurrently. Merged units reuse
     *  the existing unit-atomicity semantics (internally sequential). MAVEN_LOCAL consumers stay
     *  parallel — m2 resolution is read-only. */
    public static List<List<List<String>>> levels(List<List<String>> order,
                                                  List<PlanModel.PlanEdge> edges) {
        Map<String, Integer> unitOf = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            for (String repo : order.get(i)) {
                unitOf.put(repo, i);
            }
        }
        Map<Integer, Set<Integer>> providers = new HashMap<>();
        for (PlanModel.PlanEdge edge : edges) {
            Integer consumer = unitOf.get(edge.fromRepo());
            Integer provider = unitOf.get(edge.toRepo());
            if (consumer != null && provider != null && !consumer.equals(provider)) {
                providers.computeIfAbsent(consumer, k -> new HashSet<>()).add(provider);
            }
        }
        List<List<List<String>>> layers = new ArrayList<>();
        Set<Integer> placed = new HashSet<>();
        while (placed.size() < order.size()) {
            List<List<String>> layer = new ArrayList<>();
            List<Integer> ready = new ArrayList<>();
            for (int i = 0; i < order.size(); i++) {
                if (!placed.contains(i) && placed.containsAll(providers.getOrDefault(i, Set.of()))) {
                    ready.add(i);
                }
            }
            if (ready.isEmpty()) {
                throw new IllegalStateException("execution order units form a cycle — plan is invalid");
            }
            layer.addAll(mergeSharedIncludeBuilds(ready, order, edges));
            placed.addAll(ready);
            layers.add(layer);
        }
        return layers;
    }

    /** Union-find merge of same-layer units whose INCLUDE_BUILD closures intersect. Roots are the
     *  smallest original unit index, and groups are emitted (and concatenated) in original index
     *  order, so the result is deterministic. Units with no INCLUDE_BUILD edges keep closure =
     *  {their own members} and only merge when another unit's closure contains them. */
    private static List<List<String>> mergeSharedIncludeBuilds(List<Integer> ready,
                                                               List<List<String>> order,
                                                               List<PlanModel.PlanEdge> edges) {
        int n = ready.size();
        List<Set<String>> closures = new ArrayList<>(n);
        for (int i : ready) {
            closures.add(includeBuildClosure(order.get(i), edges));
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!java.util.Collections.disjoint(closures.get(i), closures.get(j))) {
                    int ri = find(parent, i);
                    int rj = find(parent, j);
                    if (ri != rj) {
                        parent[Math.max(ri, rj)] = Math.min(ri, rj);   // smallest index wins → determinism
                    }
                }
            }
        }
        Map<Integer, List<String>> merged = new java.util.LinkedHashMap<>();
        for (int k = 0; k < n; k++) {
            merged.computeIfAbsent(find(parent, k), r -> new ArrayList<>()).addAll(order.get(ready.get(k)));
        }
        return new ArrayList<>(merged.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Repo names transitively reachable from any unit member over INCLUDE_BUILD edges — the same
     *  walk as {@code Propagation.includeBuildArgs}, but collecting names and INCLUDING the members
     *  themselves (a provider merges with a same-layer consumer that composes its checkout). */
    private static Set<String> includeBuildClosure(List<String> unit, List<PlanModel.PlanEdge> edges) {
        Set<String> closure = new LinkedHashSet<>(unit);
        Deque<String> queue = new ArrayDeque<>(unit);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (PlanModel.PlanEdge edge : edges) {
                if (edge.fromRepo().equals(current) && "INCLUDE_BUILD".equals(edge.mechanism())
                        && closure.add(edge.toRepo())) {
                    queue.add(edge.toRepo());
                }
            }
        }
        return closure;
    }
}
