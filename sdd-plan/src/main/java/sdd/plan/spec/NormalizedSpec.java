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
                             List<String> attachments, List<String> sources) {
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
        sources = List.copyOf(sources);
    }

    /** Pre-{@code sources} 13-argument shape, kept so every existing construction site (main and
     *  test) keeps compiling untouched: {@code sources} defaults to the empty list, i.e. no
     *  provenance bullets. Only the Task-3 fetchers populate {@code sources} — the model-driven
     *  normalizers (Confluence, Jira) never do, so their construction sites have no reason to
     *  change either. */
    public NormalizedSpec(String id, String title, String owner, String status,
                          String goal, String background,
                          List<SpecItem> requirements, List<SpecItem> acceptance,
                          List<SpecItem> constraints, List<Touchpoint> touchpoints,
                          List<String> outOfScope, List<SpecItem> openQuestions,
                          List<String> attachments) {
        this(id, title, owner, status, goal, background, requirements, acceptance, constraints,
                touchpoints, outOfScope, openQuestions, attachments, List.of());
    }
}
