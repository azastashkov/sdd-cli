package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link DiagnosticsDir} owns {@code <workspace>/.sdd/diagnostics/} — where B2 says every
 * diagnostic file lives, named so the newest is obvious, bounded so the directory cannot grow
 * without limit.
 */
class DiagnosticsDirTest {
    @TempDir
    Path workspace;

    @Test
    void allocatesAFileUnderSddDiagnosticsNamedForTheCommandAndTimestamp() {
        InstantSource clock = InstantSource.fixed(Instant.parse("2026-08-17T10:15:30.123Z"));

        Path file = DiagnosticsDir.allocate(workspace, "review", clock);

        assertThat(file.getParent()).isEqualTo(workspace.resolve(".sdd/diagnostics"));
        assertThat(file.getFileName().toString()).contains("review").endsWith(".log");
        assertThat(Files.isDirectory(workspace.resolve(".sdd/diagnostics"))).isTrue();
    }

    @Test
    void twoAllocationsAMillisecondApartInTheSameProcessDoNotCollide() {
        Path f1 = DiagnosticsDir.allocate(workspace, "review", InstantSource.fixed(Instant.parse("2026-08-17T10:15:30.100Z")));
        Path f2 = DiagnosticsDir.allocate(workspace, "review", InstantSource.fixed(Instant.parse("2026-08-17T10:15:30.101Z")));

        assertThat(f1).isNotEqualTo(f2);
    }

    @Test
    void theNewestFileSortsLastByPlainNameOrdering() {
        Path early = DiagnosticsDir.allocate(workspace, "doctor", InstantSource.fixed(Instant.parse("2026-08-17T10:00:00.000Z")));
        Path late = DiagnosticsDir.allocate(workspace, "doctor", InstantSource.fixed(Instant.parse("2026-08-17T11:00:00.000Z")));

        assertThat(early.getFileName().toString()).isLessThan(late.getFileName().toString());
    }

    @Test
    void retentionKeepsOnlyTheMostRecentFilesDeletingOldestFirst() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve(".sdd/diagnostics"));
        for (int i = 0; i < 25; i++) {
            String name = String.format("202608%02d-000000000-pid1-doctor.log", i + 1);
            Files.writeString(dir.resolve(name), "x");
        }

        DiagnosticsDir.allocate(workspace, "doctor", InstantSource.fixed(Instant.parse("2026-09-01T00:00:00.000Z")));

        try (var stream = Files.list(dir)) {
            List<Path> remaining = stream.sorted().toList();
            // 25 pre-existing + 1 just allocated = 26; retention must not exceed the cap.
            assertThat(remaining.size()).isLessThanOrEqualTo(20);
            // The newest of the pre-existing files (day 25) must have survived; the oldest (day 1)
            // must not have.
            assertThat(remaining.stream().map(p -> p.getFileName().toString()))
                    .noneMatch(n -> n.startsWith("20260801"))
                    .anyMatch(n -> n.startsWith("20260825"));
        }
    }

    @Test
    void mostRecentReturnsFilesNewestFirstExcludingTheGivenPath() throws IOException {
        Path dir = Files.createDirectories(workspace.resolve(".sdd/diagnostics"));
        Path a = dir.resolve("20260817-100000000-pid1-review.log");
        Path b = dir.resolve("20260817-110000000-pid1-doctor.log");
        Path c = dir.resolve("20260817-120000000-pid1-review.log");
        Files.writeString(a, "a");
        Files.writeString(b, "b");
        Files.writeString(c, "c");

        List<Path> recent = DiagnosticsDir.mostRecent(workspace, 5, c);

        assertThat(recent).containsExactly(b, a);
    }

    @Test
    void allocationNeverThrowsEvenWhenTheDirectoryCannotBeCreated() throws IOException {
        Path blocker = workspace.resolve(".sdd");
        Files.writeString(blocker, "not a directory");

        assertThatCode(() -> DiagnosticsDir.allocate(workspace, "doctor", InstantSource.system()))
                .doesNotThrowAnyException();
    }
}
