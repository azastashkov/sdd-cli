package sdd.cli.implement;

import java.util.List;
import java.util.Optional;

/** The approved plan.json as an in-memory model (sdd-cli mirror of sdd-plan's package-private records). */
public record PlanModel(String specId, int planVersion, String specSha256, String planSha256,
                        List<PlanRepo> repos, List<List<String>> order, List<PlanEdge> edges,
                        List<PlanContract> contracts, List<PlanStep> steps) {

    public PlanModel {
        repos = List.copyOf(repos);
        order = order.stream().map(List::copyOf).toList();
        edges = List.copyOf(edges);
        contracts = List.copyOf(contracts);
        steps = List.copyOf(steps);
    }

    public record PlanRepo(String name, String role, String annotation, String versionAction, String baseSha) {
    }

    public record PlanEdge(String fromRepo, String toRepo, String mode, String mechanism) {
    }

    public record PlanContract(String id, String kind, String provider, List<String> consumers, String body,
                               String compat, List<String> declared) {
        public PlanContract {
            consumers = List.copyOf(consumers);
            declared = List.copyOf(declared);
        }
    }

    /**
     * @param openspec the raw {@code - openspec:} block lines. Empty for every plan.json written
     *                 before the OpenSpec export existed — {@code PlanJsonReader} reads a missing
     *                 key as an empty list, so a frozen run from before this feature still loads
     *                 and still resumes.
     */
    public record PlanStep(String repo, List<String> covers, String versionAction, List<String> provides,
                           List<String> consumes, List<String> files, List<String> verification,
                           String subSpec, List<String> openspec) {
        /** Pre-OpenSpec shape, so every existing construction site compiles untouched. */
        public PlanStep(String repo, List<String> covers, String versionAction, List<String> provides,
                        List<String> consumes, List<String> files, List<String> verification,
                        String subSpec) {
            this(repo, covers, versionAction, provides, consumes, files, verification, subSpec,
                    List.of());
        }

        public PlanStep {
            covers = List.copyOf(covers);
            provides = List.copyOf(provides);
            consumes = List.copyOf(consumes);
            files = List.copyOf(files);
            verification = List.copyOf(verification);
            openspec = List.copyOf(openspec);
        }
    }

    public Optional<PlanRepo> repo(String name) {
        return repos.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    public Optional<PlanStep> step(String repo) {
        return steps.stream().filter(s -> s.repo().equals(repo)).findFirst();
    }
}
