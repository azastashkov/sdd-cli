package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IndexCommandTest {
    @TempDir Path ws;

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """;
    }

    private record Run(int exitCode, String out) {}

    private Run index(Path workspace, String... extraArgs) {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        List<String> args = new ArrayList<>(List.of("index", "--workspace", workspace.toString()));
        args.addAll(List.of(extraArgs));
        int code = cmd.execute(args.toArray(new String[0]));
        return new Run(code, sw.toString());
    }

    @Test
    void missingConfigPrintsCleanErrorAndExitsOne() {
        Run run = index(ws); // ws = empty @TempDir, no sdd.yml
        assertThat(run.out()).contains("error:").contains("sdd.yml").doesNotContain("at sdd.");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void noCardsFlagSkipsCardGeneration() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());

        Run run = index(ws, "--no-cards");

        assertThat(run.out()).contains("cards: skipped");
        assertThat(run.exitCode()).isEqualTo(0); // empty workspace: no repos, nothing to fail
    }

    @Test
    void cardsEnabledByDefaultPrintsCountsLineEvenWithNoRepos() throws Exception {
        // yaml()'s coder base_url (127.0.0.1:1) is unreachable — proves the default path
        // constructs an HttpChatModel without --no-cards, yet with zero scanned repos
        // RepoCardGenerator's repo loop is a no-op, so the model is never actually called.
        Files.writeString(ws.resolve("sdd.yml"), yaml());

        Run run = index(ws); // no --no-cards

        assertThat(run.out()).contains("cards: 0 generated, 0 cached, 0 failed");
        assertThat(run.exitCode()).isZero();
    }
}
