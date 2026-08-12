package sdd.agent.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One repo's slice of an approved plan, as the runner receives it (4C fills it from plan.json + spec). */
public record RepoStep(String repo, Path repoRoot, String subSpec, List<String> requirements,
                       List<String> files, List<ContractRef> provides, List<ContractRef> consumes,
                       List<String> acceptanceChecks) {
    public RepoStep {
        Objects.requireNonNull(repo);
        Objects.requireNonNull(repoRoot);
        Objects.requireNonNull(subSpec);
        requirements = List.copyOf(requirements);
        files = List.copyOf(files);
        provides = List.copyOf(provides);
        consumes = List.copyOf(consumes);
        acceptanceChecks = List.copyOf(acceptanceChecks);
    }
}
