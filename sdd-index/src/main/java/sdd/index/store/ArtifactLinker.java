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

            // Re-derive modes from declared_version for all edges (idempotent). The consuming
            // module's language selects the grammar: the same specifier means different things in
            // the two ecosystems, and reading an npm range with Maven rules silently calls every
            // caret range PINNED. See IndexPersistence.classifyMode.
            record EdgeRow(long id, String declaredVersion, String language) {}
            List<EdgeRow> edges = h.createQuery("""
                            SELECT e.id, e.declared_version, m.language
                            FROM dep_edge e JOIN module m ON m.id = e.from_module_id""")
                    .map((rs, ctx) -> new EdgeRow(rs.getLong("id"), rs.getString("declared_version"),
                            rs.getString("language")))
                    .list();
            for (EdgeRow e : edges) {
                h.createUpdate("UPDATE dep_edge SET mode=:m WHERE id=:id")
                        .bind("m", IndexPersistence.classifyMode(e.language(), e.declaredVersion()).name())
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

            // npm's equivalent of an included build: a workspaces monorepo materialises a sibling
            // package as a symlink into live source, so a dependency satisfied inside the same repo
            // is composite regardless of the version range written down. Guarded on language so it
            // cannot touch Gradle edges — sdd-init.gradle only ever emits ExternalDependency, so a
            // same-repo Gradle edge does not exist and an unguarded rule would be a silent trap for
            // whoever changes that.
            h.execute("""
                    UPDATE dep_edge SET mode='COMPOSITE'
                    WHERE is_internal=1 AND EXISTS(
                      SELECT 1 FROM module cm, module pm
                      WHERE cm.id=dep_edge.from_module_id AND pm.id=dep_edge.to_module_id
                        AND cm.repo_id = pm.repo_id AND cm.language='TYPESCRIPT')""");
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
