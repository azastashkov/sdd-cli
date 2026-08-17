package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;
import sdd.plan.approve.SmokeRunner;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 Fix 4 (review): {@code sdd plan approve}'s Gate-1 Jira write-back must be diagnosable too
 * — the review found the original Task 8 pass left it silently un-diagnosed, reasoning ApproveCommand
 * would have to duplicate {@code JiraWriteBack}'s own config-loading guard to open a writer of its
 * own. The fix instead has {@code JiraWriteBack.post} open (and close) its OWN writer, right after
 * it has already loaded config and confirmed write-back is on — zero change to {@code
 * ApproveCommand}. These tests exercise that path end to end, without touching the pre-existing
 * {@code ApproveCommandTest} (whose fixtures this file's private helpers mirror).
 */
class ApproveCommandDiagnosticsTest {
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().build();

    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run approve(ApproveCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private void seedEstateAndKb() throws Exception {
        FixtureRepo.in(ws, "lib-core").file("a.txt", "x").commit("init");
        String sha = sdd.plan.approve.LiveGit.state(ws.resolve("lib-core")).head();
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('lib-core','"
                        + ws.resolve("lib-core") + "','LIBRARY','" + sha + "')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            });
        }
    }

    private void writeSpecWithJiraSourceAndPlan() throws Exception {
        Files.writeString(ws.resolve("loyalty.md"), """
                ---
                id: SPEC-7
                title: Loyalty tiers
                owner: ana
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc

                ## Sources
                - jira PROJ-9 updated 2026-08-16T09:12:00Z %s/browse/PROJ-9
                """.formatted(wm.baseUrl()));
        Files.writeString(ws.resolve("loyalty.plan.md"), """
                ---
                spec: SPEC-7
                plan_version: 1
                ---

                ## Summary
                S.

                ## Open Questions
                - none

                ## Affected Repos
                - lib-core — seed/SEED — covers: R1 — why: w

                ## Excluded Candidates
                - none

                ## Execution Order
                1. lib-core

                ## Interface Contracts
                - none

                ## Repo Steps

                ### lib-core
                - covers: R1
                - version_action: minor
                - provides: -
                - consumes: -

                Do it.

                ## Generation Notes
                - none
                """);
    }

    private static final String MODELS_YAML = """
            models:
              planner:
                base_url: http://127.0.0.1:1/v1
                model: deepseek-v4-flash
              coder:
                base_url: http://127.0.0.1:1/v1
                model: qwen
            """;

    private String diagnosticsContent() throws IOException {
        Path dir = ws.resolve(".sdd/diagnostics");
        if (!Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder all = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                all.append(Files.readString(f));
            }
        }
        return all.toString();
    }

    @Test
    void aSuccessfulGate1CommentIsLoggedToADiagnosticsFileAndTheTokenNeverReachesIt() throws Exception {
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")).willReturn(created()));
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-approve-secret
                  write_back: comment
                """.formatted(wm.baseUrl()));
        seedEstateAndKb();
        writeSpecWithJiraSourceAndPlan();
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("commented on PROJ-9");
        String content = diagnosticsContent();
        assertThat(content).contains("Jira").contains("PROJ-9").contains("status=201");
        assertThat(content).doesNotContain("sk-jira-approve-secret");
    }

    @Test
    void aFailedGate1CommentIsAlsoLoggedToDiagnostics() throws Exception {
        // This is exactly the failure category Task 8 exists to make debuggable: previously
        // invisible in diagnostics, per the review.
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")).willReturn(unauthorized()));
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira-approve-secret
                  write_back: comment
                """.formatted(wm.baseUrl()));
        seedEstateAndKb();
        writeSpecWithJiraSourceAndPlan();
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();   // Gate-1 write-back is best-effort, never fails the command
        assertThat(run.out()).contains("  warn: jira comment failed:");
        String content = diagnosticsContent();
        assertThat(content).contains("rejected the configured token").contains("PROJ-9");
    }
}
