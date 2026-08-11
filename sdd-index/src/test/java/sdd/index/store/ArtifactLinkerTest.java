package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.gradle.GradleModel;
import sdd.index.scan.RepoScan;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactLinkerTest {
    @TempDir Path ws;
    private Database db;

    private static GradleModel.Project project(String name, String grp, List<String> plugins,
                                               List<GradleModel.DeclaredDep> deps) {
        return new GradleModel.Project(":", name, grp, "1.0", Path.of("/w/" + name),
                plugins, false, List.of(),
                Map.of("compileClasspath",
                        new GradleModel.DepConfig(deps, List.of(), List.of())));
    }

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        // lib-core: publishes com.acme:lib-core
        IndexPersistence.persistRepo(db.jdbi(),
                new RepoScan("lib-core", Path.of("/w/lib-core"), "b".repeat(40), "main", ""),
                new GradleModel.Extract(List.of(project("lib-core", "com.acme",
                        List.of("java-library", "maven-publish"), List.of())), List.of()),
                "OK", null);
        // svc-orders: depends on lib-core (pinned) and lib-included (composite via includedBuilds)
        IndexPersistence.persistRepo(db.jdbi(),
                new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", ""),
                new GradleModel.Extract(List.of(project("svc-orders", "com.acme",
                        List.of("java", "org.springframework.boot"),
                        List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "1.0"),
                                new GradleModel.DeclaredDep("com.acme", "lib-included", "1.0"),
                                new GradleModel.DeclaredDep("org.apache.commons", "commons-lang3", "3.14.0")))),
                        List.of(Path.of("/w/lib-included"))),
                "OK", null);
        // lib-included: composite producer
        IndexPersistence.persistRepo(db.jdbi(),
                new RepoScan("lib-included", Path.of("/w/lib-included"), "c".repeat(40), "main", ""),
                new GradleModel.Extract(List.of(project("lib-included", "com.acme",
                        List.of("java-library", "maven-publish"), List.of())), List.of()),
                "OK", null);
    }

    @Test
    void marksInternalEdgesAndCompositeAndReportsOrphans() {
        ArtifactLinker.LinkReport report = ArtifactLinker.link(db.jdbi(), Map.of());

        assertThat(report.internalEdges()).isEqualTo(2);
        List<Map<String, Object>> internal = db.jdbi().withHandle(h ->
                h.createQuery("SELECT to_name, mode, is_internal FROM dep_edge WHERE is_internal=1 ORDER BY to_name")
                        .mapToMap().list());
        assertThat(internal).hasSize(2);
        assertThat(internal.get(0)).containsEntry("to_name", "lib-core").containsEntry("mode", "PINNED");
        assertThat(internal.get(1)).containsEntry("to_name", "lib-included").containsEntry("mode", "COMPOSITE");
        // commons-lang3 stays external
        Integer external = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM dep_edge WHERE to_name='commons-lang3' AND is_internal=0")
                .mapTo(Integer.class).one());
        assertThat(external).isEqualTo(1);
        assertThat(report.orphanArtifacts()).isEmpty();
        assertThat(report.conflicts()).isEmpty();
    }

    @Test
    void overridesRemapAndUnknownRepoIsConflict() {
        ArtifactLinker.LinkReport report = ArtifactLinker.link(db.jdbi(),
                Map.of("org.apache.commons:commons-lang3", "lib-core",
                       "com.acme:ghost", "no-such-repo"));

        Integer remapped = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM dep_edge WHERE to_name='commons-lang3' AND is_internal=1")
                .mapTo(Integer.class).one());
        assertThat(remapped).isEqualTo(1);
        assertThat(report.conflicts()).anySatisfy(c -> assertThat(c).contains("no-such-repo"));
    }

    @Test
    void compositeModeRevertsWhenIncludedBuildRemoved() {
        ArtifactLinker.link(db.jdbi(), Map.of());
        // precondition: composite
        String before = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT mode FROM dep_edge WHERE to_name='lib-included'").mapTo(String.class).one());
        assertThat(before).isEqualTo("COMPOSITE");
        // simulate the consumer dropping its includeBuild
        db.jdbi().useHandle(h -> h.execute("UPDATE repo SET included_builds='[]' WHERE name='svc-orders'"));
        ArtifactLinker.link(db.jdbi(), Map.of());
        String after = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT mode FROM dep_edge WHERE to_name='lib-included'").mapTo(String.class).one());
        assertThat(after).isEqualTo("PINNED");
    }
}
