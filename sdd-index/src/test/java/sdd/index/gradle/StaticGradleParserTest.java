package sdd.index.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StaticGradleParserTest {
    @TempDir Path repo;

    @Test
    void parsesQuotedMapAndCatalogDependencies() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [versions]
                core = "2.3.0"
                [libraries]
                lib-core = { module = "com.acme:lib-core", version.ref = "core" }
                """);
        Files.writeString(repo.resolve("settings.gradle"), "rootProject.name = 'svc-x'\n");
        Files.writeString(repo.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.2.0'
                }
                dependencies {
                    implementation 'org.apache.commons:commons-lang3:3.14.0'
                    api group: 'com.acme', name: 'lib-events', version: '1.0.0-SNAPSHOT'
                    implementation(libs.lib.core)
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.3'
                }
                """);

        GradleModel.Extract e = StaticGradleParser.parse(repo);

        assertThat(e.projects()).hasSize(1);
        GradleModel.Project p = e.projects().get(0);
        assertThat(p.plugins()).contains("java", "org.springframework.boot");
        GradleModel.DepConfig cc = p.configurations().get("compileClasspath");
        assertThat(cc.declared()).extracting(GradleModel.DeclaredDep::name)
                .contains("commons-lang3", "lib-events", "lib-core")
                .doesNotContain("junit-jupiter"); // test-only configs are not compileClasspath
        assertThat(cc.declared()).filteredOn(d -> d.name().equals("lib-core"))
                .first().satisfies(d -> {
                    assertThat(d.group()).isEqualTo("com.acme");
                    assertThat(d.version()).isEqualTo("2.3.0");
                });
        assertThat(cc.resolved()).isEmpty();
    }

    @Test
    void scansFirstLevelSubmodules() throws Exception {
        Files.writeString(repo.resolve("settings.gradle"), "include 'app'\n");
        Files.createDirectories(repo.resolve("app"));
        Files.writeString(repo.resolve("app/build.gradle.kts"), """
                plugins { id("java-library") }
                dependencies { implementation("com.acme:lib-core:2.0.0") }
                """);

        GradleModel.Extract e = StaticGradleParser.parse(repo);
        assertThat(e.projects()).extracting(GradleModel.Project::path).contains(":app");
        assertThat(e.projects()).filteredOn(p -> p.path().equals(":app")).first()
                .satisfies(p -> assertThat(p.configurations().get("compileClasspath").declared())
                        .extracting(GradleModel.DeclaredDep::name).contains("lib-core"));
    }
}
