package sdd.core.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link DiagnosticWriter} is the "one file per command invocation" the whole of Task 8 Part B
 * exists to produce — see the class javadoc for the append/redact/cap/never-fail contract. The
 * two tests that matter most to a security reviewer are
 * {@link #aKnownTokenValueNeverReachesTheProducedFileAcrossEveryWritePath} (a leaked token here is
 * a security incident, not a style bug — the Task 8 brief's own words) and
 * {@link #aWriteFailureNeverThrowsAndTheFileSimplyStopsGrowing} (diagnostics can never cost a
 * command its exit code).
 */
class DiagnosticWriterTest {
    private static final String SECRET = "sk-live-abcdef1234567890";
    private static final InstantSource CLOCK = InstantSource.fixed(Instant.parse("2026-08-17T10:15:30.123Z"));

    @TempDir
    Path tmp;

    private DiagnosticWriter writer(Path file, PrintWriter warn) {
        return new DiagnosticWriter(file, Set.of(SECRET), CLOCK, warn);
    }

    @Test
    void httpRequestWritesTheRequiredFieldsAsOneReadableLine() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.httpRequest("Jira", "GET", "/rest/api/2/issue/PROJ-1", 200, 42, 1, false,
                "application/json", null);
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("Jira").contains("GET").contains("/rest/api/2/issue/PROJ-1")
                .contains("200").contains("42").contains("attempt=1").contains("retry=false")
                .contains("application/json");
    }

    @Test
    void nonTwoXxResponsesIncludeTheErrorBodySnippet() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.httpRequest("Bitbucket", "POST", "/rest/api/1.0/projects/T/repos/r/merge", 409, 10, 1, false,
                "application/json", "{\"errors\":[{\"message\":\"PR has been updated\"}]}");
        w.close();

        assertThat(Files.readString(file)).contains("PR has been updated");
    }

    @Test
    void aSecretStraddlingTheBodySnippetCapBoundaryIsFullyRedactedNotLeftAsAFragment() throws IOException {
        // Fix 1 (Task 8 review, CRITICAL): the error body is capped to 500 chars for display —
        // MAX_BODY_SNIPPET_CHARS in the production class. A secret placed so it starts BEFORE that
        // cutoff and ends AFTER it must still be removed in full: redaction has to run against the
        // WHOLE body before any truncation, or the surviving fragment no longer exact-substring-
        // matches the full secret and slips through.
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);
        String prefix = "x".repeat(480);   // pushes the secret's start to just before char 500
        String body = "{\"message\":\"" + prefix + SECRET + "\"}";   // SECRET now spans across index 500

        w.httpRequest("Jira", "GET", "/rest/api/2/myself", 401, 5, 1, false, "application/json", body);
        w.close();

        String content = Files.readString(file);
        assertThat(content).doesNotContain(SECRET);
        // Guard against a redaction that only catches the WHOLE-secret case: no half-length
        // fragment of it (the part that would have survived a cap-then-redact bug) may appear either.
        assertThat(content).doesNotContain(SECRET.substring(0, SECRET.length() / 2));
        assertThat(content).doesNotContain(SECRET.substring(SECRET.length() / 2));
    }

    @Test
    void failureWalksTheFullCauseChainWithClassAndMessagePerCause() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        Exception root = new java.io.IOException("connection refused");
        Exception wrapped = new sdd.core.http.AtlassianException("transport error talking to Jira: connection refused", root);

        w.failure("Jira GET /rest/api/2/issue/PROJ-1", wrapped);
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("AtlassianException").contains("transport error talking to Jira")
                .contains("IOException").contains("connection refused");
    }

    @Test
    void anUnexpectedExceptionTypeIncludesAStackTraceSoABugIsDiagnosable() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.failure("Jira GET /rest/api/2/issue/PROJ-1", new NullPointerException("boom"));
        w.close();

        assertThat(Files.readString(file)).contains("NullPointerException").contains("at sdd.core.diagnostics");
    }

    @Test
    void anExpectedExceptionTypeOmitsTheStackTrace() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.failure("Jira GET /rest/api/2/issue/PROJ-1", new sdd.core.http.AtlassianException("HTTP 401"));
        w.close();

        assertThat(Files.readString(file)).doesNotContain("at sdd.core.diagnostics");
    }

    @Test
    void gate2LogsAnEventLineForEachRepoAndPhase() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.gate2("lib", "squash applied (squashed=true) sha=abc123");
        w.gate2("lib", "checkpoint write succeeded");
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("lib").contains("squash applied").contains("checkpoint write succeeded");
        // Ordering is the whole point of Gate-2 diagnostics: a reader must be able to confirm from
        // the log alone which happened first.
        assertThat(content.indexOf("squash applied")).isLessThan(content.indexOf("checkpoint write succeeded"));
    }

    @Test
    void gitPushLogsHostRefLeaseAndFailureMessage() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.gitPush("bb.corp.local", "refs/heads/sdd/SPEC-1-v1/lib", null, "push rejected: stale lease");
        w.close();

        String content = Files.readString(file);
        assertThat(content).contains("bb.corp.local").contains("refs/heads/sdd/SPEC-1-v1/lib")
                .contains("push rejected: stale lease");
    }

    @Test
    void headerAndNoteAreTimestampedFromTheInjectedClock() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.header("=== sdd diagnostics ===");
        w.note("hello");
        w.close();

        assertThat(Files.readString(file)).contains("2026-08-17T10:15:30.123Z");
    }

    @Test
    void aSingleEntryLargerThanTheCapIsTruncatedRatherThanWrittenInFull() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        String huge = "x".repeat(50_000);
        w.note(huge);
        w.close();

        String content = Files.readString(file);
        assertThat(content.length()).isLessThan(huge.length());
        assertThat(content).contains("truncated");
    }

    // --- redaction: the highest-risk contract in Task 8 ------------------------------------------

    @Test
    void aKnownTokenValueNeverReachesTheProducedFileAcrossEveryWritePath() throws IOException {
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = writer(file, null);

        w.header("command: sdd doctor --token " + SECRET);
        w.httpRequest("Jira", "GET", "/rest/api/2/myself?token=" + SECRET, 200, 5, 1, false,
                "application/json", null);
        w.httpRequest("Jira", "GET", "/rest/api/2/myself", 401, 5, 1, false, "application/json",
                "body carrying " + SECRET + " by mistake");
        w.failure("Jira GET /rest/api/2/myself", new RuntimeException("rejected token " + SECRET));
        w.gitPush("bb.corp.local", "refs/heads/x", true,
                "cannot push: remote https://x-token-auth:" + SECRET + "@bb.corp.local/scm/p/r.git rejected it");
        w.note("saw token " + SECRET + " in an unexpected place");
        w.close();

        String content = Files.readString(file);
        assertThat(content).doesNotContain(SECRET);
    }

    @Test
    void elidesUserinfoInAGitRemoteUrlEvenWhenTheEmbeddedTokenIsNotAKnownSecret() throws IOException {
        // The Task 6 worked example: a git-config rewrite embedding a token that "git remote
        // get-url" resolved back into printed output. Here the token is deliberately NOT passed
        // as a known secret, to prove the userinfo-elision rule alone (not the secret backstop)
        // is what catches this shape.
        Path file = tmp.resolve("d.log");
        DiagnosticWriter w = new DiagnosticWriter(file, Set.of(), CLOCK, null);

        w.gitPush("bb.corp.local", "refs/heads/x", null,
                "cannot push: remote https://x-token-auth:some-unregistered-token@bb.corp.local/scm/p/r.git rejected it");
        w.close();

        assertThat(Files.readString(file)).doesNotContain("some-unregistered-token")
                .contains("https://<redacted>@bb.corp.local");
    }

    // --- never fail the caller --------------------------------------------------------------------

    @Test
    void aWriteFailureNeverThrowsAndTheFileSimplyStopsGrowing() {
        // A regular FILE where the writer expects to create a DIRECTORY: every write beneath it
        // must fail, deterministically, on every platform — a more portable failure to engineer in
        // a test than chmod-based permission tricks.
        Path blocker = tmp.resolve("blocker");
        Path fileUnderBlocker = blocker.resolve("diagnostics.log");

        assertThatCode(() -> {
            try {
                Files.writeString(blocker, "not a directory");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            DiagnosticWriter w = new DiagnosticWriter(fileUnderBlocker, Set.of(SECRET), CLOCK, null);
            w.header("header");
            w.httpRequest("Jira", "GET", "/x", 200, 1, 1, false, "application/json", null);
            w.failure("ctx", new RuntimeException("boom"));
            w.gate2("lib", "event");
            w.gitPush("host", "ref", true, null);
            w.note("note");
            w.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void aConstructionFailureWarnsOnceOnStderrWhenAWarnWriterIsProvided() throws IOException {
        Path blocker = tmp.resolve("blocker2");
        Files.writeString(blocker, "not a directory");
        Path fileUnderBlocker = blocker.resolve("diagnostics.log");
        StringWriter errBuf = new StringWriter();
        PrintWriter err = new PrintWriter(errBuf, true);

        DiagnosticWriter w = new DiagnosticWriter(fileUnderBlocker, Set.of(), CLOCK, err);
        w.note("first");
        w.note("second");
        w.close();

        String printed = errBuf.toString();
        assertThat(printed).contains("  warn: ");
        // Warned exactly once, not once per failed write — a broken diagnostics channel must not
        // itself become a source of noisy stderr spam.
        assertThat(printed.lines().filter(l -> l.contains("  warn: ")).count()).isEqualTo(1);
    }

    private static final class UncheckedIOException extends RuntimeException {
        UncheckedIOException(IOException cause) { super(cause); }
    }
}
