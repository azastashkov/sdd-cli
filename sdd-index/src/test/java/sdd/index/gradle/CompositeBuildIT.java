package sdd.index.gradle;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.extract.GradleBuildExtractor;
import sdd.index.scan.WorkspaceScanner;
import sdd.index.store.ArtifactLinker;
import sdd.index.store.IndexPersistence;
import sdd.index.testing.FixtureGradleRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("gradle-it")
class CompositeBuildIT {
    @TempDir Path ws;

    @Test
    void realIncludeBuildFlowsThroughExtractorPersistenceAndLinkerAsComposite() {
        FixtureGradleRepo.in(ws, "lib-x", "8.10.2")
                .withSettings("rootProject.name = 'lib-x'\n")
                .withBuildFile("""
                        plugins { id 'java-library'; id 'maven-publish' }
                        group = 'com.acme'
                        version = '1.0.0'
                        publishing { publications { maven(MavenPublication) { from components.java } } }
                        """)
                .withFile("src/main/java/X.java", "public class X {}\n")
                .withFile(".gitignore", ".gradle/\nbuild/\n")
                .commit();
        FixtureGradleRepo.in(ws, "svc-y", "8.10.2")
                .withSettings("""
                        rootProject.name = 'svc-y'
                        includeBuild('../lib-x')
                        """)
                .withBuildFile("""
                        plugins { id 'java' }
                        group = 'com.acme'
                        repositories { mavenCentral() }
                        dependencies { implementation 'com.acme:lib-x:1.0.0' }
                        """)
                .withFile("src/main/java/Y.java", "public class Y {}\n")
                .withFile(".gitignore", ".gradle/\nbuild/\n")
                .commit();

        GradleModel.Extract svcExtract = new GradleExtractor(Map.of()).extract(ws.resolve("svc-y"));
        assertThat(svcExtract.includedBuilds())
                .anySatisfy(p -> assertThat(p.toString()).endsWith("lib-x"));

        GradleModel.Extract libExtract = new GradleExtractor(Map.of()).extract(ws.resolve("lib-x"));
        try (Database db = Database.open(ws)) {
            var scans = WorkspaceScanner.scan(ws, List.of());
            IndexPersistence.persistRepo(db.jdbi(), scans.get(0),
                    GradleBuildExtractor.adapt(libExtract), "GRADLE", "OK", null);
            IndexPersistence.persistRepo(db.jdbi(), scans.get(1),
                    GradleBuildExtractor.adapt(svcExtract), "GRADLE", "OK", null);
            ArtifactLinker.link(db.jdbi(), Map.of());
            String mode = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT mode FROM dep_edge WHERE to_name='lib-x' AND is_internal=1")
                    .mapTo(String.class).one());
            assertThat(mode).isEqualTo("COMPOSITE");
        }
    }
}
