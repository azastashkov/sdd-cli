package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;

import java.util.List;

/** KB lookup: the DISTINCT declared internal dependencies from one repo's modules onto another
 *  repo's artifacts (dep_edge is module-level; this collapses to unique GA + declaration facts). */
public final class DeclaredDeps {
    public record Declared(String group, String name, String declaredVersion, String declaredVia) {
    }

    private DeclaredDeps() {
    }

    public static List<Declared> between(Jdbi jdbi, String consumerRepo, String providerRepo) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT e.to_grp AS grp, e.to_name AS name,
                                        e.declared_version AS declared, e.declared_via AS via
                        FROM dep_edge e
                        JOIN module mf ON mf.id = e.from_module_id
                        JOIN module mt ON mt.id = e.to_module_id
                        JOIN repo rf ON rf.id = mf.repo_id
                        JOIN repo rt ON rt.id = mt.repo_id
                        WHERE rf.name = :consumer AND rt.name = :provider AND e.is_internal = 1
                        ORDER BY grp, name""")
                .bind("consumer", consumerRepo)
                .bind("provider", providerRepo)
                .map((rs, ctx) -> new Declared(rs.getString("grp"), rs.getString("name"),
                        rs.getString("declared"), rs.getString("via")))
                .list());
    }
}
