package sdd.plan.spec;

import java.util.List;
import java.util.Objects;

/**
 * The internal structured spec model — the ONLY shape anything downstream of ingestion
 * consumes, regardless of where the spec came from (canonical markdown, Confluence, ...).
 */
public record NormalizedSpec(String id, String title, String owner, String status,
                             String goal, String background,
                             List<SpecItem> requirements, List<SpecItem> acceptance,
                             List<SpecItem> constraints, List<Touchpoint> touchpoints,
                             List<String> outOfScope, List<SpecItem> openQuestions,
                             List<String> attachments) {
    public NormalizedSpec {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(status);
        Objects.requireNonNull(goal);
        Objects.requireNonNull(background);
        requirements = List.copyOf(requirements);
        acceptance = List.copyOf(acceptance);
        constraints = List.copyOf(constraints);
        touchpoints = List.copyOf(touchpoints);
        outOfScope = List.copyOf(outOfScope);
        openQuestions = List.copyOf(openQuestions);
        attachments = List.copyOf(attachments);
    }
}
