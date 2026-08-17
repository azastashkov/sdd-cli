package sdd.plan.confluence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.plan.source.SourceDoc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code loadDoc} is the shared read-file -> deterministic-extract -> {@link SourceDoc} step
 * that both {@link ConfluenceExportSource#load} and {@code sdd.cli.PlanCommand}'s multi-ref
 * general path call, rather than each re-implementing file I/O and extraction — see the code
 * review that added it (Task 1 finding 1). These tests pin its contract independent of either
 * caller.
 */
class ConfluenceExportSourceTest {
    @TempDir Path dir;

    @Test
    void loadDocExtractsTextAttachmentsAndDerivesIdFromTheFilename() throws Exception {
        Path file = dir.resolve("loyalty-page.html");
        Files.writeString(file, "<h1>T</h1><p>Prose.</p><p><img src=\"images/diagram.png\"></p>");

        SourceDoc doc = ConfluenceExportSource.loadDoc(file.toString());

        assertThat(doc.kind()).isEqualTo(SourceDoc.Kind.CONFLUENCE_PAGE);
        assertThat(doc.id()).isEqualTo("spec-loyalty-page");
        assertThat(doc.text()).contains("Prose.");
        assertThat(doc.attachments()).containsExactly("diagram.png");
        assertThat(doc.url()).isNull();
        assertThat(doc.title()).isNull();
    }

    @Test
    void loadDocSurfacesAMissingFileAsUncheckedIo() {
        assertThatThrownBy(() -> ConfluenceExportSource.loadDoc(dir.resolve("nope.html").toString()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void loadDocRejectsAnOversizedExportTheSameWayExtractAlwaysHas() throws Exception {
        // ConfluenceExtract enforces its own per-document cap; loadDoc must not swallow or
        // change that behaviour now that it sits behind a shared helper
        Path file = dir.resolve("huge.html");
        Files.writeString(file, "<p>" + "x".repeat(300_001) + "</p>");

        assertThatThrownBy(() -> ConfluenceExportSource.loadDoc(file.toString()))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("too large");
    }
}
