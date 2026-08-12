package sdd.cli.implement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
}
