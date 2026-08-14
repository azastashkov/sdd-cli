package sdd.cli.explain;

import org.jdbi.v3.core.Jdbi;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.Closure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code impact} intent: "what breaks if this changes", answered by {@link Closure#expand} alone
 * — the deterministic reverse closure over the Gradle dependency graph, plus BOM declaration
 * sites, REST/Kafka contract hops and SCC cycles. No model participates: {@link #of} takes no
 * {@code ChatModel}, by signature, and this class never reaches for {@code ImpactAnalysis} /
 * {@code ModelSeeder} — that pipeline would add a third model call and let a model contribute
 * repos to what must be a deterministic closure (spec Amendment 2026-08-14).
 *
 * <p>{@link Closure.Expansion#added()} deliberately omits the root repos — {@code affected} is
 * seeded with them internally but only newly-discovered repos are returned. An impact answer that
 * silently drops the very repo the question was about is exactly the defect this class guards
 * against, so {@link #of} renders the roots as their own section, explicitly, before {@code added}.
 */
final class ImpactFacts {
    private static final String CLOSURE_SOURCE = "v_repo_dep_edge + dep_edge + rest_call_edge + kafka_role (Closure.expand)";

    /**
     * {@code sections} is what {@link EvidenceCollector} appends to the evidence bundle;
     * {@code affectedRepos} — roots union {@code added} — is what the absence guard's caveat is
     * scoped to: the exact, bounded set of repos this specific answer talks about. Neither "the
     * whole estate" (noise on a small closure) nor "the roots alone" (under-reports once the
     * closure has grown) is right; this is the set the rendered answer actually asserts things
     * about, no more and no less.
     */
    record Result(List<Section> sections, Set<String> affectedRepos) {
    }

    private ImpactFacts() {
    }

    static Result of(Jdbi jdbi, Set<String> roots) {
        Closure.Expansion expansion = Closure.expand(jdbi, roots);

        List<Section> sections = new ArrayList<>();
        sections.add(rootsSection(roots));
        sections.add(addedSection(expansion));
        sections.add(cyclesSection(expansion));
        sections.add(warningsSection(expansion));

        LinkedHashSet<String> affected = new LinkedHashSet<>(new TreeSet<>(roots));
        for (AffectedRepo repo : expansion.added()) {
            affected.add(repo.repo());
        }
        return new Result(sections, affected);
    }

    private static Section rootsSection(Set<String> roots) {
        List<Fact> facts = new TreeSet<>(roots).stream().map(Fact::new).toList();
        return Section.capped("Impact: root repo(s)", "repo", facts, Section.DEFAULT_LIMIT);
    }

    private static Section addedSection(Closure.Expansion expansion) {
        List<Fact> facts = expansion.added().stream().map(ImpactFacts::describe).toList();
        return Section.capped("Impact: affected repos", CLOSURE_SOURCE, facts, Section.DEFAULT_LIMIT);
    }

    private static Fact describe(AffectedRepo repo) {
        return new Fact(repo.repo() + " — " + repo.role() + "/" + repo.annotation()
                + " — why: " + String.join("; ", repo.reasons()));
    }

    private static Section cyclesSection(Closure.Expansion expansion) {
        List<Fact> facts = expansion.cycles().stream().map(Fact::new).toList();
        return Section.capped("Impact: dependency cycles", CLOSURE_SOURCE, facts, Section.DEFAULT_LIMIT);
    }

    private static Section warningsSection(Closure.Expansion expansion) {
        List<Fact> facts = expansion.warnings().stream().map(Fact::new).toList();
        return Section.capped("Impact: warnings", CLOSURE_SOURCE, facts, Section.DEFAULT_LIMIT);
    }
}
