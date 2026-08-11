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
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--no-cards", description = "Skip model-generated repo card summaries")
    boolean noCards;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        SddConfig config;
        try {
            config = ConfigLoader.load(workspace);
        } catch (RuntimeException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
        try (Database db = Database.open(workspace)) {
            IndexService service;
            if (noCards) {
                service = new IndexService();
            } else {
                ModelEndpoint coder = config.models().get("coder");
                service = new IndexService(null, new HttpChatModel(coder), coder.model());
            }
            List<IndexService.RepoResult> results = service.run(config, db);
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
                out.println("cards: skipped");
            } else {
                out.printf(Locale.ROOT, "cards: %d generated, %d cached, %d failed%n",
                        cardResult.generated(), cardResult.cached(), cardResult.failed());
            }
            boolean allFailed = !results.isEmpty()
                    && results.stream().allMatch(r -> r.status().equals("FAILED"));
            return allFailed ? 1 : 0;
        } catch (RuntimeException e) {
            spec.commandLine().getErr().println("error: " + e.getMessage());
            return 1;
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
