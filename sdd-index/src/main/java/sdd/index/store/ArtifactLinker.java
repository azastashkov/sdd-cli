package sdd.index.store;

import org.jdbi.v3.core.Jdbi;
import sdd.index.gradle.ModeClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ArtifactLinker {
    public record LinkReport(int internalEdges, List<String> conflicts, List<String> orphanArtifacts) {}

    private ArtifactLinker() {}

    public static LinkReport link(Jdbi jdbi, Map<String, String> artifactOverrides) {
        List<String> conflicts = new ArrayList<>();
        int internalEdges = jdbi.inTransaction(h -> {
            for (Map.Entry<String, String> e : artifactOverrides.entrySet()) {
                String[] ga = e.getKey().split(":", 2);
                Optional<Long> rootModule = h.createQuery("""
                                SELECT m.id FROM module m JOIN repo r ON r.id = m.repo_id
                                WHERE r.name = :repo AND m.gradle_path = ':'""")
                        .bind("repo", e.getValue()).mapTo(Long.class).findOne();
                if (rootModule.isEmpty()) {
                    conflicts.add("override " + e.getKey() + " -> unknown repo '" + e.getValue() + "'");
                    continue;
                }
                h.createUpdate("INSERT INTO artifact(grp, name, module_id) VALUES (:g, :n, :m) "
                                + "ON CONFLICT(grp, name) DO UPDATE SET module_id=excluded.module_id")
                        .bind("g", ga[0]).bind("n", ga.length > 1 ? ga[1] : "")
                        .bind("m", rootModule.get()).execute();
            }

            h.execute("UPDATE dep_edge SET is_internal=0, to_module_id=NULL");
            h.execute("""
                    UPDATE dep_edge SET is_internal=1,
                      to_module_id=(SELECT a.module_id FROM artifact a
                                    WHERE a.grp=dep_edge.to_grp AND a.name=dep_edge.to_name)
                    WHERE EXISTS(SELECT 1 FROM artifact a
                                 WHERE a.grp=dep_edge.to_grp AND a.name=dep_edge.to_name)""");

            // Re-derive modes from declared_version for all edges (idempotent)
            record EdgeRow(long id, String declaredVersion) {}
            List<EdgeRow> edges = h.createQuery("SELECT id, declared_version FROM dep_edge")
                    .map((rs, ctx) -> new EdgeRow(rs.getLong("id"), rs.getString("declared_version")))
                    .list();
            for (EdgeRow e : edges) {
                h.createUpdate("UPDATE dep_edge SET mode=:m WHERE id=:id")
                        .bind("m", ModeClassifier.classify(e.declaredVersion(), false).name())
                        .bind("id", e.id())
                        .execute();
            }

            h.execute("""
                    UPDATE dep_edge SET mode='COMPOSITE'
                    WHERE is_internal=1 AND EXISTS(
                      SELECT 1 FROM module cm JOIN repo cr ON cr.id=cm.repo_id,
                                   module pm JOIN repo pr ON pr.id=pm.repo_id
                      WHERE cm.id=dep_edge.from_module_id AND pm.id=dep_edge.to_module_id
                        AND cr.included_builds LIKE '%"' || pr.path || '"%')""");
            return h.createQuery("SELECT count(*) FROM dep_edge WHERE is_internal=1")
                    .mapTo(Integer.class).one();
        });

        List<String> orphans = jdbi.withHandle(h -> h.createQuery("""
                        SELECT a.grp || ':' || a.name FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        WHERE m.kind = 'LIBRARY'
                          AND NOT EXISTS(SELECT 1 FROM dep_edge e
                                         WHERE e.to_grp = a.grp AND e.to_name = a.name AND e.is_internal = 1)""")
                .mapTo(String.class).list());
        return new LinkReport(internalEdges, List.copyOf(conflicts), List.copyOf(orphans));
    }
}
