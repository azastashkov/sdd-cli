package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.index.gradle.GradleModel;
import sdd.index.scan.RepoScan;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    void edgesAreCreatedOnlyForDeclaredDependenciesNotResolvedTransitives() {
        try (Database db = Database.open(ws)) {
            GradleModel.Extract extract = new GradleModel.Extract(List.of(new GradleModel.Project(
                    ":", "svc-orders", "com.acme", "0.1.0", Path.of("/w/svc-orders"),
                    List.of("java"), false, List.of(),
                    Map.of("compileClasspath", new GradleModel.DepConfig(
                            List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "2.3.0")),
                            List.of(new GradleModel.ResolvedDep("com.acme", "lib-core", "2.3.0", List.of()),
                                    new GradleModel.ResolvedDep("org.slf4j", "slf4j-api", "2.0.13", List.of())),
                            List.of())))),
                    List.of());
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, extract, "OK", null);

            List<Map<String, Object>> edges = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT to_name, declared_version, resolved_version, mode FROM dep_edge")
                            .mapToMap().list());
            assertThat(edges).hasSize(1);
            assertThat(edges.get(0)).containsEntry("to_name", "lib-core")
                    .containsEntry("declared_version", "2.3.0")
                    .containsEntry("resolved_version", "2.3.0")
                    .containsEntry("mode", "PINNED");
        }
    }

    @Test
    void gaPublishedByAnotherRepoRecordsConflictWarningOnTheRepoRow() {
        try (Database db = Database.open(ws)) {
            GradleModel.Extract owner = publisherExtract("lib-core", "com.acme", "lib-core");
            IndexPersistence.persistRepo(db.jdbi(),
                    new RepoScan("lib-core", Path.of("/w/lib-core"), "a".repeat(40), "main", ""),
                    owner, "OK", null);
            GradleModel.Extract squatter = publisherExtract("lib-core-fork", "com.acme", "lib-core");
            IndexPersistence.persistRepo(db.jdbi(),
                    new RepoScan("lib-core-fork", Path.of("/w/lib-core-fork"), "b".repeat(40), "main", ""),
                    squatter, "OK", null);

            String error = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT error FROM repo WHERE name='lib-core-fork'")
                    .mapTo(String.class).one());
            assertThat(error).contains("GA conflict").contains("com.acme:lib-core").contains("lib-core");
            // the original owner's row is untouched
            java.util.Optional<String> ownerError = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT error FROM repo WHERE name='lib-core'")
                    .mapTo(String.class).findOne());
            assertThat(ownerError).isEmpty();
        }
    }

    private static GradleModel.Extract publisherExtract(String moduleName, String grp, String artifactId) {
        return new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", moduleName, grp, "1.0.0", Path.of("/w/" + moduleName),
                List.of("java-library", "maven-publish"), false,
                List.of(new GradleModel.Publication(grp, artifactId)),
                Map.of("compileClasspath", new GradleModel.DepConfig(List.of(), List.of(), List.of())))),
                List.of());
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

    @Test
    void reindexingUsageTargetRepoDoesNotViolateForeignKeysAndKeepsTheUsageRow() {
        try (Database db = Database.open(ws)) {
            RepoScan producer = new RepoScan("lib-core", Path.of("/w/lib-core"), "b".repeat(40), "main", "");
            GradleModel.Extract producerExtract = publisherExtract("lib-core", "com.acme", "lib-core");
            IndexPersistence.persistRepo(db.jdbi(), producer, producerExtract, "OK", null);
            RepoScan consumer = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), consumer, serviceExtract(), "OK", null);
            // what UsageLinker writes: a consumer-module row pointing at a module of ANOTHER repo
            db.jdbi().useHandle(h -> h.execute("""
                    INSERT INTO api_usage(from_module_id, target_fqcn, target_module_id, ref_kind)
                    VALUES ((SELECT m.id FROM module m JOIN repo r ON r.id=m.repo_id
                             WHERE r.name='svc-orders'),
                            'com.acme.Lib',
                            (SELECT m.id FROM module m JOIN repo r ON r.id=m.repo_id
                             WHERE r.name='lib-core'),
                            'IMPORT')"""));

            // re-persisting the TARGET repo deletes its modules — must not wedge the run
            IndexPersistence.persistRepo(db.jdbi(), producer, producerExtract, "OK", null);

            Map<String, Object> usage = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT target_fqcn, target_module_id FROM api_usage").mapToMap().one());
            assertThat(usage).containsEntry("target_fqcn", "com.acme.Lib");
            assertThat(usage.get("target_module_id")).isNull();
        }
    }

    @Test
    void reindexingRepoDropsFtsRowsOfItsOldModules() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            long moduleId = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT id FROM module").mapTo(Long.class).one());
            db.jdbi().useHandle(h -> FtsSymbolWriter.insert(h, moduleId, "OrderService", "com.acme.OrderService"));

            // re-persist: modules are deleted and reinserted with NEW ids, so the symbol rows keyed
            // to the old ids can never be reached by a later per-module delete
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);

            Integer orphans = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM fts_symbol").mapTo(Integer.class).one());
            assertThat(orphans).isZero();
        }
    }

    @Test
    void mergesMultipleConfigurationsKeepingFirstSeenVersions() {
        try (Database db = Database.open(ws)) {
            Map<String, GradleModel.DepConfig> configs = new LinkedHashMap<>();
            configs.put("compileClasspath", new GradleModel.DepConfig(
                    List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "2.3.0")),
                    List.of(new GradleModel.ResolvedDep("com.acme", "lib-core", "2.3.0", List.of())),
                    List.of()));
            configs.put("runtimeClasspath", new GradleModel.DepConfig(
                    List.of(new GradleModel.DeclaredDep("com.acme", "lib-core", "2.3.0")),
                    List.of(new GradleModel.ResolvedDep("com.acme", "lib-core", "2.4.0", List.of())),
                    List.of()));

            GradleModel.Extract extract = new GradleModel.Extract(
                    List.of(new GradleModel.Project(":", "lib-core", "com.acme", "0.1.0",
                            Path.of("/w/lib-core"), List.of("java"), false, List.of(), configs)),
                    List.of());

            RepoScan scan = new RepoScan("lib-core", Path.of("/w/lib-core"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, extract, "OK", null);

            List<Map<String, Object>> edges = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT to_name, configuration, resolved_version FROM dep_edge WHERE to_name='lib-core'")
                            .mapToMap().list());
            assertThat(edges).hasSize(1);
            assertThat(edges.get(0))
                    .containsEntry("configuration", "compileClasspath")
                    .containsEntry("resolved_version", "2.3.0");
        }
    }

    @Test
    void testOnlyDependencyProducesADepEdgeLabeledWithTheTestConfiguration() {
        // Reproduces the live-smoke blind spot: a product declares a dependency only in
        // testCompileClasspath (e.g. testImplementation "com.trading:mock-pricing-venue:...")
        // and the KB must still see it as a dep_edge, or the planner's impact closure (design doc
        // line 50: closure runs over ALL internal edges) never pulls the producer repo in.
        try (Database db = Database.open(ws)) {
            Map<String, GradleModel.DepConfig> configs = new LinkedHashMap<>();
            configs.put("compileClasspath", new GradleModel.DepConfig(List.of(), List.of(), List.of()));
            configs.put("testCompileClasspath", new GradleModel.DepConfig(
                    List.of(new GradleModel.DeclaredDep("com.trading", "mock-pricing-venue", "1.0.0")),
                    List.of(), List.of()));

            GradleModel.Extract extract = new GradleModel.Extract(
                    List.of(new GradleModel.Project(":", "product-b", "com.trading", "0.1.0",
                            Path.of("/w/product-b"), List.of("java"), false, List.of(), configs)),
                    List.of());

            RepoScan scan = new RepoScan("product-b", Path.of("/w/product-b"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, extract, "OK", null);

            List<Map<String, Object>> edges = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT to_name, configuration, mode, declared_via FROM dep_edge "
                            + "WHERE to_name='mock-pricing-venue'").mapToMap().list());
            assertThat(edges).hasSize(1);
            assertThat(edges.get(0))
                    .containsEntry("configuration", "testCompileClasspath")
                    .containsEntry("mode", "PINNED")
                    .containsEntry("declared_via", "DIRECT");
        }
    }

    @Test
    void dependencyDeclaredInBothCompileAndTestConfigurationsProducesOneEdgeLabeledCompileScope() {
        try (Database db = Database.open(ws)) {
            Map<String, GradleModel.DepConfig> configs = new LinkedHashMap<>();
            configs.put("compileClasspath", new GradleModel.DepConfig(
                    List.of(new GradleModel.DeclaredDep("com.trading", "shared-lib", "2.0.0")),
                    List.of(new GradleModel.ResolvedDep("com.trading", "shared-lib", "2.0.0", List.of())),
                    List.of()));
            configs.put("testCompileClasspath", new GradleModel.DepConfig(
                    List.of(new GradleModel.DeclaredDep("com.trading", "shared-lib", "2.0.0")),
                    List.of(), List.of()));

            GradleModel.Extract extract = new GradleModel.Extract(
                    List.of(new GradleModel.Project(":", "product-b", "com.trading", "0.1.0",
                            Path.of("/w/product-b"), List.of("java"), false, List.of(), configs)),
                    List.of());

            RepoScan scan = new RepoScan("product-b", Path.of("/w/product-b"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, extract, "OK", null);

            List<Map<String, Object>> edges = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT to_name, configuration, resolved_version FROM dep_edge "
                            + "WHERE to_name='shared-lib'").mapToMap().list());
            assertThat(edges).hasSize(1);
            assertThat(edges.get(0))
                    .containsEntry("configuration", "compileClasspath")
                    .containsEntry("resolved_version", "2.0.0");
        }
    }

    @Test
    void reindexingProducerAfterLinkDoesNotViolateForeignKeys() {
        try (Database db = Database.open(ws)) {
            RepoScan producer = new RepoScan("lib-core", Path.of("/w/lib-core"), "b".repeat(40), "main", "");
            GradleModel.Extract producerExtract = new GradleModel.Extract(List.of(new GradleModel.Project(
                    ":", "lib-core", "com.acme", "2.3.0", Path.of("/w/lib-core"),
                    List.of("java-library", "maven-publish"), false, List.of(),
                    Map.of("compileClasspath", new GradleModel.DepConfig(List.of(), List.of(), List.of())))),
                    List.of());
            IndexPersistence.persistRepo(db.jdbi(), producer, producerExtract, "OK", null);
            RepoScan consumer = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), consumer, serviceExtract(), "OK", null);
            ArtifactLinker.link(db.jdbi(), Map.of());
            // re-persist the PRODUCER after linking — must not throw
            IndexPersistence.persistRepo(db.jdbi(), producer, producerExtract, "OK", null);
            // and a fresh link restores the internal edge to the new module row
            ArtifactLinker.link(db.jdbi(), Map.of());
            Integer internal = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT count(*) FROM dep_edge WHERE to_name='lib-core' AND is_internal=1")
                    .mapTo(Integer.class).one());
            assertThat(internal).isEqualTo(1);
        }
    }

    @Test
    void markStaleAppendsToExistingErrorInsteadOfOverwriting() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            db.jdbi().useHandle(h -> h.execute("UPDATE repo SET error='prior note' WHERE name='svc-orders'"));
            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            String error = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT error FROM repo WHERE name='svc-orders'").mapTo(String.class).one());
            assertThat(error).contains("prior note").contains("network down");
        }
    }

    @Test
    void markStaleDoesNotAppendDuplicateIdenticalMessages() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            String error = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT error FROM repo WHERE name='svc-orders'").mapTo(String.class).one());
            assertThat(error.split("network down", -1).length - 1).isEqualTo(1);
        }
    }

    @Test
    void markStaleAppendIsNotSwallowedByARawSubstringOfAnExistingLargerCount() {
        // Same dedup hazard as SourcePersistence.updateParseStatus: "3 source files failed to
        // parse" is a raw substring of "13 source files failed to parse", so a naive instr()
        // check must not treat the shorter note as a duplicate and drop it.
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            db.jdbi().useHandle(h -> h.execute(
                    "UPDATE repo SET error='13 source files failed to parse; ' WHERE name='svc-orders'"));
            IndexPersistence.markStale(db.jdbi(), "svc-orders", "3 source files failed to parse");
            String error = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT error FROM repo WHERE name='svc-orders'").mapTo(String.class).one());
            assertThat(error).isEqualTo("13 source files failed to parse; 3 source files failed to parse; ");
        }
    }
}
