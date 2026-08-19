package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstateJailTest {
    @TempDir Path tmp;

    private EstateJail jail() throws Exception {
        Map<String, Path> roots = new LinkedHashMap<>();
        for (String repo : new String[] {"repo-a", "repo-b"}) {
            Path root = Files.createDirectories(tmp.resolve(repo).resolve("src"));
            Files.writeString(root.resolve("Same.java"), "class Same {}   // " + repo);
            Files.createDirectories(tmp.resolve(repo).resolve(".git"));
            Files.writeString(tmp.resolve(repo).resolve(".git").resolve("config"), "[core]");
            roots.put(repo, tmp.resolve(repo));
        }
        return new EstateJail(roots);
    }

    @Test
    void theRepoPrefixSelectsBetweenIdenticalPathsInDifferentRepos() throws Exception {
        EstateJail jail = jail();

        assertThat(Files.readString(jail.resolveExisting("repo-a/src/Same.java"))).endsWith("repo-a");
        assertThat(Files.readString(jail.resolveExisting("repo-b/src/Same.java"))).endsWith("repo-b");
    }

    @Test
    void anUnknownRepoSaysWhatToDoAboutIt() throws Exception {
        assertThatThrownBy(() -> jail().resolveExisting("repo-c/src/Same.java"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown repo 'repo-c'")
                .hasMessageContaining("list_repos");
    }

    @Test
    void gitStaysOffLimitsPerRoot() throws Exception {
        // The delegated PathJail owns this rule; the test exists to prove delegation actually
        // happens rather than the check being lost in translation.
        assertThatThrownBy(() -> jail().resolveExisting("repo-a/.git/config"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining(".git is off-limits");
    }

    @Test
    void escapingOneRootIntoAnotherRepoIsStillAnEscape() throws Exception {
        // repo-b IS in the estate, but reaching it via ../ from repo-a bypasses the addressing
        // scheme, so the per-root containment check must still refuse it.
        assertThatThrownBy(() -> jail().resolveExisting("repo-a/../repo-b/src/Same.java"))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void aSymlinkPointingOutOfItsRootIsRefused() throws Exception {
        EstateJail jail = jail();
        Path secret = Files.writeString(tmp.resolve("outside.txt"), "secret");
        Files.createSymbolicLink(tmp.resolve("repo-a").resolve("link.txt"), secret);

        assertThatThrownBy(() -> jail.resolveExisting("repo-a/link.txt"))
                .isInstanceOf(ToolException.class);
    }
}
