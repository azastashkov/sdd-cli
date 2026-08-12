package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathJailTest {
    @TempDir Path root;

    @Test
    void resolvesInsidePathsAndRejectsEscapesAndDotGit() {
        PathJail jail = new PathJail(root);

        assertThat(jail.resolve("src/A.java")).isEqualTo(root.resolve("src/A.java").normalize());
        assertThatThrownBy(() -> jail.resolve("../outside"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThatThrownBy(() -> jail.resolve("src/../../x"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThatThrownBy(() -> jail.resolve(".git/config"))
                .isInstanceOf(ToolException.class).hasMessageContaining(".git");
        assertThatThrownBy(() -> jail.resolve("modules/.git/HEAD"))
                .isInstanceOf(ToolException.class).hasMessageContaining(".git");
    }

    @Test
    void resolveExistingFollowsSymlinksBackIntoTheJail() throws Exception {
        Path outside = Files.createDirectory(root.resolveSibling("outside-" + root.getFileName()));
        Files.writeString(outside.resolve("secret.txt"), "x");
        Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"));
        PathJail jail = new PathJail(root);

        // logical resolve passes (name is inside), but resolveExisting's toRealPath escapes → rejected
        assertThatThrownBy(() -> jail.resolveExisting("link.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThatThrownBy(() -> jail.resolveExisting("missing.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no such file");
    }

    @Test
    void resolveCreatableRejectsASymlinkedParentDirectoryEscapingTheJail() throws Exception {
        Path outside = Files.createDirectory(root.resolveSibling("outside-parent-" + root.getFileName()));
        Files.createSymbolicLink(root.resolve("docs"), outside);
        PathJail jail = new PathJail(root);

        // "docs" logically resolves inside the jail (resolve() alone would pass it), but it is a
        // symlink to an outside directory and the target file doesn't exist yet, so only the
        // ancestor-realpath walk in resolveCreatable can catch this.
        assertThatThrownBy(() -> jail.resolveCreatable("docs/x.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThat(Files.exists(outside.resolve("x.txt"))).isFalse();
    }

    @Test
    void resolveCreatableAllowsNewFilesUnderRealSubdirectories() throws Exception {
        Files.createDirectories(root.resolve("src"));
        PathJail jail = new PathJail(root);

        assertThat(jail.resolveCreatable("src/New.java"))
                .isEqualTo(root.resolve("src/New.java").normalize());
    }

    @Test
    void resolveCreatableWalksUpThroughMultipleNonExistentAncestors() throws Exception {
        PathJail jail = new PathJail(root);

        // none of a/b/c exist yet; the walk-up must reach root (which does exist) without NPEing
        assertThat(jail.resolveCreatable("a/b/c/New.java"))
                .isEqualTo(root.resolve("a/b/c/New.java").normalize());
    }

    @Test
    void resolveCreatableDelegatesToResolveExistingWhenTheTargetAlreadyExists() throws Exception {
        Path outside = Files.createDirectory(root.resolveSibling("outside-target-" + root.getFileName()));
        Files.writeString(outside.resolve("secret.txt"), "x");
        Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"));
        PathJail jail = new PathJail(root);

        assertThatThrownBy(() -> jail.resolveCreatable("link.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
    }

    @Test
    void resolveRejectsANulByteInThePath() {
        PathJail jail = new PathJail(root);

        assertThatThrownBy(() -> jail.resolve("a\0b"))
                .isInstanceOf(ToolException.class).hasMessageContaining("invalid path");
    }
}
