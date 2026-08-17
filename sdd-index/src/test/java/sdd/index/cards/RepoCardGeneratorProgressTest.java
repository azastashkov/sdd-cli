package sdd.index.cards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.index.testing.RecordingProgress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RepoCardGenerator}'s progress reporting: a fresh card counts as work (a {@code
 * start}/{@code finish} pair), a cache hit does not (design doc / Task 2 scope — {@code
 * RepoCardGenerator.java:62-65} is the cache-hit branch).
 */
class RepoCardGeneratorProgressTest {
    @TempDir Path ws;
    private Database db;

    private static ChatResponse ok(String cardLine) {
        return new ChatResponse(ChatMessage.assistant(
                "{\"card_line\": \"" + cardLine + "\", \"card_md\": \"## Purpose\\nEvidenced.\"}"),
                "stop", new Usage(100, 50));
    }

    @BeforeEach
    void seed() throws Exception {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders', '"
                    + ws.resolve("svc-orders") + "', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
        });
        Files.createDirectories(ws.resolve("svc-orders"));
    }

    @Test
    void freshCardEmitsStartFinishCachedCardOnASecondRunDoesNot() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(ok("Order service.")));
        RecordingProgress first = new RecordingProgress();

        RepoCardGenerator.CardResult firstResult =
                RepoCardGenerator.generate(db.jdbi(), ws, model, "qwen", first);

        assertThat(firstResult.generated()).isEqualTo(1);
        assertThat(first.events()).containsExactly(
                "phase:cards:1", "start:svc-orders", "finish:svc-orders");

        // second run, unchanged inputs: cached, no model call (empty script would throw if used)
        ScriptedChatModel silent = new ScriptedChatModel(List.of());
        RecordingProgress second = new RecordingProgress();

        RepoCardGenerator.CardResult secondResult =
                RepoCardGenerator.generate(db.jdbi(), ws, silent, "qwen", second);

        assertThat(secondResult.cached()).isEqualTo(1);
        assertThat(second.events()).containsExactly("phase:cards:1"); // cache hit: not shown as work
    }

    @Test
    void defaultFourArgOverloadBehavesIdenticallyToPassingNoOpProgressExplicitly() {
        ScriptedChatModel modelA = new ScriptedChatModel(List.of(ok("Order service.")));
        ScriptedChatModel modelB = new ScriptedChatModel(List.of(ok("Order service.")));

        RepoCardGenerator.CardResult viaDefaultOverload =
                RepoCardGenerator.generate(db.jdbi(), ws, modelA, "qwen");
        // reset the cache so the second call does real work too, under an explicit no-op Progress
        db.jdbi().useHandle(h -> h.execute("DELETE FROM repo_card"));
        RepoCardGenerator.CardResult viaExplicitNoOp =
                RepoCardGenerator.generate(db.jdbi(), ws, modelB, "qwen", sdd.core.progress.Progress.noOp());

        assertThat(viaDefaultOverload).isEqualTo(viaExplicitNoOp);
    }
}
