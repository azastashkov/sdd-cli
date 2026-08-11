package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexCommandTest {
    @TempDir Path ws;

    @Test
    void missingConfigPrintsCleanErrorAndExitsOne() {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("index", "--workspace", ws.toString()); // ws = empty @TempDir
        assertThat(sw.toString()).contains("error:").contains("sdd.yml").doesNotContain("at sdd.");
        assertThat(code).isEqualTo(1);
    }
}
