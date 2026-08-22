package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.config.AtlassianConfig;
import sdd.core.config.AtlassianSite;
import sdd.core.config.ConfigException;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.diagnostics.AtlassianWireDump;
import sdd.core.diagnostics.DiagnosticWriter;
import sdd.core.diagnostics.Diagnostics;
import sdd.core.http.HttpClients;
import sdd.core.http.RestClient;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.core.progress.Progress;
import sdd.core.retrieve.FtsRetriever;
import sdd.plan.confluence.ImageDescriber;
import sdd.plan.confluence.ConfluenceClient;
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
import sdd.plan.jira.JiraClient;
import sdd.plan.jira.JiraSpecSource;
import sdd.plan.source.SourceBudget;
import sdd.plan.source.SourceBullet;
import sdd.plan.source.SourceBundle;
import sdd.plan.source.SourceDoc;
import sdd.plan.spec.MarkdownSpecSource;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.SpecParseException;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecRefKind;
import sdd.plan.spec.SpecRenderer;
import sdd.plan.spec.SpecSources;
import sdd.plan.spec.SpecValidator;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

    /**
     * A CLI flag rather than a spec section, deliberately. A spec is a durable, reviewed artifact
     * that {@code sdd plan approve} SHA-hashes; a git ref is a mutable machine-local coordinate, and
     * {@code since: HEAD~5} written into a spec would mean something different every day, silently.
     * The RESOLVED sha travels into the plan through the seed's provenance, which is what keeps the
     * plan self-describing without making the spec time-dependent.
     */
    @Option(names = "--fetch-only",
            description = "Fetch the source documents, print their provenance and sizes, and stop. "
                    + "No model is called. Use it to test Jira/Confluence access on its own.")
    boolean fetchOnly;

    @Option(names = "--since", description = "Seed impact analysis from what changed in git: "
            + "<ref>, <a>..<b>, or <repo>=<ref> to scope it (repeatable)")
    List<String> since = new ArrayList<>();

    @Parameters(arity = "0..*",
            description = "Spec refs: canonical .md, or exported Confluence .html/.htm/.xhtml "
                    + "(0 or more; a canonical .md ref cannot be combined with anything else)")
    List<String> refs = new ArrayList<>();

    @Spec CommandSpec spec;

    ChatModel plannerForTest;   // test seam — mirrors IndexService's injectable ChatModel

    /** Test seam for {@code SDD_ATLASSIAN_DUMP}, same idiom as {@link #plannerForTest}: a test
     *  must be able to enable the dump without setting a variable on the whole JVM. */
    Map<String, String> envForTest;

    /**
     * Resolves {@code --since} into per-repo revision ranges. A bare ref applies to every indexed
     * repo that can resolve it; {@code <repo>=<ref>} scopes it to one.
     *
     * <p>A repo that cannot resolve the ref is a WARNING, never a problem: an operator typo or a
     * repo that simply predates the tag must not become a blocking question about the estate, which
     * is a different kind of claim entirely.
     */
    static List<sdd.plan.impact.ChangeSet.RepoChange> changeSet(org.jdbi.v3.core.Jdbi jdbi,
                                                                List<String> since,
                                                                PrintWriter errWriter) {
        if (since.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, String> ranges = new java.util.LinkedHashMap<>();
        for (String entry : since) {
            int eq = entry.indexOf('=');
            if (eq > 0) {
                ranges.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
            } else {
                for (String repo : sdd.core.kb.KbEntities.repoNames(jdbi)) {
                    ranges.putIfAbsent(repo, entry.trim());
                }
            }
        }
        List<sdd.plan.impact.ChangeSet.RepoChange> changes =
                sdd.plan.impact.ChangeSet.compute(jdbi, ranges);
        for (sdd.plan.impact.ChangeSet.RepoChange change : changes) {
            if (!"RESOLVED".equals(change.resolution())) {
                errWriter.println("warn: --since " + change.repo() + " " + change.range()
                        + ": " + change.resolution().toLowerCase().replace('_', ' '));
            } else if (emptyRange(change)) {
                // A range that resolves to one commit is the commonest --since mistake and the
                // quietest: a BARE ref means "<ref>..HEAD", so passing the ref you are checked out
                // at yields a window containing nothing. It used to be reported as a successful
                // "0 files", which reads like an answer.
                errWriter.println("warn: --since " + change.repo() + " " + change.range()
                        + ": resolves to a single commit (" + change.fromSha() + "), so the window "
                        + "is EMPTY — a bare ref means <ref>..HEAD, and you are at that ref. Give "
                        + "both ends, e.g. --since " + change.repo() + "=<last-good>..<broken>");
            }
        }
        // Empty windows are dropped, not carried: they contribute no seeds to a plan, and in
        // `sdd explore` they would become git_history's default revision — a..a, which answers
        // nothing. The warning above says so; silently passing one on would not.
        return changes.stream()
                .filter(c -> "RESOLVED".equals(c.resolution()))
                .filter(c -> !emptyRange(c))
                .toList();
    }

    /** A resolved range whose two ends are the same commit: nothing can have changed in it. */
    private static boolean emptyRange(sdd.plan.impact.ChangeSet.RepoChange change) {
        return change.fromSha() != null && change.fromSha().equals(change.toSha());
    }

    /** Test seam — mirrors {@code IndexCommand.progressForTest}/{@code ReviewCommand.progressForTest}:
     *  {@code null} in real use, where {@link #call} falls back to {@link SddCli#resolve}. */
    Progress progressForTest;

    private static final int SEED_MAX_ATTEMPTS = 2;   // assistive calls fail fast — analysis degrades, reruns are cheap

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        // Resolved before anything else, stopped in the finally below on every return path — same
        // reasoning as IndexCommand/ReviewCommand (design doc, "Arming": "Pair it with try/finally
        // in the command"). Only the validate() path (a canonical spec ref) ever paints anything;
        // the normalize()/normalizeWithAtlassian() paths never call a Progress method (see their
        // javadoc for why), so resolving here regardless just guarantees the live renderer's
        // ticker thread — if one was armed — is always stopped rather than leaked on those paths.
        Progress progress = progressForTest != null ? progressForTest : SddCli.resolve(spec);
        try {
            if (refs.isEmpty() && texts.isEmpty()) {
                errWriter.println("error: missing required parameter: <ref>");
                return 1;
            }
            List<SpecRefKind> kinds = refs.stream().map(SpecSources::classify).toList();
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
            boolean hasAtlassianRefs = kinds.stream().anyMatch(k -> k == SpecRefKind.JIRA || k == SpecRefKind.CONFLUENCE_PAGE);
            // Rejected rather than half-supported. --fetch-only exists to isolate Jira/Confluence
            // ACCESS from everything downstream of it; on a canonical spec there is nothing to
            // fetch, and on an export file the fetching already happened when someone saved it.
            // Accepting the flag and quietly doing something else is how a diagnostic stops being
            // one.
            if (fetchOnly && !hasAtlassianRefs) {
                errWriter.println("error: --fetch-only applies to Jira and Confluence refs; "
                        + "this invocation has none to fetch");
                return 1;
            }
            try {
                if (markdownRefs == 1) {
                    return validate(config, refs.get(0), outWriter, errWriter, progress);
                }
                return hasAtlassianRefs ? normalizeWithAtlassian(config, kinds, outWriter) : normalize(config, outWriter);
            } catch (RuntimeException e) {
                // Same rule as IndexCommand/ImplementCommand/ReviewCommand: stop() (idempotent)
                // erases the live line before this prints, so "error: ..." doesn't land at column
                // 80 of the last frame — reachable here since validate() is the path that paints.
                progress.stop();
                errWriter.println("error: " + e.getMessage());
                return 1;
            }
        } finally {
            progress.stop();
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

    /**
     * The Task 3 path: any mix of JIRA/CONFLUENCE_PAGE refs with {@code --text} and
     * CONFLUENCE_EXPORT refs, assembled into one bundle. This mirrors {@link #normalize}'s
     * export/text bundle-building — {@code ConfluenceExportSource.loadDoc} per export ref, one
     * FREE_TEXT doc per {@code --text} — but routes Jira/Confluence-page material through
     * {@link JiraSpecSource} instead, so link-following and Sources provenance happen exactly
     * once, in the one place that already knows how. The human gate is unchanged: this always
     * ends at {@link #writeNormalized}, never at impact analysis.
     */
    private Integer normalizeWithAtlassian(SddConfig config, List<SpecRefKind> kinds, PrintWriter outWriter) {
        if (out != null && SpecSources.isConfluenceExport(out.toString())) {
            throw new IllegalArgumentException("--out target must be a markdown file (got " + out + ")");
        }
        List<String> jiraKeys = new ArrayList<>();
        List<String> confluencePageRefs = new ArrayList<>();
        List<String> exportRefs = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            switch (kinds.get(i)) {
                case JIRA -> jiraKeys.add(refs.get(i));
                case CONFLUENCE_PAGE -> confluencePageRefs.add(refs.get(i));
                case CONFLUENCE_EXPORT -> exportRefs.add(refs.get(i));
                case MARKDOWN -> { /* unreachable: markdownRefs == 0 whenever this method runs */ }
            }
        }

        AtlassianConfig atlassian = config.atlassian();
        if (!jiraKeys.isEmpty() && (atlassian == null || atlassian.jira() == null)) {
            throw new ConfigException("no atlassian.jira configured in sdd.yml");
        }
        if (!confluencePageRefs.isEmpty() && (atlassian == null || atlassian.confluence() == null)) {
            throw new ConfigException("no atlassian.confluence configured in sdd.yml");
        }

        // Task 8: one diagnostics file for this invocation, opened only once the config checks
        // above have already passed — an unconfigured site is a config error (reported the same
        // way it always was), not something worth a diagnostics file of its own.
        DiagnosticWriter diagnostics = Diagnostics.open(workspace, "plan", commandLine(), atlassian,
                InstantSource.system(), spec.commandLine().getErr());
        try {
            return normalizeWithAtlassian(config, atlassian, jiraKeys, confluencePageRefs, exportRefs,
                    outWriter, diagnostics);
        } finally {
            diagnostics.close();
        }
    }

    private Integer normalizeWithAtlassian(SddConfig config, AtlassianConfig atlassian, List<String> jiraKeys,
            List<String> confluencePageRefs, List<String> exportRefs, PrintWriter outWriter,
            DiagnosticWriter diagnostics) {
        ModelEndpoint planner = config.models().get("planner");
        ChatModel model = plannerForTest != null ? plannerForTest : new HttpChatModel(planner);
        HttpClient httpClient = HttpClients.build(atlassian.tls(), atlassian.proxy());
        RestClient.TransportContext transport = RestClient.TransportContext.of(atlassian.tls(), atlassian.proxy());
        // Built from EVERY configured site's token, not just the one client being constructed:
        // a Jira comment quoting a Confluence PAT must be redacted out of the Jira dump too.
        AtlassianWireDump wireDump = AtlassianWireDump.fromEnv(envForTest != null ? envForTest : System.getenv(), workspace,
                AtlassianWireDump.secrets(
                        atlassian.jira() == null ? null : atlassian.jira().token(),
                        atlassian.confluence() == null ? null : atlassian.confluence().token(),
                        atlassian.bitbucket() == null ? null : atlassian.bitbucket().site().token()));

        JiraClient jiraClient = null;
        if (!jiraKeys.isEmpty()) {
            jiraClient = new JiraClient(
                    atlassianRestClient("Jira", atlassian.jira(), httpClient, diagnostics, transport)
                            .wireDump(wireDump),
                    atlassian.jira().baseUrl());
        }
        ConfluenceClient confluenceClient = null;
        String confluenceHost = null;
        // Confluence is built whenever there is a direct Confluence-page ref to fetch, OR when
        // there is Jira material that might link to it — atlassian.confluence is independently
        // optional, so a Jira-only estate with no Confluence site simply gets no link-following
        // (JiraSpecSource treats a null ConfluencePages as "nothing to follow", not an error).
        if (atlassian.confluence() != null && (!confluencePageRefs.isEmpty() || !jiraKeys.isEmpty())) {
            AtlassianSite site = atlassian.confluence();
            // atlassianRestClient is evaluated first (Java argument order) and raises the
            // deferred-credential message if the token is unset, so site.token() below is only
            // ever reached once that has already succeeded — no second check needed.
            confluenceClient = new ConfluenceClient(
                    atlassianRestClient("Confluence", site, httpClient, diagnostics, transport)
                            .wireDump(wireDump),
                    httpClient, site.token(), site.baseUrl(), site.timeout(), diagnostics)
                    // The tiny-link probe bypasses RestClient entirely, so it needs the dump
                    // attached separately or the one exchange a proxy most often mangles would be
                    // the one exchange the dump cannot see.
                    .wireDump(wireDump);
            confluenceHost = URI.create(site.baseUrl()).getHost();
        }

        JiraSpecSource jiraSpecSource = new JiraSpecSource(jiraClient, confluenceClient, confluenceHost,
                atlassian.followDepth(), atlassian.maxPages(), atlassian.maxLinkedIssues(),
                model, planner.model(), planner.maxTokens());

        List<SourceDoc> docs = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!jiraKeys.isEmpty()) {
            JiraSpecSource.Fetched fetched = jiraSpecSource.fetch(jiraKeys);
            docs.addAll(fetched.docs());
            notes.addAll(fetched.notes());
        }
        // Two separate anchors, not one: a CONFLUENCE_EXPORT ref is a real filesystem path (its
        // "<ref>.spec.md" sibling-file naming is the pre-existing behaviour, unchanged below), but
        // a CONFLUENCE_PAGE ref is a URL — treating a URL as a filesystem path would try to create
        // a "https:/host/..." directory tree. A page-ref-only run (no Jira, no export ref, no
        // --text) instead derives its id/filename from the fetched page's own id, workspace-
        // relative, the same shape the Jira case already uses (Fix 1, Task 3 review: this path
        // previously had no anchor at all and crashed on texts.get(0) with an empty --text list).
        String exportAnchorRef = null;
        String exportAnchorId = null;
        String pageAnchorId = null;
        for (String ref : confluencePageRefs) {
            String pageId = confluenceClient.resolvePageId(ref);
            if (pageId == null) {
                throw new IllegalArgumentException("cannot resolve Confluence URL: " + ref);
            }
            SourceDoc doc = confluenceClient.fetchPage(pageId);
            // Not under --fetch-only. That flag's whole contract is "exercises Jira and Confluence
            // with no model call and nothing written" (docs/commands.md), and it exists to make a
            // first live run on a closed network readable by separating the network from the
            // model. Describing images here would spend an upload and two model calls per image on
            // the one path that promises neither.
            if (!fetchOnly) {
                doc = describeImages(config, confluenceClient, doc, notes, outWriter);
            }
            docs.add(doc);
            if (pageAnchorId == null) {
                pageAnchorId = doc.id();
            }
        }
        for (String ref : exportRefs) {
            SourceDoc doc = ConfluenceExportSource.loadDoc(ref);
            docs.add(doc);
            if (exportAnchorRef == null) {
                exportAnchorRef = ref;
                exportAnchorId = doc.id();
            }
        }
        for (int i = 0; i < texts.size(); i++) {
            docs.add(new SourceDoc(SourceDoc.Kind.FREE_TEXT, "text-" + (i + 1), null, null, null,
                    texts.get(i), List.of()));
        }

        // The spec's id/filename derive from the Jira key when one is present (Section 5: "keep
        // it stable, since plan.json and the run directory are named from it") — ahead of any
        // Confluence-export anchor, Confluence-page anchor, or --text slug, since a Jira ref is
        // the primary requirement record whenever one is in the mix.
        String fallbackId = !jiraKeys.isEmpty() ? jiraKeys.get(0)
                : exportAnchorId != null ? exportAnchorId
                : pageAnchorId != null ? pageAnchorId
                : "spec-" + slugify(texts.get(0));
        Path target = out != null ? out
                : !jiraKeys.isEmpty() ? workspace.resolve(jiraKeys.get(0) + ".spec.md")
                : exportAnchorRef != null ? Path.of(exportAnchorRef + ".spec.md")
                : pageAnchorId != null ? workspace.resolve(pageAnchorId + ".spec.md")
                : workspace.resolve(slugify(texts.get(0)) + ".spec.md");

        if (fetchOnly) {
            return printFetched(docs, notes, outWriter);
        }
        NormalizedSpec normalized = jiraSpecSource.assemble(docs, notes, fallbackId);
        return writeNormalized(normalized, target, outWriter);
    }

    /**
     * Expands this page's image markers into model-written descriptions, when configured.
     *
     * <p>A no-op unless {@code atlassian.describe_images} names a model — absent is off, and every
     * byte of output is then what it was before this feature existed. Live only for a fetched PAGE:
     * an exported HTML file keeps only an image's filename, with no page and no attachment id to
     * fetch bytes against.
     *
     * <p>The database is opened here rather than at the top of the command, because a Confluence
     * normalization has never needed an index and must not start needing one. A run that describes
     * nothing never touches it; a run that does is already doing far more expensive things.
     *
     * <p>Every failure is a note. The page text is already fetched and worth having, and an
     * optional enrichment must not be able to cost it.
     */
    private SourceDoc describeImages(SddConfig config, ConfluenceClient client, SourceDoc doc,
            List<String> notes, PrintWriter outWriter) {
        String modelKey = config.atlassian() == null ? null : config.atlassian().describeImages();
        if (modelKey == null || doc.attachments().isEmpty()) {
            return doc;
        }
        ModelEndpoint vision = config.models().get(modelKey);
        HttpChatModel model = new HttpChatModel(vision);
        try (Database db = Database.open(config.workspace())) {
            ImageDescriber.Result result = new ImageDescriber(client, model, model, vision.model(),
                    vision.maxTokens(), db.jdbi()).describe(doc);
            notes.addAll(result.notes());
            outWriter.println("images: " + result.described() + " described, " + result.cached()
                    + " cached, " + result.skipped() + " skipped, " + result.failed() + " failed");
            return result.doc();
        } catch (RuntimeException e) {
            notes.add("[image] describing images on page " + doc.id() + " failed: " + e.getMessage());
            return doc;
        }
    }

    /**
     * Prints exactly what was fetched, and stops before the model.
     *
     * <p>Without this seam the first command that touches Jira runs fetch, linked-issue traversal,
     * link harvesting, budgeting, one model call, spec rendering and a re-parse self-check behind a
     * single exit code — six subsystems whose failures are indistinguishable from outside. It is
     * also the only way to exercise Atlassian access at all when the model gateway is unreachable,
     * which on a corporate network is a routine condition rather than an outage.
     *
     * <p>The budget is applied first, so what prints is what a normalization would actually have
     * seen — including which documents were dropped to fit, which is otherwise visible only as an
     * absence in the finished spec.
     */
    private Integer printFetched(List<SourceDoc> docs, List<String> notes, PrintWriter outWriter) {
        SourceBundle capped = SourceBudget.apply(new SourceBundle(docs, notes));
        outWriter.println("# Sources");
        for (SourceDoc doc : capped.docs()) {
            outWriter.println("- " + SourceBullet.render(doc));
        }
        outWriter.println();
        outWriter.println("# Sizes");
        int total = 0;
        for (SourceDoc doc : capped.docs()) {
            int chars = doc.text() == null ? 0 : doc.text().length();
            total += chars;
            outWriter.println("- " + doc.kind() + " " + doc.id() + ": " + chars + " chars"
                    + (doc.attachments().isEmpty() ? ""
                            : "  attachments: " + String.join(", ", doc.attachments())));
        }
        outWriter.println("- TOTAL: " + total + " chars across " + capped.docs().size()
                + " document(s)");
        outWriter.println();
        outWriter.println("# Notes");
        if (capped.notes().isEmpty()) {
            outWriter.println("- none");
        } else {
            capped.notes().forEach(n -> outWriter.println("- " + n));
        }
        outWriter.println();
        outWriter.println("fetched only — no model was called, nothing was written");
        return 0;
    }

    /**
     * The spec's already-answered questions, in the channel {@code sdd plan revise} already uses.
     *
     * <p>{@code sdd explore --interactive} writes a human's answer into the spec's Open Questions
     * as {@code <question> — resolved: <answer>}. Without this the drafter would see that text only
     * incidentally, as part of the rendered spec, and could well re-raise the same question as
     * blocking — making somebody answer at Gate 1 what they already answered during the survey,
     * which is the specific waste this feature exists to remove.
     *
     * <p>Reuses {@code PlanDrafter}'s existing {@code # Prior questions and human resolutions}
     * heading rather than inventing a second format, so the explore loop and the revise loop feed
     * the model through one channel.
     */
    private static String answeredQuestions(NormalizedSpec spec) {
        StringBuilder out = new StringBuilder();
        for (SpecItem question : spec.openQuestions()) {
            int marker = question.text().indexOf(" — resolved: ");
            if (marker < 0) {
                continue;
            }
            out.append("- ").append(question.id()).append(": ")
                    .append(question.text(), 0, marker).append('\n');
            out.append("  resolved: ")
                    .append(question.text().substring(marker + " — resolved: ".length()))
                    .append('\n');
        }
        return out.toString();
    }

    /** {@code ["plan", ...the exact tokens this invocation was called with]} — mirrors {@code
     *  DoctorCommand}'s identically-shaped helper; see that class for why {@code originalArgs()}
     *  rather than reconstructing the argv from individual option fields. */
    private List<String> commandLine() {
        List<String> argv = new ArrayList<>();
        argv.add(spec.name());
        argv.addAll(spec.commandLine().getParseResult().originalArgs());
        return argv;
    }

    /** An unset {@code ${VAR}} token does not fail config loading (Task 2's deferred-credential
     *  idiom) — it is raised here instead, the point a site's {@link RestClient} is actually
     *  about to be built, exactly like {@code AtlassianProbe} does for {@code sdd doctor}. */
    private static RestClient atlassianRestClient(String siteName, AtlassianSite site, HttpClient httpClient,
            DiagnosticWriter diagnostics, RestClient.TransportContext transport) {
        if (site.tokenError() != null) {
            throw new ConfigException(site.tokenError());
        }
        return new RestClient(siteName, site.baseUrl(), site.token(), site.tokenVar(), site.timeout(), httpClient,
                diagnostics, transport);
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

    private Integer validate(SddConfig config, String ref, PrintWriter outWriter, PrintWriter errWriter,
            Progress progress) {
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

            progress.phase("impact analysis");
            List<sdd.plan.impact.ChangeSet.RepoChange> changes =
                    changeSet(db.jdbi(), since, errWriter);
            ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                    parsed, model, planner.model(), planner.maxTokens(), changes);
            // printImpact is a report block, but — unlike IndexCommand/ReviewCommand — it prints
            // midway through this method, with execution-order/open-questions/drafting still to
            // come: stop() would END the session, leaving those later phases nothing to paint
            // into. suspend() is exactly this seam's tool for "a caller prints through its own
            // writer without a painted frame clobbering it" (Progress.suspend javadoc) — it
            // erases, runs the print, and repaints, so the live line survives to report the
            // remaining phases.
            progress.suspend(() -> printImpact(outWriter, result));

            progress.phase("execution order");
            List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);
            progress.phase("open questions");
            List<Question> questions = OpenQuestions.detect(db.jdbi(), result);
            progress.phase("draft plan");
            PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), parsed, result, order,
                    answeredQuestions(parsed), model, planner.model(), planner.maxTokens());
            // Stopped here, not left to call()'s finally: same "erase before the report starts"
            // reasoning as IndexCommand/ReviewCommand — the "plan written" block below must not
            // collide with a live, un-erased frame. stop() is idempotent, so call()'s later
            // stop() in the outer finally is a harmless no-op.
            progress.stop();
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
