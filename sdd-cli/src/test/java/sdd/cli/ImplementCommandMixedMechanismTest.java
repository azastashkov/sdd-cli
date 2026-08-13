package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * e2e proof that a single consumer composes BOTH substitution mechanisms on one Gradle invocation:
 * {@code --include-build} for an INCLUDE_BUILD provider and {@code --init-script} for a MAVEN_LOCAL
 * provider (design line 61). lib and legacy share no plan edge, so Scheduler.levels batches them into
 * one layer and the orchestrator runs them CONCURRENTLY — the coder double must be thread-safe.
 */
class ImplementCommandMixedMechanismTest {
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
    void oneConsumerComposesIncludeBuildAndInitScriptAcrossTwoProviders() throws Exception {
        FixtureRepo lib = repo("lib", "exit 0");   // INCLUDE_BUILD provider, no publish
        FixtureRepo legacy = repo("legacy",
                "case \"$*\" in *publishToMavenLocal*) echo \"$*\" > publish-args; exit 0 ;; *) exit 0 ;; esac");
        // svc's verification passes ONLY when BOTH flags composed on one invocation
        FixtureRepo svc = repo("svc",
                "case \"$*\" in *--include-build*--init-script*|*--init-script*--include-build*) exit 0 ;; *) exit 1 ;; esac");
        svc.file("build.gradle", "dependencies {\n    implementation \"com.acme:legacy:2.0.0\"\n}\n")
                .commit("add build.gradle");   // committed before the SHA is recorded below

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('legacy', ?, 'LIBRARY')", legacy.path().toString());
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', ?, 'SERVICE')", svc.path().toString());
                // insertion order: lib -> repo id 1, legacy -> repo id 2, svc -> repo id 3
                h.execute("INSERT INTO module(repo_id, gradle_path, grp, name, version, kind) "
                        + "VALUES (2, ':', 'com.acme', 'legacy', '2.0.0', 'LIBRARY')");   // legacy root module -> module id 1
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3, ':', 'SERVICE')");   // svc root module -> module id 2
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2, 'com.acme', 'legacy', 'compileClasspath', '2.0.0', 'DIRECT', 'PINNED', 1, 1)");
                        // from = svc's module (id 2), to = legacy's module (id 1) -> DeclaredDeps.between("svc","legacy")
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
                title: Mixed
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
                    {"name":"lib","role":"seed","annotation":"SEED","version_action":"none","base_sha":"%s"},
                    {"name":"legacy","role":"seed","annotation":"SEED","version_action":"patch","base_sha":"%s"},
                    {"name":"svc","role":"dependent","annotation":"CODE_CHANGE_LIKELY","version_action":"none","base_sha":"%s"}],
                  "order":[["lib"],["legacy"],["svc"]],
                  "edges":[
                    {"from_repo":"svc","to_repo":"lib","mode":"COMPOSITE","mechanism":"INCLUDE_BUILD"},
                    {"from_repo":"svc","to_repo":"legacy","mode":"PINNED","mechanism":"MAVEN_LOCAL"}],
                  "contracts":[],
                  "steps":[
                    {"repo":"lib","covers":["R1"],"version_action":"none","provides":[],"consumes":[],"files":[],"verification":[],"sub_spec":"x"},
                    {"repo":"legacy","covers":["R1"],"version_action":"patch","provides":[],"consumes":[],"files":[],"verification":[],"sub_spec":"x"},
                    {"repo":"svc","covers":["R1"],"version_action":"none","provides":[],"consumes":[],"files":[],"verification":[],"sub_spec":"x"}] }
                """.formatted(specSha, lib.headSha(), legacy.headSha(), svc.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        // CRITICAL: lib and legacy share no edge, so Scheduler.levels batches them into ONE layer and
        // they run CONCURRENTLY. A shared ScriptedChatModel (unsynchronized ArrayDeque) would race —
        // use a stateless lambda: every call returns a fresh done(), order-independent and thread-safe.
        cmd.coderForTest = req -> done();

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("lib: SUCCEEDED")
                .contains("legacy: SUCCEEDED")
                .contains("svc: SUCCEEDED");
        String publishArgs = Files.readString(legacy.path().resolve("publish-args"));
        assertThat(publishArgs).contains("-Pversion=2.0.1");
        assertThat(Files.readString(svc.path().resolve("build.gradle")))
                .contains("com.acme:legacy:2.0.1");
        // svc's stub having passed proves both flags composed on one invocation.
    }
}
