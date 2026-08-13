package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.cli.implement.TestJars;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
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

/** e2e proof of the japicmp binary-compatibility gate (design line 62) through the real CLI:
 *  a provider whose actual jar diverges from its contract fails before any consumer starts. */
class ImplementCommandContractTest {
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

    @Test
    void binaryIncompatibleDriftFailsTheProviderBeforeConsumersStart() throws Exception {
        Path jars = Files.createDirectories(ws.resolve("prebuilt"));
        Path baselineJar = TestJars.jar(jars, "base.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path brokenJar = TestJars.jar(jars, "broken.jar", "Api",
                "public class Api { public long f(int x) { return x; } }");
        // First assemble (baseline, tree at base) copies base.jar; every later assemble copies broken.jar.
        // NOTE: escalation would re-run startBranch+clean, deleting .baseline-done — these scripts stay
        // on attempt 1 (done -> verify exit 0)
        FixtureRepo lib = repo("lib",
                "case \"$*\" in *assemble*) mkdir -p build/libs; "
                        + "if [ -f .baseline-done ]; then cp " + brokenJar + " build/libs/lib-1.0.jar; "
                        + "else cp " + baselineJar + " build/libs/lib-1.0.jar; touch .baseline-done; fi; exit 0 ;; "
                        + "*) exit 0 ;; esac");
        FixtureRepo svc = repo("svc", "exit 0");
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
                title: Contract
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
                  "edges":[{"from_repo":"svc","to_repo":"lib","mode":"SNAPSHOT","mechanism":"NONE"}],
                  "contracts":[{"id":"c1","kind":"java-api","provider":"lib","consumers":["svc"],
                    "body":"Api.f(int): int","compat":"binary-compatible"}],
                  "steps":[
                    {"repo":"lib","covers":["R1"],"version_action":"minor","provides":["c1"],"consumes":[],"files":[],"verification":[],"sub_spec":"x"},
                    {"repo":"svc","covers":["R1"],"version_action":"patch","provides":[],"consumes":["c1"],"files":[],"verification":[],"sub_spec":"x"}] }
                """.formatted(specSha, lib.headSha(), svc.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        // lib's single "done" is the only call the japicmp-failing run consumes — svc never starts.
        cmd.coderForTest = new ScriptedChatModel(List.of(done()));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(2);
        assertThat(out.toString()).contains("lib: FAILED").contains("binary-incompatible")
                .contains("svc: SKIPPED_UPSTREAM_FAILED");
    }
}
