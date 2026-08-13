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
                               String compat) {
        public PlanContract {
            consumers = List.copyOf(consumers);
        }
    }

    public record PlanStep(String repo, List<String> covers, String versionAction, List<String> provides,
                           List<String> consumes, List<String> files, List<String> verification, String subSpec) {
        public PlanStep {
            covers = List.copyOf(covers);
            provides = List.copyOf(provides);
            consumes = List.copyOf(consumes);
            files = List.copyOf(files);
            verification = List.copyOf(verification);
        }
    }

    public Optional<PlanRepo> repo(String name) {
        return repos.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    public Optional<PlanStep> step(String repo) {
        return steps.stream().filter(s -> s.repo().equals(repo)).findFirst();
    }
}
