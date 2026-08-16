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

    // --- atlassian: block probes -----------------------------------------------------------

    @Test
    void absentAtlassianBlockChangesDoctorsOutputNotAtAll() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));

        Run run = doctor(ws);

        assertThat(run.out()).doesNotContain("atlassian");
    }

    @Test
    void reachableJiraSiteReportsOkWithTheUsername() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-test
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/2/myself").willReturn(okJson("{\"name\":\"jsmith\"}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] atlassian:jira").contains("HTTP 200 as jsmith");
        // Independently optional: jira configured alone must not produce confluence/bitbucket lines.
        assertThat(run.out()).doesNotContain("atlassian:confluence").doesNotContain("atlassian:bitbucket");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void unreachableJiraSiteFailsAndFailsTheOverallExitCode() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-test
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/2/myself").willReturn(unauthorized()));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[FAIL] atlassian:jira")
                .contains("Jira rejected the configured token (HTTP 401) — reissue it");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void reachableConfluenceSiteReportsOk() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  confluence:
                    base_url: %s
                    token: sk-confluence-test
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/user/current").willReturn(okJson("{\"username\":\"jsmith\"}")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] atlassian:confluence").contains("HTTP 200 as jsmith");
    }

    // Fix 2 (review): Bitbucket DC has no /users/self resource, so doctor probes only
    // /rest/api/1.0/projects/{project} and reads the username from that response's
    // X-AUSERNAME header — a single call, not two.
    @Test
    void bitbucketProbesTheProjectEndpointAndReadsTheUsernameFromTheAusernameHeader() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  bitbucket:
                    base_url: %s
                    token: sk-bb-test
                    project: TRADING
                """.formatted(wm.baseUrl()));
        wm.stubFor(get("/v1/models").willReturn(okJson("{\"data\":[]}")));
        wm.stubFor(get("/rest/api/1.0/projects/TRADING").willReturn(okJson("{\"key\":\"TRADING\"}")
                .withHeader("X-AUSERNAME", "jsmith")));

        Run run = doctor(ws);

        assertThat(run.out()).contains("[ OK ] atlassian:bitbucket").contains("HTTP 200 as jsmith");
        wm.verify(0, getRequestedFor(urlEqualTo("/rest/api/1.0/users/self")));
    }
}
