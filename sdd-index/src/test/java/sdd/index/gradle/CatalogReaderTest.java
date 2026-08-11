package sdd.index.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogReaderTest {
    @TempDir Path repo;

    @Test
    void readsLibraryCoordinates() throws Exception {
        Files.createDirectories(repo.resolve("gradle"));
        Files.writeString(repo.resolve("gradle/libs.versions.toml"), """
                [versions]
                core = "2.3.0"
                [libraries]
                lib-core = { module = "com.acme:lib-core", version.ref = "core" }
                commons = { group = "org.apache.commons", name = "commons-lang3", version = "3.14.0" }
                """);
        assertThat(CatalogReader.internalGAs(repo))
                .containsExactlyInAnyOrder("com.acme:lib-core", "org.apache.commons:commons-lang3");
    }

    @Test
    void absentCatalogYieldsEmpty() {
        assertThat(CatalogReader.internalGAs(repo)).isEmpty();
    }
}
