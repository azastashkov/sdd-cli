package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementCommandResumeTest {
    @TempDir Path ws;

    private FixtureRepo repo(String name, String gradlewBody) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + gradlewBody + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        return repo.commit("base");
    }

    private static ChatResponse done() {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall("d", "done", "{\"result\":\"success\",\"summary\":\"ok\"}")),
                null), "tool_calls", new Usage(10, 5));
    }

    private void writeFixture(FixtureRepo lib, FixtureRepo svc) throws Exception {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', ?, 'SERVICE')", svc.path().toString());
            });
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-9
                title: Prop
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: x

                ## Acceptance Criteria
                - A1: x
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-9","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[
                    {"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"},
                    {"name":"svc","role":"dependent","annotation":"CODE_CHANGE_LIKELY","version_action":"patch","base_sha":"%s"}],
                  "order":[["lib"],["svc"]],
                  "edges":[{"from_repo":"svc","to_repo":"lib","mode":"COMPOSITE","mechanism":"NONE"}],
                  "contracts":[],
                  "steps":[
                    {"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],"files":[],"verification":[],"sub_spec":"x"},
                    {"repo":"svc","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],"files":[],"verification":[],"sub_spec":"x"}] }
                """.formatted(specSha, lib.headSha(), svc.headSha()));
    }

    @Test
    void aPausedRunResumesToCompletion() throws Exception {
        FixtureRepo lib = repo("lib", "exit 0");
        FixtureRepo svc = repo("svc", "exit 0");
        writeFixture(lib, svc);

        ImplementCommand first = new ImplementCommand();
        first.coderForTest = req -> {
            throw new ModelException("transport error: Connection refused", new java.io.IOException("x"));
        };
        CommandLine firstCli = new CommandLine(first);
        firstCli.setOut(new PrintWriter(new StringWriter()));
        int firstExit = firstCli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());
        assertThat(firstExit).isEqualTo(3);

        ImplementCommand second = new ImplementCommand();
        second.coderForTest = new ScriptedChatModel(List.of(done(), done()));   // lib done, svc done
        StringWriter out = new StringWriter();
        CommandLine secondCli = new CommandLine(second);
        secondCli.setOut(new PrintWriter(out));
        int secondExit = secondCli.execute("--workspace", ws.toString(), "--resume",
                ws.resolve("s.plan.json").toString());

        assertThat(secondExit).isEqualTo(0);
        assertThat(out.toString()).contains("lib: SUCCEEDED").contains("svc: SUCCEEDED");
    }

    @Test
    void aFreshRerunOverAnExistingRunRefusesAndPointsAtResume() throws Exception {
        FixtureRepo lib = repo("lib", "exit 0");
        FixtureRepo svc = repo("svc", "exit 0");
        writeFixture(lib, svc);

        ImplementCommand first = new ImplementCommand();
        first.coderForTest = new ScriptedChatModel(List.of(done(), done()));   // lib done, svc done
        CommandLine firstCli = new CommandLine(first);
        firstCli.setOut(new PrintWriter(new StringWriter()));
        int firstExit = firstCli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());
        assertThat(firstExit).isEqualTo(0);

        ImplementCommand second = new ImplementCommand();
        second.coderForTest = new ScriptedChatModel(List.of(done(), done()));
        StringWriter errOut = new StringWriter();
        CommandLine secondCli = new CommandLine(second);
        secondCli.setOut(new PrintWriter(new StringWriter()));
        secondCli.setErr(new PrintWriter(errOut));
        int secondExit = secondCli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(secondExit).isEqualTo(4);
        assertThat(errOut.toString()).contains("--resume");
    }
}
