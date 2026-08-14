package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.review.Decision;
import sdd.cli.review.DecisionRecord;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sdd status} (design line 21/94): a read-only view of a run's state and Gate-2 decisions —
 * closes the Phase-5 CLI surface without re-rendering the whole Gate-2 report. Unlike every other
 * command in this phase, {@code status} never checks a repo out and never touches the run lock, so
 * these fixtures never need a real git repo the way {@code CleanCommandTest}'s do — a bare plan.json
 * + state.json + decisions.json is the whole story.
 */
class StatusCommandTest {
    @TempDir Path ws;

    private static final String RUN_ID = "SPEC-9-v1";

    private Path fixture() throws Exception {
        String planJson = """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"a"},
                           {"name":"svc","role":"dependent","annotation":"X","version_action":"patch","base_sha":"b"},
                           {"name":"aux","role":"dependent","annotation":"X","version_action":"patch","base_sha":"c"}],
                  "order":[["lib"],["svc"],["aux"]],
                  "edges":[],
                  "contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."},
                           {"repo":"svc","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],
                    "files":["S.java"],"verification":[],"sub_spec":"Add x to S."},
                           {"repo":"aux","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],
                    "files":["X.java"],"verification":[],"sub_spec":"Add x to X."}] }
                """;
        Path planPath = ws.resolve("s.plan.json");
        Files.writeString(planPath, planJson);

        RunStore store = RunStore.system();
        Path runDir = store.create(ws, RUN_ID, planJson, "");
        store.releaseLock(runDir);
        store.writeState(runDir, new RunState(RUN_ID, List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/lib", "abc1234", "ok"),
                new RepoRun("svc", RepoState.SUCCEEDED, "sdd/SPEC-9-v1/svc", "def5678", "ok"),
                new RepoRun("aux", RepoState.FAILED, "sdd/SPEC-9-v1/aux", null, "verify failed")),
                null, 42L));
        return planPath;
    }

    private static Path runDir(Path ws) {
        return ws.resolve(".sdd/runs/" + RUN_ID);
    }

    private static void decide(Path runDir, Map<String, DecisionRecord> decisions) {
        RunStore.system().writeDecisions(runDir, decisions);
    }

    private record Invocation(int exit, String out, String err) {
    }

    private static Invocation exec(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new StatusCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute(args);
        return new Invocation(exit, out.toString(), err.toString());
    }

    /** The one line containing {@code needle}, asserting there is exactly one — lets a test pin
     *  what appears TOGETHER on a repo's line rather than merely somewhere in the whole output. */
    private static String theLineContaining(String output, String needle) {
        List<String> matches = output.lines().filter(line -> line.contains(needle)).toList();
        assertThat(matches).as("line containing \"%s\" in:%n%s", needle, output).hasSize(1);
        return matches.get(0);
    }

    @Test
    void namedRunShowsPerRepoStateDecisionBranchAndTheDecisionsSummaryLine() throws Exception {
        Path planPath = fixture();
        decide(runDir(ws), Map.of("lib", new DecisionRecord(Decision.APPROVED, "")));

        Invocation r = exec("--workspace", ws.toString(), planPath.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains(RUN_ID);

        String libLine = theLineContaining(r.out(), "sdd/SPEC-9-v1/lib");
        assertThat(libLine).contains("lib").contains("SUCCEEDED").contains("APPROVED");

        String auxLine = theLineContaining(r.out(), "sdd/SPEC-9-v1/aux");
        assertThat(auxLine).contains("aux").contains("FAILED");

        assertThat(r.out()).contains("1 approved, 0 rejected, 0 redo, 2 pending");
        assertThat(r.out()).contains("42");   // total tokens
        assertThat(r.out()).contains("idle"); // lock was released by the fixture
    }

    @Test
    void aLiveLockReportsInProgress() throws Exception {
        Path planPath = fixture();
        Files.writeString(runDir(ws).resolve("lock"), Long.toString(ProcessHandle.current().pid()));

        Invocation r = exec("--workspace", ws.toString(), planPath.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("in progress");
        assertThat(r.out()).doesNotContain("idle");
    }

    @Test
    void aReleasedLockReportsIdle() throws Exception {
        Path planPath = fixture();   // fixture() already releases the lock

        Invocation r = exec("--workspace", ws.toString(), planPath.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("idle");
        assertThat(r.out()).doesNotContain("in progress");
    }

    @Test
    void anExplicitlyNamedPlanWithNoRunDirExitsFour() throws Exception {
        Files.writeString(ws.resolve("nope.plan.json"), """
                { "spec_id":"SPEC-99","plan_version":1,"spec_sha256":"z","plan_sha256":"z",
                  "repos":[],"order":[],"edges":[],"contracts":[],"steps":[] }
                """);

        Invocation r = exec("--workspace", ws.toString(), ws.resolve("nope.plan.json").toString());

        assertThat(r.exit()).isEqualTo(4);
        assertThat(r.err()).contains("no run to status");
    }

    @Test
    void aBareInvocationWithNoPlanScansEveryRunDirAndNeverTouchesTheEstate() throws Exception {
        fixture();

        Invocation r = exec("--workspace", ws.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains(RUN_ID).contains("lib").contains("SUCCEEDED");
    }

    @Test
    void anEmptyWorkspaceReportsNoRuns() throws Exception {
        Invocation r = exec("--workspace", ws.toString());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("no runs found");
    }
}
