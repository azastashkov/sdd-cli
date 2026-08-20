package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileToolsReadTest {
    @TempDir Path root;
    private FileTools tools;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/a"));
        Files.writeString(root.resolve("src/main/java/a/A.java"), "class A {\n  int loyaltyTier;\n}\n");
        Files.writeString(root.resolve("src/main/java/a/B.java"), "class B {}\n");
        Files.createDirectories(root.resolve("build/classes"));
        Files.writeString(root.resolve("build/classes/ignore.txt"), "loyaltyTier here too\n");
        tools = new FileTools(new PathJail(root));
    }

    @Test
    void readFileReturnsContentAndCapsAt400LinesOr16Kb() throws Exception {
        assertThat(tools.readFile("src/main/java/a/A.java")).contains("int loyaltyTier;");

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append("line ").append(i).append('\n');
        }
        Files.writeString(root.resolve("big.txt"), big.toString());
        String read = tools.readFile("big.txt");
        assertThat(read).contains("line 0").contains("line 399")
                .doesNotContain("line 400")
                .contains("truncated");

        assertThatThrownBy(() -> tools.readFile("nope.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no such file");
    }

    @Test
    void listFilesSortsAndMarksDirectories() {
        String listing = tools.listFiles("src/main/java/a");
        assertThat(listing).isEqualTo("A.java\nB.java\n");

        assertThat(tools.listFiles("src/main/java")).isEqualTo("a/\n");
    }

    @Test
    void searchWalksSourceSkipsBuildDirsAndIsDeterministic() {
        String hits = tools.search("loyaltyTier");

        assertThat(hits).isEqualTo("src/main/java/a/A.java:2:   int loyaltyTier;\n");
        assertThat(hits).doesNotContain("build/");
    }

    @Test
    void searchSkipsNodeModulesDistAndTargetAsWalkNoise() throws Exception {
        Files.createDirectories(root.resolve("frontend/node_modules"));
        Files.writeString(root.resolve("frontend/node_modules/x.js"), "loyaltyTier\n");
        Files.createDirectories(root.resolve("frontend/dist"));
        Files.writeString(root.resolve("frontend/dist/x.js"), "loyaltyTier\n");
        Files.createDirectories(root.resolve("svc/target"));
        Files.writeString(root.resolve("svc/target/x.txt"), "loyaltyTier\n");

        String hits = tools.search("loyaltyTier");

        assertThat(hits).doesNotContain("node_modules").doesNotContain("dist/").doesNotContain("target/");
    }

    @Test
    void searchCapsAnOverlongMatchingLineAtMaxHitChars() throws Exception {
        String longLine = "x".repeat(5000) + "loyaltyTier" + "y".repeat(100);
        Files.writeString(root.resolve("long.txt"), longLine + "\n");

        String hits = tools.search("loyaltyTier");

        String hitLine = hits.lines().filter(l -> l.startsWith("long.txt:")).findFirst().orElseThrow();
        // "long.txt:1: " prefix + MAX_HIT_CHARS(300) chars of the line + the ellipsis marker
        assertThat(hitLine.length()).isLessThanOrEqualTo("long.txt:1: ".length() + 300 + 1 + 10);
        assertThat(hitLine).endsWith("…");
    }

    @Test
    void searchCapsTheTotalResultAtMaxSearchBytes() throws Exception {
        Files.createDirectories(root.resolve("many"));
        for (int f = 0; f < 5; f++) {
            StringBuilder content = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                content.append("loyaltyTier ").append("z".repeat(400)).append('\n');
            }
            Files.writeString(root.resolve("many/f" + f + ".txt"), content.toString());
        }

        String hits = tools.search("loyaltyTier");

        assertThat(hits.length()).isLessThanOrEqualTo(16384 + "... (more matches omitted)\n".length());
        assertThat(hits).contains("... (more matches omitted)\n");
    }

    @Test
    void listFilesOnAnUnreadableDirectoryThrowsToolExceptionNotUncheckedIOException() throws Exception {
        Path locked = root.resolve("locked");
        Files.createDirectory(locked);
        Files.writeString(locked.resolve("secret.txt"), "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(isUnreadable(locked), "POSIX perms not enforced for this user/filesystem");

            assertThatThrownBy(() -> tools.listFiles("locked"))
                    .isInstanceOf(ToolException.class)
                    .isNotInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("cannot list");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void searchOverAnUnreadableSubdirectoryThrowsToolExceptionNotUncheckedIOException() throws Exception {
        Path locked = root.resolve("src/main/java/locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("secret.txt"), "x");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(isUnreadable(locked), "POSIX perms not enforced for this user/filesystem");

            assertThatThrownBy(() -> tools.search("loyaltyTier"))
                    .isInstanceOf(ToolException.class)
                    .isNotInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("search failed");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private static boolean isUnreadable(Path dir) {
        try (Stream<Path> probe = Files.list(dir)) {
            probe.findAny();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    @Test
    void searchListsFilesByGlobWhenNoRegexIsGiven() throws Exception {
        // Implement-time search had no glob at all. Measured on a recorded run (estate11
        // SPEC-203-v1, trading-core), the agent tried to scope a search by writing the filename
        // into the regex -- SubmitRequest\\.java.*price, four times across turns 54-57 -- and got
        // an empty result each time, because hits match per LINE and a filename never shares a
        // line with the code it names.
        Files.createDirectories(root.resolve("src/test/java/a"));
        Files.writeString(root.resolve("src/test/java/a/ATest.java"), "class ATest {}\n");
        Files.writeString(root.resolve("src/main/java/a/Empty.java"), "");

        String listed = tools.search(null, "src/main/**/*.java");

        assertThat(listed).contains("src/main/java/a/A.java")
                .contains("src/main/java/a/B.java")
                // an empty file is nameable this way and can never be found by a content search
                .contains("src/main/java/a/Empty.java")
                .doesNotContain("ATest.java");
    }

    @Test
    void searchWithBothScopesTheContentSearchToTheGlob() throws Exception {
        Files.writeString(root.resolve("notes.md"), "loyaltyTier is discussed here\n");

        assertThat(tools.search("loyaltyTier", "**/*.java"))
                .contains("A.java").doesNotContain("notes.md");
    }

    @Test
    void searchWithNeitherArgumentSaysWhatItNeeds() {
        assertThatThrownBy(() -> tools.search(null, null))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("give a regex");
    }
}
