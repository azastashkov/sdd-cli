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
}
