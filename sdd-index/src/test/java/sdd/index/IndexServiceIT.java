package sdd.index;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.testing.FixtureGradleRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("gradle-it")
class IndexServiceIT {
    @TempDir Path ws;

    private SddConfig config() {
        return new SddConfig(ws, "fts", Map.of(), Map.of(), List.of(), Map.of());
    }

    // Real Gradle builds leave untracked build/.gradle output in the fixture working tree;
    // gitignore it so a second scan still sees a clean, unchanged repo (the whole point of
    // the incremental-skip fingerprint).
    private static final String GITIGNORE = "build/\n.gradle/\n";

    private void buildFixtureEstate() {
        FixtureGradleRepo.in(ws, "lib-core", "8.10.2")
                .withSettings("rootProject.name = 'lib-core'\n")
                .withBuildFile("""
                        plugins { id 'java-library'; id 'maven-publish' }
                        group = 'com.acme'
                        version = '2.3.0'
                        publishing { publications { maven(MavenPublication) { from components.java } } }
                        """)
                .withFile("src/main/java/C.java", "public class C {}\n")
                .withFile(".gitignore", GITIGNORE)
                .commit();
        FixtureGradleRepo.in(ws, "svc-orders", "8.10.2")
                .withSettings("rootProject.name = 'svc-orders'\n")
                .withBuildFile("""
                        plugins { id 'java' }
                        group = 'com.acme'
                        version = '0.1.0'
                        repositories { mavenCentral() }
                        dependencies { implementation 'com.acme:lib-core:2.3.0' }
                        """)
                .withFile("src/main/java/A.java", "public class A {}\n")
                .withFile(".gitignore", GITIGNORE)
                .commit();
        FixtureGradleRepo.in(ws, "broken-build", "8.10.2")
                .withSettings("throw new GradleException('kaput')\n")
                .withFile("build.gradle", """
                        plugins { id 'java' }
                        dependencies { implementation 'com.acme:lib-core:2.3.0' }
                        """)
                .withFile(".gitignore", GITIGNORE)
                .commit();
    }

    @Test
    void indexesEstateWithDegradedFallbackAndIncrementalSkip() {
        buildFixtureEstate();
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService();
            List<IndexService.RepoResult> first = service.run(config(), db);

            assertThat(first).extracting(IndexService.RepoResult::repo)
                    .containsExactly("broken-build", "lib-core", "svc-orders");
            assertThat(first).filteredOn(r -> r.repo().equals("broken-build")).first()
                    .satisfies(r -> assertThat(r.status()).isEqualTo("DEGRADED"));
            assertThat(first).filteredOn(r -> r.repo().equals("svc-orders")).first()
                    .satisfies(r -> {
                        assertThat(r.status()).isEqualTo("OK");
                        assertThat(r.internalDeps()).isEqualTo(1);
                        assertThat(r.parseStatus()).isEqualTo("OK");
                    });

            // internal edge svc-orders -> lib-core is marked and PINNED
            // (broken-build also declares lib-core, so scope to svc-orders's own edge)
            Map<String, Object> edge = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT e.is_internal, e.mode FROM dep_edge e
                            JOIN module m ON m.id = e.from_module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name='svc-orders' AND e.to_name='lib-core' AND e.is_internal=1""")
                    .mapToMap().one());
            assertThat(edge).containsEntry("mode", "PINNED");
            // broken-build's declared-only edge also links internally
            Integer internalCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM dep_edge WHERE is_internal=1").mapTo(Integer.class).one());
            assertThat(internalCount).isEqualTo(2);

            // source extraction ran on the real-Gradle path
            Integer typeCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM java_type").mapTo(Integer.class).one());
            assertThat(typeCount).isGreaterThanOrEqualTo(2); // A (svc-orders) + C (lib-core)
            // ...and stored repo-relative. Gradle canonicalizes projectDir while the scanner does
            // not, so on a symlinked root (macOS: /var -> /private/var) an uncanonicalized
            // relativize silently produces "../../.." escapes instead of usable paths.
            List<String> filePaths = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT file_path FROM java_type").mapTo(String.class).list());
            assertThat(filePaths).isNotEmpty().allSatisfy(p -> assertThat(p).doesNotStartWith(".."));

            // second run: clean repos skip (fingerprint unchanged); DEGRADED repo retries
            List<IndexService.RepoResult> second = service.run(config(), db);
            assertThat(second).filteredOn(r -> r.repo().equals("svc-orders")).first()
                    .satisfies(r -> assertThat(r.skipped()).isTrue());
            assertThat(second).filteredOn(r -> r.repo().equals("broken-build")).first()
                    .satisfies(r -> assertThat(r.skipped()).isFalse());
        }
    }
}
