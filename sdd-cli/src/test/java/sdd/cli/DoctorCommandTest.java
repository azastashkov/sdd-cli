package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class DoctorCommandTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: %s/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: %s/v1
                    model: qwen
                """.formatted(wm.baseUrl(), wm.baseUrl());
    }

    private record Run(int exitCode, String out) {}

    private Run doctor(Path workspace) {
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));
        int code = cmd.execute("doctor", "--workspace", workspace.toString());
        return new Run(code, sw.toString());
    }

    @Test
    void allChecksPassExitsZero() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] java")
                .contains("[ OK ] config")
                .contains("[ OK ] database")
                .contains("[ OK ] model:planner")
                .contains("[ OK ] model:coder");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void unreachableModelEndpointFails() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(serverError()));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[FAIL] model:planner");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void missingConfigFailsButStillReportsJava() {
        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] java").contains("[FAIL] config");
        assertThat(run.exitCode()).isEqualTo(1);
    }
}
