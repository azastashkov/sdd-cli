package sdd.cli.implement;

import sdd.agent.run.RepoStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconciles a persisted run state for --resume (design line 60: branch HEADs are the checkpoints).
 * SUCCEEDED survives only if its branch still points at the recorded checkpoint; FAILED stays FAILED
 * (two attempts already spent); everything else re-runs from PENDING — startBranch hard-resets and
 * cleans, so whatever a crash or pause left in the tree is irrelevant.
 */
public final class Resume {
    public record Prep(RunState state, List<String> problems) {
        public Prep {
            problems = List.copyOf(problems);
        }
    }

    private Resume() {
    }

    public static Prep prepare(RunState persisted, Map<String, RepoStep> steps) {
        List<String> problems = new ArrayList<>();
        List<RepoRun> reconciled = new ArrayList<>();
        for (RepoRun repo : persisted.repos()) {
            switch (repo.state()) {
                case SUCCEEDED -> {
                    RepoStep step = steps.get(repo.repo());
                    String head = step == null || repo.branch() == null
                            ? "" : RunGit.branchHead(step.repoRoot(), repo.branch());
                    if (!head.equals(repo.checkpointSha())) {
                        problems.add(repo.repo() + ": checkpoint " + repo.checkpointSha()
                                + " is no longer the HEAD of " + repo.branch() + " (found "
                                + (head.isEmpty() ? "no branch" : head) + ") — cannot resume this run");
                    }
                    reconciled.add(repo);
                }
                case FAILED -> reconciled.add(repo);
                default -> reconciled.add(new RepoRun(repo.repo(), RepoState.PENDING, repo.branch(), null, ""));
            }
        }
        return new Prep(new RunState(persisted.runId(), reconciled, null, persisted.tokensSpent()), problems);
    }
}
