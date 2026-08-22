package sdd.cli;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;
import sdd.plan.approve.SmokeRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.created;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.unauthorized;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ApproveCommandTest {
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

    /** Same fixture as {@link #writeSpecAndPlan}, plus a "## Sources" bullet naming a fetched
     *  Jira root issue — the Task 4 write-back's only trigger for actually touching config/network
     *  (see {@code JiraWriteBack.post}'s empty-{@code jiraKeys} short-circuit). */
    private void writeSpecWithJiraSourceAndPlan(String resolution) throws Exception {
        writeSpecAndPlan(resolution);
        Files.writeString(ws.resolve("loyalty.md"), Files.readString(ws.resolve("loyalty.md")) + """

                ## Sources
                - jira PROJ-9 updated 2026-08-16T09:12:00Z %s/browse/PROJ-9
                """.formatted(wm.baseUrl()));
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

    @Test
    void approveCommentsOnEachJiraSourceIssueWhenWriteBackIsConfigured() throws Exception {
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")).willReturn(created()));
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                  write_back: comment
                """.formatted(wm.baseUrl()));
        seedEstateAndKb();
        writeSpecWithJiraSourceAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("plan approved: ").contains("commented on PROJ-9");
        wm.verify(postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-9/comment"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson(
                        "{\"body\": \"sdd: plan approved for `SPEC-7` \\u2014 `1` repos affected, "
                                + "execution order: `lib-core`\"}")));
    }

    @Test
    void approveWithWriteBackNoneOrAbsentPostsNothingAndPrintsNothing() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                """.formatted(wm.baseUrl()));   // write_back defaults to none
        seedEstateAndKb();
        writeSpecWithJiraSourceAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).doesNotContain("commented on").doesNotContain("jira comment failed");
        wm.verify(0, postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")));
    }

    @Test
    void approveNoCommentFlagSuppressesEvenWhenWriteBackIsConfigured() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                  write_back: comment
                """.formatted(wm.baseUrl()));
        seedEstateAndKb();
        writeSpecWithJiraSourceAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), "--no-comment",
                ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).doesNotContain("commented on");
        wm.verify(0, postRequestedFor(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")));
    }

    @Test
    void approveWithAFailingJiraCommentWarnsButExitCodeStaysZeroAndPlanJsonStillWritten() throws Exception {
        wm.stubFor(post(urlEqualTo("/rest/api/2/issue/PROJ-9/comment")).willReturn(unauthorized()));
        Files.writeString(ws.resolve("sdd.yml"), MODELS_YAML + """
                atlassian:
                  jira:
                    base_url: %s
                    token: sk-jira
                  write_back: comment
                """.formatted(wm.baseUrl()));
        seedEstateAndKb();
        writeSpecWithJiraSourceAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        // The property most likely to regress (Task 4 brief): a failed post must never flip a
        // successful approval's exit code.
        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("  warn: jira comment failed: ");
        assertThat(Files.exists(ws.resolve("loyalty.plan.json"))).isTrue();
    }

    /**
     * Approve writes the estate sidecar beside plan.json, and it carries what plan.json drops.
     *
     * <p>The blocking question and its resolution are the case that matters: today nothing after
     * approve can see that a blocking question was ever asked, let alone answered, because
     * PlanJson never carried questions and plan.md is not read again after Gate 1.
     */
    @Test
    void approveAlsoWritesTheEstateSidecarCarryingWhatPlanJsonDrops() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        Path estate = ws.resolve("loyalty.estate.yaml");
        assertThat(run.out()).contains("estate: " + estate);
        String yaml = Files.readString(estate);
        assertThat(yaml).contains("spec_id: SPEC-7")
                .contains("resolution: use tierFor.")
                .contains("blocking: true");
    }

    /**
     * And it loads back to the same model plan.json does. This is the whole basis for preferring it
     * at implement time: a second serialization of the same facts is only safe while it is provably
     * the same facts.
     */
    @Test
    void theSidecarAndPlanJsonLoadToTheIdenticalModel() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");
        approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        sdd.cli.implement.PlanModel fromJson = sdd.cli.implement.PlanJsonReader.read(
                Files.readString(ws.resolve("loyalty.plan.json")));
        sdd.cli.implement.PlanModel fromEstate = sdd.cli.implement.PlanJsonReader.read(
                sdd.plan.approve.EstateYaml.toJson(Files.readString(ws.resolve("loyalty.estate.yaml"))));

        assertThat(fromEstate).isEqualTo(fromJson);
    }

    /**
     * A blocking question answered in the OpenSpec tree unblocks the plan.
     *
     * <p>This is what makes the workspace's change directory somewhere you WORK rather than
     * somewhere you look. plan.md leaves the question unresolved, which on its own is an approval
     * failure; the answer exists only in design.md.
     */
    @Test
    void aBlockingQuestionAnsweredInTheOpenSpecTreeIsHonoured() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("");                       // no resolution in plan.md
        Path design = ws.resolve("openspec/changes/spec-7-v1/design.md");
        Files.createDirectories(design.getParent());
        Files.writeString(design, """
                ## Open Questions
                - Q1 [blocking]: Which method?
                  - resolution: use tierFor, answered in the tree.
                """);
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.out()).contains("openspec: 1 question resolution(s) read from design.md");
        assertThat(run.exitCode()).as("out=%s", run.out()).isZero();
        // Into the change directory, not beside plan.json: the tree exists, so that is where
        // proposal.md tells a reader to look. The two halves of this work composing.
        assertThat(Files.readString(ws.resolve("openspec/changes/spec-7-v1/estate.yaml")))
                .contains("use tierFor, answered in the tree.");
        assertThat(Files.exists(ws.resolve("loyalty.estate.yaml"))).isFalse();
    }

    /** Absent tree, unchanged behaviour: every plan approved before this existed still approves. */
    @Test
    void withNoOpenSpecTreeApproveIsExactlyWhatItWas() throws Exception {
        seedEstateAndKb();
        writeSpecAndPlan("  - resolution: use tierFor.");
        ApproveCommand cmd = new ApproveCommand();
        cmd.smokeForTest = (consumer, provider) -> new SmokeRunner.Result(true, "");

        Run run = approve(cmd, "--workspace", ws.toString(), ws.resolve("loyalty.plan.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).doesNotContain("openspec: ");
    }
}
