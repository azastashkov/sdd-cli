package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.BitbucketSite;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.diagnostics.Diagnostics;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.diagnostics.DiagnosticsDir;
import sdd.core.http.AtlassianProbe;
import sdd.core.http.HttpClients;
import sdd.core.llm.EndpointProbe;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Check that sdd's environment is ready")
public final class DoctorCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    /**
     * Task 8 B5: {@code sdd doctor --report [path]} runs the same checks as a plain {@code sdd
     * doctor} but ALSO assembles them, plus the header block and a tail of recent diagnostic
     * history, into one self-contained file designed to be handed to someone who cannot reach this
     * network — see {@link #call}'s closing section. {@code arity = "0..1"} with an empty {@code
     * fallbackValue} is what lets {@code --report} alone (no path) mean "use the default location"
     * while {@code --report /some/path} means exactly that path; {@code null} (the field's default)
     * means the flag was not given at all — three states, not two, which is why this is a
     * nullable/blank-checked {@link String} rather than a {@link Path} with some sentinel value.
     */
    @Option(names = "--report", arity = "0..1", fallbackValue = "",
            description = "Write a self-contained diagnostics report (default path under "
                    + ".sdd/diagnostics/); prints the path and a one-line note that it is safe to share")
    String report;

    @Spec CommandSpec spec;

    /** Test seam — mirrors {@code PlanCommand.plannerForTest}/{@code ApproveCommand.smokeForTest}:
     *  {@code null} in real use, where {@link #call} falls back to {@link InstantSource#system()}. */
    InstantSource clockForTest;

    private boolean allOk = true;
    private DiagnosticWriter diagnostics;

    @Override
    public Integer call() {
        InstantSource clock = clockForTest != null ? clockForTest : InstantSource.system();
        boolean reportRequested = report != null;

        // Config is loaded FIRST, ahead of the java/database checks that used to run before it,
        // so the diagnostics writer below (which needs the atlassian: block to know which secrets
        // to redact — B4) exists before the very first report() call, not partway through. Every
        // report() line's CONTENT is unchanged; only the internal order of "load config" versus
        // "print the java line" moved, which no existing assertion depends on (each checks a line's
        // presence, never the order between java/config/database).
        SddConfig config = null;
        RuntimeException configError = null;
        try {
            config = ConfigLoader.load(workspace);
        } catch (RuntimeException e) {
            configError = e;
        }
        AtlassianConfig atlassianConfig = config != null ? config.atlassian() : null;

        // Gate review minor: Path.of(report) throws InvalidPathException (a RuntimeException) on a
        // malformed --report argument, and this used to sit outside any guard — the FIRST thing
        // this method could do after that would be an uncaught stack trace out of call(), not a
        // clean [FAIL] line like every other doctor check produces. There is no diagnostics file to
        // open at an invalid path either, so this reports directly and exits rather than trying.
        Path diagFile;
        try {
            diagFile = reportRequested && !report.isBlank() ? Path.of(report)
                    : DiagnosticsDir.allocate(workspace, "doctor", clock);
        } catch (RuntimeException e) {
            spec.commandLine().getOut().printf("[FAIL] report-path — %s%n", e.getMessage());
            return 1;
        }
        diagnostics = Diagnostics.openAt(diagFile, commandLine(), atlassianConfig, clock, spec.commandLine().getErr());

        try {
            int javaMajor = Runtime.version().feature();
            report(javaMajor >= 21, "java", "runtime " + javaMajor);

            if (configError == null) {
                report(true, "config", workspace.resolve("sdd.yml").toString());
            } else {
                report(false, "config", configError.getMessage());
            }

            try (Database db = Database.open(workspace)) {
                report(true, "database", ".sdd/index.db schema v" + db.schemaVersion());
            } catch (RuntimeException e) {
                report(false, "database", e.getMessage());
            }

            if (config != null) {
                for (Map.Entry<String, ModelEndpoint> entry : config.models().entrySet()) {
                    EndpointProbe.ProbeResult result = EndpointProbe.probe(entry.getValue());
                    report(result.ok(), "model:" + entry.getKey(),
                            entry.getValue().baseUrl() + " → " + result.detail());
                }
                if (config.atlassian() != null) {
                    probeAtlassian(config.atlassian());
                }
            }

            if (reportRequested) {
                appendRecentHistory(diagFile);
                spec.commandLine().getOut().println("diagnostics report written: " + diagFile);
                spec.commandLine().getOut().println("this file is safe to share: known secret values are "
                        + "redacted by construction — internal hostnames and Jira/Confluence/Bitbucket "
                        + "project and issue keys are NOT, since they are needed for diagnosis.");
            }
            return allOk ? 0 : 1;
        } finally {
            diagnostics.close();
        }
    }

    /** B5's "tail of the most recent diagnostic files" — recent history alongside this run's own
     *  fresh probe results, so a single pasted file carries both "what is happening now" and "what
     *  happened the last few times" without a human having to go collect several files. Best-effort:
     *  a file that has since been deleted or become unreadable between {@link DiagnosticsDir
     *  #mostRecent} listing it and this reading it is simply skipped. */
    private void appendRecentHistory(Path diagFile) {
        List<Path> recent = DiagnosticsDir.mostRecent(workspace, 3, diagFile);
        if (recent.isEmpty()) {
            return;
        }
        diagnostics.note("--- tail of recent diagnostic files ---");
        for (Path file : recent) {
            diagnostics.note("from " + file.getFileName() + ":");
            try {
                List<String> lines = Files.readAllLines(file);
                int from = Math.max(0, lines.size() - 40);
                for (String line : lines.subList(from, lines.size())) {
                    diagnostics.note("  " + line);
                }
            } catch (IOException | RuntimeException e) {
                diagnostics.note("  (could not read " + file + ": " + e.getMessage() + ")");
            }
        }
    }

    /** {@code ["doctor", ...the exact tokens this command was invoked with]} — {@link
     *  DiagnosticHeader#render}'s "command line as invoked". {@code spec.name()} rather than a
     *  literal {@code "doctor"} so a future rename of the command needs no matching change here. */
    private List<String> commandLine() {
        List<String> argv = new ArrayList<>();
        argv.add(spec.name());
        argv.addAll(spec.commandLine().getParseResult().originalArgs());
        return argv;
    }

    // A missing atlassian: block must not change this output at all — every check below only
    // runs when the corresponding site is actually configured, matching how model probes only run
    // per declared models: entry above.
    private void probeAtlassian(AtlassianConfig ac) {
        HttpClient client;
        String clientBuildError = null;
        try {
            client = HttpClients.build(ac.tls(), ac.proxy());
        } catch (RuntimeException e) {
            // A bad atlassian.tls.truststore (missing file, unreadable, wrong password) fails
            // HttpClients.build itself, before any site is even probed. Report it against every
            // configured site rather than crashing doctor — one broken truststore should not hide
            // whether the rest of the estate's checks (java, config, database, models) are fine.
            client = null;
            clientBuildError = e.getMessage();
        }
        Path truststore = ac.tls() != null ? ac.tls().truststore() : null;

        if (ac.jira() != null) {
            reportAtlassianProbe("atlassian:jira", "Jira", ac.jira(), "/rest/api/2/myself",
                    client, clientBuildError, truststore, "name", "displayName");
        }
        if (ac.confluence() != null) {
            reportAtlassianProbe("atlassian:confluence", "Confluence", ac.confluence(), "/rest/api/user/current",
                    client, clientBuildError, truststore, "username", "displayName");
        }
        if (ac.bitbucket() != null) {
            BitbucketSite bb = ac.bitbucket();
            // Fix 2 (review): Bitbucket Data Center's REST 1.0 API has no /users/self resource —
            // /users/{userSlug} needs a real slug doctor does not have, so a second probe there
            // would almost always 404 and report a healthy Bitbucket as down, which is expensive
            // on the closed network doctor runs on first. The one probe below
            // (GET /rest/api/1.0/projects/{project}, already needed to confirm the project itself
            // is reachable) also authenticates, so its X-AUSERNAME response header gives the same
            // identity confirmation without a second call.
            if (clientBuildError != null) {
                report(false, "atlassian:bitbucket", clientBuildError);
            } else {
                AtlassianProbe.ProbeResult result = AtlassianProbe.probeHeaderLabel("Bitbucket", bb.site(),
                        "/rest/api/1.0/projects/" + bb.project(), client, truststore, diagnostics, "X-AUSERNAME");
                report(result.ok(), "atlassian:bitbucket", bb.site().baseUrl() + " → " + result.detail());
            }
        }
        // Gate review minor: atlassian.tls set but no site configured at all — clientBuildError was
        // computed above but every report() call for it lives inside an `if (ac.<site>() != null)`
        // block, so with no site this was silently dropped and doctor exited 0 having printed
        // nothing about a truststore that does not even load. Reported once here, only when no
        // per-site branch above already reported the same error against a real site.
        if (clientBuildError != null && ac.jira() == null && ac.confluence() == null && ac.bitbucket() == null) {
            report(false, "atlassian:tls", clientBuildError);
        }
    }

    private void reportAtlassianProbe(String check, String siteName, AtlassianSite site, String path,
            HttpClient client, String clientBuildError, Path truststore, String... labelFields) {
        if (clientBuildError != null) {
            report(false, check, clientBuildError);
            return;
        }
        AtlassianProbe.ProbeResult result = AtlassianProbe.probe(siteName, site, path, client, truststore,
                diagnostics, labelFields);
        report(result.ok(), check, site.baseUrl() + " → " + result.detail());
    }

    private void report(boolean ok, String check, String detail) {
        if (!ok) {
            allOk = false;
        }
        spec.commandLine().getOut().printf("[%s] %s — %s%n", ok ? " OK " : "FAIL", check, detail);
        // Every check, not just the Atlassian ones — sdd doctor --report's whole point is to be
        // the ONE file a human hands to someone remote (B5), so it has to carry the java/config/
        // database/model verdicts too, not only the HTTP entries RestClient/AtlassianProbe log on
        // their own. Written unconditionally (not gated on --report) so the always-on default file
        // (B6) has this context too, in case a human goes looking at it before ever passing the flag.
        diagnostics.note("probe " + check + ": " + (ok ? "OK" : "FAIL") + " — " + detail);
    }
}
