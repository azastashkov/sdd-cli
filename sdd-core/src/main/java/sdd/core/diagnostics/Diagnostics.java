package sdd.core.diagnostics;

import sdd.core.config.AtlassianConfig;
import sdd.core.config.ModelEndpoint;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

/**
 * The one-line facade every command's {@code call()} uses to open its diagnostics file: allocate a
 * path (B2), collect every known secret across the whole {@code atlassian:} config (B4), open the
 * writer, and write the header (B3) — in that order, so a call site cannot forget one of the four
 * steps or, worse, get the order wrong and open the writer before its redaction set is complete.
 *
 * <p><b>Never fails the caller — the facade itself, not just its collaborators (Fix 3, Task 8
 * review).</b> {@link DiagnosticWriter}'s own constructor and write methods already swallow an
 * unopenable/failing file, and {@link DiagnosticsDir#allocate} already swallows a path it cannot
 * create. But this class calls one thing neither of those wraps: {@link DiagnosticHeader#render},
 * which runs arbitrary formatting logic (including a real truststore load attempt) against
 * whatever the caller handed in — a caller-supplied {@code null} {@code argv}, or some future
 * change to that method, is exactly the kind of thing that "should never throw" right up until it
 * does. Every call site in this repo already passes this facade a {@link PrintWriter} it trusts to
 * survive being unreachable, and {@code RunContext.load}/{@code DoctorCommand.call} were both found
 * NOT independently guarding against {@link #open}/{@link #openAt} throwing — an exception here
 * would have surfaced as "error: ..." and exit 4, a diagnostics problem failing the very command it
 * was meant to help debug. Both methods below are therefore wrapped end to end: any failure
 * anywhere in this facade prints one warning (when {@code warnOut} is non-null) and returns {@link
 * DiagnosticWriter#noOp()} — a writer that touches disk not at all — rather than propagating.
 */
public final class Diagnostics {
    private Diagnostics() {
    }

    /**
     * @param command   the subcommand name the allocated file is tagged with (e.g. {@code "doctor"},
     *                  {@code "review"}) — see {@link DiagnosticsDir#allocate}.
     * @param argv      the command line as invoked, INCLUDING the subcommand name at index 0 — see
     *                  {@link DiagnosticHeader#render}.
     * @param atlassian the loaded config's {@code atlassian:} block, or null.
     * @param warnOut   where a "could not open/write diagnostics" warning is printed at most once,
     *                  or null to suppress it entirely (some callers — a probe running inside
     *                  another probe loop — have no natural stderr to attribute a diagnostics-only
     *                  warning to and pass null deliberately).
     */
    public static DiagnosticWriter open(Path workspace, String command, List<String> argv,
            AtlassianConfig atlassian, InstantSource clock, PrintWriter warnOut) {
        try {
            return openAt(DiagnosticsDir.allocate(workspace, command, clock), argv, atlassian, clock, warnOut);
        } catch (RuntimeException e) {
            warn(warnOut, e);
            return DiagnosticWriter.noOp();
        }
    }

    /**
     * Like {@link #open}, but at a CALLER-CHOSEN path rather than one {@link DiagnosticsDir}
     * allocates — {@code sdd doctor --report <path>} is the one place a human names the exact
     * output location instead of accepting the default under {@code .sdd/diagnostics/}.
     *
     * <p>Delegates to {@link #openAt(Path, List, AtlassianConfig, Map, InstantSource, PrintWriter)}
     * with no model endpoints — every existing caller of this overload predates model mTLS and
     * never writes a model-tls diagnostic line, so there is nothing to add to the redaction set.
     */
    public static DiagnosticWriter openAt(Path file, List<String> argv, AtlassianConfig atlassian,
            InstantSource clock, PrintWriter warnOut) {
        return openAt(file, argv, atlassian, Map.of(), clock, warnOut);
    }

    /**
     * {@link #openAt(Path, List, AtlassianConfig, InstantSource, PrintWriter)}, plus the loaded
     * config's {@code models:} block — {@code sdd doctor} is the one caller that ever writes a
     * {@code model-tls} diagnostic line ({@code DoctorCommand.recordModelTlsDiagnostics}), and that
     * line is already built to never interpolate a model endpoint's {@code tls.key_password}/
     * {@code tls.truststore_password}. Passing {@code models} here adds both secrets to {@link
     * DiagnosticsSecrets#collect(AtlassianConfig, Map)}'s redaction set anyway, as a genuine
     * backstop for that guarantee rather than a documentation claim about one — see {@link
     * DiagnosticsSecrets}'s class javadoc.
     */
    public static DiagnosticWriter openAt(Path file, List<String> argv, AtlassianConfig atlassian,
            Map<String, ModelEndpoint> models, InstantSource clock, PrintWriter warnOut) {
        try {
            DiagnosticWriter writer = new DiagnosticWriter(file, DiagnosticsSecrets.collect(atlassian, models),
                    clock, warnOut);
            writer.header(DiagnosticHeader.render(argv, atlassian, RuntimeInfo.sddVersion(), RuntimeInfo.gitCommit()));
            return writer;
        } catch (RuntimeException e) {
            warn(warnOut, e);
            return DiagnosticWriter.noOp();
        }
    }

    private static void warn(PrintWriter warnOut, RuntimeException e) {
        if (warnOut != null) {
            warnOut.println("  warn: could not open diagnostics: " + e.getMessage());
        }
    }
}
