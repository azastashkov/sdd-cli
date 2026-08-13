package sdd.index.gradle;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.testing.FixtureGradleRepo;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@Tag("gradle-it")
class GradleExtractorIT {
    @TempDir Path tmp;

    @Test
    void extractsProjectsDepsAndBootMarker() {
        Path repo = FixtureGradleRepo.in(tmp, "svc-orders", "8.10.2")
                .withSettings("rootProject.name = 'svc-orders'\n")
                .withBuildFile("""
                        plugins { id 'java' }
                        group = 'com.acme'
                        version = '0.1.0'
                        repositories { mavenCentral() }
                        dependencies {
                            implementation 'org.apache.commons:commons-lang3:3.14.0'
                            implementation 'com.acme:lib-core:2.0.0'
                        }
                        """)
                .withFile("src/main/java/A.java", "public class A {}\n")
                .commit();

        GradleModel.Extract extract = new GradleExtractor(Map.of()).extract(repo);

        assertThat(extract.projects()).hasSize(1);
        GradleModel.Project p = extract.projects().get(0);
        assertThat(p.name()).isEqualTo("svc-orders");
        assertThat(p.group()).isEqualTo("com.acme");
        assertThat(p.plugins()).contains("java");
        assertThat(p.hasBootJarTask()).isFalse();
        GradleModel.DepConfig cc = p.configurations().get("compileClasspath");
        assertThat(cc.declared()).extracting(GradleModel.DeclaredDep::name)
                .contains("commons-lang3", "lib-core");
        // commons-lang3 resolves from Maven Central; internal lib-core does not exist remotely
        assertThat(cc.resolved()).extracting(GradleModel.ResolvedDep::name).contains("commons-lang3");
        assertThat(cc.unresolved()).anySatisfy(u -> assertThat(u).contains("lib-core"));
    }

    @Test
    void testScopeDependencyIsDeclaredOnlyNoResolutionAttempted() {
        // Live-smoke blind spot: a testImplementation dep on an internal artifact (e.g.
        // trading-product-b's testImplementation on trading-core's mock-pricing-venue) never
        // resolves against a public repo, so the init script must not lenient-resolve test
        // classpaths — it only needs the declared edge to see the product->library relationship.
        Path repo = FixtureGradleRepo.in(tmp, "svc-with-test-dep", "8.10.2")
                .withSettings("rootProject.name = 'svc-with-test-dep'\n")
                .withBuildFile("""
                        plugins { id 'java' }
                        group = 'com.acme'
                        version = '0.1.0'
                        repositories { mavenCentral() }
                        dependencies {
                            testImplementation 'com.acme:test-double:1.0'
                        }
                        """)
                .withFile("src/main/java/A.java", "public class A {}\n")
                .commit();

        GradleModel.Extract extract = new GradleExtractor(Map.of()).extract(repo);

        GradleModel.Project p = extract.projects().get(0);
        GradleModel.DepConfig tc = p.configurations().get("testCompileClasspath");
        assertThat(tc).isNotNull();
        assertThat(tc.declared()).extracting(GradleModel.DeclaredDep::name).contains("test-double");
        // proves no lenient resolution ran against the test classpath: an internal, unpublished
        // artifact would otherwise show up as unresolved, not silently absent
        assertThat(tc.resolved()).isEmpty();
        assertThat(tc.unresolved()).isEmpty();
    }

    @Test
    void brokenSettingsThrowsExtractionException() {
        Path repo = FixtureGradleRepo.in(tmp, "broken", "8.10.2")
                .withSettings("throw new GradleException('intentionally broken')\n")
                .commit();
        assertThatThrownBy(() -> new GradleExtractor(Map.of()).extract(repo))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void wrapperVersionAndJdkMapping() {
        Path repo = FixtureGradleRepo.in(tmp, "v", "8.10.2").withSettings("").commit();
        assertThat(GradleExtractor.wrapperVersion(repo)).isEqualTo("8.10.2");
        assertThat(GradleExtractor.jdkMajorFor("8.10.2")).isEqualTo(21);
        assertThat(GradleExtractor.jdkMajorFor("8.5")).isEqualTo(21);
        assertThat(GradleExtractor.jdkMajorFor("7.6.4")).isEqualTo(17);
        assertThat(GradleExtractor.jdkMajorFor("6.9")).isEqualTo(11);
        assertThat(GradleExtractor.jdkMajorFor(null)).isEqualTo(21);
    }
}
