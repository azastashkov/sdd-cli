package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GraphCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run graph(String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(new GraphCommand());
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private void seedKb() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-a','/w/2','SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            });
        }
    }

    @Test
    void printsMermaidToStdoutByDefault() {
        seedKb();

        Run run = graph("--workspace", ws.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("graph LR")
                .contains("svc_a[\"svc-a\"]:::service")
                .contains("svc_a -->|PINNED| lib_core");
    }

    @Test
    void outOptionWritesTheFileInsteadOfPrintingTheGraph() throws Exception {
        seedKb();
        Path target = ws.resolve("estate.mmd");

        Run run = graph("--workspace", ws.toString(), "--out", target.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("graph written: " + target)
                .doesNotContain("classDef");
        assertThat(Files.readString(target)).startsWith("graph LR\n").contains("-->|PINNED|");
    }

    @Test
    void missingKnowledgeBaseFailsWithoutCreatingIt() {
        Run run = graph("--workspace", ws.toString());

        assertThat(run.out()).contains("error: knowledge base is empty — run sdd index first");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isFalse();
    }

    @Test
    void graphIsRegisteredOnTheRootCommand() {
        seedKb();
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));

        int code = cmd.execute("graph", "--workspace", ws.toString());

        assertThat(sw.toString()).contains("graph LR");
        assertThat(code).isZero();
    }
}
