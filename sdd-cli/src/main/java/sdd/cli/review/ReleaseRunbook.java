package sdd.cli.review;

import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.Scheduler;

import java.util.List;

/**
 * The Gate-2 release runbook (design line 66): the order a human releases the estate in, derived
 * from the plan's topological order and each repo's inbound consumers.
 */
public final class ReleaseRunbook {
    private ReleaseRunbook() {
    }

    public static String render(PlanModel plan, RunState state) {
        StringBuilder md = new StringBuilder();
        int step = 1;
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoState repoState = state.stateOf(repo);
            md.append(step++).append(". ").append(repo);
            if (repoState != RepoState.SUCCEEDED) {
                md.append(" — not releasable (").append(repoState == null ? "UNKNOWN" : repoState)
                        .append(")\n");
                continue;
            }
            String sha = state.repos().stream().filter(r -> r.repo().equals(repo)).findFirst()
                    .map(r -> r.checkpointSha() == null ? "" : r.checkpointSha()).orElse("");
            md.append(" — release from ").append(sha.isEmpty() ? "(no checkpoint)" : sha);
            List<String> pinned = plan.edges().stream()
                    .filter(e -> e.toRepo().equals(repo) && "PINNED".equals(e.mode()))
                    .map(PlanModel.PlanEdge::fromRepo).sorted().toList();
            List<String> others = plan.edges().stream()
                    .filter(e -> e.toRepo().equals(repo) && !"PINNED".equals(e.mode()))
                    .map(PlanModel.PlanEdge::fromRepo).sorted().toList();
            if (!pinned.isEmpty()) {
                md.append(", then merge pinned dependents: ").append(String.join(", ", pinned));
            }
            if (!others.isEmpty()) {
                md.append(pinned.isEmpty() ? ", " : "; ").append("dependents pick up on republish: ")
                        .append(String.join(", ", others));
            }
            if (pinned.isEmpty() && others.isEmpty()) {
                md.append(" — no downstream release step");
            }
            md.append('\n');
        }
        return md.toString();
    }
}
