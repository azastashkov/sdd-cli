package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
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
    void anUnknownModelKeyIsRefusedBeforeAnythingIsRead() throws Exception {
        Path spec = setUpEstate();
        ExploreCommand cmd = new ExploreCommand();

        Run run = explore(cmd, "--workspace", ws.toString(), "--model", "nope", spec.toString());

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.out()).contains("no models entry named 'nope'");
    }
}
