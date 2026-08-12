package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
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

class ReviseCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run revise(ReviseCommand cmd, String... args) {
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
    void reviseBumpsVersionFoldsQaAndBacksUpTheOldPlan() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
            });
        }
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

                ## Touchpoints
                - class: LoyaltyTier
                """);
        Files.writeString(ws.resolve("loyalty.plan.md"), """
                ---
                spec: SPEC-7
                plan_version: 2
                ---

                ## Summary
                Old summary.

                ## Open Questions
                - Q1 [blocking]: which method?
                  - resolution: tierFor(String).

                ## Affected Repos
                - lib-core — seed/SEED — covers: R1 — why: w

                ## Excluded Candidates
                - none

                ## Execution Order
                1. lib-core

                ## Interface Contracts
                - none

                ## Repo Steps
                - none

                ## Generation Notes
                - none
                """);
        ReviseCommand cmd = new ReviseCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1)),
                new ChatResponse(ChatMessage.assistant(
                        "{\"summary\": \"New summary.\", \"questions\": [], \"contracts\": [], \"repo_steps\": []}"),
                        "stop", new Usage(1, 1))));

        Run run = revise(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("plan revised (version 3): " + ws.resolve("loyalty.plan.md"))
                .contains("previous version backed up: ");
        String revised = Files.readString(ws.resolve("loyalty.plan.md"));
        assertThat(revised).contains("plan_version: 3").contains("New summary.");
        assertThat(Files.readString(ws.resolve("loyalty.plan.md.bak"))).contains("Old summary.");
        // the drafter saw the prior Q&A
        ScriptedChatModel scripted = (ScriptedChatModel) cmd.plannerForTest;
        assertThat(scripted.requests().get(1).messages().get(1).content())
                .contains("# Prior questions and human resolutions")
                .contains("Q1 [blocking]: which method?")
                .contains("resolved: tierFor(String).");
    }
}
