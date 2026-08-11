package sdd.index.cards;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepoCardGeneratorTest {
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
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders', '" + ws.resolve("svc-orders") + "', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1, 'C', 'get', 'GET', '/api/x', '/api/x')");
        });
        Files.createDirectories(ws.resolve("svc-orders"));
        Files.writeString(ws.resolve("svc-orders/README.md"), "# Orders service\nHandles orders.\n");
    }

    @Test
    void generatesPersistsAndCachesByInputHash() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(ok("Order service.")));
        RepoCardGenerator.CardResult first = RepoCardGenerator.generate(db.jdbi(), ws, model, "qwen");
        assertThat(first.generated()).isEqualTo(1);
        assertThat(first.failed()).isZero();

        Map<String, Object> card = db.jdbi().withHandle(h ->
                h.createQuery("SELECT card_line, model, input_hash FROM repo_card").mapToMap().one());
        assertThat(card).containsEntry("card_line", "Order service.").containsEntry("model", "qwen");
        assertThat((String) card.get("input_hash")).hasSize(64);

        // prompt content includes evidenced data
        assertThat(model.requests().get(0).messages().get(1).content())
                .contains("svc-orders").contains("GET /api/x").contains("# Orders service");

        // second run, unchanged inputs: cached, no model call (empty script would throw if called)
        ScriptedChatModel silent = new ScriptedChatModel(List.of());
        RepoCardGenerator.CardResult second = RepoCardGenerator.generate(db.jdbi(), ws, silent, "qwen");
        assertThat(second.cached()).isEqualTo(1);
        assertThat(second.generated()).isZero();
    }

    @Test
    void malformedResponseCountsFailedAndRunContinues() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('zzz-repo', '" + ws.resolve("zzz") + "', 'LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
        });
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("not json"), "stop", new Usage(1, 1)),
                ok("Second repo fine.")));
        RepoCardGenerator.CardResult result = RepoCardGenerator.generate(db.jdbi(), ws, model, "qwen");
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.generated()).isEqualTo(1);
    }

    @Test
    void consecutiveModelFailuresShortCircuit() {
        db.jdbi().useHandle(h -> {
            for (int i = 2; i <= 6; i++) {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r" + i + "', '/w/r" + i + "', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ", ':', 'LIBRARY')");
            }
        });
        ChatModelThrowingAlways broken = new ChatModelThrowingAlways();
        RepoCardGenerator.CardResult result = RepoCardGenerator.generate(db.jdbi(), ws, broken, "qwen");
        assertThat(result.failed()).isEqualTo(6);     // all 6 repos failed
        assertThat(broken.calls).isEqualTo(3);        // but only 3 live calls before short-circuit
    }

    private static final class ChatModelThrowingAlways implements sdd.core.llm.ChatModel {
        int calls;
        @Override
        public sdd.core.llm.ChatResponse complete(sdd.core.llm.ChatRequest req) {
            calls++;
            throw new ModelException("connection refused", 0);
        }
    }
}
