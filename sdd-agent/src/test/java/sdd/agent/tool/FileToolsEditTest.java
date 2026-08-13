package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolsEditTest {
    @TempDir Path root;
    private FileTools tools;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("src"));
        tools = new FileTools(new PathJail(root));
    }

    @Test
    void createsANewFileWithAnEmptySearchBlock() throws Exception {
        String result = tools.applyEdit("src/New.java", "", "class New {}\n");

        assertThat(result).isEqualTo("created src/New.java");
        assertThat(Files.readString(root.resolve("src/New.java"))).isEqualTo("class New {}\n");
    }

    @Test
    void exactSubstringEditsApply() throws Exception {
        Files.writeString(root.resolve("src/A.java"), "class A {\n    int x = 1;\n}\n");

        assertThat(tools.applyEdit("src/A.java", "int x = 1;", "int x = 2;"))
                .isEqualTo("edited src/A.java");
        assertThat(Files.readString(root.resolve("src/A.java"))).contains("int x = 2;");

        // an unindented needle is still an exact substring of the indented line
        assertThat(tools.applyEdit("src/A.java", "int x = 2;", "int x = 3;"))
                .isEqualTo("edited src/A.java");
        assertThat(Files.readString(root.resolve("src/A.java"))).contains("int x = 3;");
    }

    @Test
    void multiLineBlockWithDifferentIndentationMatchesViaLenientFallback() throws Exception {
        Files.writeString(root.resolve("src/A.java"),
                "class A {\n    void m() {\n        int x = 1;\n        int y = 2;\n    }\n}\n");
        // the two-line needle has NO indentation, so exact indexOf fails and the line-stripped
        // lenient path runs (its multi-line match + splice/rejoin was previously uncovered)
        String search = "int x = 1;\nint y = 2;";

        assertThat(tools.applyEdit("src/A.java", search, "int x = 10;\nint y = 20;"))
                .isEqualTo("edited src/A.java");
        assertThat(Files.readString(root.resolve("src/A.java")))
                .contains("int x = 10;").contains("int y = 20;");
    }

    @Test
    void javaEditThatBreaksSyntaxIsRevertedAndReported() throws Exception {
        Path a = root.resolve("src/A.java");
        Files.writeString(a, "class A {\n    int x = 1;\n}\n");

        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int x = 1;", "int x = ;"))
                .isInstanceOf(ToolException.class).hasMessageContaining("syntax");
        assertThat(Files.readString(a)).isEqualTo("class A {\n    int x = 1;\n}\n");   // unchanged
    }

    @Test
    void creationThroughASymlinkedParentDirectoryIsRejectedAndNothingIsWrittenOutside() throws Exception {
        Path outside = Files.createDirectory(root.resolveSibling("outside-" + root.getFileName()));
        Files.createSymbolicLink(root.resolve("docs"), outside);

        assertThatThrownBy(() -> tools.applyEdit("docs/x.txt", "", "hi"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThat(Files.exists(outside.resolve("x.txt"))).isFalse();

        // a normal creation in a real (non-symlinked) subdir still works
        assertThat(tools.applyEdit("src/Real.java", "", "class Real {}\n"))
                .isEqualTo("created src/Real.java");
        assertThat(Files.readString(root.resolve("src/Real.java"))).isEqualTo("class Real {}\n");
    }

    @Test
    void missingAmbiguousAndCreateOverExistingFail() throws Exception {
        Files.writeString(root.resolve("src/A.java"), "class A {\n  int x;\n  int x;\n}\n");

        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int y;", "int z;"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no match");
        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int x;", "int q;"))
                .isInstanceOf(ToolException.class).hasMessageContaining("ambiguous");
        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "", "class A {}"))
                .isInstanceOf(ToolException.class).hasMessageContaining("already exists");
    }

    @Test
    void successfulEditAndCreationAreRecordedWithCorrectActionAndLineCounts() throws Exception {
        Files.writeString(root.resolve("src/A.java"), "class A {\n    int x = 1;\n}\n");

        tools.applyEdit("src/A.java", "int x = 1;", "int x = 2;\nint y = 3;");
        tools.applyEdit("src/New.java", "", "class New {\n    int z;\n}\n");

        assertThat(tools.appliedEdits()).hasSize(2);
        FileTools.AppliedEdit edit = tools.appliedEdits().get(0);
        assertThat(edit.path()).isEqualTo("src/A.java");
        assertThat(edit.action()).isEqualTo("edit");
        assertThat(edit.searchLines()).isEqualTo(1);
        assertThat(edit.replaceLines()).isEqualTo(2);
        FileTools.AppliedEdit created = tools.appliedEdits().get(1);
        assertThat(created.path()).isEqualTo("src/New.java");
        assertThat(created.action()).isEqualTo("create");
        assertThat(created.searchLines()).isEqualTo(0);
        assertThat(created.replaceLines()).isEqualTo(3);
    }

    @Test
    void aSyntaxRevertedJavaEditIsNotRecorded() throws Exception {
        Files.writeString(root.resolve("src/A.java"), "class A {\n    int x = 1;\n}\n");

        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int x = 1;", "int x = ;"))
                .isInstanceOf(ToolException.class);

        assertThat(tools.appliedEdits()).isEmpty();
    }

    @Test
    void aFailedAmbiguousOrNotFoundEditIsNotRecorded() throws Exception {
        Files.writeString(root.resolve("src/A.java"), "class A {\n  int x;\n  int x;\n}\n");

        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int y;", "int z;"))
                .isInstanceOf(ToolException.class);
        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int x;", "int q;"))
                .isInstanceOf(ToolException.class);

        assertThat(tools.appliedEdits()).isEmpty();
    }
}
