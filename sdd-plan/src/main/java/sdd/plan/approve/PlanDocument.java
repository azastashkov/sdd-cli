package sdd.plan.approve;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The parsed Gate-1 plan.md — exactly what the human approved, structure-checked only. */
public record PlanDocument(String specId, int planVersion, String summary,
                           List<PlanQuestion> questions, List<PlanRepo> affected,
                           List<PlanExcluded> excluded, List<List<String>> order,
                           List<PlanContract> contracts, List<PlanStep> steps,
                           List<String> notes) {
    public PlanDocument {
        Objects.requireNonNull(specId);
        Objects.requireNonNull(summary);
        questions = List.copyOf(questions);
        affected = List.copyOf(affected);
        excluded = List.copyOf(excluded);
        List<List<String>> copiedOrder = new ArrayList<>();
        for (List<String> unit : order) {
            copiedOrder.add(List.copyOf(unit));
        }
        order = List.copyOf(copiedOrder);
        contracts = List.copyOf(contracts);
        steps = List.copyOf(steps);
        notes = List.copyOf(notes);
    }

    /** resolution is null when the human has not written one. */
    public record PlanQuestion(int number, boolean blocking, String text, String resolution) {
        public PlanQuestion {
            Objects.requireNonNull(text);
        }
    }

    public record PlanRepo(String repo, String role, String annotation, List<String> covers,
                           String why) {
        public PlanRepo {
            Objects.requireNonNull(repo);
            Objects.requireNonNull(role);
            Objects.requireNonNull(annotation);
            covers = List.copyOf(covers);
            Objects.requireNonNull(why);
        }
    }

    public record PlanExcluded(String repo, String detail) {
        public PlanExcluded {
            Objects.requireNonNull(repo);
            Objects.requireNonNull(detail);
        }
    }

    public record PlanContract(String id, String kind, String provider, List<String> consumers,
                               String body, String compat, List<String> declared) {
        public PlanContract {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(provider);
            consumers = List.copyOf(consumers);
            Objects.requireNonNull(body);
            declared = List.copyOf(declared);
        }
    }

    public record PlanStep(String repo, List<String> covers, String versionAction,
                           List<String> provides, List<String> consumes, List<String> files,
                           List<String> verification, String subSpec) {
        public PlanStep {
            Objects.requireNonNull(repo);
            covers = List.copyOf(covers);
            Objects.requireNonNull(versionAction);
            provides = List.copyOf(provides);
            consumes = List.copyOf(consumes);
            files = List.copyOf(files);
            verification = List.copyOf(verification);
            Objects.requireNonNull(subSpec);
        }
    }
}
