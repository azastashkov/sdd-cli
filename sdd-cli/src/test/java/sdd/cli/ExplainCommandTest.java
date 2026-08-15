package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.explain.ExplainFixture;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code sdd explain} wires interpret -&gt; deterministic fetch -&gt; narrate (Tasks 1-7) into a
 * command with three load-bearing properties: it writes nothing without {@code --out} (not even
 * the knowledge base itself, on the empty-KB path -- {@link Database#open} would otherwise create
 * {@code .sdd/} as a side effect); zero facts skip call 2 entirely, proven by a request-count
 * assertion rather than trusting {@link ScriptedChatModel} exhaustion to fail loudly; and every
 * degradation rung still exits 0 with the {@code ## Evidence} section printed.
 */
class ExplainCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run explain(ExplainCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
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

    /** Same shape, but {@code planner.api_key} references an environment variable that is never
     *  set -- {@code ConfigLoader} defers the failure into {@code apiKeyError} rather than
     *  throwing at load time, so {@code HttpChatModel}'s constructor is the first thing to raise it. */
    private String yamlWithUnresolvableApiKey() {
        return """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                    max_tokens: 16384
                    api_key: ${SDD_EXPLAIN_TEST_MISSING_VAR_XYZ}
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """;
    }

    private void seedKb() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (Database db = Database.open(ws)) {
            ExplainFixture.seed(db.jdbi());
        }
    }

    private Set<Path> listWorkspace() throws Exception {
        try (var stream = Files.walk(ws)) {
            return stream.map(ws::relativize).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    // --- property: writes nothing without --out ---------------------------------------------

    @Test
    void missingKnowledgeBaseFailsWithoutCreatingIt() {
        Run run = explain(new ExplainCommand(), "--workspace", ws.toString(), "what is lib-core");

        assertThat(run.out()).contains("error: knowledge base is empty — run sdd index first");
        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isFalse();
    }

    @Test
    void emptyKnowledgeBaseFailsWithoutCreatingIt() throws Exception {
        // Database.open would create .sdd/index.db as a side effect; the Files.exists guard must
        // run first (GraphCommand.java's idiom) or an empty-repo KB silently gets created here.
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        try (Database db = Database.open(ws)) {
            // no repos inserted -- the KB file exists, but is empty
        }

        Run run = explain(new ExplainCommand(), "--workspace", ws.toString(), "what is lib-core");

        assertThat(run.out()).contains("error: knowledge base is empty — run sdd index first");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void nothingIsWrittenToTheWorkspaceWithoutOutOption() throws Exception {
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                response("""
                        {"intent":"describe","restatement":"What is lib-core?",
                         "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}""", "stop"),
                response("lib-core is a library repo.", "stop")));
        Set<Path> before = listWorkspace();

        Run run = explain(cmd, "--workspace", ws.toString(), "what", "is", "lib-core");

        assertThat(run.exitCode()).isZero();
        Set<Path> after = listWorkspace();
        assertThat(after).isEqualTo(before);
    }

    // --- unusable input: missing question ----------------------------------------------------

    @Test
    void missingQuestionFailsWithHouseStyleMessageNotPicocliExit2() {
        Run run = explain(new ExplainCommand(), "--workspace", ws.toString());

        assertThat(run.out()).contains("error: missing required parameter: <question>");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void blankQuestionFailsTheSameWay() {
        Run run = explain(new ExplainCommand(), "--workspace", ws.toString(), "   ");

        assertThat(run.out()).contains("error: missing required parameter: <question>");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    // --- happy path ----------------------------------------------------------------------------

    @Test
    void happyPathPrintsProseAndEvidenceToStdout() throws Exception {
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                response("""
                        {"intent":"describe","restatement":"What is lib-core?",
                         "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}""", "stop"),
                response("lib-core is a library repo with one module.", "stop")));

        Run run = explain(cmd, "--workspace", ws.toString(), "what", "is", "lib-core");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("Interpreted as: What is lib-core?")
                .contains("lib-core is a library repo with one module.")
                .contains("## Evidence")
                .contains("### Interpretation");
    }

    @Test
    void outOptionWritesMarkdownAndPrintsConfirmation() throws Exception {
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                response("""
                        {"intent":"describe","restatement":"What is lib-core?",
                         "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}""", "stop"),
                response("lib-core is a library repo.", "stop")));
        Path target = ws.resolve("out.md");

        Run run = explain(cmd, "--workspace", ws.toString(), "--out", target.toString(), "what", "is", "lib-core");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("explanation written: " + target)
                .doesNotContain("## Evidence");   // body goes to the file, not stdout
        String written = Files.readString(target);
        assertThat(written).contains("Interpreted as: What is lib-core?")
                .contains("lib-core is a library repo.")
                .contains("## Evidence");
    }

    @Test
    void explainIsRegisteredOnTheRootCommand() {
        // Mirrors GraphCommandTest/PlanCommandTest's registration test: no KB and no sdd.yml, so
        // this never reaches model construction (plannerForTest isn't reachable from the CLI
        // entrypoint) -- it only needs to prove the "explain" subcommand routes to ExplainCommand.
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));

        int code = cmd.execute("explain", "--workspace", ws.toString(), "what is lib-core");

        assertThat(sw.toString()).contains("error: knowledge base is empty — run sdd index first");
        assertThat(code).isEqualTo(1);
    }

    // --- degradation ladder: every rung exits 0 and still prints Evidence ---------------------

    @Test
    void missingCredentialFallsBackToDeterministicInterpretationAndStillExitsZero() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yamlWithUnresolvableApiKey());
        try (Database db = Database.open(ws)) {
            ExplainFixture.seed(db.jdbi());
        }
        ExplainCommand cmd = new ExplainCommand();   // plannerForTest left null: real HttpChatModel is built,
        // and its constructor is what must throw ConfigException(apiKeyError) here

        Run run = explain(cmd, "--workspace", ws.toString(), "what", "is", "lib-core");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("interpreter unavailable")
                .contains("SDD_EXPLAIN_TEST_MISSING_VAR_XYZ")
                .contains("## Evidence")
                .contains("### Interpretation");
    }

    @Test
    void call1FailureFallsBackAndStillExitsZeroWithEvidence() throws Exception {
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        ChatModel alwaysThrows = req -> {
            throw new ModelException("connection refused", 0);
        };
        cmd.plannerForTest = alwaysThrows;

        Run run = explain(cmd, "--workspace", ws.toString(), "what", "is", "lib-core");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("interpreter unavailable: model error: connection refused")
                .contains("## Evidence")
                // the fallback's literal whole-word matching finds "lib-core" named in the question,
                // which downgrades to DESCRIBE and pulls in RepoFacts' per-repo sections (REPO-kind
                // entities never get their own citation section -- it would just echo the value back)
                .contains("Repo: lib-core");
    }

    @Test
    void noEntityResolvesDowngradesToSearchWithDropsListed() throws Exception {
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(
                response("""
                        {"intent":"describe","restatement":"What is ghost-repo, and what about PriceApi?",
                         "entities":[{"kind":"repo","value":"ghost-repo","role":"subject"}],
                         "search_terms":["PriceApi"]}""", "stop"),
                response("PriceApi is referenced by svc-orders.", "stop")));

        Run run = explain(cmd, "--workspace", ws.toString(),
                "what", "is", "ghost-repo,", "and", "what", "about", "PriceApi?");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out())
                .contains("model referenced repo 'ghost-repo'")
                .contains("dropped")
                .contains("downgraded to search")
                .contains("## Evidence");
    }

    @Test
    void zeroFactsSkipsTheNarratorCallEntirely() throws Exception {
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                response("""
                        {"intent":"search","restatement":"What about zzz-nonexistent-term?",
                         "entities":[],"search_terms":["zzz-nonexistent-term-xyz"]}""", "stop")));
        cmd.plannerForTest = model;

        Run run = explain(cmd, "--workspace", ws.toString(), "what", "about", "zzz-nonexistent-term-xyz");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("no facts in the knowledge base match this question")
                .contains("## Evidence");
        // the request-count proof: call 2 was never attempted, so exactly one call was made --
        // not "the script ran out and call 2 would have thrown".
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void exactlyTwoModelCallsEndToEndWhenEvidenceIsFound() throws Exception {
        // The question-scoped candidate vocabulary QuestionInterpreter now builds runs a
        // deterministic FTS search before call 1 -- not a model call. This pins the "still exactly
        // two model calls" invariant end-to-end through the real command, not just at the
        // QuestionInterpreter unit level, so a request-count assertion (not script exhaustion)
        // proves it.
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                response("""
                        {"intent":"describe","restatement":"What is lib-core?",
                         "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}""", "stop"),
                response("lib-core is a library repo.", "stop")));
        cmd.plannerForTest = model;

        Run run = explain(cmd, "--workspace", ws.toString(), "what", "is", "lib-core");

        assertThat(run.exitCode()).isZero();
        assertThat(model.requests()).hasSize(2);
    }

    @Test
    void call2FailureStillPrintsAnswerUnavailableAndFullEvidence() throws Exception {
        seedKb();
        ExplainCommand cmd = new ExplainCommand();
        String interpretationJson = """
                {"intent":"describe","restatement":"What is lib-core?",
                 "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}""";
        int[] callCount = {0};
        ChatModel firstOkThenThrows = req -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return response(interpretationJson, "stop");
            }
            throw new ModelException("HTTP 500: internal error", 500);
        };
        cmd.plannerForTest = firstOkThenThrows;

        Run run = explain(cmd, "--workspace", ws.toString(), "what", "is", "lib-core");

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("answer unavailable: model error: HTTP 500: internal error")
                .contains("the facts below are complete")
                .contains("## Evidence")
                .contains("### Interpretation");
        assertThat(callCount[0]).isEqualTo(2);
    }
}
