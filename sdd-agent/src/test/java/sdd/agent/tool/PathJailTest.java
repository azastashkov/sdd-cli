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
}
