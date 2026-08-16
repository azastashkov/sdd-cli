package sdd.core.diagnostics;

import sdd.core.config.ConfigException;
import sdd.core.http.AtlassianException;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.InstantSource;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * The whole reason Task 8 Part B exists: one append-only, best-effort, self-redacting file per
 * {@code sdd} command invocation under {@code .sdd/diagnostics/}, designed from the ground up to be
 * copied out of a closed corporate network and pasted into a chat with someone who cannot reach
 * that network at all — see the Task 8 brief's "why this exists" section, which drives every
 * decision below.
 *
 * <p><b>Never fails the caller.</b> Opening the file, and every subsequent write, is wrapped so an
 * {@link IOException} (read-only disk, full volume, a directory where a file was expected) can
 * never propagate: the writer marks itself broken and every further call becomes a silent no-op.
 * {@code JiraWriteBack} (Task 4) is the established precedent for a side effect that must never
 * affect a command's exit code, followed here for the same reason — diagnostics is itself exactly
 * such a side effect. A warning is printed at most ONCE per writer, to the optional {@code warnOut}
 * (matching the brief's two-space {@code "  warn: "} convention), so a broken diagnostics channel
 * degrades gracefully instead of becoming a second source of noisy failure.
 *
 * <p><b>Redacted by construction, not by caller discipline.</b> Every string this class writes
 * passes through a {@link Redactor} built once, at construction, from the FULL set of secrets the
 * command knows about (see {@code DiagnosticsSecrets} — every resolved Atlassian token across every
 * configured site, plus the truststore password) — not just the one site a particular caller
 * happens to be talking to. That means a {@code RestClient} built for Jira still cannot leak the
 * Bitbucket token even if some future bug crossed the streams. See {@link Redactor}'s own javadoc
 * for the specific rules (known-secret substring match, URL userinfo elision, Authorization-header
 * elision, credential-looking query parameters).
 *
 * <p><b>Bounded.</b> A single entry larger than {@link #MAX_ENTRY_CHARS} is truncated with a visible
 * marker rather than written in full — "nobody pastes a 40 MB file" (brief). The directory-level
 * retention policy (keep the most recent N files) lives in {@link DiagnosticsDir}, not here; this
 * class only ever appends to the one file it was opened against.
 *
 * <p><b>Cheap.</b> Buffered append, flushed after every write for durability against a process
 * crash, but never {@code fsync}'d — flushing pushes bytes to the OS's own buffer, which is what
 * "no fsync per line" (brief §B6) is actually about; the expensive part it forbids is a guaranteed
 * disk sync, not a userspace flush.
 */
public final class DiagnosticWriter implements Closeable {
    /** Hard cap on a single written entry — long enough for a real stack trace or an error body
     *  snippet, short enough that one runaway entry cannot itself make the file unpasteable. */
    static final int MAX_ENTRY_CHARS = 4_000;

    /** B3's "first ~500 chars of the response body" on a non-2xx — capped again independently of
     *  {@link #MAX_ENTRY_CHARS} so the httpRequest LINE's other fields are never pushed out by an
     *  oversized body even before the whole-entry cap would kick in. */
    private static final int MAX_BODY_SNIPPET_CHARS = 500;

    private static final int MAX_STACK_FRAMES = 20;

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    // Exception types this codebase throws DELIBERATELY, with a message already engineered to say
    // what happened (AtlassianException's rejected-token/HTTP-status messages, ConfigException's
    // deferred-credential messages, the IllegalState/IllegalArgument this repo uses throughout for
    // "this specific thing went wrong", any IOException subtype the JDK's own networking/TLS stack
    // produces). For these, class + message is the whole story — a stack trace only adds noise to
    // a file a human is meant to read start to finish. Anything else (a NullPointerException, an
    // ArrayIndexOutOfBoundsException — the classic "this is a bug, not an anticipated failure"
    // families) gets its stack trace included, because that IS the diagnosis for a bug nobody
    // anticipated, and this file is the only channel that will ever see it.
    private final Redactor redactor;
    private final InstantSource clock;
    private final PrintWriter warnOut;
    private BufferedWriter writer;
    private boolean warned;

    public DiagnosticWriter(Path file, Collection<String> secrets, InstantSource clock, PrintWriter warnOut) {
        this.redactor = Redactor.of(secrets);
        this.clock = clock;
        this.warnOut = warnOut;
        this.writer = open(file);
    }

    private BufferedWriter open(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            warnOnce("could not open diagnostics file " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** B3's header block — written once, verbatim (already redacted as a backstop like everything
     *  else), as the file's very first content. */
    public void header(String text) {
        writeBlock(text);
    }

    /** B3's "per Atlassian HTTP request" line. {@code errorBodySnippet} should be null on a 2xx —
     *  callers pass it only when they already have a non-2xx body to report; this method does not
     *  itself branch on {@code status} so a caller's own on-non-2xx guard is not duplicated here. */
    public void httpRequest(String site, String method, String path, int status, long durationMs,
            int attempt, boolean retry, String contentType, String errorBodySnippet) {
        StringBuilder line = new StringBuilder("http site=").append(site).append(" method=").append(method)
                .append(" path=").append(path).append(" status=").append(status)
                .append(" duration=").append(durationMs).append("ms")
                .append(" attempt=").append(attempt).append(" retry=").append(retry)
                .append(" content-type=").append(contentType == null ? "-" : contentType);
        if (errorBodySnippet != null) {
            line.append(" body=").append(cap(errorBodySnippet, MAX_BODY_SNIPPET_CHARS));
        }
        writeLine(line.toString());
    }

    /** B3's "on any failure": the full cause chain, class + message per cause, with a stack trace
     *  only when the innermost/any unanticipated cause is not one of this codebase's deliberate
     *  failure types — see this class's javadoc for the exact split. */
    public void failure(String context, Throwable t) {
        StringBuilder block = new StringBuilder("failure: ").append(context);
        int n = 0;
        boolean anyUnexpected = false;
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            n++;
            block.append('\n').append("  cause ").append(n).append(": ")
                    .append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                block.append(": ").append(cause.getMessage());
            }
            if (!isExpected(cause)) {
                anyUnexpected = true;
            }
            if (n >= 10) {
                block.append('\n').append("  ... (cause chain truncated)");
                break;
            }
        }
        if (anyUnexpected) {
            block.append('\n').append(stackTrace(t));
        }
        writeBlock(block.toString());
    }

    /** B3's Gate-2 decision events — one line per phase transition, deliberately, rather than one
     *  combined per-repo record: sequential timestamped lines are what let a reader confirm from
     *  the log ALONE that no merge line ever follows a refused-squash line for the same repo,
     *  which is the specific ordering property {@code BitbucketDecisions}' javadoc calls the single
     *  most important thing Task 5 added. */
    public void gate2(String repo, String event) {
        writeLine("gate2 repo=" + repo + " " + event);
    }

    /** B3's "Git push outcomes". {@code forceWithLeaseHeld} is null when the outcome is unknown
     *  (a push that failed before or during the lease check itself, as opposed to a lease that was
     *  read and then explicitly rejected) — callers are not expected to distinguish every JGit
     *  failure mode finely enough to always know which. */
    public void gitPush(String remoteHost, String ref, Boolean forceWithLeaseHeld, String failureMessage) {
        String lease = forceWithLeaseHeld == null ? "unknown" : String.valueOf(forceWithLeaseHeld);
        StringBuilder line = new StringBuilder("git-push host=").append(remoteHost).append(" ref=").append(ref)
                .append(" force-with-lease-held=").append(lease);
        if (failureMessage != null) {
            line.append(" FAILED: ").append(failureMessage);
        } else {
            line.append(" OK");
        }
        writeLine(line.toString());
    }

    /** A free-text line for anything else B3 asks for that does not warrant its own typed method —
     *  the "one-line statement of unverified behaviours", a probe outcome in {@code sdd doctor
     *  --report}, or a note about the diagnostics channel itself. */
    public void note(String line) {
        writeLine(line);
    }

    // Exact-class match, not "instanceof a common supertype every RuntimeException shares" — the
    // point is to name the specific families this codebase deliberately throws with an
    // already-engineered message, not to swallow stack traces for every RuntimeException whatever
    // its origin.
    private static boolean isExpected(Throwable t) {
        if (t instanceof IOException) {
            return true;
        }
        Class<?> c = t.getClass();
        return c == AtlassianException.class || c == ConfigException.class
                || c == IllegalStateException.class || c == IllegalArgumentException.class
                || c == InterruptedException.class;
    }

    private static String stackTrace(Throwable t) {
        StackTraceElement[] frames = t.getStackTrace();
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(frames.length, MAX_STACK_FRAMES);
        for (int i = 0; i < limit; i++) {
            sb.append("    at ").append(frames[i]).append('\n');
        }
        if (frames.length > limit) {
            sb.append("    ... (").append(frames.length - limit).append(" more frames omitted)");
        }
        return sb.toString().stripTrailing();
    }

    private synchronized void writeLine(String content) {
        writeRaw(prefixed(content));
    }

    private synchronized void writeBlock(String content) {
        writeRaw(prefixed(content));
    }

    private String prefixed(String content) {
        return "[" + TS.format(clock.instant()) + "] " + content;
    }

    private void writeRaw(String content) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(cap(redactor.scrub(content), MAX_ENTRY_CHARS));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            writer = null;
            warnOnce("diagnostics write failed: " + e.getMessage());
        }
    }

    private static String cap(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + " …[truncated, " + (s.length() - max) + " more chars]";
    }

    private void warnOnce(String message) {
        if (!warned && warnOut != null) {
            warnOut.println("  warn: " + message);
            warned = true;
        }
    }

    @Override
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            warnOnce("could not close diagnostics file: " + e.getMessage());
        } finally {
            writer = null;
        }
    }
}
