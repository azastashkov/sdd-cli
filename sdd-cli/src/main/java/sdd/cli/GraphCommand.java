package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sdd.core.db.Database;
import sdd.index.report.MermaidGraph;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "graph", description = "Render the knowledge base's estate graph as Mermaid")
public final class GraphCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--out", description = "Write the graph to a file instead of stdout")
    Path out;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        try {
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
                String mermaid = MermaidGraph.render(db.jdbi());
                if (out != null) {
                    try {
                        Files.writeString(out, mermaid);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    outWriter.println("graph written: " + out);
                } else {
                    outWriter.print(mermaid);
                    outWriter.flush();
                }
                return 0;
            }
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }
}
