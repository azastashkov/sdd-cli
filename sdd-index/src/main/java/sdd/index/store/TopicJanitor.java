package sdd.index.store;

import org.jdbi.v3.core.Jdbi;

public final class TopicJanitor {
    private TopicJanitor() {}

    public static int clean(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createUpdate(
                "DELETE FROM kafka_topic WHERE NOT EXISTS "
                        + "(SELECT 1 FROM kafka_role r WHERE r.topic_id = kafka_topic.id)")
                .execute());
    }
}
