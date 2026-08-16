package sdd.core.diagnostics;

import sdd.core.config.AtlassianConfig;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;

/**
 * The one-line facade every command's {@code call()} uses to open its diagnostics file: allocate a
 * path (B2), collect every known secret across the whole {@code atlassian:} config (B4), open the
 * writer, and write the header (B3) — in that order, so a call site cannot forget one of the four
 * steps or, worse, get the order wrong and open the writer before its redaction set is complete.
 *
 * <p>Never fails the caller. {@link DiagnosticWriter}'s own constructor already swallows an
 * unopenable file; nothing added here (the allocation, the secret collection, the header render)
 * can itself throw in a way this class does not already delegate to something equally
 * failure-swallowing — see each collaborator's own javadoc.
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
        return openAt(DiagnosticsDir.allocate(workspace, command, clock), argv, atlassian, clock, warnOut);
    }

    /**
     * Like {@link #open}, but at a CALLER-CHOSEN path rather than one {@link DiagnosticsDir}
     * allocates — {@code sdd doctor --report <path>} is the one place a human names the exact
     * output location instead of accepting the default under {@code .sdd/diagnostics/}.
     */
    public static DiagnosticWriter openAt(Path file, List<String> argv, AtlassianConfig atlassian,
            InstantSource clock, PrintWriter warnOut) {
        DiagnosticWriter writer = new DiagnosticWriter(file, DiagnosticsSecrets.collect(atlassian), clock, warnOut);
        writer.header(DiagnosticHeader.render(argv, atlassian, RuntimeInfo.sddVersion(), RuntimeInfo.gitCommit()));
        return writer;
    }
}
