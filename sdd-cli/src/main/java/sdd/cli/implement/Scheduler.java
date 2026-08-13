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
     *  is scheduled as one entry and executed internally sequentially by the orchestrator. */
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
            for (int i : ready) {
                layer.add(order.get(i));
            }
            placed.addAll(ready);
            layers.add(layer);
        }
        return layers;
    }
}
