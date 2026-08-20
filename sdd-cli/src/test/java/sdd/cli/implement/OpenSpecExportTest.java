package sdd.cli.implement;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.plan.openspec.OpenSpecChange;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The writer's whole job is restraint: it writes under {@code changes/<our-id>/} and nowhere else,
 * and never over bytes it did not produce. The target repository belongs to somebody else, and an
 * export that clobbers a human's proposal destroys work while looking like a feature.
 */
class OpenSpecExportTest {

    @TempDir Path repo;

    private static final String CHANGE_ID = "spec-tiers-v1";

    private static OpenSpecChange.Files files(String proposalBody) {
        return new OpenSpecChange.Files("schema: spec-driven\n", proposalBody, "## Context\nx\n",
                "## 1. Do\n\n- [ ] 1.1 it\n",
                Map.of("tier-resolution", "# tier-resolution\n\n## ADDED Requirements\n"));
    }

    @BeforeEach
    void gitInit() throws Exception {
        Git.init().setDirectory(repo.toFile()).call().close();
    }

    private Path changeDir() {
        return repo.resolve("openspec/changes").resolve(CHANGE_ID);
    }

    @Test
    void writesTheWholeChangeIntoAnEmptyRepo() {
        OpenSpecExport.Result result =
                OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));

        assertThat(result.written()).isTrue();
        assertThat(changeDir().resolve("proposal.md")).exists();
        assertThat(changeDir().resolve(".openspec.yaml")).exists();
        assertThat(changeDir().resolve("tasks.md")).exists();
        assertThat(changeDir().resolve("design.md")).exists();
        assertThat(changeDir().resolve("specs/tier-resolution/spec.md")).exists();
        assertThat(result.events()).anySatisfy(e -> assertThat(e).contains("wrote change"));
    }

    @Test
    void aSecondIdenticalRunWritesNothing() {
        // The --retry / --resume path. It is only a no-op because the renderer is deterministic,
        // which is the concrete reason `created:` is never emitted.
        OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));

        OpenSpecExport.Result second =
                OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));

        assertThat(second.written()).isFalse();
        assertThat(second.events()).singleElement().asString()
                .contains("already present and identical");
    }

    @Test
    void aChangeDirectoryThatDiffersIsLeftEntirelyAloneNotMerged() throws Exception {
        OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));
        Files.writeString(changeDir().resolve("proposal.md"), "## Why\nA HUMAN EDITED THIS\n",
                StandardCharsets.UTF_8);

        OpenSpecExport.Result result =
                OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nregenerated\n"));

        assertThat(result.written()).isFalse();
        // Not merged, not partially written: every file keeps the bytes it had, including the ones
        // that were identical. A half-written change validates as nobody's intent.
        assertThat(Files.readString(changeDir().resolve("proposal.md")))
                .isEqualTo("## Why\nA HUMAN EDITED THIS\n");
        assertThat(result.events()).singleElement().asString()
                .contains("exists and differs").contains("proposal.md");
    }

    @Test
    void nothingIsEverWrittenUnderSpecs() {
        // openspec/specs/** is the repository's source of truth. Folding a delta into it is the
        // foreign agent's act, and archive's rules are where a reimplementation would diverge.
        OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));

        assertThat(repo.resolve("openspec/specs")).doesNotExist();
    }

    @Test
    void configYamlIsNeitherCreatedNorTouched() throws Exception {
        // Its schema for the targeted version is unverified; inventing one produces a file the real
        // CLI may reject. An existing one is a human's and is never read, let alone rewritten.
        OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));
        assertThat(repo.resolve("openspec/config.yaml")).doesNotExist();

        Files.createDirectories(repo.resolve("openspec"));
        Files.writeString(repo.resolve("openspec/config.yaml"), "schema: spec-driven\nmine: true\n");
        OpenSpecExport.materialize(repo, "other-change-v1", files("## Why\nbecause\n"));

        assertThat(Files.readString(repo.resolve("openspec/config.yaml")))
                .isEqualTo("schema: spec-driven\nmine: true\n");
    }

    @Test
    void anExistingUnrelatedChangeIsUntouched() throws Exception {
        Path other = repo.resolve("openspec/changes/somebody-elses-v1");
        Files.createDirectories(other);
        Files.writeString(other.resolve("proposal.md"), "theirs\n");

        OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));

        assertThat(Files.readString(other.resolve("proposal.md"))).isEqualTo("theirs\n");
    }

    @Test
    void aGitIgnoredExportIsWrittenButLoudlyReported() throws Exception {
        // RunGit.commitAll is JGit's AddCommand, which honours .gitignore — the same property that
        // keeps NpmOverlay's backups out of the reviewed diff. So an ignored openspec/ produces a
        // checkpoint with none of these files, silently, unless somebody says so.
        Files.writeString(repo.resolve(".gitignore"), "openspec/\n");

        OpenSpecExport.Result result =
                OpenSpecExport.materialize(repo, CHANGE_ID, files("## Why\nbecause\n"));

        assertThat(result.written()).isTrue();
        assertThat(result.events()).anySatisfy(e -> assertThat(e)
                .contains("WARNING").contains("git-ignored")
                .contains("will NOT reach the checkpoint commit"));
    }

    @Test
    void capabilityExistsSeesOnlyARealMainSpec() throws Exception {
        assertThat(OpenSpecExport.capabilityExists(repo, "tier-resolution")).isFalse();

        Files.createDirectories(repo.resolve("openspec/specs/tier-resolution"));
        assertThat(OpenSpecExport.capabilityExists(repo, "tier-resolution"))
                .as("a directory without spec.md is not a capability").isFalse();

        Files.writeString(repo.resolve("openspec/specs/tier-resolution/spec.md"), "# x\n");
        assertThat(OpenSpecExport.capabilityExists(repo, "tier-resolution")).isTrue();
    }
}
