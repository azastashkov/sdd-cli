package sdd.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.RunSettings;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.kb.KbHierarchy;
import sdd.core.testing.FixtureRepo;
import sdd.index.extract.BuildModel;
import sdd.index.extract.GradleBuildExtractor;
import sdd.index.gradle.GradleModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one question {@code api_usage} could never answer: WHICH class in another repo implements
 * this interface.
 *
 * <p>{@code api_usage} records {@code (from_module_id, target_fqcn, 'EXTENDS')} and drops the
 * subtype's own fqcn, so the KB could say "some type in this module extends X" and nothing more.
 * Measured on the real estate, that is why three of five implementors of a changed interface never
 * reached the planning model: promotion into the evidence window keys on names, and no name existed.
 */
class TypeHierarchyEndToEndTest {
    @TempDir Path ws;

    private static BuildModel.Extract extractFor(Path repoDir, String name, List<String> plugins,
                                                 List<GradleModel.DeclaredDep> deps) {
        return GradleBuildExtractor.adapt(new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", name, "com.acme", "1.0", repoDir, plugins, false, List.of(),
                Map.of("compileClasspath",
                        new GradleModel.DepConfig(deps, List.of(), List.of())))),
                List.of()));
    }

    @Test
    void aCrossRepoImplementorIsNamedAndReachableFromTheInterface() {
        FixtureRepo.in(ws, "lib-fix")
                .file("src/main/java/com/acme/fix/SessionListener.java", """
                        package com.acme.fix;
                        public interface SessionListener {
                            void onLogon(String session);
                        }
                        """)
                .commit("init");
        FixtureRepo.in(ws, "svc-venue")
                .file("src/main/java/com/acme/venue/VenueSimulator.java", """
                        package com.acme.venue;
                        import com.acme.fix.SessionListener;
                        public class VenueSimulator implements SessionListener {
                            public void onLogon(String session) {}
                        }
                        """)
                // Same repo, no import: the package-local rung of the resolution ladder.
                .file("src/main/java/com/acme/venue/LoudSimulator.java", """
                        package com.acme.venue;
                        public class LoudSimulator extends VenueSimulator {
                        }
                        """)
                .commit("init");

        SddConfig config = new SddConfig(ws, Map.of(), Map.of(), null, List.of(), Map.of(),
                List.of(), List.of(), RunSettings.defaults(), Map.of());
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir ->
                    repoDir.getFileName().toString().equals("lib-fix")
                            ? extractFor(repoDir, "lib-fix", List.of("java-library", "maven-publish"),
                                    List.of())
                            : extractFor(repoDir, "svc-venue", List.of("java"),
                                    List.of(new GradleModel.DeclaredDep("com.acme", "lib-fix", "1.0"))));
            assertThat(service.run(config, db))
                    .allSatisfy(r -> assertThat(r.parseStatus()).isEqualTo("OK"));

            // The cross-repo implementor is named — the thing api_usage cannot do.
            List<KbHierarchy.Subtype> direct =
                    KbHierarchy.subtypesOf(db.jdbi(), "com.acme.fix.SessionListener");
            assertThat(direct).singleElement().satisfies(s -> {
                assertThat(s.repo()).isEqualTo("svc-venue");
                assertThat(s.fqcn()).isEqualTo("com.acme.venue.VenueSimulator");
                assertThat(s.relation()).isEqualTo("IMPLEMENTS");
                assertThat(s.resolution()).isEqualTo("IMPORT");
            });

            // ...and the walk is transitive, which is what an interface change actually needs:
            // LoudSimulator breaks too, and it never mentions SessionListener anywhere.
            assertThat(KbHierarchy.subtypeClosure(db.jdbi(),
                    Set.of("com.acme.fix.SessionListener")))
                    .containsExactlyInAnyOrder("com.acme.venue.VenueSimulator",
                            "com.acme.venue.LoudSimulator");
        }
    }

    @Test
    void aSupertypeThatIsNotImportedIsRecordedAsAGuessRatherThanDroppedOrAsserted() {
        FixtureRepo.in(ws, "svc-solo")
                .file("src/main/java/com/acme/solo/Base.java",
                        "package com.acme.solo;\npublic class Base {}\n")
                .file("src/main/java/com/acme/solo/Derived.java",
                        "package com.acme.solo;\npublic class Derived extends Base {}\n")
                .commit("init");

        SddConfig config = new SddConfig(ws, Map.of(), Map.of(), null, List.of(), Map.of(),
                List.of(), List.of(), RunSettings.defaults(), Map.of());
        try (Database db = Database.open(ws)) {
            new IndexService(repoDir -> extractFor(repoDir, "svc-solo", List.of("java"), List.of()))
                    .run(config, db);

            // SAME_PACKAGE, not IMPORT: the row records how it was arrived at, so a reader can tell
            // a resolved supertype from an inferred one. unresolved != nonexistent, and neither is
            // it certainty.
            assertThat(KbHierarchy.subtypesOf(db.jdbi(), "com.acme.solo.Base"))
                    .singleElement().satisfies(s -> {
                        assertThat(s.fqcn()).isEqualTo("com.acme.solo.Derived");
                        assertThat(s.relation()).isEqualTo("EXTENDS");
                        assertThat(s.resolution()).isEqualTo("SAME_PACKAGE");
                    });
        }
    }
}
