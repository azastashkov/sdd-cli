package sdd.cli.implement;

import sdd.agent.run.RepoStep;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gates a run before any repo executes: clean trees at the pinned base SHAs, with runnable wrappers.
 *  Full M8 staleness recovery (re-index / auto-advance on drift) is 4C-3; this phase hard-fails on drift
 *  (fresh runs; resume trusts checkpoints instead). */
public final class PreFlight {
    private PreFlight() {
    }

    public record Result(boolean ok, List<String> problems) {
        public Result {
            problems = List.copyOf(problems);
        }
    }

    public static Result check(Map<String, RepoStep> steps, PlanModel plan) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, RepoStep> entry : steps.entrySet()) {
            String repo = entry.getKey();
            var root = entry.getValue().repoRoot();
            if (!environment(repo, entry.getValue(), plan, problems)) {
                continue;
            }
            try {
                if (!RunGit.isClean(root)) {
                    problems.add(repo + ": working tree is dirty");
                }
                String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
                String head = RunGit.head(root);
                if (!base.isEmpty() && !head.equals(base)) {
                    problems.add(repo + ": HEAD " + head + " has drifted from the plan base " + base
                            + " — re-approve the plan");
                }
            } catch (IllegalStateException e) {
                problems.add(repo + ": " + e.getMessage());
            }
        }
        return new Result(problems.isEmpty(), problems);
    }

    /** Resume gate: environment-only checks for the repos that will actually run. Tree state is NOT
     *  checked — startBranch hard-resets to base and cleans untracked debris on entry. */
    public static Result checkResume(Map<String, RepoStep> steps, PlanModel plan, RunState state) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, RepoStep> entry : steps.entrySet()) {
            if (state.stateOf(entry.getKey()) != RepoState.PENDING) {
                continue;
            }
            environment(entry.getKey(), entry.getValue(), plan, problems);
        }
        return new Result(problems.isEmpty(), problems);
    }

    /** Checkout exists, the repo's toolchain is usable, base SHA is present. Returns false (and has
     *  already recorded a problem) when the checkout itself is missing, since the rest need it. */
    private static boolean environment(String repo, RepoStep step, PlanModel plan, List<String> problems) {
        var root = step.repoRoot();
        if (!Files.isDirectory(root)) {
            problems.add(repo + ": checkout not found at " + root);
            return false;
        }
        switch (sdd.core.toolchain.Toolchain.detect(root)) {
            case GRADLE -> {
                if (!Files.isExecutable(root.resolve("gradlew"))) {
                    problems.add(repo + ": no executable gradle wrapper at " + root.resolve("gradlew"));
                }
            }
            case NPM -> {
                // sdd must never run `npm install` itself: it mutates the tree mid-run, is slow,
                // and can reach the network at an arbitrary moment. Refusing with an actionable
                // message is better than every verification failing with `sh: vitest: command not
                // found` while the agent burns its whole escalation ladder on an environment
                // problem it cannot fix.
                if (!Files.isDirectory(root.resolve("node_modules"))) {
                    problems.add(repo + ": node_modules is not installed at " + root
                            + " — run npm install (or npm ci) before sdd implement");
                }
            }
            case UNKNOWN -> problems.add(repo + ": cannot determine build system at " + root
                    + " (no gradle build files, no package.json)");
        }
        String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
        if (base.isEmpty()) {
            problems.add(repo + ": plan has no base SHA for this repo");
        }
        return true;
    }
}
