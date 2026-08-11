package sdd.plan.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownSpecSourceTest {
    @TempDir Path dir;

    @Test
    void loadsAndParsesAFileRef() throws Exception {
        Path file = dir.resolve("s.md");
        Files.writeString(file, """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc
                """);
        assertThat(new MarkdownSpecSource().load(file.toString()).id()).isEqualTo("S-1");
    }

    @Test
    void missingFileSurfacesAsUncheckedIo() {
        assertThatThrownBy(() -> new MarkdownSpecSource().load(dir.resolve("nope.md").toString()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void refShapeSelection() {
        assertThat(SpecSources.isConfluenceExport("page.HTML")).isTrue();
        assertThat(SpecSources.isConfluenceExport("page.xhtml")).isTrue();
        assertThat(SpecSources.isConfluenceExport("page.htm")).isTrue();
        assertThat(SpecSources.isConfluenceExport("spec.md")).isFalse();
    }
}
