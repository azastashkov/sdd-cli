package sdd.cli.review;

import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.Scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Gate-2's mutable per-run decision model (design line 68): after {@code sdd implement} goes
 * green, a human approves/rejects/redoes each repo. The cross-repo invariants live here rather
 * than in the CLI layer: a repo may only be approved once its own run SUCCEEDED and every
 * transitive upstream (provider) is clear of REJECTED/REDO; rejecting or redoing a repo downgrades
 * every transitive downstream (consumer) that was already APPROVED back to PENDING, clearing its
 * reason — the approval was made against an upstream that is no longer trustworthy, so the human
 * re-decides it rather than the tool silently keeping a stale approval or auto-rejecting a repo
 * that never itself failed.
 */
public final class Decisions {
    private final Map<String, DecisionRecord> records;

    public Decisions(Map<String, DecisionRecord> initial) {
        this.records = new LinkedHashMap<>(initial);
    }

    public static Decisions empty() {
        return new Decisions(Map.of());
    }

    /** PENDING for any repo with no recorded decision — the implicit starting state. */
    public Decision of(String repo) {
        DecisionRecord record = records.get(repo);
        return record == null ? Decision.PENDING : record.decision();
    }

    public String reasonOf(String repo) {
        DecisionRecord record = records.get(repo);
        return record == null ? "" : record.reason();
    }

    /** Sorted so {@code decisions.json} (and anything rendered from it) is stable across writes. */
    public Map<String, DecisionRecord> asMap() {
        return new TreeMap<>(records);
    }

    /** {@code downgraded} is sorted and non-empty only for reject/redo — approve never downgrades
     *  anything, it either applies cleanly to the single repo or refuses. */
    public record Outcome(boolean applied, String message, List<String> downgraded) {
        private static Outcome refused(String message) {
            return new Outcome(false, message, List.of());
        }

        private static Outcome applied(String message, List<String> downgraded) {
            return new Outcome(true, message, downgraded);
        }
    }

    public Outcome approve(String repo, PlanModel plan, RunState state) {
        RepoState repoState = state.stateOf(repo);
        if (repoState == null) {
            return Outcome.refused(repo + " has no run state");
        }
        if (repoState != RepoState.SUCCEEDED) {
            return Outcome.refused(repo + " is " + repoState + ", only SUCCEEDED repos can be approved");
        }
        String blocker = nearestBlockedUpstream(repo, plan);
        if (blocker != null) {
            return Outcome.refused(repo + " cannot be approved while upstream " + blocker
                    + " is " + of(blocker));
        }
        records.put(repo, new DecisionRecord(Decision.APPROVED, ""));
        return Outcome.applied(repo + " approved", List.of());
    }

    public Outcome reject(String repo, PlanModel plan, String reason) {
        records.put(repo, new DecisionRecord(Decision.REJECTED, reason == null ? "" : reason));
        return Outcome.applied(repo + " rejected", downgradeDownstream(repo, plan));
    }

    public Outcome redo(String repo, PlanModel plan, String reason) {
        records.put(repo, new DecisionRecord(Decision.REDO, reason == null ? "" : reason));
        return Outcome.applied(repo + " marked for redo", downgradeDownstream(repo, plan));
    }

    /** Breadth-first over the transitive provider closure (Scheduler.upstreams gives only the
     *  direct providers of one repo); returns the nearest repo that is REJECTED or REDO, or null
     *  when the whole closure is clear. */
    private String nearestBlockedUpstream(String repo, PlanModel plan) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(Scheduler.upstreams(repo, plan.edges()));
        while (!queue.isEmpty()) {
            String up = queue.poll();
            if (!visited.add(up)) {
                continue;
            }
            Decision decision = of(up);
            if (decision == Decision.REJECTED || decision == Decision.REDO) {
                return up;
            }
            queue.addAll(Scheduler.upstreams(up, plan.edges()));
        }
        return null;
    }

    /**
     * The transitive consumer closure of {@code repo}, sorted and excluding {@code repo} itself.
     * There is no Scheduler helper for this direction — upstreams() only walks providers — so this
     * reads plan.edges() directly: fromRepo is the consumer, toRepo the provider
     * ({@link PlanModel.PlanEdge}), so a repo's direct downstream is every edge's fromRepo where
     * toRepo equals it. Public because {@code redo} re-verifies exactly this set (design line 67).
     */
    public static List<String> transitiveDownstream(String repo, PlanModel plan) {
        Set<String> visited = new HashSet<>();
        List<String> closure = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(directDownstream(repo, plan));
        while (!queue.isEmpty()) {
            String down = queue.poll();
            if (!visited.add(down) || down.equals(repo)) {   // a cycle must not re-list the origin
                continue;
            }
            closure.add(down);
            queue.addAll(directDownstream(down, plan));
        }
        closure.sort(String::compareTo);
        return closure;
    }

    /** Downgrades every APPROVED repo in the consumer closure back to PENDING (clearing its
     *  reason), returning the ones actually downgraded. */
    private List<String> downgradeDownstream(String repo, PlanModel plan) {
        List<String> downgraded = new ArrayList<>();
        for (String down : transitiveDownstream(repo, plan)) {
            if (of(down) == Decision.APPROVED) {
                records.put(down, new DecisionRecord(Decision.PENDING, ""));
                downgraded.add(down);
            }
        }
        return downgraded;
    }

    private static List<String> directDownstream(String repo, PlanModel plan) {
        List<String> consumers = new ArrayList<>();
        for (PlanModel.PlanEdge edge : plan.edges()) {
            if (edge.toRepo().equals(repo)) {
                consumers.add(edge.fromRepo());
            }
        }
        return consumers;
    }
}
