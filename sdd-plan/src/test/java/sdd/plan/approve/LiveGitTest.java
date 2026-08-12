package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveGitTest {
    @TempDir Path dir;

    @Test
    void readsHeadAndCleanlinessAndFlipsDirtyOnEdit() throws Exception {
        FixtureRepo.in(dir, "r1").file("a.txt", "x").commit("init");
        Path repo = dir.resolve("r1");

        LiveGit.State clean = LiveGit.state(repo);
        assertThat(clean.head()).hasSize(40);
        assertThat(clean.clean()).isTrue();

        Files.writeString(repo.resolve("a.txt"), "changed");
        LiveGit.State dirty = LiveGit.state(repo);
        assertThat(dirty.head()).isEqualTo(clean.head());
        assertThat(dirty.clean()).isFalse();
    }

    @Test
    void nonRepoFailsLoudly() {
        assertThatThrownBy(() -> LiveGit.state(dir.resolve("nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot read git state");
    }
}
