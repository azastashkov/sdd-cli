package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 B6: "write diagnostics for Atlassian operations by default" covers {@code sdd plan}'s
 * Jira/Confluence ingestion (Gate 1) exactly as much as {@code sdd doctor}'s probes or {@code sdd
 * review}'s Bitbucket calls (Gate 2) — {@code RestClient} is the single choke point, so wiring one
 * more command through it is "does {@code sdd plan} open a diagnostics file and does the Jira token
 * stay out of it", not a re-implementation of anything {@link RestClientDiagnosticsTest} already
 * covers at the {@code RestClient} level.
 */
class PlanCommandDiagnosticsTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run plan(PlanCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                    max_tokens: 16384
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """;
    }

    @Test
    void aJiraIngestionRunWritesADiagnosticsFileAndTheTokenNeverReachesIt() throws Exception {
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-1"
                + "?expand=renderedFields&fields=summary,description,issuelinks,subtasks,comment,status,updated"))
                .willReturn(okJson("""
                        {"id": "1", "key": "PROJ-1",
                         "fields": {"summary": "Order API", "status": {"name": "Open"},
                                    "updated": "2026-08-16T09:12:00.000+0000", "subtasks": [],
                                    "issuelinks": [], "comment": {"comments": []}},
                         "renderedFields": {"description": "<p>Add pagination.</p>",
                                             "comment": {"comments": []}}}
                        """)));
        wm.stubFor(get(urlEqualTo("/rest/api/2/issue/PROJ-1/remotelink")).willReturn(okJson("[]")));
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-super-secret
                """.formatted(wm.baseUrl()));
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "Order API", "owner": "", "status": "", "goal": "Add pagination.",
                         "background": "", "requirements": ["r"], "acceptance": ["a"], "constraints": [],
                         "touchpoints": [], "out_of_scope": [], "open_questions": [], "unmapped": []}"""),
                "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(), "PROJ-1");

        assertThat(run.exitCode()).isZero();
        Path dir = ws.resolve(".sdd/diagnostics");
        assertThat(Files.isDirectory(dir)).isTrue();
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                all.append(Files.readString(f));
            }
        }
        String content = all.toString();
        assertThat(content).contains("=== sdd diagnostics ===").contains("Jira").contains("PROJ-1")
                .contains("status=200");
        assertThat(content).doesNotContain("sk-jira-super-secret");
    }
}
