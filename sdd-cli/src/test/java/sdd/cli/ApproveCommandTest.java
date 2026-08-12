package sdd.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;
import sdd.plan.approve.SmokeRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApproveCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run approve(ApproveCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private String seedEstateAndKb() throws Exception {
        // one real git repo so LiveGit agrees with the KB
        FixtureRepo.in(ws, "lib-core").file("a.txt", "x").commit("init");
        String sha = sdd.plan.approve.LiveGit.state(ws.resolve("lib-core")).head();
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('lib-core','"
                        + ws.resolve("lib-core") + "','LIBRARY','" + sha + "')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            });
        }
        return sha;
    }

    private void writeSpecAndPlan(String resolution) throws Exception {
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
                """);
        Files.writeString(ws.resolve("loyalty.plan.md"), """
                ---
                spec: SPEC-7
                plan_version: 1
                ---

                ## Summary
                S.

                ## Open Questions
                - Q1 [blocking]: which?
                %s

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
                """.formatted(resolution));
    }

    @Test
    void cleanPlanApprovesAndWritesPinnedPlanJson() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("plan approved: " + ws.resolve("loyalty.plan.json"))
                .contains("spec sha256: ").contains("plan sha256: ");
        assertThat(run.exitCode()).isZero();
        String json = Files.readString(ws.resolve("loyalty.plan.json"));
        assertThat(json).contains("\"spec_id\" : \"SPEC-7\"").contains("\"base_sha\"");
    }

    @Test
    void unresolvedBlockingQuestionBlocksApproval() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("problem: Q1 [blocking] has no resolution");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(Files.exists(ws.resolve("loyalty.plan.json"))).isFalse();
    }

    @Test
    void dirtyRepoBlocksApproval() throws Exception {
        seedEstateAndKb();
        Files.writeString(ws.resolve("lib-core/a.txt"), "edited");
        writeSpecAndPlan("  - resolution: r.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("problem: repo lib-core is stale or dirty");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void approveIsReachableThroughTheRootCommand() throws Exception {
        // pins picocli's subcommand-before-positional resolution for 'sdd plan approve'
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));

        int code = cmd.execute("plan", "approve", ws.resolve("missing.plan.md").toString());

        assertThat(sw.toString()).contains("error: ");
        assertThat(code).isEqualTo(1);
    }

    @Test
    void missingSpecSiblingFailsCleanly() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("  - resolution: r.");
        Files.delete(ws.resolve("loyalty.md"));

        Run run = approve(new ApproveCommand(), "--workspace", ws.toString(),
                ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("error: ").contains("loyalty.md");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void wrongExtensionFails() throws Exception {
        Run run = approve(new ApproveCommand(), "--workspace", ws.toString(),
                ws.resolve("loyalty.md").toString());

        assertThat(run.out()).contains("error: approve expects a .plan.md file");
        assertThat(run.exitCode()).isEqualTo(1);
    }
}
