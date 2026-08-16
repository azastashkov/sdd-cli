package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.extract.BuildModel;
import sdd.index.extract.GradleBuildExtractor;
import sdd.index.gradle.GradleModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

class SourceExtractionTest {
    @TempDir Path ws;

    private static BuildModel.Extract extractAt(Path projectDir) {
        return GradleBuildExtractor.adapt(new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", "lib-core", "com.acme", "1.0", projectDir,
                List.of("java-library"), false, List.of(),
                Map.of("compileClasspath", new GradleModel.DepConfig(
                        List.of(), List.of(), List.of())))),
                List.of()));
    }

    /**
     * The scanner hands over the path it listed (symlink intact) while Gradle reports a
     * canonicalized projectDir. Relativizing one against the other yields "../../.." junk — on
     * macOS this happens for every temp-dir fixture, since /var is a symlink to /private/var.
     */
    @Test
    void relPathsStayRepoRelativeWhenTheRepoIsReachedThroughASymlink() throws Exception {
        Path real = Files.createDirectories(ws.resolve("real/lib-core"));
        Files.createDirectories(real.resolve("src/main/java/com/acme"));
        Files.writeString(real.resolve("src/main/java/com/acme/C.java"),
                "package com.acme;\npublic class C {}\n");
        Path link = ws.resolve("link");
        try {
            Files.createSymbolicLink(link, ws.resolve("real"));
        } catch (UnsupportedOperationException | IOException e) {
            abort("filesystem cannot create symlinks: " + e);
        }
        Path scannedPath = link.resolve("lib-core");     // what WorkspaceScanner reports
        Path projectDir = real.toRealPath();             // what Gradle reports

        try (Database db = Database.open(ws)) {
            long repoId = db.jdbi().withHandle(h -> {
                h.createUpdate("INSERT INTO repo(name, path, kind) VALUES ('lib-core', :p, 'LIBRARY')")
                        .bind("p", scannedPath.toString()).execute();
                long id = h.createQuery("SELECT id FROM repo WHERE name='lib-core'").mapTo(Long.class).one();
                h.createUpdate("INSERT INTO module(repo_id, gradle_path, kind) VALUES (:r, ':', 'LIBRARY')")
                        .bind("r", id).execute();
                return id;
            });

            String status = SourceExtraction.extractRepo(db.jdbi(), repoId, "lib-core",
                    scannedPath, extractAt(projectDir));

            assertThat(status).isEqualTo("OK");
            List<String> filePaths = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT file_path FROM java_type").mapTo(String.class).list());
            assertThat(filePaths).containsExactly("src/main/java/com/acme/C.java");
        }
    }
}
