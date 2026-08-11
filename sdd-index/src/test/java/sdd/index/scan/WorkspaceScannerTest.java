package sdd.index.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceScannerTest {
    @TempDir Path ws;

    @Test
    void findsGitReposSortedAndSkipsNonReposAndExcludes() throws Exception {
        FixtureRepo.in(ws, "svc-b").file("a.txt", "x").commit("init");
        FixtureRepo.in(ws, "lib-a").file("a.txt", "x").commit("init");
        FixtureRepo.in(ws, "sandbox").file("a.txt", "x").commit("init");
        Files.createDirectories(ws.resolve("not-a-repo"));

        List<RepoScan> scans = WorkspaceScanner.scan(ws, List.of("sandbox"));

        assertThat(scans).extracting(RepoScan::name).containsExactly("lib-a", "svc-b");
        RepoScan libA = scans.get(0);
        assertThat(libA.headCommit()).hasSize(40);
        assertThat(libA.branch()).isEqualTo("main");
        assertThat(libA.dirtyHash()).isEmpty();
        assertThat(libA.fingerprint()).isEqualTo(libA.headCommit() + ":");
    }

    @Test
    void unreadableRepoIsRecordedAsFailureAndDoesNotStopTheScan() throws Exception {
        FixtureRepo.in(ws, "healthy").file("a.txt", "x").commit("init");
        Files.createDirectories(ws.resolve("wrecked"));
        Files.writeString(ws.resolve("wrecked/.git"), "this is not a gitdir link\n");

        List<String> failures = new java.util.ArrayList<>();
        List<RepoScan> scans = WorkspaceScanner.scan(ws, List.of(), failures);

        assertThat(scans).extracting(RepoScan::name).containsExactly("healthy");
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).startsWith("wrecked:").contains("wrecked");
    }

    @Test
    void dirtyTreeChangesFingerprint() throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, "r").file("a.txt", "one").commit("init");
        String cleanFp = WorkspaceScanner.scan(ws, List.of()).get(0).fingerprint();

        Files.writeString(repo.path().resolve("a.txt"), "two");
        RepoScan dirty = WorkspaceScanner.scan(ws, List.of()).get(0);

        assertThat(dirty.dirtyHash()).isNotEmpty();
        assertThat(dirty.fingerprint()).isNotEqualTo(cleanFp);
    }

    @Test
    void dirtyHashIsStableAcrossRescans() throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, "r").file("a.txt", "one").commit("init");
        Files.writeString(repo.path().resolve("a.txt"), "two");
        String first = WorkspaceScanner.scan(ws, List.of()).get(0).dirtyHash();
        String second = WorkspaceScanner.scan(ws, List.of()).get(0).dirtyHash();
        assertThat(first).isNotEmpty().isEqualTo(second);
    }
}
