package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicJanitorTest {
    @TempDir Path ws;

    @Test
    void deletesTopicsWithNoRolesKeepsReferencedOnes() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r','/w/r','SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','SERVICE')");
                h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('live.topic','LITERAL')");
                h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orphan.topic','LITERAL')");
                h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1, 1, 'PRODUCER')");
            });
            int cleaned = TopicJanitor.clean(db.jdbi());
            assertThat(cleaned).isEqualTo(1);
            List<String> remaining = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT name FROM kafka_topic").mapTo(String.class).list());
            assertThat(remaining).containsExactly("live.topic");
        }
    }
}
