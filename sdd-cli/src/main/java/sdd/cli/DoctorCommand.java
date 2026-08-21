package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.BitbucketSite;
import sdd.core.config.ConfigException;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.diagnostics.Diagnostics;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.diagnostics.DiagnosticsDir;
import sdd.core.http.AtlassianProbe;
import sdd.core.http.HttpClients;
import sdd.core.http.TlsConfig;
import sdd.core.llm.EndpointProbe;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * Phase 3's "Command line vs configuration": the one flag that earns its place is diagnostic,
     * not configuration — TLS settings themselves stay in {@code sdd.yml} (per-endpoint, and a
     * flag cannot express "one cert per escalation tier" without inventing
     * {@code --tls-cert-for-<tier>}). This restricts which model tier(s) get probed, so an operator
     * iterating on one certificate is not stuck waiting for every other tier to be probed too on
     * every attempt. Null (the default) means every configured model is probed, exactly as before
     * this flag existed — {@link #call}'s definition of done requires a plain {@code sdd doctor}
     * invocation's output to be byte-for-byte unchanged, and this default is what guarantees that.
     */
    @Option(names = "--endpoint", description = "Probe only this model endpoint instead of every "
            + "configured tier — for iterating on one endpoint's TLS certificate")
    String endpointFilter;

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
        // Fix 2 (Gate re-review): pass config.models() too, not just atlassianConfig — the model-tls
        // diagnostic line below is already built to never interpolate a key/truststore password,
        // but this gives DiagnosticsSecrets a genuine redaction backstop for that guarantee instead
        // of leaving docs/commands.md's "still applies the redaction pass ... as a backstop" claim
        // true in prose only. config may be null here (configError case) — Map.of() then, same as
        // every other model-less workspace.
        Map<String, ModelEndpoint> models = config != null ? config.models() : Map.of();
        diagnostics = Diagnostics.openAt(diagFile, commandLine(), atlassianConfig, models, clock,
                spec.commandLine().getErr());

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
                boolean matchedFilter = probeModels(config, clock);
                if (endpointFilter != null && !matchedFilter) {
                    report(false, "endpoint", "no model named '" + endpointFilter + "' is configured");
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

    /**
     * Probes every configured model, or — with {@link #endpointFilter} set — only the one named.
     * Returns whether {@link #endpointFilter} actually matched a configured model, so {@link #call}
     * can report a clean {@code [FAIL] endpoint} for a typo'd {@code --endpoint} name rather than
     * silently probing nothing and exiting {@code 0}. With no filter (every existing invocation,
     * and every pre-existing test) this iterates every entry exactly as before this flag existed.
     */
    /**
     * Opt-in because it costs a real completion per tier, unlike every other check here. It exists
     * because {@code /models} reachability and "the model can produce a usable answer" are different
     * questions, and repo-card generation fails on the second while doctor only ever asked the
     * first — leaving a reader with {@code finish_reason=length} and no way to see why.
     */
    /**
     * Separate from {@code --completion} because it answers a different question and costs its own
     * call: a tier can complete perfectly well and still never emit a tool call, and only the
     * coding tiers need to. When every tier of the ladder fails an implement run with
     * "no tool calls", this is what says whether the endpoint can return them at all.
     */
    @Option(names = "--tools",
            description = "Also ask each model tier to call a tool, and report whether it could "
                    + "(costs one call per tier; sdd implement is entirely tool-driven)")
    boolean tools;

    /**
     * How many tool declarations to put on the wire, so a gateway's ceiling can be FOUND rather
     * than inferred.
     *
     * <p>Plain {@code --tools} sends one declaration, which answers "can this endpoint emit a tool
     * call at all" and nothing else. The failure this exists for is different: some gateways
     * degrade as the declaration set grows, measured on one at 20/20 with a single declaration,
     * 13/20 with six and 0/20 with nine. {@code sdd explore} advertises ten by default and twelve
     * with both {@code --interactive} and {@code --since}, so the one-declaration probe passes
     * cheerfully while a real survey dies.
     *
     * <p>Takes a list because the answer is a threshold, not a yes/no — {@code --tools-count
     * 1,6,10,12} walks it in one session — and pairs with {@code --tools-repeat} because that
     * ceiling was measured as PROBABILISTIC and observed to move within a single day. A single
     * green attempt at one count is the weakest possible evidence about it.
     */
    @Option(names = "--tools-count", split = ",",
            description = "How many tool declarations to send, as a list to sweep "
                    + "(e.g. 1,6,10,12). Finds the gateway's declaration ceiling instead of "
                    + "inferring it. Implies --tools.")
    java.util.List<Integer> toolsCount = new java.util.ArrayList<>();

    @Option(names = "--tools-repeat",
            description = "Attempts per --tools-count entry (default 1). The ceiling is "
                    + "probabilistic, so one green attempt proves little.")
    int toolsRepeat = 1;

    /**
     * Measure the loop's own recovery, not just the cold failure rate.
     *
     * <p>A gateway that answers in prose 15% of the time looks fatal on paper — three consecutive
     * prose turns end a run MALFORMED, which over a 200-turn survey is a coin flip IF the turns are
     * independent. They are not: the loop appends the prose turn and a nudge and asks again. This
     * measures P(prose | nudged), which is the number the survival estimate actually needs, and
     * costs a second call only for attempts that failed.
     */
    @Option(names = "--tools-nudge",
            description = "With --tools-count: after a prose reply, retry it exactly as the agent "
                    + "loop does and report how often that recovers. Answers whether a prose rate "
                    + "is survivable rather than fatal.")
    boolean toolsNudge;

    @Option(names = "--completion",
            description = "Also ask each model tier to produce a short answer, and report "
                    + "finish_reason, token spend and any inline reasoning (costs one call per tier)")
    boolean completion;

    private boolean probeModels(SddConfig config, InstantSource clock) {
        boolean matched = false;
        for (Map.Entry<String, ModelEndpoint> entry : config.models().entrySet()) {
            if (endpointFilter != null && !entry.getKey().equals(endpointFilter)) {
                continue;
            }
            matched = true;
            probeModelEndpoint(entry.getKey(), entry.getValue(), clock);
        }
        return matched;
    }

    /** One model tier: the Phase 3 TLS pre-flight (a no-op for every api-key-only endpoint), then
     *  the existing endpoint probe (byte-for-byte the same check/text as before this phase), then
     *  the Phase 3 diagnostics line (a no-op unless a client certificate is configured). */
    private void probeModelEndpoint(String name, ModelEndpoint ep, InstantSource clock) {
        modelTlsPreflight(name, ep.tls(), clock);
        EndpointProbe.ProbeResult result = EndpointProbe.probe(ep);
        report(result.ok(), "model:" + name, ep.baseUrl() + " → " + result.detail());
        reportModelName(name, ep, result);
        recordModelTlsDiagnostics(name, ep.tls(), result);
        // connected(), not ok(): the probe above asks for /models, which a gateway is under no
        // obligation to serve. Gating on ok() meant that on such a gateway --tools printed one red
        // line and never ran the tool-call probe at all — silently withholding the one check that
        // predicts whether sdd implement or sdd explore can work there. A non-2xx that still
        // answered (a 404 listing route, a 401) makes these MORE informative, not less.
        if (completion && result.connected()) {
            probeCompletion(name, ep);
        }
        // --tools-count implies --tools, the way --retry implies --resume: an operator who named
        // counts has already said what they want probed, and silently ignoring the flag they typed
        // is the one response that teaches them nothing.
        if ((tools || !toolsCount.isEmpty()) && result.connected()) {
            probeToolCalling(name, ep, result);
        }
    }

    /**
     * Reports whether the tier can return a tool call, which is all sdd implement ever asks of it.
     *
     * <p>Uses the default retry contract rather than a single attempt, deliberately: a probe exists
     * to predict a real run, and a real run retries a 429 six times with backoff. Probing without
     * retries reported a transient rate limit as a hard endpoint failure — harsher than the thing
     * it was meant to predict, which is the one way a diagnostic can be worse than no diagnostic.
     */
    private void probeToolCalling(String name, ModelEndpoint ep,
                                  EndpointProbe.ProbeResult endpointProbe) {
        if (!toolsCount.isEmpty()) {
            sweepToolCalling(name, ep, endpointProbe);
            return;
        }
        var r = sdd.core.llm.ToolCallProbe.probe(ep, new sdd.core.llm.HttpChatModel(ep));
        String check = "model:" + name + ":tools";
        report(r.ok(), check, r.detail());
        if (!r.ok()) {
            var out = spec.commandLine().getOut();
            out.println("    finish_reason   : " + r.finishReason());
            out.println("    tokens used     : " + r.completionTokens() + " of " + r.maxTokensSent());
            out.println("    answered instead: " + r.contentExcerpt());
            printDumpHint(r.detail());
        }
        diagnostics.note(check + ": called_tool=" + r.calledTool()
                + " tool=" + r.toolName()
                + " finish_reason=" + r.finishReason()
                + " completion_tokens=" + r.completionTokens()
                + " max_tokens=" + r.maxTokensSent());
    }

    /**
     * Walks the requested declaration counts and reports how many attempts survived each.
     *
     * <p><b>Single attempts, deliberately</b> — {@code maxAttempts = 1}. Everywhere else a probe
     * retries because it exists to predict a real run, and a real run retries. Here that would
     * destroy the measurement: the thing being counted IS the failure rate, and retrying until
     * something works reports a gateway that fails half the time as one that works.
     *
     * <p><b>The verdict names the failure MODE, not just the counts.</b> The first live run of this
     * against a real gateway reported "declaration ceiling reached" for a sweep that reached no
     * ceiling at all: success sat near 85% flat from 1 to 11 declarations, and every failure was
     * {@code NO TOOL CALL ... finish_reason=stop} — the endpoint answering in prose, which is a
     * different fault with a different fix (see {@code models.<name>.tool_calls}). A sweep that
     * calls every imperfect result a ceiling is a diagnostic that confirms whatever you already
     * suspected, so the two are separated here and a non-monotonic result is called out as not
     * being a ceiling.
     */
    private void sweepToolCalling(String name, ModelEndpoint ep,
                                  EndpointProbe.ProbeResult endpointProbe) {
        var out = spec.commandLine().getOut();
        int repeats = Math.max(1, toolsRepeat);
        java.util.List<Integer> counts = toolsCount.stream()
                .filter(java.util.Objects::nonNull).filter(c -> c >= 1).sorted().distinct().toList();
        if (counts.isEmpty()) {
            report(false, "model:" + name + ":tools", "--tools-count needs at least one count >= 1");
            return;
        }
        boolean allOk = true;
        int prose = 0;
        int argsOnly = 0;
        int transport = 0;
        int totalNudged = 0;
        int totalRecovered = 0;
        java.util.List<Integer> rates = new java.util.ArrayList<>();
        StringBuilder note = new StringBuilder();
        for (int count : counts) {
            int ok = 0;
            int nudged = 0;
            int recovered = 0;
            String lastFailure = null;
            String lastSaid = null;
            for (int i = 0; i < repeats; i++) {
                var model = new sdd.core.llm.HttpChatModel(ep, 1);
                sdd.core.llm.ToolCallProbe.Result r;
                if (toolsNudge) {
                    var n = sdd.core.llm.ToolCallProbe.probeNudged(ep, model, count);
                    r = n.cold();
                    if (n.afterNudge() != null) {
                        nudged++;
                        if (n.recovered()) {
                            recovered++;
                        }
                    }
                } else {
                    r = sdd.core.llm.ToolCallProbe.probe(ep, model, count);
                }
                if (r.ok()) {
                    ok++;
                    continue;
                }
                lastFailure = r.detail();
                switch (r.fault()) {
                    case ARGUMENTS_ONLY -> {
                        argsOnly++;
                        lastSaid = r.contentExcerpt();
                    }
                    case TRANSPORT -> transport++;
                    default -> {
                        prose++;
                        lastSaid = r.contentExcerpt();
                    }
                }
            }
            allOk &= ok == repeats;
            rates.add(ok);
            totalNudged += nudged;
            totalRecovered += recovered;
            out.println(String.format("    %3d declarations : %d/%d cold%s%s",
                    count, ok, repeats,
                    nudged == 0 ? "" : String.format(", %d of %d recovered after the nudge",
                            recovered, nudged),
                    lastFailure == null ? "" : "   " + lastFailure));
            // The one line that separates "wrote no call" from "wrote a call sdd could not parse".
            // Dropping it was why the first live sweep could not be read without a second run.
            if (lastSaid != null && !lastSaid.isBlank()) {
                out.println("        answered instead: " + lastSaid);
            }
            note.append(count).append('=').append(ok).append('/').append(repeats).append(' ');
            if (lastFailure != null) {
                printDumpHint(lastFailure);
            }
        }
        String check = "model:" + name + ":tools";
        // Nothing succeeded anywhere, so the sweep exercised no declaration count at all and any
        // sentence about ceilings would be manufactured from an empty measurement.
        if (rates.stream().allMatch(r -> r == 0)) {
            report(false, check, "THIS SWEEP MEASURED NOTHING — every attempt failed at every "
                    + "count (" + note.toString().strip() + "), so nothing was learned about "
                    + "declaration count. Fix the failure above and re-run"
                    + modelHint(ep, endpointProbe));
            diagnostics.note(check + ": sweep inconclusive " + note.toString().strip());
            return;
        }
        report(allOk, check, allOk
                ? "every count carried: " + note.toString().strip()
                : verdict(note.toString().strip(), counts, rates, repeats, prose, argsOnly,
                        transport)
                        + recoveryNote(totalNudged, totalRecovered));
        diagnostics.note(check + ": sweep " + note.toString().strip() + " repeats=" + repeats
                + " prose_failures=" + prose + " arguments_only=" + argsOnly
                + " transport_failures=" + transport
                + " nudged=" + totalNudged + " recovered=" + totalRecovered);
    }

    /**
     * Says when the configured model is not one this gateway serves — on the ENDPOINT line, where
     * it is seen before anything else runs.
     *
     * <p>Printed here and not only from the tool sweep because the endpoint probe is what an
     * operator runs first, and it was reporting a cheerful green {@code HTTP 200} for a tier whose
     * every completion would 404 with {@code "No such model"}. The listing that proves it had
     * already been fetched and discarded.
     *
     * <p>Silent when the name IS served, and silent when the listing could not be parsed: a hint
     * that fires on every healthy run is noise, and a wrong hint is worse than none.
     */
    private void reportModelName(String name, ModelEndpoint ep, EndpointProbe.ProbeResult result) {
        if (result.models().isEmpty() || result.models().contains(ep.model())) {
            return;
        }
        var out = spec.commandLine().getOut();
        out.println("    configured model '" + ep.model() + "' is NOT served here — this gateway "
                + "offers: " + String.join(", ", result.models()));
        out.println("    every completion will fail with \"No such model\"; set models." + name
                + ".model to one of the above");
        diagnostics.note("model:" + name + ": configured=" + ep.model()
                + " served=" + String.join(",", result.models()));
    }

    /**
     * Names the models the gateway actually serves, when the configured one is not among them.
     *
     * <p>{@code {"status":404,"message":"No such model"}} is a complete diagnosis and a useless
     * one: it does not say what WOULD work. The {@code /models} listing that ran seconds earlier
     * does, and until now its body was discarded. On a closed network that difference is a round
     * trip to another machine.
     */
    private String modelHint(ModelEndpoint ep, EndpointProbe.ProbeResult endpointProbe) {
        if (endpointProbe == null || endpointProbe.models().isEmpty()) {
            return "";
        }
        if (endpointProbe.models().contains(ep.model())) {
            return "";   // the name is served; whatever failed was not the model name
        }
        return ". Configured model '" + ep.model() + "' is NOT in this gateway's /models listing, "
                + "which offers: " + String.join(", ", endpointProbe.models());
    }

    /**
     * What the nudge bought, in the terms the run cares about.
     *
     * <p>Three CONSECUTIVE prose turns end a run MALFORMED, so what threatens a survey is not the
     * cold rate but the rate that survives a retry. A high recovery figure means the cold rate
     * overstates the danger by a lot.
     */
    private static String recoveryNote(int nudged, int recovered) {
        if (nudged == 0) {
            return "";
        }
        int pct = recovered * 100 / nudged;
        String head = ". The loop's own retry recovered " + recovered + " of " + nudged
                + " failed attempts (" + pct + "%)";
        if (pct == 0) {
            return head + " — NOTHING recovered, so the cold rate above is the real per-turn rate "
                    + "and three of them in a row ends a run MALFORMED";
        }
        return head + " — a run is wedged only by THREE IN A ROW, so the cold rate above overstates "
                + "the risk by roughly that much";
    }

    /**
     * Says which fault this was, because the fixes are unrelated.
     *
     * <p>A ceiling is a claim about MONOTONICITY — it works below a threshold and not above. A rate
     * that wobbles at every count is a different animal and must not be described as a ceiling, or
     * the operator goes and sets {@code single_tool} to fix something it cannot fix.
     */
    private static String verdict(String note, java.util.List<Integer> counts,
                                  java.util.List<Integer> rates, int repeats,
                                  int prose, int argsOnly, int transport) {
        boolean monotonic = true;
        for (int i = 1; i < rates.size(); i++) {
            if (rates.get(i) > rates.get(i - 1)) {
                monotonic = false;
                break;
            }
        }
        boolean cleanSomewhere = rates.stream().anyMatch(r -> r == repeats);
        StringBuilder v = new StringBuilder();
        if (monotonic && rates.get(rates.size() - 1) == 0 && cleanSomewhere) {
            v.append("declaration ceiling reached — ").append(note)
                    .append(" (explore advertises 10 by default, 12 with --interactive and --since; "
                            + "explore.single_tool collapses them to 1)");
        } else {
            v.append("NOT a declaration ceiling — ").append(note)
                    .append(" (success does not fall away with the count, so single_tool would not "
                            + "fix this)");
        }
        if (argsOnly > 0 && prose == 0 && transport == 0) {
            v.append(". Every failure was ARGUMENTS ONLY — the model CALLED a tool and the reply "
                    + "does not say which, so sdd refuses it rather than guess which of the "
                    + "declared tools to run. This is not a prose problem and not a count problem: "
                    + "the tier can drive an agent, the calls are merely unaddressed");
        } else if (prose > 0 && argsOnly == 0 && transport == 0) {
            v.append(". Every failure was the endpoint ANSWERING IN PROSE rather than rejecting "
                    + "the request — check models.<name>.tool_calls (a gateway that lets the model "
                    + "write the call as content needs `text`), and read the 'answered instead' "
                    + "lines above to see whether it wrote a call sdd could not parse");
        } else if (transport > 0 && prose == 0 && argsOnly == 0) {
            v.append(". Every failure was a TRANSPORT error — the request never produced a reply, "
                    + "so nothing here is about tool calling at all");
        } else if (prose + argsOnly + transport > 0) {
            v.append(". Mixed failures: ").append(prose).append(" prose, ").append(argsOnly)
                    .append(" arguments-only, ").append(transport)
                    .append(" transport — these have different fixes, do not treat them as one");
        }
        return v.toString();
    }

    /**
     * Points at the one thing that settles an endpoint's complaint about the request itself.
     *
     * <p>A 4xx here means the gateway read the body and rejected it, and gateway messages for that
     * are routinely useless — "invalid JSON syntax" for a body that verifiably parses, naming
     * neither the field nor the reason. Reading the exchange is the only honest next step, and an
     * operator has no way to guess that sdd can show it. Only printed when the dump is not already
     * on, and only for a status that means the request was the problem: nothing about a 500 or a
     * timeout is answered by looking at bytes that were accepted.
     */
    private void printDumpHint(String detail) {
        if (detail == null || System.getenv("SDD_HTTP_DUMP") != null) {
            return;
        }
        if (!detail.startsWith("HTTP 4")) {
            return;
        }
        spec.commandLine().getOut().println(
                "    the endpoint rejected the REQUEST — to see exactly what was sent and what came "
                + "back:\n      SDD_HTTP_DUMP=/tmp/sdd-wire.jsonl sdd doctor --endpoint <name> --tools\n"
                + "    (bodies only, no headers, written where you say — not included in --report)");
    }

    /**
     * Names {@code SDD_ATLASSIAN_DUMP} once, the same way {@link #printDumpHint} names its model
     * counterpart. Deliberately a hint and not a default: the dump contains whatever the tickets
     * and pages contain, so turning it on is the operator's decision, not doctor's.
     */
    private void printAtlassianDumpHint() {
        if (System.getenv(sdd.core.diagnostics.AtlassianWireDump.ENV) != null) {
            return;
        }
        spec.commandLine().getOut().println(
                "    to record exactly what Jira/Confluence sent and returned:\n"
                + "      SDD_ATLASSIAN_DUMP=1 sdd plan --fetch-only <ISSUE-KEY>\n"
                + "    (writes .sdd/atlassian-wire.jsonl; credentials redacted, ticket text is not)");
    }

    /** Reports what the tier actually produced, with the numbers that explain a truncation. */
    private void probeCompletion(String name, ModelEndpoint ep) {
        var r = sdd.core.llm.CompletionProbe.probe(ep, new sdd.core.llm.HttpChatModel(ep));
        String check = "model:" + name + ":completion";
        report(r.ok(), check, r.detail());
        var out = spec.commandLine().getOut();
        out.println("    max_tokens sent : " + r.maxTokensSent());
        out.println("    tokens used     : " + r.promptTokens() + " prompt / "
                + r.completionTokens() + " completion");
        out.println("    reasoning chars : " + r.reasoningChars()
                + (r.reasoningChars() > 0
                        ? "  <- inline <think> reasoning; no request parameter disables this on "
                                + "every provider, so max_tokens must cover it"
                        : ""));
        out.println("    answer chars    : " + r.answerChars());
        out.println("    raw reply head  : " + r.rawExcerpt());
        if (!r.ok()) {
            printDumpHint(r.detail());
        }
        diagnostics.note(check + ": finish_reason=" + r.finishReason()
                + " completion_tokens=" + r.completionTokens()
                + " max_tokens=" + r.maxTokensSent()
                + " reasoning_chars=" + r.reasoningChars()
                + " answer_chars=" + r.answerChars());
    }

    /**
     * The plan's "before probing an mTLS endpoint" pre-flight — validates exactly the failures the
     * plan calls out as otherwise opaque: the cert/key files exist and are readable, the key
     * actually parses (surfacing {@link HttpClients#keyManagers}'s actionable messages —
     * built from {@code sdd.core.http.PemKeyLoader}, package-private there — including the
     * {@code openssl} conversion hint for PKCS#1/SEC1/legacy-encrypted keys — verbatim, never
     * reimplemented here), and the client certificate is not expired (with a
     * warning inside 30 days) — the single most common mTLS failure, whose TLS alert
     * ({@code bad_certificate}) says nothing useful on its own. Also wires
     * {@link HttpClients#keyFilePermissionWarning}, deliberately left unwired by Phase 2 because
     * {@code sdd-core} has no writer.
     *
     * <p>A no-op when {@code tls} carries no client certificate (null {@code tls}, or {@code tls}
     * with {@code clientCert()}/{@code clientKey()} both null — every endpoint using only
     * {@code api_key}, exactly {@code every existing workspace}) — this is what keeps a plain
     * {@code sdd doctor} invocation's stdout byte-for-byte unchanged by this phase.
     *
     * <p>{@code clock} is never the wall clock read directly — it is the same
     * {@link InstantSource} {@link #call} already threads through for {@code --report}'s file
     * naming — so the expiry check is deterministic in tests: a real, currently-valid certificate
     * generated at test time is made to look expired simply by advancing the injected clock past
     * its {@code notAfter}, with no need for an actually-expired fixture that would itself go stale
     * and silently start failing years from now.
     */
    private void modelTlsPreflight(String name, TlsConfig tls, InstantSource clock) {
        if (tls == null || tls.clientCert() == null || tls.clientKey() == null) {
            return;
        }
        String check = "model:" + name + ":tls";
        X509Certificate leaf;
        try {
            // HttpClients.keyManagers alone already proves: both files exist and are readable, any
            // deferred tls.key_password error, and that the key parses — the exact same code path
            // (and therefore the exact same actionable messages) the real handshake below will use.
            HttpClients.keyManagers(tls);
            leaf = HttpClients.clientCertificateChain(tls.clientCert()).get(0);
        } catch (ConfigException e) {
            report(false, check, e.getMessage());
            return;
        }
        String permissionWarning = HttpClients.keyFilePermissionWarning(tls.clientKey());
        if (permissionWarning != null) {
            spec.commandLine().getOut().println(permissionWarning);
        }
        Instant now = clock.instant();
        Instant notAfter = leaf.getNotAfter().toInstant();
        String subject = leaf.getSubjectX500Principal().getName();
        if (!notAfter.isAfter(now)) {
            report(false, check, "client certificate expired " + notAfter + " (subject=" + subject + ")");
            return;
        }
        long daysLeft = Duration.between(now, notAfter).toDays();
        report(true, check, "client certificate ok — subject=" + subject + ", expires " + notAfter
                + " (" + daysLeft + "d)");
        if (daysLeft <= 30) {
            spec.commandLine().getOut().println("  warn: client certificate for " + check
                    + " expires in " + daysLeft + " day(s) (" + notAfter + ")");
        }
    }

    /**
     * Phase 3's "Diagnostics" section, verbatim: cert path, subject, expiry, the negotiated TLS
     * protocol, and whether a custom truststore was in use — everything a remote reader needs to
     * diagnose an mTLS-configured model endpoint and cannot otherwise obtain, and nothing else
     * (never the key, never {@code tls.key_password}).
     *
     * <p><b>Never a {@code ModelEndpoint} or {@code TlsConfig} reference reaches a diagnostic
     * line.</b> This method's parameters are deliberately the individual fields it needs
     * ({@code name}, {@code tls}, {@code probeResult}) — {@code tls} itself is inspected only via
     * its accessors ({@code clientCert()}, {@code truststore()}), never concatenated or
     * {@code toString()}'d directly. See the review note carried into this phase: neither
     * {@code ModelEndpoint} nor {@code TlsConfig} overrides {@code toString()} (both are records, so
     * the default prints every component — including {@code TlsConfig.keyPassword()}), and rather
     * than add a redaction-only {@code toString()} override to production records for code that
     * would still have to remember not to rely on it, the safer fix is the one applied everywhere
     * else in this codebase for exactly this shape of secret ({@code ConfigLoader}'s
     * {@code apiKeyError}/{@code keyPasswordError} idiom): never construct the interpolation in the
     * first place. {@code DoctorCommandTest#aConfiguredKeyPasswordNeverReachesStdoutOrTheDiagnosticsFile}
     * proves this holds for a real encrypted key's password end to end, not merely by code reading.
     *
     * <p>Best-effort: a re-parse failure here (already reported as a failed
     * {@code model:<name>:tls} check by {@link #modelTlsPreflight}) simply omits subject/expiry
     * rather than throwing a second time over the same broken certificate.
     */
    private void recordModelTlsDiagnostics(String name, TlsConfig tls, EndpointProbe.ProbeResult probeResult) {
        if (tls == null || tls.clientCert() == null) {
            return;
        }
        String subject = "?";
        String expires = "?";
        try {
            X509Certificate leaf = HttpClients.clientCertificateChain(tls.clientCert()).get(0);
            subject = leaf.getSubjectX500Principal().getName();
            expires = leaf.getNotAfter().toInstant().toString();
        } catch (RuntimeException e) {
            // Already reported by modelTlsPreflight (or will be, by the probe below); diagnostics
            // is best-effort only and must never throw a second time over the same broken cert.
        }
        String protocol = probeResult.negotiatedProtocol() != null ? probeResult.negotiatedProtocol() : "?";
        String truststore = tls.truststore() != null ? tls.truststore().toString() : "(JDK default truststore)";
        diagnostics.note("model-tls name=" + name + " cert=" + tls.clientCert() + " subject=" + subject
                + " expires=" + expires + " protocol=" + protocol + " truststore=" + truststore);
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
        printAtlassianDumpHint();
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
