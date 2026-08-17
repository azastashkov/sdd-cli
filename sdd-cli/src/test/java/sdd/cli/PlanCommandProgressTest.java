package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.progress.Progress;
import sdd.core.testing.ScriptedChatModel;
import sdd.index.testing.RecordingProgress;
import sdd.index.testing.StopMarksSharedBuffer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlanCommand}'s own progress wiring — the {@code plan} counterpart of {@code
 * IndexCommandProgressTest}/{@code ReviewCommandProgressTest}, which this file mirrors: same
 * {@code progressForTest} seam, same reason (design doc "Arming": no test in this tree calls
 * {@code SddCli.main}).
 *
 * <p>Unlike {@code index}/{@code review}, {@link PlanCommand#validate} has a report block
 * ({@code printImpact}) printed MIDWAY through the method, with more phases (execution order,
 * open questions, drafting) still to run afterward — so this file also proves the {@code
 * impact:} block is routed through {@link Progress#suspend}, not {@link Progress#stop}, since
 * {@code stop()} would end the whole session before those later phases ever painted anything.
 */
class PlanCommandProgressTest {
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

    /** An empty knowledge base — no seeds, no dependents — so {@code ImpactAnalysis.analyze}'s
     *  own single model round-trip ({@code "repos": []}) is enough; the drafting call still runs
     *  (it is not short-circuited by an empty impact result), so a second scripted response is
     *  needed for it, exactly like {@code PlanCommandTest.validCanonicalSpecPrintsSummary}. */
    private void seedWorkspace() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            });
        }
        Files.writeString(ws.resolve("loyalty.md"), VALID_SPEC);
    }

    private ScriptedChatModel scriptedModel() {
        return new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("""
                        {"repos": []}"""), "stop", new Usage(10, 10)),
                new ChatResponse(ChatMessage.assistant("not json"), "stop", new Usage(10, 10))));
    }

    @Test
    void callEmitsPhaseEventsForAllFourValidateStages() throws Exception {
        seedWorkspace();
        PlanCommand command = new PlanCommand();
        command.plannerForTest = scriptedModel();
        RecordingProgress progress = new RecordingProgress();
        command.progressForTest = progress;

        Run run = plan(command, "--workspace", ws.toString(), ws.resolve("loyalty.md").toString());

        assertThat(run.exitCode()).isZero();
        assertThat(progress.events()).containsSubsequence(
                "phase:impact analysis:0",
                "suspend",
                "phase:execution order:0",
                "phase:open questions:0",
                "phase:draft plan:0",
                "stop");
    }

    /**
     * The ordering property a bare "stop is the last {@code Progress} event" assertion cannot
     * prove: {@link PlanCommand#call()} must call {@link Progress#stop()} right after {@code
     * PlanDrafter.draft} returns, before the "plan written" report block prints its first line —
     * not merely before the method returns via the outer {@code finally}.
     */
    @Test
    void stopFiresBeforeTheReportPrintsNotJustEventually() throws Exception {
        seedWorkspace();
        PlanCommand command = new PlanCommand();
        command.plannerForTest = scriptedModel();
        StringWriter sharedOut = new StringWriter();
        command.progressForTest = new StopMarksSharedBuffer(sharedOut);

        CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(sharedOut, true));
        cli.setErr(new PrintWriter(new StringWriter(), true));
        int code = cli.execute("--workspace", ws.toString(), ws.resolve("loyalty.md").toString());

        assertThat(code).isZero();
        String text = sharedOut.toString();
        int stopIndex = text.indexOf("<<progress stopped>>");
        int firstReportLine = text.indexOf("plan written: ");
        assertThat(stopIndex).as("stop() must have fired at all").isGreaterThanOrEqualTo(0);
        assertThat(firstReportLine).as("and before the report's own first line").isGreaterThan(stopIndex);
    }

    @Test
    void stopIsCalledEvenWhenNoRefIsGiven() {
        PlanCommand command = new PlanCommand();
        RecordingProgress progress = new RecordingProgress();
        command.progressForTest = progress;

        Run run = plan(command, "--workspace", ws.toString());

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(progress.events()).containsExactly("stop");
    }
}
