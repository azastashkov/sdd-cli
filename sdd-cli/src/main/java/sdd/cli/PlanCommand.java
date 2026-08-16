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
import sdd.plan.confluence.ConfluenceNormalizer;
import sdd.plan.confluence.SpecNormalizationException;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.OpenQuestions;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.PlanMdRenderer;
import sdd.plan.gen.Question;
import sdd.plan.gen.SafeWrite;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactAnalysis;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.source.SourceBundle;
import sdd.plan.source.SourceDoc;
import sdd.plan.spec.MarkdownSpecSource;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParseException;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecRefKind;
import sdd.plan.spec.SpecRenderer;
import sdd.plan.spec.SpecSources;
import sdd.plan.spec.SpecValidator;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "plan",
        description = "Ingest one or more specs (canonical markdown, Confluence export, or free text) "
                + "and run impact analysis",
        subcommands = {ApproveCommand.class, ReviseCommand.class})
public final class PlanCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--out",
            description = "Where to write the normalized spec (Confluence/text refs only; default derived from ref/text)")
    Path out;

    @Option(names = "--text", description = "Free-text requirement (repeatable) — never inferred from a "
            + "positional, since a bare string is indistinguishable from a path")
    List<String> texts = new ArrayList<>();

    @Parameters(arity = "0..*",
            description = "Spec refs: canonical .md, or exported Confluence .html/.htm/.xhtml "
                    + "(0 or more; a canonical .md ref cannot be combined with anything else)")
    List<String> refs = new ArrayList<>();

    @Spec CommandSpec spec;

    ChatModel plannerForTest;   // test seam — mirrors IndexService's injectable ChatModel

    private static final int SEED_MAX_ATTEMPTS = 2;   // assistive calls fail fast — analysis degrades, reruns are cheap

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        if (refs.isEmpty() && texts.isEmpty()) {
            errWriter.println("error: missing required parameter: <ref>");
            return 1;
        }
        List<SpecRefKind> kinds = refs.stream().map(SpecSources::classify).toList();
        // Ruling R2: Jira/Confluence-page refs are rejected outright in this task — they must
        // never fall through to MarkdownSpecSource, which would report a confusing "file not
        // found" instead of the honest "not configured yet".
        if (kinds.stream().anyMatch(k -> k == SpecRefKind.JIRA || k == SpecRefKind.CONFLUENCE_PAGE)) {
            errWriter.println("error: Jira/Confluence ingestion is not configured");
            return 1;
        }
        long markdownRefs = kinds.stream().filter(k -> k == SpecRefKind.MARKDOWN).count();
        // A canonical spec is already normalized — combining it with any other ref or with
        // --text (including a second canonical ref) is meaningless, so both shapes reject here.
        if (markdownRefs > 1 || (markdownRefs == 1 && (refs.size() > 1 || !texts.isEmpty()))) {
            errWriter.println("error: a canonical spec ref cannot be combined with other sources");
            return 1;
        }
        SddConfig config;
        try {
            config = ConfigLoader.load(workspace);
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
        try {
            return markdownRefs == 1
                    ? validate(config, refs.get(0), outWriter, errWriter)
                    : normalize(config, outWriter);
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

        // Single Confluence export ref, nothing else: the pre-existing one-document path,
        // unchanged, so its behaviour (and the tests pinning it) stay identical.
        if (refs.size() == 1 && texts.isEmpty()) {
            String ref = refs.get(0);
            NormalizedSpec normalized =
                    new ConfluenceExportSource(model, planner.model(), planner.maxTokens()).load(ref);
            return writeNormalized(normalized, out != null ? out : Path.of(ref + ".spec.md"), outWriter);
        }

        // General path: any mix of Confluence-export refs and --text becomes one SourceBundle.
        // Each export ref goes through ConfluenceExportSource.loadDoc — the same read+extract
        // step the single-doc fast path above uses via ConfluenceExportSource.load — rather than
        // PlanCommand re-implementing file I/O and extraction a second time.
        List<SourceDoc> docs = new ArrayList<>();
        String anchorRef = null;
        String anchorId = null;
        for (String ref : refs) {
            SourceDoc doc = ConfluenceExportSource.loadDoc(ref);
            docs.add(doc);
            if (anchorRef == null) {
                anchorRef = ref;
                anchorId = doc.id();
            }
        }
        for (int i = 0; i < texts.size(); i++) {
            docs.add(new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-" + (i + 1), null, null, null,
                    texts.get(i), List.of()));
        }
        SourceBundle bundle = new SourceBundle(docs, List.of());
        // Output naming: derive from the first Confluence-export ref exactly like the
        // single-doc path above; only when there is NO file ref to derive from (pure --text)
        // does the id/filename come from slugifying the first --text instead.
        String fallbackId = anchorId != null ? anchorId : "spec-" + slugify(texts.get(0));
        // A file ref already carries its own directory (it is the path the operator passed in);
        // pure --text has no such anchor, so its default target is resolved against --workspace.
        Path target = out != null ? out
                : anchorRef != null ? Path.of(anchorRef + ".spec.md")
                : workspace.resolve(slugify(texts.get(0)) + ".spec.md");
        NormalizedSpec normalized =
                ConfluenceNormalizer.normalize(bundle, model, planner.model(), planner.maxTokens(), fallbackId);
        return writeNormalized(normalized, target, outWriter);
    }

    private Integer writeNormalized(NormalizedSpec normalized, Path target, PrintWriter outWriter) {
        String rendered = SpecRenderer.render(normalized);
        try {
            SpecParser.parse(rendered);   // self-check: never hand the human a gate file that cannot re-parse
        } catch (SpecParseException e) {
            throw new SpecNormalizationException(
                    "normalized spec failed self-check (" + e.getMessage() + ") — rerun normalization", e);
        }
        Path backup = SafeWrite.writeWithBackup(target, rendered);
        outWriter.println("normalized spec written: " + target);
        if (backup != null) {
            outWriter.println("previous version backed up: " + backup);
        }
        for (String problem : SpecValidator.problems(normalized)) {
            outWriter.println("  gate: " + problem);
        }
        outWriter.println("review and edit the spec, then run: sdd plan " + workspacePrefix() + target);
        return 0;
    }

    /** First ~6 words of free text, slugified with the same shape
     *  {@code ConfluenceExportSource.specId} uses for a filename (lowercase, non-alphanumerics
     *  collapsed to '-', leading/trailing '-' trimmed) — the one rule for "turn a human-facing
     *  name into a filename/id fragment" every source in this seam shares, so two ids for "the
     *  same" name never diverge by punctuation alone. That method operates on a filename, not on
     *  free text, so its exact code isn't reusable here — only its shape is. No clock, no random
     *  value: the same {@code --text} always produces the same slug. */
    private static String slugify(String text) {
        String[] words = text.strip().split("\\s+");
        String joined = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(6, words.length)));
        String slug = joined.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "spec" : slug;
    }

    private Integer validate(SddConfig config, String ref, PrintWriter outWriter, PrintWriter errWriter) {
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

            List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);
            List<Question> questions = OpenQuestions.detect(db.jdbi(), result);
            PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), parsed, result, order,
                    model, planner.model(), planner.maxTokens());
            String planMd = PlanMdRenderer.render(parsed, result, order, questions, draft);
            String base = ref.endsWith(".md") ? ref.substring(0, ref.length() - 3) : ref;
            Path planPath = Path.of(base + ".plan.md");
            Path backup = SafeWrite.writeWithBackup(planPath, planMd);
            outWriter.println("plan written: " + planPath);
            if (backup != null) {
                outWriter.println("previous version backed up: " + backup);
            }
            outWriter.println("review and edit the plan, then run: sdd plan approve");
        }
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
