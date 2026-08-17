package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.HttpChatModel;
import sdd.core.progress.Progress;
import sdd.index.IndexService;
import sdd.index.cards.RepoCardGenerator;
import sdd.index.store.ArtifactLinker;
import sdd.index.store.RestMatcher;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "index", description = "Build or refresh the knowledge base for a workspace")
public final class IndexCommand implements Callable<Integer> {
    // Cards are cached background enrichment — fail fast, retry on the next index run.
    private static final int CARD_MAX_ATTEMPTS = 2;

    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--no-cards", description = "Skip model-generated repo card summaries")
    boolean noCards;

    @Option(names = "--force", description = "Re-index every repo even when its fingerprint is "
            + "unchanged, instead of skipping it as (unchanged, skipped). Composable with "
            + "--no-cards. Repo cards are still cached independently by content hash "
            + "(RepoCardGenerator): --force does not itself force card regeneration unless the "
            + "card input actually changed.")
    boolean force;

    @Spec CommandSpec spec;

    /** Test seam — mirrors {@code DoctorCommand.clockForTest}/{@code PlanCommand.plannerForTest}:
     *  {@code null} in real use, where {@link #call} falls back to {@link SddCli#resolve}. Lets
     *  a test observe exactly what this command hands {@code IndexService.run} without needing
     *  {@code SddCli.main} to have armed anything (design doc, "Arming": no test in this tree
     *  calls {@code main}). */
    Progress progressForTest;

    @Override
    public Integer call() {
        // Resolved before anything else, and stopped in the finally below regardless of which
        // return path is taken (design doc, "Renderers": "Pair it with try/finally in the
        // command") — a live renderer's ticker thread is started in its own constructor
        // (LiveProgress), so even the earliest error return (bad config) must still stop it
        // rather than leaking a scheduler that outlives this call.
        Progress progress = progressForTest != null ? progressForTest : SddCli.resolve(spec);
        try {
            PrintWriter out = spec.commandLine().getOut();
            SddConfig config;
            try {
                config = ConfigLoader.load(workspace);
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("error: " + e.getMessage());
                return 1;
            }
            // Preflight, before Database.open: on a workspace with no prior .sdd/index.db, open()
            // creates .sdd and the meta table as a side effect. ConfigLoader defers an unset api_key
            // ${VAR} rather than failing the whole config load, but --no-cards aside, this command
            // WILL construct an HttpChatModel against coder below — check it here instead of letting
            // that construction (deep inside the try) be the first thing to notice, after the db
            // already exists on disk.
            if (!noCards) {
                String apiKeyError = config.models().get("coder").apiKeyError();
                if (apiKeyError != null) {
                    spec.commandLine().getErr().println("error: " + apiKeyError);
                    return 1;
                }
            }
            try (Database db = Database.open(workspace)) {
                IndexService service;
                if (noCards) {
                    service = new IndexService();
                } else {
                    ModelEndpoint coder = config.models().get("coder");
                    service = new IndexService(null, new HttpChatModel(coder, CARD_MAX_ATTEMPTS), coder.model());
                }
                List<IndexService.RepoResult> results = service.run(config, db, force, progress);
                for (IndexService.RepoResult r : results) {
                    out.printf(Locale.ROOT, "%-28s %-9s parse=%-8s modules=%-3d internal-deps=%-3d%s%s%n",
                            r.repo(), r.status(), r.parseStatus() == null ? "-" : r.parseStatus(),
                            r.modules(), r.internalDeps(),
                            r.skipped() ? " (unchanged, skipped)" : "",
                            r.error() == null ? "" : "  ! " + firstLine(r.error()));
                }
                ArtifactLinker.LinkReport link = service.lastLinkReport();
                out.printf(Locale.ROOT, "link: %d internal edges, %d conflicts, %d orphan artifacts%n",
                        link.internalEdges(), link.conflicts().size(), link.orphanArtifacts().size());
                link.conflicts().forEach(c -> out.println("  conflict: " + c));
                link.orphanArtifacts().forEach(o -> out.println("  orphan: " + o));
                out.printf(Locale.ROOT, "usage: %d internal type refs%n", service.lastUsageReport().internalRefs());
                int[] springCounts = db.jdbi().withHandle(h -> new int[]{
                        h.createQuery("SELECT count(*) FROM rest_endpoint").mapTo(Integer.class).one(),
                        h.createQuery("SELECT count(*) FROM rest_client").mapTo(Integer.class).one(),
                        h.createQuery("SELECT count(*) FROM kafka_role").mapTo(Integer.class).one()});
                out.printf(Locale.ROOT, "spring: %d endpoints, %d clients, %d kafka roles%n",
                        springCounts[0], springCounts[1], springCounts[2]);
                RestMatcher.Report matchReport = service.lastRestReport();
                out.printf(Locale.ROOT, "match: %d high, %d medium, %d low, %d manual edges%n",
                        matchReport.high(), matchReport.medium(), matchReport.low(), matchReport.manual());
                matchReport.warnings().forEach(w -> out.println("  warn: " + w));
                RepoCardGenerator.CardResult cardResult = service.lastCardResult();
                if (cardResult == null) {
                    out.println(service.lastCardError() == null
                            ? "cards: skipped"
                            : "cards: failed (" + firstLine(service.lastCardError()) + ")");
                } else {
                    out.printf(Locale.ROOT, "cards: %d generated, %d cached, %d failed%n",
                            cardResult.generated(), cardResult.cached(), cardResult.failed());
                    cardResult.failures().forEach(f -> out.println("  card: " + f));
                }
                out.println("report: " + service.lastReportPath());
                boolean allFailed = !results.isEmpty()
                        && results.stream().allMatch(r -> r.status().equals("FAILED"));
                return allFailed ? 1 : 0;
            } catch (RuntimeException e) {
                spec.commandLine().getErr().println("error: " + e.getMessage());
                return 1;
            }
        } finally {
            progress.stop();
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
