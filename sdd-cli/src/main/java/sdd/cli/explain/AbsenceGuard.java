package sdd.cli.explain;

import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The mandatory, deterministic absence guard (spec Amendment 2026-08-14, rule 2): "nothing
 * consumes X" is the single most dangerous sentence {@code sdd explain} can produce, and the KB
 * cannot support it — a {@code rest_client} row may have no resolvable path and
 * {@code kafka_topic.resolution} can be {@code DYNAMIC}, so an unresolved caller is invisible to
 * every {@code consumers}/{@code impact} query rather than genuinely absent from the estate. This
 * is code, not a prompt instruction: {@link EvidenceCollector} calls {@link #caveat} on every
 * {@code CONSUMERS}/{@code IMPACT} request and appends its result to {@link Evidence#caveats()}
 * unconditionally, so the caveat is present even when both counts are zero.
 *
 * <p><strong>Scope.</strong> Neither the whole estate (noise once only a few repos are actually in
 * play) nor just the question's literal subject repo(s) (under-reports once {@code impact}'s
 * closure has grown past its roots) is right. Both callers pass exactly the set of repos their
 * answer is actually about — for {@code consumers}, every repo any asked-about entity resolved to;
 * for {@code impact}, {@link ImpactFacts.Result#affectedRepos()}, the roots union
 * {@code Closure.expand}'s {@code added} — so the count is scoped to precisely what the reader is
 * being told about, never more, never less.
 */
final class AbsenceGuard {
    private AbsenceGuard() {
    }

    static String caveat(Jdbi jdbi, Set<String> reposInPlay) {
        List<String> repos = List.copyOf(new TreeSet<>(reposInPlay));
        int unresolvedClients = repos.isEmpty() ? 0 : countUnresolvedRestClients(jdbi, repos);
        int dynamicTopics = repos.isEmpty() ? 0 : countDynamicTopics(jdbi, repos);
        return "Absence is never proof here: " + unresolvedClients
                + " rest_client row(s) with no resolvable path and " + dynamicTopics
                + " kafka_topic row(s) with resolution DYNAMIC among the repo(s) this answer covers ("
                + (repos.isEmpty() ? "none" : String.join(", ", repos)) + ") are invisible to this "
                + "query — a consumer that never shows up here is not proof none exists.";
    }

    /**
     * Counts the clients this query genuinely cannot see. The predicate is deliberately
     * {@code norm_path IS NULL OR resolution = 'DYNAMIC'} — the same one the curation report uses
     * for its "Unresolved REST clients" section, so the number here can be checked against that
     * list — and NOT "resolution is not LITERAL".
     *
     * <p>The older predicate counted every non-LITERAL resolution, which swept in rows that ARE
     * matchable: a constant folded from a nearby declaration, or a path template whose parameters
     * occupy whole segments. Those are matched by the same rule Spring's own templates are matched
     * by, so counting them meant the caveat's number grew as extraction got BETTER — the opposite
     * of what a reader would conclude from it.
     */
    private static int countUnresolvedRestClients(Jdbi jdbi, List<String> repos) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM rest_client rc
                        JOIN module m ON m.id = rc.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name IN (<repos>) AND (rc.norm_path IS NULL OR rc.resolution = 'DYNAMIC')""")
                .bindList("repos", repos).mapTo(Integer.class).one());
    }

    private static int countDynamicTopics(Jdbi jdbi, List<String> repos) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(DISTINCT kt.id) FROM kafka_topic kt
                        JOIN kafka_role kr ON kr.topic_id = kt.id
                        JOIN module m ON m.id = kr.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name IN (<repos>) AND kt.resolution = 'DYNAMIC'""")
                .bindList("repos", repos).mapTo(Integer.class).one());
    }
}
