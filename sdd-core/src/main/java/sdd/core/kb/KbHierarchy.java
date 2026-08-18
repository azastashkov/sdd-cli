package sdd.core.kb;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Type-hierarchy queries over {@code type_supertype}.
 *
 * <p>This exists because of the rule this codebase keeps re-learning: a fact with no reader is
 * worse than no fact, because it advertises a capability that does not exist.
 * {@code api_usage.ref_kind} has been written by one site and read by none since it was added.
 * {@code type_supertype} ships with this class in the same change for that reason.
 */
public final class KbHierarchy {

    /** How deep a subtype walk may go. A hierarchy is shallow; the bound guards a cycle, which is a
     *  compile error in Java but IS representable here because the join is by name. */
    private static final int MAX_DEPTH = 8;

    public record Subtype(String repo, String fqcn, String filePath, String relation,
                          String resolution) {
    }

    private KbHierarchy() {
    }

    /** Direct subtypes of a fully-qualified type, estate-wide, deterministically ordered. */
    public static List<Subtype> subtypesOf(Jdbi jdbi, String supertypeFqcn) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT r.name AS repo, t.fqcn AS fqcn, t.file_path AS path,
                               s.relation AS relation, s.resolution AS resolution
                        FROM type_supertype s
                        JOIN java_type t ON t.id = s.type_id
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE s.supertype_fqcn = :fqcn
                        ORDER BY r.name, t.fqcn""")
                .bind("fqcn", supertypeFqcn)
                .map((rs, ctx) -> new Subtype(rs.getString("repo"), rs.getString("fqcn"),
                        rs.getString("path"), rs.getString("relation"), rs.getString("resolution")))
                .list());
    }

    /**
     * Every fqcn transitively below the given roots, roots excluded.
     *
     * <p>Iterative rather than a recursive CTE so the depth bound is visible where it is enforced,
     * and so a cycle costs one wasted round rather than a stack.
     */
    public static Set<String> subtypeClosure(Jdbi jdbi, Set<String> roots) {
        Set<String> seen = new LinkedHashSet<>(roots);
        Set<String> out = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>(roots);
        for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String fqcn : frontier) {
                for (Subtype sub : subtypesOf(jdbi, fqcn)) {
                    if (seen.add(sub.fqcn())) {
                        out.add(sub.fqcn());
                        next.add(sub.fqcn());
                    }
                }
            }
            frontier = next;
        }
        return out;
    }
}
