package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.llm.ChatModel;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecRenderer;
import sdd.plan.spec.Touchpoint;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExploreCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run explore(ExploreCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private static final String SPEC = """
            ---
            id: SPEC-9
            title: Retire the tier cache
            owner: ana
            status: draft
            ---

            ## Goal
            Stop using tier.lvc.map.

            ## Requirements
            - R1: Nothing reads tier.lvc.map after this change.

            ## Acceptance Criteria
            - A1: The key is gone.
            """;

    private static ChatResponse call(String id, String tool, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, tool, args)), null), "tool_calls", new Usage(10, 5));
    }

    /** A workspace with one indexed repo containing the line the explorer will cite. */
    private Path setUpEstate() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """);
        Path src = Files.createDirectories(ws.resolve("payments-api/src/main/java/com/acme"));
        Files.writeString(src.resolve("Publisher.java"), """
                package com.acme;
                public class Publisher {
                    static final String KEY = "tier.lvc.map";
                }
                """);
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute("INSERT INTO repo(name, path, kind) VALUES (?,?,?)",
                    "payments-api", ws.resolve("payments-api").toString(), "SERVICE"));
        }
        Path spec = ws.resolve("SPEC-9.md");
        Files.writeString(spec, SPEC);
        return spec;
    }

    /**
     * The same estate, but as a real checkout with two commits — what --since needs.
     *
     * @return the spec path; the regression landed in the SECOND commit
     */
    private Path setUpGitEstate() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """);
        sdd.core.testing.FixtureRepo repo = sdd.core.testing.FixtureRepo.in(ws, "payments-api")
                .file("src/main/java/com/acme/Publisher.java", """
                        package com.acme;
                        public class Publisher {
                        }
                        """)
                .commit("release 7", java.time.Instant.parse("2026-01-01T00:00:00Z"));
        good = repo.headSha();
        repo.file("src/main/java/com/acme/Publisher.java", """
                        package com.acme;
                        public class Publisher {
                            static final String KEY = "tier.lvc.map";
                        }
                        """)
                .commit("cache the tier map", java.time.Instant.parse("2026-01-08T00:00:00Z"));
        try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute("INSERT INTO repo(name, path, kind) VALUES (?,?,?)",
                    "payments-api", ws.resolve("payments-api").toString(), "SERVICE"));
        }
        Path spec = ws.resolve("SPEC-9.md");
        Files.writeString(spec, SPEC);
        return spec;
    }

    private String good;

    @Test
    void sinceSeedsTheSurveyWithWhatChangedAndOffersTheHistoryTool() throws Exception {
        Path spec = setUpGitEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "git_history", "{\"repo\":\"payments-api\",\"op\":\"diff\"}"),
                call("2", "read_file",
                        "{\"path\":\"payments-api/src/main/java/com/acme/Publisher.java\"}"),
                call("3", "record_finding", """
                        {"claim":"the tier cache landed in the second commit",\
                        "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"found it\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), "--since", good, spec.toString());

        assertThat(run.out()).isNotNull();
        assertThat(run.exitCode()).describedAs(run.out()).isZero();
        assertThat(run.out()).contains("history over 1 repo");
        // The tool reached git, and the model then cited the CURRENT file rather than the diff.
        assertThat(run.out()).contains("git_history payments-api diff");
        assertThat(SpecParser.parse(Files.readString(spec)).evidence())
                .containsExactly("the tier cache landed in the second commit"
                        + " — payments-api/src/main/java/com/acme/Publisher.java:3");
    }

    /** The deterministic half: what changed is computed, not discovered. */
    @Test
    void theResolvedChangeListReachesTheModelWithTheTask() throws Exception {
        Path spec = setUpGitEstate();
        ExploreCommand cmd = new ExploreCommand();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"nothing to do\"}")));
        cmd.explorerForTest = model;

        explore(cmd, "--workspace", ws.toString(), "--since", good, spec.toString());

        String prompt = model.requests().get(0).messages().stream()
                .map(ChatMessage::content).filter(java.util.Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(prompt).contains("What changed in the range under investigation")
                .contains("src/main/java/com/acme/Publisher.java")
                // resolved shas, never the ref the human typed -- HEAD~1 means something else
                // tomorrow, and a survey that quotes it back is not reproducible
                .contains(good.substring(0, 8));
    }

    @Test
    void withoutSinceNothingAboutTheRunChanges() throws Exception {
        Path spec = setUpGitEstate();
        ExploreCommand cmd = new ExploreCommand();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"nothing to do\"}")));
        cmd.explorerForTest = model;

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).doesNotContain("history over");
        assertThat(model.requests().get(0).tools()).extracting(sdd.core.llm.ToolSpec::name)
                .doesNotContain("git_history");
    }

    /** An operator typo must not become a blocking claim about the estate. */
    @Test
    void anUnresolvableRefWarnsAndTheSurveyStillRuns() throws Exception {
        Path spec = setUpGitEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"nothing to do\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), "--since", "v99", spec.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("warn: --since").contains("ref not found");
        assertThat(run.out()).doesNotContain("history over");
    }

    @Test
    void findingsAndTouchpointsLandInTheSpecAndItStillRoundTrips() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "search_code", "{\"regex\":\"tier\\\\.lvc\\\\.map\"}"),
                call("2", "record_finding", """
                        {"claim":"tier.lvc.map is a Redis key written by Publisher",\
                        "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""),
                call("3", "propose_touchpoint", "{\"kind\":\"repo\",\"value\":\"payments-api\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"one key, one repo\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.exitCode()).isZero();
        NormalizedSpec updated = SpecParser.parse(Files.readString(spec));
        assertThat(updated.evidence()).containsExactly(
                "tier.lvc.map is a Redis key written by Publisher"
                        + " — payments-api/src/main/java/com/acme/Publisher.java:3");
        assertThat(updated.touchpoints())
                .containsExactly(new Touchpoint(Touchpoint.Kind.REPO, "payments-api"));
        // The spec-round-trip law still holds for the file we just handed a human.
        assertThat(SpecParser.parse(SpecRenderer.render(updated))).isEqualTo(updated);
        // The verbatim line goes to the console, where the reviewer checks it — not into the spec,
        // which stays readable prose.
        assertThat(run.out()).contains("static final String KEY = \"tier.lvc.map\";");
        assertThat(run.out()).contains("sdd plan");
    }

    @Test
    void everythingTheHumanWroteSurvivesAndASecondRunDoesNotDuplicate() throws Exception {
        Path spec = setUpEstate();
        List<ChatResponse> script = List.of(
                call("1", "search_code", "{\"regex\":\"tier\\\\.lvc\\\\.map\"}"),
                call("2", "record_finding", """
                        {"claim":"tier.lvc.map is a Redis key written by Publisher",\
                        "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""),
                call("3", "propose_touchpoint", "{\"kind\":\"repo\",\"value\":\"payments-api\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"done\"}"));

        ExploreCommand first = new ExploreCommand();
        first.explorerForTest = new ScriptedChatModel(script);
        explore(first, "--workspace", ws.toString(), spec.toString());
        ExploreCommand second = new ExploreCommand();
        second.explorerForTest = new ScriptedChatModel(script);
        explore(second, "--workspace", ws.toString(), spec.toString());

        NormalizedSpec updated = SpecParser.parse(Files.readString(spec));
        assertThat(updated.evidence()).hasSize(1);
        assertThat(updated.touchpoints()).hasSize(1);
        assertThat(updated.requirements()).hasSize(1);   // the human's document is untouched
        assertThat(updated.goal()).isEqualTo("Stop using tier.lvc.map.");
    }

    @Test
    void anEarlyEndingRunStillWritesWhatItFoundAndSaysItStoppedEarly() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        // Records one finding, then repeats the same call until the wedge detector fires — the
        // notebook is in the toolbox, so a non-DONE terminal state still yields a proposal.
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "read_file",
                        "{\"path\":\"payments-api/src/main/java/com/acme/Publisher.java\"}"),
                call("2", "record_finding", """
                        {"claim":"the key is declared here",\
                        "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""),
                call("3", "list_repos", "{}"),
                call("4", "list_repos", "{}"),
                call("5", "list_repos", "{}")));

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.exitCode()).isEqualTo(2);   // not DONE, but not a failure either
        NormalizedSpec updated = SpecParser.parse(Files.readString(spec));
        assertThat(updated.evidence()).hasSize(1);
        assertThat(updated.openQuestions()).anySatisfy(q ->
                assertThat(q.text()).contains("ended early").contains("WEDGED"));
    }

    @Test
    void anEmptySurveyLeavesTheSpecAlone() throws Exception {
        Path spec = setUpEstate();
        String before = Files.readString(spec);
        ExploreCommand cmd = new ExploreCommand();
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "list_repos", "{}"),
                call("2", "done", "{\"result\":\"blocked\",\"summary\":\"cannot place the task\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("nothing recorded — the spec is unchanged");
        assertThat(Files.readString(spec)).isEqualTo(before);
    }

    @Test
    void singleToolModeDrivesTheWholeRunThroughOneDeclaration() throws Exception {
        Path spec = setUpEstate();
        Files.writeString(ws.resolve("sdd.yml"), Files.readString(ws.resolve("sdd.yml"))
                + "explore:\n  single_tool: true\n");
        ExploreCommand cmd = new ExploreCommand();
        // Every call arrives as the multiplexed `sdd` tool — including done, which AgentLoop
        // intercepts by NAME. If routing were not wired into the loop this run would never
        // finish: done would be dispatched, which is a programming error, not a completion.
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "sdd", "{\"action\":\"search_code\",\"regex\":\"tier\\\\.lvc\\\\.map\"}"),
                call("2", "sdd", """
                        {"action":"record_finding",\
                        "claim":"tier.lvc.map is a Redis key written by Publisher",\
                        "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}"""),
                call("3", "sdd", "{\"action\":\"propose_touchpoint\",\"kind\":\"repo\",\"value\":\"payments-api\"}"),
                call("4", "sdd", "{\"action\":\"done\",\"result\":\"success\",\"summary\":\"one key\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("single-tool mode");
        NormalizedSpec updated = SpecParser.parse(Files.readString(spec));
        assertThat(updated.evidence()).hasSize(1);
        assertThat(updated.touchpoints())
                .containsExactly(new Touchpoint(Touchpoint.Kind.REPO, "payments-api"));
    }

    @Test
    void singleToolModeAdvertisesOneDeclarationToTheEndpoint() throws Exception {
        Path spec = setUpEstate();
        Files.writeString(ws.resolve("sdd.yml"), Files.readString(ws.resolve("sdd.yml"))
                + "explore:\n  single_tool: true\n");
        ExploreCommand cmd = new ExploreCommand();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "sdd", "{\"action\":\"done\",\"result\":\"blocked\",\"summary\":\"no\"}")));
        cmd.explorerForTest = model;

        explore(cmd, "--workspace", ws.toString(), spec.toString());

        // The whole point: what actually went on the wire is one declaration, not nine.
        assertThat(model.requests()).first().satisfies(req ->
                assertThat(req.tools()).singleElement().satisfies(t ->
                        assertThat(t.name()).isEqualTo("sdd")));
    }

    @Test
    void everyRunLeavesATranscriptAndALiveTraceOfWhatItDid() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "search_code", "{\"regex\":\"tier\\\\.lvc\\\\.map\"}"),
                call("2", "read_file",
                        "{\"path\":\"payments-api/src/main/java/com/acme/Publisher.java\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"done\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        // Live: each call is reported as what it DID, not as an opaque tool name.
        assertThat(run.out()).contains("search_code tier").contains("read_file payments-api/");
        // Persisted: the per-turn record AgentLoop already builds, which explore used to discard.
        Path transcript = ws.resolve(".sdd/explore/SPEC-9/transcript.jsonl");
        assertThat(transcript).exists();
        List<String> turns = Files.readAllLines(transcript);
        assertThat(turns).hasSize(3);
        assertThat(turns.get(0)).contains("\"turn\":1").contains("search_code")
                .contains("prompt_tokens").contains("tool_results");
        assertThat(run.out()).contains("transcript: ");
    }

    @Test
    void aRunThatStopsImmediatelyStillExplainsItselfInTheTranscript() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        // One request, no tool call, then the endpoint stops answering usefully — the case where
        // the proxy log shows a single request and the console previously said almost nothing.
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("I cannot help with that."), "stop",
                        new Usage(10, 5)),
                new ChatResponse(ChatMessage.assistant("Still cannot."), "stop", new Usage(10, 5)),
                new ChatResponse(ChatMessage.assistant("No."), "stop", new Usage(10, 5))));

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("MALFORMED").contains("no tool call");
        List<String> turns = Files.readAllLines(ws.resolve(".sdd/explore/SPEC-9/transcript.jsonl"));
        assertThat(turns).hasSize(3);
        // What the model actually said is the thing that distinguishes a refusal from a
        // capability gap, and it is in the file.
        assertThat(turns.get(0)).contains("I cannot help with that.").contains("\"finish\":\"stop\"");
        assertThat(Files.readString(ws.resolve(".sdd/explore/SPEC-9/events.txt")))
                .contains("no tool call");
    }

    @Test
    void anEndpointThatDiesMidRunStillHandsBackWhatWasFoundAndWhyItStopped() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        // One good turn, then the endpoint fails for good. This is the shape that previously
        // threw everything away: no notebook, no transcript, one line of error on stderr.
        cmd.explorerForTest = new ChatModel() {
            private int calls;

            @Override
            public sdd.core.llm.ChatResponse complete(sdd.core.llm.ChatRequest request) {
                if (++calls == 1) {
                    return call("1", "search_code", "{\"regex\":\"tier\\\\.lvc\\\\.map\"}");
                }
                throw new sdd.core.llm.ModelException("HTTP 500: {\"status\":500}", 500);
            }
        };

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.out()).contains("ENDPOINT FAILED after 1 completed turns")
                .contains("HTTP 500");
        // The turn that DID happen is on disk, which is the whole point.
        List<String> turns = Files.readAllLines(ws.resolve(".sdd/explore/SPEC-9/transcript.jsonl"));
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0)).contains("search_code");
    }

    @Test
    void findingsSurviveAnEndpointFailureAndTheSpecSaysTheSurveyIsIncomplete() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.explorerForTest = new ChatModel() {
            private int calls;

            @Override
            public sdd.core.llm.ChatResponse complete(sdd.core.llm.ChatRequest request) {
                return switch (++calls) {
                    case 1 -> call("1", "search_code", "{\"regex\":\"tier\\\\.lvc\\\\.map\"}");
                    case 2 -> call("2", "record_finding", """
                            {"claim":"tier.lvc.map is written by Publisher",\
                            "citation":"payments-api/src/main/java/com/acme/Publisher.java:3"}""");
                    default -> throw new sdd.core.llm.ModelException("transport error: reset", 0);
                };
            }
        };

        explore(cmd, "--workspace", ws.toString(), spec.toString());

        NormalizedSpec updated = SpecParser.parse(Files.readString(spec));
        assertThat(updated.evidence()).hasSize(1);
        assertThat(updated.openQuestions()).anySatisfy(q ->
                assertThat(q.text()).contains("ENDPOINT FAILED").contains("may be incomplete"));
    }

    @Test
    void anUnknownModelKeyIsRefusedBeforeAnythingIsRead() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();

        Run run = explore(cmd, "--workspace", ws.toString(), "--model", "nope", spec.toString());

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.out()).contains("no models entry named 'nope'");
    }

    @Test
    void aClarificationLandsInTheSpecAndTheSpecStillRoundTrips() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.in = new java.io.BufferedReader(new java.io.StringReader("only the EU tenant\n"));
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "ask_user_question", "{\"question\":\"Which tenant is affected?\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"asked\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), "--interactive", spec.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("? Which tenant is affected?");
        String written = Files.readString(spec);
        assertThat(written).contains("Which tenant is affected? — resolved: only the EU tenant");
        // The round-trip law: a spec sdd wrote must parse back to what it rendered, or the next
        // command in the chain fails on a file the human never touched.
        NormalizedSpec updated = SpecParser.parse(written);
        assertThat(SpecParser.parse(sdd.plan.spec.SpecRenderer.render(updated))).isEqualTo(updated);
    }

    @Test
    void withoutInteractiveTheToolIsNotOfferedAndTheSurveyStillFinishes() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "ask_user_question", "{\"question\":\"Which tenant?\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"no human\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), spec.toString());

        // Refused, not fatal: the run completes and the model is told what to do instead.
        assertThat(run.exitCode()).isZero();
        assertThat(Files.readString(spec)).doesNotContain("resolved:");
    }

    @Test
    void aClosedStdinEndsTheQuestioningWithoutHangingOrFailingTheRun() throws Exception {
        // The CI shape: --interactive was passed but nothing is there to answer. It must degrade,
        // never block, and never refuse to start.
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();
        cmd.in = new java.io.BufferedReader(new java.io.StringReader(""));
        cmd.explorerForTest = new ScriptedChatModel(List.of(
                call("1", "ask_user_question", "{\"question\":\"First?\"}"),
                call("2", "ask_user_question", "{\"question\":\"Second?\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"nobody home\"}")));

        Run run = explore(cmd, "--workspace", ws.toString(), "--interactive", spec.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(Files.readString(spec)).doesNotContain("resolved:");
    }
}
