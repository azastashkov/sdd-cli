package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatch that decides which edit a planned bump actually is. Its absence is what let an npm
 * consumer's pin go unmoved: {@link VersionBump} only ever walks Gradle build files, so an
 * {@code ("npm", "@scope/pkg")} coordinate matched nothing and was reported as "no declaration
 * found" — leaving the agent to guess a version, which is exactly what it did.
 */
class BumpEditsTest {
    @TempDir Path repo;

    private void packageJson(String deps) throws Exception {
        Files.writeString(repo.resolve("package.json"),
                "{\n  \"name\": \"consumer\",\n  \"dependencies\": {\n" + deps + "\n  }\n}\n");
    }

    // ------------------------------------------------------------------ npm

    @Test
    void anNpmRangeThatNoLongerAdmitsThePlannedVersionIsWidened() throws Exception {
        packageJson("    \"@acme/web-sdk\": \"^0.2.1\"");

        List<String> events = BumpEdits.apply(repo,
                new RepoPropagation.BumpEdit("npm", "@acme/web-sdk", "^0.2.1", "0.3.0"));

        assertThat(Files.readString(repo.resolve("package.json"))).contains("\"^0.3.0\"");
        assertThat(events).anySatisfy(e ->
                assertThat(e).contains("npm:@acme/web-sdk").contains("0.3.0"));
    }

    @Test
    void anNpmRangeThatStillAdmitsThePlannedVersionIsLeftAlone() throws Exception {
        // ^0.2.1 admits 0.2.4, so rewriting it would be churn in a diff a human has to read.
        packageJson("    \"@acme/web-sdk\": \"^0.2.1\"");

        BumpEdits.apply(repo, new RepoPropagation.BumpEdit("npm", "@acme/web-sdk", "^0.2.1", "0.2.4"));

        assertThat(Files.readString(repo.resolve("package.json"))).contains("\"^0.2.1\"");
    }

    @Test
    void anNpmBumpReportsTheEditItMadeRatherThanClaimingNoDeclarationExists() throws Exception {
        packageJson("    \"@acme/web-sdk\": \"^0.2.1\"");

        List<String> events = BumpEdits.apply(repo,
                new RepoPropagation.BumpEdit("npm", "@acme/web-sdk", "^0.2.1", "0.3.0"));

        assertThat(events).noneSatisfy(e -> assertThat(e).contains("no declaration"));
    }

    @Test
    void anNpmCoordinateThisRepoDoesNotDeclareIsReportedAsUnedited() throws Exception {
        packageJson("    \"@acme/other\": \"^1.0.0\"");

        List<String> events = BumpEdits.apply(repo,
                new RepoPropagation.BumpEdit("npm", "@acme/web-sdk", "^0.2.1", "0.3.0"));

        assertThat(events).anySatisfy(e -> assertThat(e).contains("left unedited"));
    }

    // ------------------------------------------------------------------ gradle, unchanged

    @Test
    void aGradleDeclarationIsStillBumpedAtItsDeclarationSite() throws Exception {
        Files.writeString(repo.resolve("build.gradle"),
                "dependencies { implementation 'com.acme:lib:1.2.3' }\n");

        List<String> events = BumpEdits.apply(repo,
                new RepoPropagation.BumpEdit("com.acme", "lib", "1.2.3", "1.3.0"));

        assertThat(Files.readString(repo.resolve("build.gradle"))).contains("com.acme:lib:1.3.0");
        assertThat(events).anySatisfy(e ->
                assertThat(e).contains("com.acme:lib").contains("1.3.0"));
    }

    @Test
    void aGradleCoordinateWithNoDeclarationIsReportedAsUnedited() throws Exception {
        Files.writeString(repo.resolve("build.gradle"), "dependencies { }\n");

        List<String> events = BumpEdits.apply(repo,
                new RepoPropagation.BumpEdit("com.acme", "lib", "1.2.3", "1.3.0"));

        assertThat(events).anySatisfy(e -> assertThat(e).contains("left unedited"));
    }
}
