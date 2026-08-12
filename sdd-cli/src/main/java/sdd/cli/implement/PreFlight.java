package sdd.cli.implement;

import sdd.agent.run.RepoStep;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gates a run before any repo executes: clean trees at the pinned base SHAs, with runnable wrappers.
 *  Full M8 staleness recovery (re-index / auto-advance on drift) is 4C-3; this phase hard-fails on drift. */
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
            if (!Files.isDirectory(root)) {
                problems.add(repo + ": checkout not found at " + root);
                continue;
            }
            if (!Files.isExecutable(root.resolve("gradlew"))) {
                problems.add(repo + ": no executable gradle wrapper at " + root.resolve("gradlew"));
            }
            String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
            try {
                if (!RunGit.isClean(root)) {
                    problems.add(repo + ": working tree is dirty");
                }
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
}
