package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NpmVersionBumpTest {
    @TempDir Path repo;

    private Path manifest(String rel, String json) throws Exception {
        Path file = repo.resolve(rel);
        Files.createDirectories(file.getParent() == null ? repo : file.getParent());
        Files.writeString(file, json);
        return file;
    }

    private static final String CONSUMER = """
            {
              "name": "mfe-a",
              "version": "0.1.0",
              "dependencies": {
                "@acme/web-sdk": "^0.2.1",
                "react": "18.3.1"
              },
              "devDependencies": {
                "@acme/design-system": "^0.1.0"
              }
            }
            """;

    @Test
    void aRangeThatStillAdmitsTheNewVersionIsLeftAlone() throws Exception {
        Path file = manifest("package.json", CONSUMER);

        NpmVersionBump.Result result = NpmVersionBump.apply(repo, "@acme/web-sdk", "0.2.5");

        // The common case, and the reason this usually does nothing: rewriting ^0.2.1 to ^0.2.5
        // would be churn in a diff a human has to review, for no change in what resolves.
        assertThat(result.edited()).isEmpty();
        assertThat(Files.readString(file)).contains("\"@acme/web-sdk\": \"^0.2.1\"");
    }

    @Test
    void aRangeThatStopsAdmittingTheProviderIsWidenedMinimally() throws Exception {
        Path file = manifest("package.json", CONSUMER);

        // ^0.2.1 does NOT admit 0.3.0: for a 0.x package the minor is the breaking-change axis.
        // Left alone the consumer silently keeps resolving the old package and the release ships a
        // pairing nobody tested.
        NpmVersionBump.Result result = NpmVersionBump.apply(repo, "@acme/web-sdk", "0.3.0");

        assertThat(result.edited()).containsExactly(file);
        assertThat(Files.readString(file)).contains("\"@acme/web-sdk\": \"^0.3.0\"")
                .contains("\"react\": \"18.3.1\"");           // untouched
    }

    @Test
    void caretAboveZeroHoldsTheMajorSoAMinorBumpNeedsNoEdit() throws Exception {
        manifest("package.json", """
                {"name":"x","dependencies":{"@acme/lib":"^1.2.0"}}
                """);

        assertThat(NpmVersionBump.apply(repo, "@acme/lib", "1.9.0").edited()).isEmpty();
        assertThat(NpmVersionBump.apply(repo, "@acme/lib", "2.0.0").edited()).hasSize(1);
    }

    @Test
    void aTildeRangeHoldsTheMinor() throws Exception {
        manifest("package.json", """
                {"name":"x","dependencies":{"@acme/lib":"~1.2.0"}}
                """);

        assertThat(NpmVersionBump.apply(repo, "@acme/lib", "1.2.9").edited()).isEmpty();
        assertThat(NpmVersionBump.apply(repo, "@acme/lib", "1.3.0").edited()).hasSize(1);
    }

    @Test
    void anExactPinIsMoved() throws Exception {
        Path file = manifest("package.json", """
                {"name":"x","dependencies":{"@acme/lib":"1.2.0"}}
                """);

        assertThat(NpmVersionBump.apply(repo, "@acme/lib", "1.3.0").edited()).containsExactly(file);
        // The fixture is compact JSON; the edit replaces only the specifier, so the
        // original spacing is exactly what comes back.
        assertThat(Files.readString(file)).contains("\"@acme/lib\":\"1.3.0\"");
    }

    @Test
    void aRangeShapeWeDoNotUnderstandIsReportedRatherThanGuessedAt() throws Exception {
        Path file = manifest("package.json", """
                {"name":"x","dependencies":{"@acme/lib":">=1.0.0 <2.0.0"}}
                """);

        NpmVersionBump.Result result = NpmVersionBump.apply(repo, "@acme/lib", "2.0.0");

        assertThat(result.edited()).isEmpty();
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w)
                .contains("not understood").contains("by hand"));
        assertThat(Files.readString(file)).contains(">=1.0.0 <2.0.0");   // untouched
    }

    @Test
    void aSourceProtocolSpecifierIsNeverGivenAVersion() throws Exception {
        Path file = manifest("package.json", """
                {"name":"x","dependencies":{"@acme/lib":"workspace:*"}}
                """);

        assertThat(NpmVersionBump.apply(repo, "@acme/lib", "9.9.9").edited()).isEmpty();
        assertThat(Files.readString(file)).contains("workspace:*");
    }

    @Test
    void thePackagesOwnVersionIsNeverMistakenForADependency() throws Exception {
        // "version" sits outside any dependency block; a bump of a package that happens to share
        // the repo's name must not rewrite the repo's own version.
        Path file = manifest("package.json", """
                {
                  "name": "@acme/lib",
                  "version": "0.2.1",
                  "dependencies": { "other": "^1.0.0" }
                }
                """);

        NpmVersionBump.apply(repo, "@acme/lib", "0.3.0");

        assertThat(Files.readString(file)).contains("\"version\": \"0.2.1\"");
    }

    @Test
    void everyWorkspaceMemberIsRewrittenAndNodeModulesIsNeverTouched() throws Exception {
        manifest("package.json", "{\"name\":\"root\",\"private\":true}");
        Path shell = manifest("packages/shell/package.json", """
                {"name":"shell","dependencies":{"@acme/lib":"^1.0.0"}}
                """);
        Path vendored = manifest("node_modules/other/package.json", """
                {"name":"other","dependencies":{"@acme/lib":"^1.0.0"}}
                """);

        NpmVersionBump.Result result = NpmVersionBump.apply(repo, "@acme/lib", "2.0.0");

        assertThat(result.edited()).containsExactly(shell);
        assertThat(Files.readString(vendored)).contains("^1.0.0");
    }

    @Test
    void anAdjacentLockfileIsReportedRatherThanHandEdited() throws Exception {
        manifest("package.json", CONSUMER);
        Files.writeString(repo.resolve("package-lock.json"), "{\"lockfileVersion\":3}");

        NpmVersionBump.Result result = NpmVersionBump.apply(repo, "@acme/web-sdk", "0.3.0");

        // A hand-patched lockfile is internally inconsistent in ways npm resolves unpredictably,
        // which is worse than an absent edit. Nothing sdd runs consumes it.
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w)
                .contains("package-lock.json").contains("npm install"));
    }

    @Test
    void formattingSurvivesAnEdit() throws Exception {
        Path file = manifest("package.json", CONSUMER);

        NpmVersionBump.apply(repo, "@acme/web-sdk", "0.3.0");

        // A JSON round-trip would reformat key order and indentation, turning a one-line version
        // change into a whole-file diff for a human to review.
        String after = Files.readString(file);
        assertThat(after).contains("  \"name\": \"mfe-a\",").contains("    \"react\": \"18.3.1\"");
        assertThat(after.lines().count()).isEqualTo(CONSUMER.lines().count());
    }
}
