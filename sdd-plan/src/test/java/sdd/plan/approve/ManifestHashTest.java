package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestHashTest {

    @TempDir Path dir;

    private void write(String relative, String body) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, body);
    }

    @Test
    void anEditToAnyFileMovesTheHash() throws Exception {
        write("proposal.md", "why");
        write("specs/a/spec.md", "requirement");
        String before = ManifestHash.of(dir);

        write("specs/a/spec.md", "requirement, edited");

        assertThat(ManifestHash.of(dir)).isNotEqualTo(before);
    }

    /** A file appearing or vanishing is an edit too — a path-only change must move the hash. */
    @Test
    void addingOrRemovingAFileMovesTheHash() throws Exception {
        write("proposal.md", "why");
        String before = ManifestHash.of(dir);

        write("design.md", "");   // empty, so only the PATH is new

        assertThat(ManifestHash.of(dir)).isNotEqualTo(before);
    }

    /** The exclusion exists because the hash is written into estate.yaml — including it would
     *  hash a file containing its own hash. */
    @Test
    void anExcludedFileIsIgnoredHoweverItChanges() throws Exception {
        write("proposal.md", "why");
        write("estate.yaml", "approved: false");
        String before = ManifestHash.of(dir, "estate.yaml");

        write("estate.yaml", "approved: true\nartifacts_sha256: " + before);

        assertThat(ManifestHash.of(dir, "estate.yaml")).isEqualTo(before);
    }

    @Test
    void theSameContentHashesTheSameWhateverOrderItWasWritten() throws Exception {
        write("b.md", "second");
        write("a.md", "first");
        String one = ManifestHash.of(dir);

        Files.delete(dir.resolve("a.md"));
        Files.delete(dir.resolve("b.md"));
        write("a.md", "first");
        write("b.md", "second");

        assertThat(ManifestHash.of(dir)).isEqualTo(one);
    }
}
