package sdd.cli.explain;

import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.EntityKind;
import sdd.core.kb.EntityMatch;
import sdd.core.kb.KbEntities;
import sdd.core.kb.KbStatus;
import sdd.core.kb.Resolution;
import sdd.core.retrieve.Retriever;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The deterministic-fetch half of {@code sdd explain}'s interpret -&gt; deterministic fetch -&gt;
 * narrate shape. No model participates here: every {@link Section} is built from SQL run against
 * the same KB call 1 validated its entities against. Dispatches on {@link Intent}; Task 4
 * implemented {@code DESCRIBE}, {@code DEPENDENCY_PATH} and {@code SEARCH}. Task 5 adds
 * {@code CONSUMERS} and {@code IMPACT}, plus the mandatory absence-guard caveat
 * ({@link AbsenceGuard}) that both of those two intents — and only those two — carry: "nothing
 * consumes X" is never something the KB can assert as fact (spec Amendment 2026-08-14, rule 2).
 */
public final class EvidenceCollector {
    private EvidenceCollector() {
    }

    public static Evidence collect(Jdbi jdbi, Retriever retriever, RetrievalRequest request) {
        List<Section> sections;
        List<String> caveats = List.of();
        switch (request.intent()) {
            case DESCRIBE -> sections = RepoFacts.of(jdbi, request);
            case DEPENDENCY_PATH -> sections = DependencyFacts.of(jdbi, request);
            case SEARCH -> sections = SearchFacts.of(jdbi, retriever, request);
            case CONSUMERS -> {
                Collected collected = consumers(jdbi, request);
                sections = collected.sections();
                caveats = List.of(AbsenceGuard.caveat(jdbi, collected.reposInPlay()));
            }
            case IMPACT -> {
                Collected collected = impact(jdbi, request);
                sections = collected.sections();
                caveats = List.of(AbsenceGuard.caveat(jdbi, collected.reposInPlay()));
            }
            default -> throw new IllegalStateException("unreachable: " + request.intent());
        }
        return new Evidence(KbStatus.provenance(jdbi), request, sections, caveats);
    }

    /** Sections built so far, plus the repos the absence-guard caveat should be scoped to. */
    private record Collected(List<Section> sections, Set<String> reposInPlay) {
    }

    /**
     * Loops the request's entities exactly like {@code RepoFacts.of}/{@code DependencyFacts.of}
     * do: resolve, cite, dispatch to {@link ConsumerFacts#of}. The caveat's scope is every repo
     * any asked-about entity resolved to — the repos this specific answer is about.
     *
     * <p>Unlike {@code RepoFacts.of}/{@code DependencyFacts.of}, this cites REPO-kind entities
     * too rather than skipping them as a "redundant echo": {@link ConsumerFacts}' four
     * repo-consumer sections (Gradle, API usage, REST, Kafka) can all legitimately be empty for a
     * repo nobody depends on, and without a citation fact that makes {@link Evidence#isEmpty()}
     * read exactly like "this repo is not in the knowledge base" — the confusion {@code sdd
     * explain} exists to prevent. Citing unconditionally keeps repo and non-repo kinds symmetric
     * here instead of special-casing only the zero-consumers case.
     */
    private static Collected consumers(Jdbi jdbi, RetrievalRequest request) {
        List<Section> sections = new ArrayList<>();
        LinkedHashSet<String> reposInPlay = new LinkedHashSet<>();
        for (EntityRef entity : request.entities()) {
            Resolution resolution = KbEntities.resolve(jdbi, entity.kind(), entity.value());
            sections.add(citation(entity, resolution, false));
            reposInPlay.addAll(resolution.repos());
            sections.addAll(ConsumerFacts.of(jdbi, resolution));
        }
        return new Collected(sections, reposInPlay);
    }

    /**
     * Roots come from resolving every entity in the request — {@link ImpactFacts#of} does the
     * rest via {@code Closure.expand}. The caveat's scope is
     * {@link ImpactFacts.Result#affectedRepos()}: roots union {@code added}, not the roots alone
     * (which would under-report once the closure has grown) and not the whole estate (noise).
     */
    private static Collected impact(Jdbi jdbi, RetrievalRequest request) {
        List<Section> sections = new ArrayList<>();
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (EntityRef entity : request.entities()) {
            Resolution resolution = KbEntities.resolve(jdbi, entity.kind(), entity.value());
            if (entity.kind() != EntityKind.REPO) {
                sections.add(citation(entity, resolution, false));
            }
            roots.addAll(resolution.repos());
        }
        ImpactFacts.Result result = ImpactFacts.of(jdbi, roots);
        sections.addAll(result.sections());
        return new Collected(sections, result.affectedRepos());
    }

    /**
     * A citation view over {@link Resolution#matches()} for one non-repo entity: which specific
     * KB rows resolution matched, so a reader can see *why* a repo was pulled in rather than just
     * that it was. {@code matches()} is additive over {@code repos()} and, for {@code TOPIC} and
     * {@code CLASS}, can carry duplicate identical rows (no {@code DISTINCT} in those two
     * queries) — collapsed here before rendering, since this is the first view built on it.
     * {@code REPO}-kind entities are, for {@code DESCRIBE}/{@code IMPACT}/{@code DEPENDENCY_PATH},
     * skipped by their callers: a repo's citation (a repo matching itself) would be a redundant
     * echo of the entity value there, since those intents always emit at least one other fact
     * about the repo regardless (describe's own {@code Repo:} row, impact's roots section, the
     * dependency-path fact itself). {@code CONSUMERS} is the one caller that passes REPO-kind
     * entities through anyway — see {@link #consumers}'s Javadoc for why.
     *
     * <p>{@code withRoleSuffix} appends "(subject)"/"(object)" to the title — meaningful only for
     * {@code DEPENDENCY_PATH}, where the subject/object distinction is the whole point of the
     * question ("why does A depend on B"). {@code RepoFacts} (describe) passes {@code false}:
     * every describe entity is nominally "subject" by default, so the suffix there would be noise,
     * not information.
     */
    static Section citation(EntityRef entity, Resolution resolution, boolean withRoleSuffix) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Fact> facts = new ArrayList<>();
        String source = "(none)";
        for (EntityMatch match : resolution.matches()) {
            source = match.source();
            String key = match.repo() + ' ' + match.detail() + ' ' + match.source();
            if (seen.add(key)) {
                facts.add(new Fact(match.repo() + ": " + match.detail()));
            }
        }
        String title = "Resolved " + kindLabel(entity.kind()) + " '" + entity.value() + "'"
                + (withRoleSuffix ? (entity.object() ? " (object)" : " (subject)") : "");
        return Section.capped(title, source, facts, Section.DEFAULT_LIMIT);
    }

    private static String kindLabel(EntityKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
