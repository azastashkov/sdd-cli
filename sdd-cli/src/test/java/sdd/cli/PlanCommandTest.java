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

class PlanCommandTest {
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
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            });
        }
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("""
                        {"repos": []}"""), "stop", new Usage(10, 10)),
                new ChatResponse(ChatMessage.assistant("not json"), "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("spec OK: SPEC-7")
                .contains("1 requirements")
                .contains("plan written: ")
                .contains("impact: 0 repos affected (0 seeds, 0 dependents, 0 contracts, 0 bom-sites)")
                .contains("impact problem: no seeds")
                .contains("impact problem: no repo covers R1");
        assertThat(run.exitCode()).isZero();
        assertThat(Files.exists(ws.resolve("loyalty.plan.md"))).isTrue();
    }

    @Test
    void validSpecRunsImpactAnalysisEndToEnd() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
            });
        }
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, """
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

                ## Touchpoints
                - class: LoyaltyTier
                """);
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"}]}"""), "stop", new Usage(10, 10)),
                new ChatResponse(ChatMessage.assistant("""
                        {"summary": "S.", "questions": [], "contracts": [], "repo_steps": []}"""),
                        "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.out())
                .contains("spec OK: SPEC-7")
                .contains("impact: 2 repos affected (1 seeds, 1 dependents, 0 contracts, 0 bom-sites)")
                .contains("lib-core")
                .contains("SEED")
                .contains("svc-pricing")
                .contains("BUMP_REBUILD_ONLY")   // no api_usage row in this estate — annotation must survive to the CLI
                .contains("plan written: ");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void emptyKnowledgeBaseFailsBeforeAnyModelWork() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("error: knowledge base is empty — run sdd index first");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isFalse();   // plan must never CREATE the KB
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
                .contains("review and edit the spec, then run: sdd plan --workspace " + ws + " " + written);
        String content = Files.readString(written);
        assertThat(content).contains("- Q1: [unmapped] Rollout table")
                .contains("## Attachments").contains("- diagram.png");

        // Gate round-trip: the written file is a valid canonical spec (KB is empty here — the
        // second PlanCommand has no planner seam, so seeding a KB would trigger a real HTTP
        // retry against the unroutable endpoint; the round trip is still proven by "spec OK"
        // printing before the empty-KB check runs)
        Run second = plan(new PlanCommand(), "--workspace", ws.toString(), written.toString());
        assertThat(second.out()).contains("spec OK: spec-loyalty-page")
                .contains("error: knowledge base is empty — run sdd index first");
        assertThat(second.exitCode()).isEqualTo(1);
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

        assertThat(sw.toString()).contains("error: knowledge base is empty — run sdd index first");
        assertThat(code).isEqualTo(1);
    }

    @Test
    void approveAndReviseSubcommandsAreNotSwallowedByTheWidenedRefsPositional() throws Exception {
        // Ruling R1: refs widened from arity "0..1" to "0..*" risks the parser treating
        // "approve"/"revise" as spec refs instead of routing into the subcommand. Both
        // subcommands validate their own filename suffix before touching any of PlanCommand's
        // config/workspace machinery, so their exact error text proves which parser handled
        // the args — a swallowed "approve" would instead surface PlanCommand's own ref-conflict
        // or missing-config error, never this one.
        StringWriter approveOut = new StringWriter();
        CommandLine approveCmd = new CommandLine(new SddCli());
        approveCmd.setOut(new PrintWriter(approveOut, true));
        approveCmd.setErr(new PrintWriter(approveOut, true));

        int approveCode = approveCmd.execute("plan", "approve", "loyalty.md");

        assertThat(approveOut.toString()).contains("error: approve expects a .plan.md file");
        assertThat(approveCode).isEqualTo(1);

        StringWriter reviseOut = new StringWriter();
        CommandLine reviseCmd = new CommandLine(new SddCli());
        reviseCmd.setOut(new PrintWriter(reviseOut, true));
        reviseCmd.setErr(new PrintWriter(reviseOut, true));

        int reviseCode = reviseCmd.execute("plan", "revise", "loyalty.md");

        assertThat(reviseOut.toString()).contains("error: revise expects a .plan.md file");
        assertThat(reviseCode).isEqualTo(1);
    }

    @Test
    void missingConfigFailsCleanly() throws Exception {
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("error: sdd.yml not found");
        assertThat(run.exitCode()).isEqualTo(1);
    }


    @Test
    void outOptionRejectsHtmlTargets() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path export = ws.resolve("page.html");
        Files.writeString(export, "<h1>T</h1>");

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(),
                "--out", ws.resolve("review.html").toString(), export.toString());

        assertThat(run.out()).contains("error: --out target must be a markdown file");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(Files.exists(ws.resolve("review.html"))).isFalse();
    }

    @Test
    void followUpHintCarriesNonDefaultWorkspace() throws Exception {
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

        Run run = plan(cmd, "--workspace", ws.toString(), export.toString());

        assertThat(run.out()).contains(
                "review and edit the spec, then run: sdd plan --workspace " + ws + " "
                        + ws.resolve("page.html.spec.md"));
    }

    @Test
    void validSpecWritesGate1PlanMd() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                        + "VALUES (1,'com.acme.LoyaltyTier','CLASS',1,'src/main/java/com/acme/LoyaltyTier.java')");
            });
        }
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, """
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

                ## Touchpoints
                - class: LoyaltyTier
                """);
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"}]}"""), "stop", new Usage(10, 10)),
                new ChatResponse(ChatMessage.assistant("""
                        {"summary": "Add the tier lookup to lib-core; svc-pricing rebuilds.",
                         "questions": [],
                         "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                                        "consumers": ["svc-pricing"], "body": "method: Tier tierFor(String)"}],
                         "repo_steps": [{"repo": "lib-core", "covers": ["R1"],
                                         "sub_spec": "Add tierFor to LoyaltyTier.",
                                         "files": ["src/main/java/com/acme/LoyaltyTier.java"],
                                         "provides_contracts": ["C-1"], "consumes_contracts": [],
                                         "version_action": "minor",
                                         "verification": ["./gradlew test"]}]}"""), "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.exitCode()).isZero();
        Path planPath = ws.resolve("loyalty.plan.md");
        assertThat(run.out()).contains("plan written: " + planPath)
                .contains("review and edit the plan, then run: sdd plan approve")
                .doesNotContain("plan.md rendering is not implemented yet");
        String planMd = Files.readString(planPath);
        assertThat(planMd).startsWith("---\nspec: SPEC-7\nplan_version: 1\n---\n")
                .contains("Add the tier lookup to lib-core")
                .contains("- lib-core — seed/SEED")
                .contains("- svc-pricing — dependent/")
                .contains("### C-1 (java-api) — lib-core -> svc-pricing")
                .contains("### lib-core")
                .contains("Add tierFor to LoyaltyTier.");
    }

    @Test
    void draftingFailureStillWritesPlanMdWithBlockingQuestion() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
            });
        }
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC + """

                ## Touchpoints
                - class: LoyaltyTier
                """);
        PlanCommand cmd = new PlanCommand();
        // seeding succeeds (empty selection), drafting response is garbage
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1)),
                new ChatResponse(ChatMessage.assistant("not json"), "stop", new Usage(1, 1))));

        Run run = plan(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.exitCode()).isZero();
        String planMd = Files.readString(ws.resolve("loyalty.plan.md"));
        assertThat(planMd).contains("[blocking]: plan drafting unavailable:")
                .contains("## Affected Repos")
                .contains("- lib-core — seed/SEED")
                .contains("## Repo Steps\n- none (drafting unavailable)");
    }

    @Test
    void backupLineAppearsWhenPlanFileIsRegenerated() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                        + "VALUES (1,'com.acme.LoyaltyTier','CLASS',1,'src/main/java/com/acme/LoyaltyTier.java')");
            });
        }
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, """
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

                ## Touchpoints
                - class: LoyaltyTier
                """);

        // First run: generate the plan
        PlanCommand cmd1 = new PlanCommand();
        cmd1.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"}]}"""), "stop", new Usage(10, 10)),
                new ChatResponse(ChatMessage.assistant("""
                        {"summary": "Add the tier lookup to lib-core; svc-pricing rebuilds.",
                         "questions": [],
                         "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                                        "consumers": ["svc-pricing"], "body": "method: Tier tierFor(String)"}],
                         "repo_steps": [{"repo": "lib-core", "covers": ["R1"],
                                         "sub_spec": "Add tierFor to LoyaltyTier.",
                                         "files": ["src/main/java/com/acme/LoyaltyTier.java"],
                                         "provides_contracts": ["C-1"], "consumes_contracts": [],
                                         "version_action": "minor",
                                         "verification": ["./gradlew test"]}]}"""), "stop", new Usage(10, 10))));

        Run run1 = plan(cmd1, "--workspace", ws.toString(), spec.toString());
        assertThat(run1.exitCode()).isZero();

        // Second run: regenerate the plan — should back up the first version
        PlanCommand cmd2 = new PlanCommand();
        cmd2.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"}]}"""), "stop", new Usage(10, 10)),
                new ChatResponse(ChatMessage.assistant("""
                        {"summary": "Add the tier lookup to lib-core; svc-pricing rebuilds.",
                         "questions": [],
                         "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                                        "consumers": ["svc-pricing"], "body": "method: Tier tierFor(String)"}],
                         "repo_steps": [{"repo": "lib-core", "covers": ["R1"],
                                         "sub_spec": "Add tierFor to LoyaltyTier.",
                                         "files": ["src/main/java/com/acme/LoyaltyTier.java"],
                                         "provides_contracts": ["C-1"], "consumes_contracts": [],
                                         "version_action": "minor",
                                         "verification": ["./gradlew test"]}]}"""), "stop", new Usage(10, 10))));

        Run run2 = plan(cmd2, "--workspace", ws.toString(), spec.toString());
        assertThat(run2.exitCode()).isZero();
        assertThat(run2.out()).contains("previous version backed up: " + ws.resolve("loyalty.plan.md.bak"));
        assertThat(Files.exists(ws.resolve("loyalty.plan.md.bak"))).isTrue();
    }

    // --- section 6: multiple refs and free text -----------------------------------------

    @Test
    void noRefsAndNoTextFailsWithTheExistingMissingParameterError() throws Exception {
        Run run = plan(new PlanCommand(), "--workspace", ws.toString());

        assertThat(run.out()).contains("error: missing required parameter: <ref>");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void markdownRefCombinedWithAnotherRefIsRejected() throws Exception {
        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), "a.md", "page.html");

        assertThat(run.out()).contains("error: a canonical spec ref cannot be combined with other sources");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void markdownRefCombinedWithTextIsRejected() throws Exception {
        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), "--text", "extra context", "a.md");

        assertThat(run.out()).contains("error: a canonical spec ref cannot be combined with other sources");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void multipleMarkdownRefsAreRejected() throws Exception {
        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), "a.md", "b.md");

        assertThat(run.out()).contains("error: a canonical spec ref cannot be combined with other sources");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    // Task 1's placeholder-rejection tests (bareJiraKeyRefIsRejectedAsNotConfigured and friends,
    // asserting "error: Jira/Confluence ingestion is not configured") are gone: Task 3 replaces
    // that placeholder with the real Jira/Confluence ingestion path exercised below. That is the
    // one expected DoD exception ("every pre-existing test still passes unmodified").

    @Test
    void bareJiraKeyRefWithNoAtlassianConfigFailsNamingWhatIsMissing() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), "PROJ-123");

        assertThat(run.out()).contains("error: no atlassian.jira configured in sdd.yml");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void jiraRefDoesNotFallThroughToTheConfusingMissingFileError() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        // a Jira ref must never reach MarkdownSpecSource — that would report a confusing "file
        // not found" instead of the honest "not configured" message
        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), "PROJ-123");

        assertThat(run.out()).doesNotContain("NoSuchFileException").doesNotContain("PROJ-123 (No such file");
    }

    @Test
    void confluencePageUrlRefWithNoAtlassianConfluenceConfiguredFailsNamingWhatIsMissing() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(),
                "https://confluence.corp.local/pages/viewpage.action?pageId=1");

        assertThat(run.out()).contains("error: no atlassian.confluence configured in sdd.yml");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void confluencePageOnlyRefEndToEndWritesAWorkspaceRelativeGateFile() throws Exception {
        // Task 3 review Fix 1: a Confluence-only ref (no Jira ref, no export ref, no --text) had
        // no anchor to derive its output filename/id from and crashed on texts.get(0) against an
        // empty list. This is the missing success test for that path.
        wm.stubFor(get(urlEqualTo("/rest/api/content/65601?expand=body.storage,version,space"))
                .willReturn(okJson("""
                        {"id": "65601", "type": "page", "title": "Order API spec",
                         "space": {"key": "ENG"}, "version": {"number": 3},
                         "body": {"storage": {"value": "<p>Pagination uses opaque cursors.</p>",
                                               "representation": "storage"}}}
                        """)));
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  confluence:
                    base_url: %s
                    token: sk-confluence
                """.formatted(wm.baseUrl()));
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "Order API spec", "owner": "", "status": "", "goal": "G.",
                         "background": "", "requirements": ["r"], "acceptance": ["a"], "constraints": [],
                         "touchpoints": [], "out_of_scope": [], "open_questions": [], "unmapped": []}"""),
                "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(),
                wm.baseUrl() + "/pages/viewpage.action?pageId=65601");

        Path written = ws.resolve("65601.spec.md");
        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("normalized spec written: " + written);
        String content = Files.readString(written);
        // A bare numeric id like "65601" is quoted by SpecRenderer (it would otherwise round-trip
        // through YAML as an integer, not a string) — see SpecRenderer.bareSafe.
        assertThat(content).contains("id: '65601'");
    }

    @Test
    void jiraBrowseUrlRefWithNoAtlassianConfigFails() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(),
                "https://jira.corp.local/browse/PROJ-123");

        assertThat(run.out()).contains("error: no atlassian.jira configured in sdd.yml");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void anUnsetJiraPatSurfacesTheDeferredCredentialMessageAtPointOfUse() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml() + """
                atlassian:
                  jira:
                    base_url: https://jira.corp.local
                    token: ${JIRA_PAT}
                """);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), "PROJ-123");

        assertThat(run.out()).contains("error: atlassian.jira.token: environment variable JIRA_PAT is not set");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void jiraRefEndToEndWritesTheGateFileWithSourcesAndPreservesTheHumanGate() throws Exception {
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
                    token: sk-jira
                """.formatted(wm.baseUrl()));
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "Order API", "owner": "", "status": "", "goal": "Add pagination.",
                         "background": "", "requirements": ["r"], "acceptance": ["a"], "constraints": [],
                         "touchpoints": [], "out_of_scope": [], "open_questions": [], "unmapped": []}"""),
                "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(), "PROJ-1");

        Path written = ws.resolve("PROJ-1.spec.md");
        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("normalized spec written: " + written)
                .contains("review and edit the spec, then run: sdd plan --workspace " + ws + " " + written)
                // the human gate: this command must never run impact analysis off a bare ticket ref
                .doesNotContain("impact:");
        String content = Files.readString(written);
        assertThat(content).contains("id: PROJ-1")
                .contains("## Sources")
                .contains("- jira PROJ-1 updated 2026-08-16T09:12:00Z " + wm.baseUrl() + "/browse/PROJ-1");
    }

    @Test
    void multipleTextArgumentsProduceAValidSlugNamedSpec() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        PlanCommand cmd = new PlanCommand();
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "Loyalty tiers", "owner": "", "status": "", "goal": "Add tiers.",
                         "background": "", "requirements": ["r"], "acceptance": ["a"],
                         "constraints": [], "touchpoints": [], "out_of_scope": [],
                         "open_questions": [], "unmapped": []}"""),
                "stop", new Usage(10, 10))));
        cmd.plannerForTest = planner;

        Run run = plan(cmd, "--workspace", ws.toString(),
                "--text", "Add loyalty tiers to the pricing engine",
                "--text", "Gold customers should see their tier.");

        assertThat(run.exitCode()).isZero();
        Path written = ws.resolve("add-loyalty-tiers-to-the-pricing.spec.md");
        assertThat(run.out()).contains("normalized spec written: " + written);
        assertThat(Files.exists(written)).isTrue();
        String content = Files.readString(written);
        assertThat(content).contains("id: spec-add-loyalty-tiers-to-the-pricing");

        String userMessage = planner.requests().get(0).messages().get(1).content();
        assertThat(userMessage).contains("## Source 1:").contains("Add loyalty tiers to the pricing engine")
                .contains("## Source 2:").contains("Gold customers should see their tier.");

        // gate round trip, mirroring the Confluence-export gate-file assertion above
        Run second = plan(new PlanCommand(), "--workspace", ws.toString(), written.toString());
        assertThat(second.out()).contains("spec OK: spec-add-loyalty-tiers-to-the-pricing")
                .contains("error: knowledge base is empty — run sdd index first");
        assertThat(second.exitCode()).isEqualTo(1);
    }

    @Test
    void confluenceExportCombinedWithTextBuildsOneBundle() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path export = ws.resolve("loyalty-page.html");
        Files.writeString(export, "<h1>Loyalty tiers</h1><p>Confluence context.</p>");
        PlanCommand cmd = new PlanCommand();
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "Loyalty tiers", "owner": "", "status": "", "goal": "Add tiers.",
                         "background": "", "requirements": ["r"], "acceptance": ["a"],
                         "constraints": [], "touchpoints": [], "out_of_scope": [],
                         "open_questions": [], "unmapped": []}"""),
                "stop", new Usage(10, 10))));
        cmd.plannerForTest = planner;

        Run run = plan(cmd, "--workspace", ws.toString(), "--text", "Extra operator context.",
                export.toString());

        assertThat(run.exitCode()).isZero();
        Path written = ws.resolve("loyalty-page.html.spec.md");
        assertThat(run.out()).contains("normalized spec written: " + written);
        assertThat(Files.exists(written)).isTrue();
        String userMessage = planner.requests().get(0).messages().get(1).content();
        assertThat(userMessage).contains("Confluence context.").contains("Extra operator context.");
    }

    @Test
    void anOversizedTextArgumentFailsLoudlyInsteadOfNormalizingAnEmptyBundle() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        PlanCommand cmd = new PlanCommand();
        ScriptedChatModel planner = new ScriptedChatModel(List.of());
        cmd.plannerForTest = planner;

        Run run = plan(cmd, "--workspace", ws.toString(), "--text", "x".repeat(300_001));

        assertThat(run.out()).contains("error: document too large for the source budget");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(planner.requests()).isEmpty();
    }
}
