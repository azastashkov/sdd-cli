package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.agent.loop.AgentBudget;
import sdd.agent.loop.AgentResult;
import sdd.agent.run.Explorer;
import sdd.agent.tool.HumanAsk;
import sdd.agent.tool.Notebook;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ExploreSettings;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.plan.gen.SafeWrite;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecRenderer;
import sdd.plan.spec.SpecValidator;
import sdd.plan.spec.Touchpoint;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Reads the estate to work out what a free-text task touches, and writes the answer back into the
 * spec for a human to review.
 *
 * <p><b>Why a subcommand and not a flag on {@code plan}.</b> {@code sdd plan approve} SHA-hashes
 * {@code plan.md}, so the drafter's evidence must be a deterministic function of the KB and the
 * spec. A model roaming the estate is not deterministic. So exploration runs BEFORE the gate,
 * materialises what it found into a file a human reviews, and that file — being a file — is the
 * deterministic input {@code sdd plan} then consumes. {@code Closure.expand} still takes no model.
 *
 * <p>Two kinds of output, because the touchpoint grammar cannot express everything a task names:
 * resolvable things become {@code ## Touchpoints} (still hints — {@code SeedFinder} re-verifies
 * them, and a miss is still a blocking problem), and everything else — a Redis channel, a database
 * table, a dashboard — becomes a cited {@code ## Evidence} bullet, which reaches the planner
 * through {@code SpecRenderer} and {@code PlanDrafter.salientTerms} like any other spec prose.
 */
@Command(name = "explore",
        description = "Search the estate for what a spec's free text actually refers to, and "
                + "write the findings back into the spec for review")
public final class ExploreCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", description = "The spec to explore (canonical .md)")
    Path specPath;

    @Option(names = "--model", description = "Which models: entry to explore with (default: planner)")
    String modelKey = "planner";

    @Option(names = "--out", description = "Write the enriched spec here instead of in place")
    Path out;

    @Option(names = "--interactive",
            description = "Let the explorer ask you a question when the answer changes what it "
                    + "would do. Without this the tool is not offered at all.")
    boolean interactive;

    /** Test-only injection point: null in real use, where the asker falls back to {@code System.in}.
     *  Copied from {@code ReviewCommand}, and for the same reason — the interactive path must be
     *  fully testable without a terminal, which is how every other interactive case in this tree is
     *  tested. */
    java.io.BufferedReader in;

    @Spec CommandSpec spec;

    ChatModel explorerForTest;   // test seam — mirrors PlanCommand.plannerForTest

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        try {
            SddConfig config = ConfigLoader.load(workspace);
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                errWriter.println("error: knowledge base is empty — run sdd index first");
                return 1;
            }
            NormalizedSpec parsed = SpecParser.parse(Files.readString(specPath));
            ModelEndpoint endpoint = config.models().get(modelKey);
            if (endpoint == null && explorerForTest == null) {
                errWriter.println("error: no models entry named '" + modelKey + "' in sdd.yml");
                return 1;
            }
            ChatModel model = explorerForTest != null ? explorerForTest : new HttpChatModel(endpoint);
            String modelName = endpoint != null ? endpoint.model() : "test";

            try (Database db = Database.open(workspace)) {
                Map<String, Path> roots = Explorer.repoRoots(db.jdbi(), workspace);
                if (roots.isEmpty()) {
                    errWriter.println("error: no repos in the knowledge base — run sdd index first");
                    return 1;
                }
                ExploreSettings settings = config.explore();
                outWriter.println("exploring " + roots.size() + " repos with " + modelKey
                        + " (up to " + settings.turns() + " turns"
                        + (settings.singleTool() ? ", single-tool mode" : "") + ")");
                Explorer.Exploration exploration = new Explorer(db.jdbi()).explore(
                        roots, SpecRenderer.render(parsed), model, modelName,
                        new AgentBudget(settings.turns(), settings.wall(), settings.tokens()),
                        settings.contextSoftCap(), endpoint != null ? endpoint.maxTokens() : 4096,
                        InstantSource.system(), settings.singleTool(),
                        line -> outWriter.println("  " + line),
                        interactive ? asker(outWriter) : null, settings.maxQuestions());
                return report(exploration, parsed, outWriter, errWriter);
            }
        } catch (java.io.IOException e) {
            errWriter.println("error: cannot read " + specPath + ": " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Writes the per-turn transcript and the event log, and returns where they went.
     *
     * <p>{@code AgentLoop} builds this for every run and, until now, {@code explore} discarded
     * it — so a run that stopped after one request left nothing to say why. {@code implement}
     * has persisted the same thing since it shipped; this is the same file in the same shape.
     */
    private Path writeDiagnostics(Explorer.Exploration exploration, String specId,
                                  PrintWriter errWriter) {
        Path dir = workspace.resolve(".sdd/explore").resolve(sanitize(specId));
        List<String> events = exploration.outcome() == null
                ? List.of() : exploration.outcome().events();
        try {
            Files.createDirectories(dir);
            StringBuilder turns = new StringBuilder();
            for (String line : exploration.transcript()) {
                turns.append(line).append('\n');
            }
            Files.writeString(dir.resolve("transcript.jsonl"), turns.toString());
            Files.writeString(dir.resolve("events.txt"),
                    String.join("\n", events) + (events.isEmpty() ? "" : "\n"));
            return dir;
        } catch (java.io.IOException e) {
            // Losing the transcript must not lose the survey: the notebook is the deliverable.
            errWriter.println("warn: could not write diagnostics to " + dir + ": " + e.getMessage());
            return null;
        }
    }

    /** Filesystem-safe spec id, same shape {@code ImplementCommand} uses for a run dir. */
    private static String sanitize(String id) {
        String cleaned = id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "spec" : cleaned;
    }

    /**
     * Prompts on stdout and reads one line.
     *
     * <p>Deliberately NOT gated on a TTY. {@code ConsoleSupport.isTerminal()} exists and is
     * correct, but gating on it would make this the only interactive thing in the tree that cannot
     * be driven by a {@code StringReader} — which is how {@code InteractiveReviewTest} drives the
     * one interactive command sdd already has. Piped input is a supported path, not an accident.
     *
     * <p>Once stdin is exhausted the asker LATCHES: every later question returns null immediately
     * without touching the stream. A run that has lost its human must not spend the rest of its
     * turns rediscovering that one question at a time.
     *
     * <p>{@code sdd explore} does not arm {@code Progress}, so there is no live line to fight. If
     * it ever does, call {@code progress.stop()} before the first prompt — never
     * {@code Progress.suspend}, which runs its action inside the renderer's monitor and would hold
     * that lock for as long as the person takes to type.
     */
    private HumanAsk asker(PrintWriter outWriter) {
        java.io.BufferedReader reader = in != null ? in
                : new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
                        java.nio.charset.StandardCharsets.UTF_8));
        boolean[] noHuman = {false};
        return (question, options) -> {
            if (noHuman[0]) {
                return null;
            }
            outWriter.println();
            outWriter.println("  ? " + question);
            for (String option : options) {
                outWriter.println("      - " + option);
            }
            outWriter.print("  your answer (blank to decline): ");
            outWriter.flush();
            try {
                String line = reader.readLine();
                if (line == null || line.isBlank()) {
                    noHuman[0] = true;
                    return null;
                }
                return line;
            } catch (java.io.IOException e) {
                noHuman[0] = true;
                return null;
            }
        };
    }

    private Integer report(Explorer.Exploration exploration, NormalizedSpec parsed,
                           PrintWriter outWriter, PrintWriter errWriter) {
        Notebook notebook = exploration.notebook();
        AgentResult result = exploration.failed() ? null : exploration.outcome().result();
        if (exploration.failed()) {
            // The endpoint failed, which is a different thing from the survey ending — say so in
            // those terms, and still hand over everything the run had reached.
            outWriter.println("explored: ENDPOINT FAILED after "
                    + exploration.transcript().size() + " completed turns");
            outWriter.println("  " + exploration.transportError());
        } else {
            outWriter.println("explored: " + result + " after " + exploration.outcome().turns()
                    + " turns, " + exploration.outcome().tokens() + " tokens");
            outWriter.println(exploration.outcome().summary());
            for (String event : exploration.outcome().events()) {
                outWriter.println("  event: " + event);
            }
        }
        Path diagnostics = writeDiagnostics(exploration, parsed.id(), errWriter);
        if (diagnostics != null) {
            outWriter.println("transcript: " + diagnostics.resolve("transcript.jsonl"));
        }
        if (notebook.isEmpty()) {
            // Not an error: an honest "nothing found" is a real answer, and rewriting the spec to
            // say so would be worse than leaving it alone.
            outWriter.println("nothing recorded — the spec is unchanged");
            return result == AgentResult.DONE ? 0 : 2;
        }
        for (Notebook.Finding finding : notebook.findings()) {
            outWriter.println("  " + finding.citation() + " reads: " + finding.citedLine());
        }
        NormalizedSpec enriched = merge(parsed, notebook,
                exploration.failed() ? "ENDPOINT FAILED" : result.name(),
                result == AgentResult.DONE);
        Path target = out != null ? out : specPath;
        String rendered = SpecRenderer.render(enriched);
        // Self-check before the human ever sees it: never hand over a gate file that cannot
        // re-parse. Same discipline as PlanCommand.writeNormalized.
        SpecParser.parse(rendered);
        Path backup = SafeWrite.writeWithBackup(target, rendered);
        outWriter.println("spec updated: " + target + "  (+" + notebook.proposals().size()
                + " touchpoints, +" + notebook.findings().size() + " evidence)");
        if (backup != null) {
            outWriter.println("previous version backed up: " + backup);
        }
        for (String problem : SpecValidator.problems(enriched)) {
            outWriter.println("  gate: " + problem);
        }
        outWriter.println("review the proposed touchpoints and evidence, then run: sdd plan " + target);
        return result == AgentResult.DONE ? 0 : 2;
    }

    /**
     * Adds what the explorer found, and nothing else.
     *
     * <p>Existing touchpoints and evidence are kept verbatim and duplicates are dropped, so running
     * {@code sdd explore} twice does not multiply the spec. Everything the human wrote survives:
     * this is a proposal appended to their document, not a rewrite of it.
     */
    static NormalizedSpec merge(NormalizedSpec spec, Notebook notebook, String ending,
                                boolean complete) {
        List<Touchpoint> touchpoints = new ArrayList<>(spec.touchpoints());
        for (Notebook.Proposal proposal : notebook.proposals()) {
            Touchpoint.Kind kind = Touchpoint.Kind.fromKey(proposal.kind());
            if (kind == null) {
                continue;   // a KB kind with no touchpoint spelling (SYMBOL) — it travels as evidence
            }
            Touchpoint candidate = new Touchpoint(kind, proposal.value());
            if (!touchpoints.contains(candidate)) {
                touchpoints.add(candidate);
            }
        }
        List<String> evidence = new ArrayList<>(spec.evidence());
        for (Notebook.Finding finding : notebook.findings()) {
            String bullet = finding.claim() + " — " + finding.citation();
            if (!evidence.contains(bullet)) {
                evidence.add(bullet);
            }
        }
        List<SpecItem> questions = new ArrayList<>(spec.openQuestions());
        // Answers a human gave during the survey, so nobody is asked the same thing twice at
        // Gate 1. The resolution goes INSIDE the Q text on purpose: SpecParser requires every Open
        // Questions line to match "- (Q[1-9][0-9]*): (.+)", so plan.md's indented
        // "  - resolution:" idiom would throw here — and widening the spec grammar to accept it
        // would move every existing spec's rendering, and with it plan.md's hash, for no reason.
        for (Notebook.Clarification clarification : notebook.clarifications()) {
            String bullet = clarification.question() + " — resolved: " + clarification.answer();
            boolean already = questions.stream().anyMatch(q -> q.text().equals(bullet));
            if (!already) {
                questions.add(new SpecItem("Q" + (questions.size() + 1), bullet));
            }
        }
        if (!complete) {
            // A partial survey must say so IN the spec. Findings from a run that hit its turn
            // budget — or whose endpoint died — look exactly like findings from a complete one,
            // and the reviewer is the only one who can decide whether the gap matters.
            questions.add(new SpecItem("Q" + (questions.size() + 1),
                    "Exploration ended early (" + ending + ") — the estate survey may be incomplete."));
        }
        return new NormalizedSpec(spec.id(), spec.title(), spec.owner(), spec.status(), spec.goal(),
                spec.background(), spec.requirements(), spec.acceptance(), spec.constraints(),
                touchpoints, evidence, spec.outOfScope(), questions, spec.attachments(),
                spec.sources());
    }
}
