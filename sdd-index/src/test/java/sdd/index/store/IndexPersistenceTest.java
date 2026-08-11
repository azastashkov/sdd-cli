package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.gradle.GradleModel;
import sdd.index.scan.RepoScan;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexPersistenceTest {
    @TempDir Path ws;

    private static GradleModel.Extract serviceExtract() {
        return new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", "svc-orders", "com.acme", "0.1.0", Path.of("/w/svc-orders"),
                List.of("java", "org.springframework.boot"), true,
                List.of(),
                Map.of("compileClasspath", new GradleModel.DepConfig(
                        List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "2.3.0"),
                                new GradleModel.DeclaredDep("com.acme", "lib-events", "1.0.0-SNAPSHOT"),
                                new GradleModel.DeclaredDep("com.acme", "lib-bom-managed", null)),
                        List.of(new GradleModel.ResolvedDep("com.acme", "lib-core", "2.3.0", List.of())),
                        List.of())))),
                List.of(Path.of("/w/lib-included")));
    }

    @Test
    void persistsRepoModulesEdgesAndStatuses() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);

            Map<String, Object> repo = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT kind, gradle_status, included_builds FROM repo WHERE name='svc-orders'")
                            .mapToMap().one());
            assertThat(repo.get("kind")).isEqualTo("SERVICE");
            assertThat(repo.get("gradle_status")).isEqualTo("OK");
            assertThat((String) repo.get("included_builds")).contains("lib-included");

            List<Map<String, Object>> edges = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT to_name, declared_version, resolved_version, mode, declared_via "
                            + "FROM dep_edge ORDER BY to_name").mapToMap().list());
            assertThat(edges).hasSize(3);
            assertThat(edges.get(1)).containsEntry("to_name", "lib-core")
                    .containsEntry("mode", "PINNED").containsEntry("declared_via", "DIRECT")
                    .containsEntry("resolved_version", "2.3.0");
            assertThat(edges.get(0)).containsEntry("to_name", "lib-bom-managed")
                    .containsEntry("mode", "BOM_MANAGED").containsEntry("declared_via", "BOM");
            assertThat(edges.get(2)).containsEntry("to_name", "lib-events")
                    .containsEntry("mode", "SNAPSHOT");
        }
    }

    @Test
    void reindexReplacesOldRowsAndMarkStalePreservesThem() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            Integer modules = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM module").mapTo(Integer.class).one());
            assertThat(modules).isEqualTo(1); // replaced, not duplicated

            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            Map<String, Object> repo = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT gradle_status, error FROM repo WHERE name='svc-orders'")
                            .mapToMap().one());
            assertThat(repo.get("gradle_status")).isEqualTo("STALE_OK");
            Integer edgeCount = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM dep_edge").mapTo(Integer.class).one());
            assertThat(edgeCount).isEqualTo(3);
        }
    }
}
