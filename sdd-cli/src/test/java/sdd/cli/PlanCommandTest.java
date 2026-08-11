package sdd.cli;

import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

class PlanCommandTest {
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

    private static final String VALID_SPEC = """
            ---
            id: SPEC-7
            title: Loyalty tiers
            owner: ana
            status: draft
            ---

            ## Goal
            Add loyalty tiers to pricing.

            ## Requirements
            - R1: Price response includes the customer tier.

            ## Acceptance Criteria
            - A1: GET /price returns tier for gold customers.
            """;

    @Test
    void validCanonicalSpecPrintsSummary() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("spec OK: SPEC-7")
                .contains("1 requirements").contains("Phase 3B");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void semanticProblemsFailNamingEachOne() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("incomplete.md");
        Files.writeString(spec, """
                ---
                id: SPEC-8
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                """);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("problem: Acceptance Criteria: at least one A item is required");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void parseErrorsSurfaceWithLineNumber() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("broken.md");
        Files.writeString(spec, VALID_SPEC.replace("- R1: Price", "* R1: Price"));

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("error: line 12: Requirements items must look like");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void confluenceExportNormalizesWritesGateFileAndReparses() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path export = ws.resolve("loyalty-page.html");
        Files.writeString(export,
                "<h1>Loyalty tiers</h1><p>We want tiers.</p><p><img src=\"images/diagram.png\"></p>");
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "Loyalty tiers", "owner": "", "status": "", "goal": "Add tiers.",
                         "background": "", "requirements": ["Price response includes tier"],
                         "acceptance": ["GET /price returns tier"], "constraints": [],
                         "touchpoints": [{"kind": "repo", "value": "svc-pricing"}],
                         "out_of_scope": [], "open_questions": [], "unmapped": ["Rollout table"]}"""),
                "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(), export.toString());

        assertThat(run.exitCode()).isZero();
        Path written = ws.resolve("loyalty-page.html.spec.md");
        assertThat(run.out()).contains("normalized spec written: " + written)
                .contains("review and edit the spec, then run: sdd plan " + written);
        String content = Files.readString(written);
        assertThat(content).contains("- Q1: [unmapped] Rollout table")
                .contains("## Attachments").contains("- diagram.png");

        // Gate round-trip: the written file is a valid canonical spec
        Run second = plan(new PlanCommand(), "--workspace", ws.toString(), written.toString());
        assertThat(second.out()).contains("spec OK: spec-loyalty-page");
        assertThat(second.exitCode()).isZero();
    }

    @Test
    void outOptionRedirectsTheGateFile() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path export = ws.resolve("page.html");
        Files.writeString(export, "<h1>T</h1><p>Prose.</p>");
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "T", "owner": "", "status": "", "goal": "G.",
                         "background": "", "requirements": ["r"], "acceptance": ["a"],
                         "constraints": [], "touchpoints": [], "out_of_scope": [],
                         "open_questions": [], "unmapped": []}"""),
                "stop", new Usage(10, 10))));
        Path target = ws.resolve("gate.md");

        Run run = plan(cmd, "--workspace", ws.toString(), "--out", target.toString(), export.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("normalized spec written: " + target);
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.exists(ws.resolve("page.html.spec.md"))).isFalse();
    }

    @Test
    void planIsRegisteredOnTheRootCommand() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));

        int code = cmd.execute("plan", "--workspace", ws.toString(), spec.toString());

        assertThat(sw.toString()).contains("spec OK: SPEC-7");
        assertThat(code).isZero();
    }

    @Test
    void missingConfigFailsCleanly() throws Exception {
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("error: sdd.yml not found");
        assertThat(run.exitCode()).isEqualTo(1);
    }
}
