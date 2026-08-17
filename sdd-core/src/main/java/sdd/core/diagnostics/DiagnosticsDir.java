package sdd.core.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * B2: {@code <workspace>/.sdd/diagnostics/} — alongside the existing {@code .sdd/} state ({@code
 * index.db}, {@code runs/}, {@code curation-report.md}). One file per command invocation, named so
 * the newest is obvious (a millisecond timestamp prefix, zero-padded, sorts lexicographically in
 * chronological order — {@code ls} needs no {@code -t}) and so two concurrent {@code sdd}
 * processes cannot collide (the OS process id is part of the name; a same-process,
 * same-millisecond collision — not achievable by two real command invocations, which each open
 * exactly one file — is additionally guarded by a monotonic counter).
 *
 * <p><b>Retention policy (stated here so {@code DiagnosticHeader} can quote it verbatim in the file
 * itself, per B2/B4's "state the policy in the file itself"): keep the most recent {@link
 * #MAX_FILES} files, deleting the oldest first.</b> A count cap rather than a total-size cap: it is
 * simpler to reason about from the file names alone (a reader sees exactly how many siblings exist
 * without summing file sizes), and Task 8's own per-entry cap ({@link DiagnosticWriter#MAX_ENTRY_CHARS})
 * already bounds how large any one file can practically get, which is what a size cap would mostly
 * be protecting against anyway.
 */
public final class DiagnosticsDir {
    /** N in "keep the most recent N files" — see this class's javadoc. */
    public static final int MAX_FILES = 20;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private DiagnosticsDir() {
    }

    /**
     * Allocates the path for a new diagnostics file for one {@code command} invocation, enforcing
     * retention on the directory first (so the file this call is about to create is never itself
     * counted against — and never immediately deleted by — its own cleanup pass). Never throws: a
     * directory that cannot be created (read-only workspace, a file where the directory should be)
     * is exactly the kind of failure {@link DiagnosticWriter} is built to swallow, so this simply
     * returns the path anyway — {@link DiagnosticWriter}'s own constructor makes the same attempt
     * and degrades the same way if it also fails.
     */
    public static Path allocate(Path workspace, String command, InstantSource clock) {
        Path dir = workspace.resolve(".sdd/diagnostics");
        try {
            Files.createDirectories(dir);
            enforceRetention(dir);
        } catch (IOException | RuntimeException e) {
            // Best-effort: DiagnosticWriter's own open() will hit and report the same underlying
            // problem when it tries to create the file a moment later.
        }
        return dir.resolve(fileName(command, clock));
    }

    /** The {@code n} most recently allocated diagnostics files under {@code workspace}, newest
     *  first, excluding {@code exclude} (typically the file the CURRENT command is itself writing
     *  — {@code sdd doctor --report}'s "tail of the most recent diagnostic files" must not include
     *  its own still-being-written output). Never throws — an unreadable directory yields an empty
     *  list rather than a failure, for the same reason {@link #allocate} never throws. */
    public static List<Path> mostRecent(Path workspace, int n, Path exclude) {
        Path dir = workspace.resolve(".sdd/diagnostics");
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(exclude))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(n)
                    .toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static String fileName(String command, InstantSource clock) {
        long pid = ProcessHandle.current().pid();
        long seq = SEQUENCE.incrementAndGet();
        return FMT.format(clock.instant()) + "-pid" + pid + "-" + seq + "-" + command + ".log";
    }

    private static void enforceRetention(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = new ArrayList<>(stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList());
        }
        // Leave room for the ONE file this call is about to create, so the directory never exceeds
        // MAX_FILES immediately afterward.
        int overflow = files.size() - (MAX_FILES - 1);
        for (int i = 0; i < overflow; i++) {
            Files.deleteIfExists(files.get(i));
        }
    }
}
