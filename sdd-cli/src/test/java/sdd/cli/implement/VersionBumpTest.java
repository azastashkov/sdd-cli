package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionBumpTest {
    @TempDir Path repo;

    @Test
    void bumpsADirectDeclarationInBuildGradle() throws Exception {
        Files.writeString(repo.resolve("build.gradle"),
                "dependencies {\n    implementation \"com.acme:lib:1.2.3\"\n}\n");

        List<Path> edited = VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(edited).containsExactly(repo.resolve("build.gradle"));
        assertThat(Files.readString(repo.resolve("build.gradle")))
                .contains("com.acme:lib:1.3.0").doesNotContain("1.2.3");
    }

    @Test
    void bumpsSubprojectKtsFilesButSkipsBuildAndGitDirs() throws Exception {
        Files.createDirectories(repo.resolve("app"));
        Files.writeString(repo.resolve("app/build.gradle.kts"),
                "dependencies {\n    implementation(\"com.acme:lib:1.2.3\")\n}\n");
        Files.createDirectories(repo.resolve("build"));
        Files.writeString(repo.resolve("build/build.gradle"), "// generated com.acme:lib:1.2.3\n");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/build.gradle"), "com.acme:lib:1.2.3\n");

        List<Path> edited = VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(edited).containsExactly(repo.resolve("app/build.gradle.kts"));
        assertThat(Files.readString(repo.resolve("build/build.gradle"))).contains("1.2.3");
    }

    @Test
    void bumpsAnInlineCatalogCoordinate() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [libraries]
                acme-lib = "com.acme:lib:1.2.3"
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(Files.readString(repo.resolve("gradle/libs.versions.toml")))
                .contains("\"com.acme:lib:1.3.0\"");
    }

    @Test
    void bumpsACatalogVersionRef() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [versions]
                acmeLib = "1.2.3"
                other = "1.2.3"

                [libraries]
                acme-lib = { module = "com.acme:lib", version.ref = "acmeLib" }
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        String toml = Files.readString(repo.resolve("gradle/libs.versions.toml"));
        assertThat(toml).contains("acmeLib = \"1.3.0\"");
        assertThat(toml).contains("other = \"1.2.3\"");   // only the referenced alias moves
    }

    @Test
    void bumpsAModuleLineWithInlineVersionKey() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [libraries]
                acme-lib = { module = "com.acme:lib", version = "1.2.3" }
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        assertThat(Files.readString(repo.resolve("gradle/libs.versions.toml")))
                .contains("version = \"1.3.0\"");
    }

    @Test
    void bumpsATableFormCatalogEntry() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [versions]
                acmeLib = "1.2.3"

                [libraries.acme-lib]
                module = "com.acme:lib"
                version.ref = "acmeLib"

                [libraries.other-lib]
                module = "org.ext:thing"
                version = "1.2.3"
                """);

        VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0");

        String toml = Files.readString(repo.resolve("gradle/libs.versions.toml"));
        assertThat(toml).contains("acmeLib = \"1.3.0\"");
        assertThat(toml).contains("version = \"1.2.3\"");   // other-lib's own version untouched
    }

    @Test
    void unmatchedDeclarationEditsNothing() throws Exception {
        Files.writeString(repo.resolve("build.gradle"),
                "dependencies { implementation \"com.acme:lib:9.9.9\" }\n");

        assertThat(VersionBump.apply(repo, "com.acme", "lib", "1.2.3", "1.3.0")).isEmpty();
        assertThat(Files.readString(repo.resolve("build.gradle"))).contains("9.9.9");
    }
}
