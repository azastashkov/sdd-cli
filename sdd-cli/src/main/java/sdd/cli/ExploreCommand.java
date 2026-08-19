package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.agent.loop.AgentBudget;
import sdd.agent.loop.AgentResult;
import sdd.agent.run.Explorer;
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
                        InstantSource.system(), settings.singleTool());
                return report(exploration, parsed, outWriter);
            }
        } catch (java.io.IOException e) {
            errWriter.println("error: cannot read " + specPath + ": " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }

    private Integer report(Explorer.Exploration exploration, NormalizedSpec parsed,
                           PrintWriter outWriter) {
        Notebook notebook = exploration.notebook();
        AgentResult result = exploration.outcome().result();
        outWriter.println("explored: " + result + " after " + exploration.outcome().turns()
                + " turns, " + exploration.outcome().tokens() + " tokens");
        outWriter.println(exploration.outcome().summary());
        if (notebook.isEmpty()) {
            // Not an error: an honest "nothing found" is a real answer, and rewriting the spec to
            // say so would be worse than leaving it alone.
            outWriter.println("nothing recorded — the spec is unchanged");
            return result == AgentResult.DONE ? 0 : 2;
        }
        for (Notebook.Finding finding : notebook.findings()) {
            outWriter.println("  " + finding.citation() + " reads: " + finding.citedLine());
        }
        NormalizedSpec enriched = merge(parsed, notebook, result);
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
    static NormalizedSpec merge(NormalizedSpec spec, Notebook notebook, AgentResult result) {
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
        if (result != AgentResult.DONE) {
            // A partial survey must say so IN the spec. Findings from a run that hit its turn
            // budget look exactly like findings from a complete one, and the reviewer is the only
            // one who can decide whether the gap matters.
            questions.add(new SpecItem("Q" + (questions.size() + 1),
                    "Exploration ended early (" + result + ") — the estate survey may be incomplete."));
        }
        return new NormalizedSpec(spec.id(), spec.title(), spec.owner(), spec.status(), spec.goal(),
                spec.background(), spec.requirements(), spec.acceptance(), spec.constraints(),
                touchpoints, evidence, spec.outOfScope(), questions, spec.attachments(),
                spec.sources());
    }
}
