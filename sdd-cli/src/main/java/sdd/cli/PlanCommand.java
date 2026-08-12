package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.core.retrieve.FtsRetriever;
import sdd.plan.confluence.ConfluenceExportSource;
import sdd.plan.confluence.SpecNormalizationException;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactAnalysis;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.spec.MarkdownSpecSource;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParseException;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecRenderer;
import sdd.plan.spec.SpecSources;
import sdd.plan.spec.SpecValidator;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "plan",
        description = "Ingest a spec (canonical markdown or Confluence export) and run impact analysis")
public final class PlanCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--out",
            description = "Where to write the normalized spec (Confluence refs only; default: <ref>.spec.md)")
    Path out;

    @Parameters(index = "0", description = "Spec ref: canonical .md, or exported Confluence .html/.htm/.xhtml")
    String ref;

    @Spec CommandSpec spec;

    ChatModel plannerForTest;   // test seam — mirrors IndexService's injectable ChatModel

    private static final int SEED_MAX_ATTEMPTS = 2;   // assistive calls fail fast — analysis degrades, reruns are cheap

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        SddConfig config;
        try {
            config = ConfigLoader.load(workspace);
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
        try {
            return SpecSources.isConfluenceExport(ref)
                    ? normalize(config, outWriter)
                    : validate(config, outWriter, errWriter);
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }

    private Integer normalize(SddConfig config, PrintWriter outWriter) {
        if (out != null && SpecSources.isConfluenceExport(out.toString())) {
            throw new IllegalArgumentException("--out target must be a markdown file (got " + out + ")");
        }
        ModelEndpoint planner = config.models().get("planner");
        ChatModel model = plannerForTest != null ? plannerForTest : new HttpChatModel(planner);
        NormalizedSpec normalized =
                new ConfluenceExportSource(model, planner.model(), planner.maxTokens()).load(ref);
        String rendered = SpecRenderer.render(normalized);
        try {
            SpecParser.parse(rendered);   // self-check: never hand the human a gate file that cannot re-parse
        } catch (SpecParseException e) {
            throw new SpecNormalizationException(
                    "normalized spec failed self-check (" + e.getMessage() + ") — rerun normalization", e);
        }
        Path target = out != null ? out : Path.of(ref + ".spec.md");
        try {
            Files.writeString(target, rendered);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        outWriter.println("normalized spec written: " + target);
        for (String problem : SpecValidator.problems(normalized)) {
            outWriter.println("  gate: " + problem);
        }
        outWriter.println("review and edit the spec, then run: sdd plan " + workspacePrefix() + target);
        return 0;
    }

    private Integer validate(SddConfig config, PrintWriter outWriter, PrintWriter errWriter) {
        NormalizedSpec parsed = new MarkdownSpecSource().load(ref);
        List<String> problems = SpecValidator.problems(parsed);
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                errWriter.println("problem: " + problem);
            }
            return 1;
        }
        outWriter.printf(Locale.ROOT,
                "spec OK: %s — %d requirements, %d acceptance, %d constraints, %d touchpoints, %d open questions%n",
                parsed.id(), parsed.requirements().size(), parsed.acceptance().size(),
                parsed.constraints().size(), parsed.touchpoints().size(), parsed.openQuestions().size());
        if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
            errWriter.println("error: knowledge base is empty — run sdd index first");
            return 1;
        }
        try (Database db = Database.open(workspace)) {
            Integer repoCount = db.jdbi().withHandle(h ->
                    h.createQuery("SELECT count(*) FROM repo").mapTo(Integer.class).one());
            if (repoCount == 0) {
                errWriter.println("error: knowledge base is empty — run sdd index first");
                return 1;
            }
            ModelEndpoint planner = config.models().get("planner");
            ChatModel model = plannerForTest != null ? plannerForTest : new HttpChatModel(planner, SEED_MAX_ATTEMPTS);
            ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                    parsed, model, planner.model(), planner.maxTokens());
            printImpact(outWriter, result);
        }
        outWriter.println("plan.md rendering is not implemented yet (Phase 3C)");
        return 0;
    }

    private void printImpact(PrintWriter outWriter, ImpactResult result) {
        long seeds = result.affected().stream().filter(a -> a.role().equals("seed")).count();
        long dependents = result.affected().stream().filter(a -> a.role().equals("dependent")).count();
        long contracts = result.affected().stream().filter(a -> a.role().equals("contract")).count();
        long bomSites = result.affected().stream().filter(a -> a.role().equals("bom-site")).count();
        outWriter.printf(Locale.ROOT, "impact: %d repos affected (%d seeds, %d dependents, %d contracts, %d bom-sites)%n",
                result.affected().size(), seeds, dependents, contracts, bomSites);
        for (AffectedRepo repo : result.affected()) {
            String reason = repo.reasons().isEmpty() ? "" : "  " + repo.reasons().get(0);
            outWriter.printf(Locale.ROOT, "  %-28s %-20s%s%n", repo.repo(), repo.annotation(), reason);
        }
        for (Seed excluded : result.excluded()) {
            outWriter.println("  excluded: " + excluded.repo() + " — " + excluded.detail());
        }
        for (String discrepancy : result.discrepancies()) {
            outWriter.println("  discrepancy: " + discrepancy);
        }
        for (String cycle : result.cycles()) {
            outWriter.println("  cycle: " + cycle);
        }
        for (String problem : result.problems()) {
            outWriter.println("  impact problem: " + problem);
        }
        for (String warning : result.warnings()) {
            outWriter.println("  warn: " + warning);
        }
    }

    private String workspacePrefix() {
        return workspace.equals(Path.of(".")) ? "" : "--workspace " + workspace + " ";
    }
}
