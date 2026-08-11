package sdd.core.testing;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureRepoTest {
    @TempDir Path tmp;

    @Test
    void buildsCommittedGitRepo() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib-a")
                .file("settings.gradle", "rootProject.name = 'lib-a'\n")
                .file("src/main/java/A.java", "public class A {}\n")
                .commit("init");

        assertThat(Files.readString(repo.path().resolve("settings.gradle")))
                .contains("lib-a");
        assertThat(repo.headSha()).hasSize(40);
        try (Git git = Git.open(repo.path().toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo("main");
            assertThat(git.status().call().isClean()).isTrue();
        }
    }

    @Test
    void multipleCommitsAdvanceHead() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "r").file("a.txt", "1").commit("c1");
        String first = repo.headSha();
        repo.file("a.txt", "2").commit("c2");
        assertThat(repo.headSha()).isNotEqualTo(first);
    }
}
