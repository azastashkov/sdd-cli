package sdd.agent.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.toolchain.Toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every fixture here is real output, captured by running the estate's own tsc and vitest rather
 * than written from memory of what they print.
 */
class NpmOutputCompactorTest {
    @TempDir Path repo;

    private static final Instant RUN_START = Instant.parse("2026-08-16T10:00:00Z");

    private OutputCompactor compactor() {
        return new OutputCompactor(repo, Toolchain.NPM, RUN_START);
    }

    /** Verbatim from `tsc --noEmit` with stdout redirected, which is how sdd always captures it. */
    private static final String TSC_OUTPUT = """
            exit 2
            src/broken.ts(2,14): error TS2304: Cannot find name 'c'.
            src/broken.ts(4,7): error TS2322: Type 'number' is not assignable to type 'string'.
            """;

    /** Verbatim from `vitest run`, with NO_COLOR set as the npm environment policy sets it. */
    private static final String VITEST_OUTPUT = """
            exit 1

             RUN  v2.1.9 /w/web

             ❏ sample.test.ts (3 tests | 2 failed) 4ms
               × math > fails on purpose 3ms
                 → expected 2 to be 3 // Object.is equality
               × math > throws on purpose 0ms
                 → boom

             FAIL  sample.test.ts > math > fails on purpose
            AssertionError: expected 2 to be 3 // Object.is equality

                  Tests  2 failed | 1 passed (3)
            """;

    @Test
    void tscDiagnosticsAreReportedLikeCompileErrors() {
        String compacted = compactor().compact(TSC_OUTPUT, "typecheck");

        assertThat(compacted).startsWith("exit 2 (typecheck)").contains("Compile errors:");
        // Rendered in the same shape as the Java path's javac errors, so both ecosystems read the
        // same way to a model that may see either.
        assertThat(compacted)
                .contains("broken.ts:2: error: TS2304 Cannot find name 'c'.")
                .contains("broken.ts:4: error: TS2322 Type 'number' is not assignable to type 'string'.");
    }

    @Test
    void theTscPrettyFormatIsUnderstoodToo() {
        String pretty = "exit 2\nsrc/a.ts:12:5 - error TS2345: Argument of type 'x' is not assignable.\n";

        assertThat(compactor().compact(pretty, "typecheck"))
                .contains("a.ts:12: error: TS2345 Argument of type 'x' is not assignable.");
    }

    @Test
    void vitestFailuresAreReadFromTheConsoleAndSayThatIsWhereTheyCameFrom() {
        String compacted = compactor().compact(VITEST_OUTPUT, "test");

        // The relaxation is visible in the output itself. Gradle guarantees an XML artifact and npm
        // guarantees nothing, so where a failure came from is part of what the reader needs.
        assertThat(compacted).contains("2 failed (from console output — no machine-readable test "
                + "report was produced):");
        assertThat(compacted)
                .contains("math > fails on purpose: expected 2 to be 3 // Object.is equality")
                .contains("math > throws on purpose: boom");
    }

    @Test
    void aFreshReportOnDiskIsPreferredToTheConsole() throws Exception {
        Path report = repo.resolve("test-results/junit.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <testsuite name="s"><testcase classname="math" name="adds">
                <failure message="expected 2 to be 3" type="AssertionError"/></testcase></testsuite>
                """);
        Files.setLastModifiedTime(report, FileTime.from(RUN_START.plusSeconds(5)));

        String compacted = compactor().compact(VITEST_OUTPUT, "test");

        assertThat(compacted).contains("1 failed:").doesNotContain("from console output");
        assertThat(compacted).contains("math#adds");
    }

    @Test
    void aStaleReportIsIgnoredRatherThanReportedAsThisRunsResult() throws Exception {
        // Gradle's report directory is task-owned and cleaned; an npm repo can simply commit a
        // junit.xml. Believing it would report someone else's result as this run's.
        Path report = repo.resolve("test-results/junit.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <testsuite name="s"><testcase classname="old" name="stale">
                <failure message="from a previous run" type="AssertionError"/></testcase></testsuite>
                """);
        Files.setLastModifiedTime(report, FileTime.from(RUN_START.minusSeconds(3600)));

        String compacted = compactor().compact(VITEST_OUTPUT, "test");

        assertThat(compacted).doesNotContain("old#stale").doesNotContain("from a previous run");
        assertThat(compacted).contains("from console output");   // fell back, rather than lying
    }

    @Test
    void aFileThatFailedToLoadIsReportedEvenWithNoPerTestLines() {
        String output = "exit 1\n\n FAIL  src/broken.test.ts [ src/broken.test.ts ]\n"
                + "Error: Failed to load url ./missing.js\n";

        // Otherwise the model is told nothing at all about a test file that does not even run.
        assertThat(compactor().compact(output, "test"))
                .contains("1 failed").contains("src/broken.test.ts");
    }

    @Test
    void aGreenRunCompactsToATailWithNoFailureSection() {
        String output = "exit 0\n\n Test Files  1 passed (1)\n      Tests  3 passed (3)\n";

        String compacted = compactor().compact(output, "test");

        assertThat(compacted).startsWith("exit 0 (test)").contains("3 passed")
                .doesNotContain("failed:");
    }

    @Test
    void reportsAreNotHarvestedForATaskThatRunsNoTests() throws Exception {
        Path report = repo.resolve("test-results/junit.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, """
                <testsuite name="s"><testcase classname="math" name="adds">
                <failure message="older failure" type="AssertionError"/></testcase></testsuite>
                """);
        Files.setLastModifiedTime(report, FileTime.from(RUN_START.plusSeconds(5)));

        // A typecheck run must not surface test failures from whenever tests last ran.
        assertThat(compactor().compact("exit 0\nno errors\n", "typecheck"))
                .doesNotContain("failed").doesNotContain("math#adds");
    }
}
