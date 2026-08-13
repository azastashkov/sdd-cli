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
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementCommandTest {
    @TempDir Path ws;

    private FixtureRepo repo(String name) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        // a real wrapper version so jdkMajorFor resolves
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        return repo.commit("base");
    }

    /** Same as {@link #repo(String)}, but the gradlew stub records every task it was invoked with. */
    private FixtureRepo repoWithTaskRecordingGradlew(String name) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\necho \"$1\" >> tasks-run\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        return repo.commit("base");
    }

    @Test
    void runsASingleRepoPlanToCompletion() throws Exception {
        FixtureRepo lib = repo("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-101
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, lib.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "apply_edit",
                                "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("lib").contains("SUCCEEDED");
        assertThat(Files.exists(ws.resolve(".sdd/runs/SPEC-101-v1/state.json"))).isTrue();
        assertThat(Files.readString(lib.path().resolve("A.java"))).contains("int x;");
        assertThat(ws.resolve(".sdd/runs/SPEC-101-v1/spec.md")).exists();
    }

    @Test
    void sddYmlVerificationExclusionsSkipTheGateAndSurfaceNotLocallyVerified() throws Exception {
        FixtureRepo libRepo = FixtureRepo.in(ws, "lib").file("A.java", "class A {}\n");
        Path g = libRepo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path props = libRepo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        FixtureRepo lib = libRepo.commit("base");

        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                verification_exclusions:
                  lib: [check]
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-101
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, lib.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "apply_edit",
                                "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        String runDirEvents = Files.readString(ws.resolve(".sdd/runs/SPEC-101-v1/lib/agent-events.jsonl"));
        assertThat(runDirEvents).contains("not locally verified");   // lib/agent-events.jsonl content
    }

    @Test
    void unknownOptionAbortsWithExitFour() {
        int exit = new CommandLine(new ImplementCommand()).execute("--no-such-flag");
        assertThat(exit).isEqualTo(4);
    }

    @Test
    void proseVerificationEntriesAreFilteredOutAndOnlyAllowlistedTasksRun() throws Exception {
        // The approved plan's verification list is dual-natured: 4B reads it as acceptance prose,
        // but the verify gate must run only entries that are actual gradle tasks. A prose entry mixed
        // in with a real task ("check") must be dropped from the gate, not passed to ./gradlew.
        FixtureRepo lib = repoWithTaskRecordingGradlew("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-101
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":["Run the tests properly","check"],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, lib.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "apply_edit",
                                "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        String tasksRun = Files.readString(lib.path().resolve("tasks-run"));
        assertThat(tasksRun).contains("check").doesNotContain("Run the tests");
        assertThat(out.toString()).contains(
                "verification entries not runnable as gradle tasks (kept as acceptance prose)")
                .contains("Run the tests properly");
    }

    @Test
    void allProseVerificationFallsBackToCheck() throws Exception {
        // A plan step whose verification list is entirely prose ("Do it well") still means "verify
        // normally" — the gate must fall back to `check`, not skip verification.
        FixtureRepo lib = repoWithTaskRecordingGradlew("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-101
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":["Do it well"],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, lib.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "apply_edit",
                                "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        String tasksRun = Files.readString(lib.path().resolve("tasks-run"));
        assertThat(tasksRun).contains("check");
    }

    @Test
    void aPersistentFourHundredFromTheModelExhaustsBothAttemptsAndReleasesTheLock() throws Exception {
        // Same fixture as runsASingleRepoPlanToCompletion, but the coder always throws a 400.
        // Smoke-fix hotfix (design line 71): AgentLoop now treats HTTP 400 as "endpoint rejected an
        // oversized request" — it evicts and retries once, and a second 400 becomes CONTEXT_EXHAUSTED
        // instead of a thrown ModelException. A coder that always 400s therefore no longer aborts the
        // whole run as a fatal config error (exit 4); it exhausts attempt 1, escalates, exhausts
        // attempt 2 too, and the run finishes PARTIAL (exit 2) — still releasing the lock.
        FixtureRepo lib = repo("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-101
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, lib.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = req -> {
            throw new sdd.core.llm.ModelException("HTTP 400: bad request", 400);
        };

        int exit = cli(cmd).execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(2);
        assertThat(ws.resolve(".sdd/runs/SPEC-101-v1/lock")).doesNotExist();   // finally released it
    }

    private static CommandLine cli(ImplementCommand cmd) {
        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        return cli;
    }
}
