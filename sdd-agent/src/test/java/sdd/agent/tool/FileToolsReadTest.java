package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
