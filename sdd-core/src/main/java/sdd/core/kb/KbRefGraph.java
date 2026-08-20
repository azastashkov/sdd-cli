package sdd.core.kb;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reference-graph queries over {@code type_ref} — "who references this type", and how far a type
 * sits from the ones a task is anchored on.
 *
 * <p>Companion to {@link KbHierarchy}, and written to the same rules: iterative rather than a
 * recursive CTE so the depth bound is visible where it is enforced, every query totally ordered
 * because {@code sdd plan approve} hashes what this feeds, and shipped with a reader so the table
 * does not join {@code api_usage.ref_kind} in the ranks of facts nobody consumes.
 *
 * <h2>Why the walk is undirected</h2>
 *
 * <p>The obvious design is inbound-only past the first hop — "who depends on the thing that
 * changed" — with outbound excluded on the grounds that following what a type uses leads into the
 * JDK-adjacent world. Measured against the real estate on 2026-08-20, that rule provably misses the
 * case the graph was built for. The path from the anchor to the classes a tier-invalidation task is
 * about is:
 *
 * <pre>
 *   Channels  &lt;--inbound--  AuthWebConfig  --outbound--&gt;  TierInvalidationListener
 * </pre>
 *
 * <p>The listener never references {@code Channels} at all; a configuration class wires the two
 * together. That shape — a component and its collaborators joined through a config or factory
 * rather than to each other — is how Spring estates are written, so an inbound-only walk would
 * systematically miss the components and see only the wiring.
 *
 * <p>The concern that motivated inbound-only is answered by a different filter than direction:
 * <b>expansion only crosses an edge whose far end is itself an indexed type.</b> On the probe
 * estate that is 1566 of 3155 edges; {@code org.slf4j.Logger} and
 * {@code io.micrometer.core.instrument.Counter} — the two most-referenced things in the whole
 * corpus — are not indexed, so they are not paths, and no amount of outbound walking reaches them.
 *
 * <h2>Why the depth bound is 2</h2>
 *
 * <p>Measured from the same two anchors, the neighbourhood grows 14 types at depth 1 (4% of the
 * estate), 126 at depth 2 (40%), 259 at depth 3 (84%). A signal that selects 84% of the estate is
 * not a signal. Both target listeners and the config class documenting the service that must be
 * left alone all sit at distance exactly 2, so 2 is where the useful reach and the last useful
 * discrimination coincide. {@link KbHierarchy}'s bound of 8 is not a precedent: a hierarchy is
 * sparse and a reference graph is dense.
 */
public final class KbRefGraph {

    /**
     * How far a reference walk may go. See the class javadoc: at 3 the neighbourhood is 84% of a
     * six-repo estate, which discriminates nothing. Raising this needs a measurement showing the
     * extra hop still separates relevant types from the rest, not an intuition that more is better.
     */
    public static final int MAX_DEPTH = 2;

    /**
     * How many types one level may contribute. A hub — a shared enum, a widely-used DTO — has an
     * inbound degree in the hundreds, and one such type on the frontier turns "near the anchor"
     * into "the estate". The estate's own maximum is 53 ({@code com.trading.model.SecurityType}),
     * so this does not bite there; it exists for the 53-repo case, and when it does bite it is
     * reported by name rather than silently applied.
     */
    static final int MAX_FRONTIER = 200;

    /** One reference edge, seen from whichever end the caller asked about. */
    public record Edge(String fqcn, String repo, String filePath, String refKind, int refCount) {
    }

    /**
     * Types within {@link #MAX_DEPTH} of the anchors, and how far each sits.
     *
     * @param distanceByFqcn anchors at 0, insertion-ordered, shortest distance wins
     * @param suppressions   frontier truncations, each naming the type and the counts involved.
     *                       Absence and truncation must never render the same.
     */
    public record Neighbourhood(Map<String, Integer> distanceByFqcn, List<String> suppressions) {
        public Neighbourhood {
            distanceByFqcn = Map.copyOf(distanceByFqcn);
            suppressions = List.copyOf(suppressions);
        }

        /** Distance from the nearest anchor, or empty when the type is outside the neighbourhood. */
        public java.util.Optional<Integer> distanceOf(String fqcn) {
            return java.util.Optional.ofNullable(distanceByFqcn.get(fqcn));
        }
    }

    private KbRefGraph() {
    }

    /** {@link #expand(Jdbi, Set, int)} at the default {@link #MAX_DEPTH}. */
    public static Neighbourhood expand(Jdbi jdbi, Set<String> anchors) {
        return expand(jdbi, anchors, MAX_DEPTH);
    }

    /** Indexed types that reference the given type, estate-wide, deterministically ordered. */
    public static List<Edge> inbound(Jdbi jdbi, String toFqcn) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT t.fqcn AS fqcn, r.name AS repo, t.file_path AS path,
                               tr.ref_kind AS kind, tr.ref_count AS count
                        FROM type_ref tr
                        JOIN java_type t ON t.id = tr.from_type_id
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE tr.to_fqcn = :fqcn
                        ORDER BY r.name, m.gradle_path, t.fqcn, tr.ref_kind""")
                .bind("fqcn", toFqcn)
                .map(KbRefGraph::toEdge)
                .list());
    }

    /**
     * Indexed types the given type references. Restricted to targets that have a {@code java_type}
     * row, because an unindexed target is a leaf: it can be named but never walked through.
     */
    public static List<Edge> outbound(Jdbi jdbi, String fromFqcn) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT t.fqcn AS fqcn, r.name AS repo, t.file_path AS path,
                               tr.ref_kind AS kind, tr.ref_count AS count
                        FROM type_ref tr
                        JOIN java_type src ON src.id = tr.from_type_id
                        JOIN java_type t ON t.fqcn = tr.to_fqcn
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE src.fqcn = :fqcn
                        ORDER BY r.name, m.gradle_path, t.fqcn, tr.ref_kind""")
                .bind("fqcn", fromFqcn)
                .map(KbRefGraph::toEdge)
                .list());
    }

    private static Edge toEdge(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext c)
            throws java.sql.SQLException {
        return new Edge(rs.getString("fqcn"), rs.getString("repo"), rs.getString("path"),
                rs.getString("kind"), rs.getInt("count"));
    }

    /**
     * Every indexed type within {@code maxDepth} reference hops of the anchors, with its distance.
     *
     * <p>An empty anchor set returns an empty neighbourhood <b>without issuing a query</b>. That is
     * not an optimisation: it is what lets a caller prove that a spec anchoring nothing produces
     * byte-identical output to the pre-graph build.
     */
    public static Neighbourhood expand(Jdbi jdbi, Set<String> anchors, int maxDepth) {
        if (anchors.isEmpty() || maxDepth <= 0) {
            return new Neighbourhood(Map.of(), List.of());
        }
        Map<String, Integer> distance = new LinkedHashMap<>();
        List<String> suppressions = new ArrayList<>();
        // TreeSet, following Closure.expandBuildEdges: the frontier is processed in sorted order so
        // the traversal — and therefore the plan hash — does not depend on row arrival order.
        Collection<String> frontier = new TreeSet<>(anchors);
        anchors.forEach(a -> distance.put(a, 0));

        for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
            Set<String> next = new TreeSet<>();
            for (String node : frontier) {
                Set<String> neighbours = new LinkedHashSet<>();
                neighbourNames(inbound(jdbi, node), neighbours);
                neighbourNames(outbound(jdbi, node), neighbours);
                neighbours.remove(node);
                if (neighbours.size() > MAX_FRONTIER) {
                    suppressions.add("neighbours of " + node + " truncated at " + MAX_FRONTIER
                            + " of " + neighbours.size());
                    neighbours = new LinkedHashSet<>(
                            new ArrayList<>(neighbours).subList(0, MAX_FRONTIER));
                }
                for (String n : neighbours) {
                    if (!distance.containsKey(n)) {
                        next.add(n);
                    }
                }
            }
            final int d = depth;
            next.forEach(n -> distance.put(n, d));
            frontier = next;
        }
        return new Neighbourhood(distance, suppressions);
    }

    private static void neighbourNames(List<Edge> edges, Set<String> into) {
        edges.forEach(e -> into.add(e.fqcn()));
    }
}
