package sdd.agent.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoStepPropagationTest {
    @TempDir Path ws;
    private Database db;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc','/w/svc','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','SERVICE')");
        });
        repoRoot = Files.createDirectories(ws.resolve("svc"));
        Files.writeString(repoRoot.resolve("A.java"), "class A {}\n");
        Path g = repoRoot.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\necho \"$@\"\nexit 0\n");   // green + echoes args
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void gradleExtraArgsReachTheVerifyGate() {
        RepoStep step = new RepoStep("svc", repoRoot, "noop", List.of(), List.of(),
                List.of(), List.of(), List.of());
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StepOutcome outcome = new RepoStepRunner(db.jdbi()).run(step, model, "qwen",
                RunnerSettings.defaults(null, List.of("--include-build", "/w/lib")));

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);
        assertThat(outcome.verificationOutput()).contains("--include-build").contains("/w/lib");
    }
}
