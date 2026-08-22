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
    void unresolvedCoderApiKeyFailsBeforeWritingAnyRunState() throws Exception {
        // coder's api_key references an env var this test process never sets, and coderForTest is
        // deliberately left unset so the real credential path is exercised (a test seam bypasses
        // HttpChatModel entirely, which would hide this defect). Pre-fix, ConfigLoader.load
        // succeeds, and the run dir/lock/plan.json/spec.md/propagation.json are all written to
        // disk before the ladder-construction loop finally throws deep inside runPlan — a fresh
        // `sdd implement` against a workspace missing a credential must produce ZERO filesystem
        // side effects, not just eventually fail.
        FixtureRepo lib = repo("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen, api_key: "${SDD_LIVE_FIXES_TEST_UNSET_API_KEY}" }
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

        ImplementCommand cmd = new ImplementCommand();   // coderForTest left null on purpose
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(4);
        assertThat(err.toString()).contains("models.coder.api_key: environment variable "
                + "SDD_LIVE_FIXES_TEST_UNSET_API_KEY is not set");
        assertThat(ws.resolve(".sdd/runs/SPEC-101-v1")).doesNotExist();
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
    void sddYmlAgentTurnsCapsTheAttemptAndTheRunFailsWithBudget() throws Exception {
        // run.agent_turns: 3 means the AgentLoop must stop after 3 model calls — the coder script
        // below scripts a would-be 4th response (done) that must never be reached.
        FixtureRepo lib = repo("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                run:
                  agent_turns: 3
                  token_budget: 1
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
                        List.of(new ToolCall("1", "read_file", "{\"path\":\"A.java\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "read_file", "{\"path\":\"B.java\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("3", "read_file", "{\"path\":\"C.java\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("4", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(2);
        assertThat(out.toString()).contains("BUDGET");
    }

    @Test
    void sddYmlAgentTokensCapsTheAttemptAndTheRunFailsWithBudget() throws Exception {
        // run.agent_tokens: 1 means the AgentLoop's per-attempt AgentBudget.maxTokens is 1 — AgentLoop
        // checks the token budget at the TOP of the loop, after accumulating the previous turn's usage,
        // so the first call's Usage(10, 5) = 15 tokens (> 1) must already stop the second turn before the
        // would-be done response below is ever reached. token_budget: 1 also caps the run-wide budget so
        // Orchestrator does not escalate the BUDGET outcome to a second attempt (same as the agent_turns
        // test above) — escalation reuses this same scripted coder and would otherwise consume the
        // trailing done response and mask the failure.
        FixtureRepo lib = repo("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                run:
                  agent_tokens: 1
                  token_budget: 1
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
                        List.of(new ToolCall("1", "read_file", "{\"path\":\"A.java\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(2);
        assertThat(out.toString()).contains("BUDGET");
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

    @Test
    void aFourOhOneFromTheModelAbortsWithExitFourAndReleasesTheLock() throws Exception {
        // HTTP 401 (unauthorized) is a fatal config error, not a retriable context-exhaustion.
        // AgentLoop should not retry but throw immediately, causing the whole run to abort with
        // exit 4. The orchestrator's finally block must still release the lock.
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
            throw new sdd.core.llm.ModelException("HTTP 401: unauthorized", 401);
        };

        int exit = cli(cmd).execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(4);
        assertThat(ws.resolve(".sdd/runs/SPEC-101-v1/lock")).doesNotExist();   // finally released it
    }

    private static CommandLine cli(ImplementCommand cmd) {
        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        return cli;
    }

    /**
     * A plan approved from a change directory has no sibling spec file, and must still implement.
     *
     * <p>approve names plan.json from the SPEC ID, which need not match whatever the spec file on
     * disk is called and may correspond to no file at all — a real run failed with
     * {@code error: EFXWUI-14082.md} for exactly that reason. The change directory is
     * self-contained, so the spec comes from its estate.yaml, and the run directory still gets a
     * canonical spec.md so --resume and review never learn the difference.
     */
    @Test
    void aPlanWithNoSiblingSpecFileTakesItsSpecFromTheChangeDirectory() throws Exception {
        FixtureRepo lib = repo("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')",
                    lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        // No SPEC-101.md anywhere: the whole point.
        Path changeDir = ws.resolve("openspec/changes/spec-101-v1");
        Files.createDirectories(changeDir);
        Files.writeString(changeDir.resolve("estate.yaml"), """
                spec_id: SPEC-101
                plan_version: 1
                approved: true
                spec:
                  id: SPEC-101
                  title: Tiers
                  owner: me
                  status: approved
                  goal: g
                  background: ''
                  requirements:
                  - id: R1
                    text: Expose tierFor.
                  acceptance:
                  - id: A1
                    text: tierFor returns a tier.
                  constraints: []
                  touchpoints: []
                  evidence: []
                  out_of_scope: []
                  open_questions: []
                  attachments: []
                  sources: []
                """);
        Files.writeString(ws.resolve("SPEC-101.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"","plan_sha256":"",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(lib.headSha()));

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
        int exit = cli.execute("--workspace", ws.toString(),
                ws.resolve("SPEC-101.plan.json").toString());

        assertThat(exit).as("out=%s", out).isEqualTo(0);
        assertThat(Files.readString(lib.path().resolve("A.java"))).contains("int x;");
        // The snapshot is a real canonical spec, rendered from the tree — so resume and review
        // never learn that this plan had no spec file.
        assertThat(Files.readString(ws.resolve(".sdd/runs/SPEC-101-v1/spec.md")))
                .contains("id: SPEC-101").contains("- R1: Expose tierFor.");
    }

    /** Neither a sibling spec nor a change directory is a clear refusal, not a stack trace. */
    @Test
    void aPlanWithNoSpecAnywhereIsRefusedByName() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("orphan.plan.json"), """
                { "spec_id":"SPEC-404","plan_version":1,"spec_sha256":"","plan_sha256":"",
                  "repos":[],"order":[],"edges":[],"contracts":[],"steps":[] }
                """);

        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new ImplementCommand());
        cli.setErr(new PrintWriter(err));
        int exit = cli.execute("--workspace", ws.toString(),
                ws.resolve("orphan.plan.json").toString());

        assertThat(exit).isEqualTo(4);
        assertThat(err.toString()).contains("no spec for this plan").contains("orphan.md");
    }
}
