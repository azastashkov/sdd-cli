package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.index.IndexService;
import sdd.index.store.ArtifactLinker;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "index", description = "Build or refresh the knowledge base for a workspace")
public final class IndexCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

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
            IndexService service = new IndexService();
            List<IndexService.RepoResult> results = service.run(config, db);
            for (IndexService.RepoResult r : results) {
                out.printf(Locale.ROOT, "%-28s %-9s modules=%-3d internal-deps=%-3d%s%s%n",
                        r.repo(), r.status(), r.modules(), r.internalDeps(),
                        r.skipped() ? " (unchanged, skipped)" : "",
                        r.error() == null ? "" : "  ! " + firstLine(r.error()));
            }
            ArtifactLinker.LinkReport link = service.lastLinkReport();
            out.printf(Locale.ROOT, "link: %d internal edges, %d conflicts, %d orphan artifacts%n",
                    link.internalEdges(), link.conflicts().size(), link.orphanArtifacts().size());
            link.conflicts().forEach(c -> out.println("  conflict: " + c));
            link.orphanArtifacts().forEach(o -> out.println("  orphan: " + o));
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
