package sdd.index.testing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureGradleRepoTest {
    @TempDir Path tmp;

    @Test
    void buildsRepoWithPinnedWrapper() throws Exception {
        Path repo = FixtureGradleRepo.in(tmp, "lib-a", "8.10.2")
                .withSettings("rootProject.name = 'lib-a'\n")
                .withBuildFile("plugins { id 'java-library' }\n")
                .commit();
        assertThat(Files.isExecutable(repo.resolve("gradlew"))).isTrue();
        assertThat(Files.readString(repo.resolve("gradle/wrapper/gradle-wrapper.properties")))
                .contains("gradle-8.10.2-bin.zip");
        assertThat(repo.resolve(".git")).exists();
        assertThat(repo.resolve("gradle/wrapper/gradle-wrapper.jar")).exists();
    }
}
