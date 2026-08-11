package sdd.index.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringConfigPersistenceTest {
    @TempDir Path ws;
    private Database db;
    private long moduleId;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
            moduleId = h.createQuery("SELECT id FROM module").mapTo(Long.class).one();
        });
    }

    private static ConfigFileParser.ConfigEntry entry(String key, String value, String profile) {
        return new ConfigFileParser.ConfigEntry(key, value, profile, "src/main/resources/application.yml");
    }

    @Test
    void persistsEntriesAndModuleIdentity() {
        db.jdbi().useHandle(h -> SpringConfigPersistence.persistModuleConfig(h, moduleId, List.of(
                entry("spring.application.name", "order-service", null),
                entry("server.servlet.context-path", "/orders", null),
                entry("billing.base-url", "http://billing", null),
                entry("billing.base-url", "https://prod", "prod"))));

        Integer count = db.jdbi().withHandle(h ->
                h.createQuery("SELECT count(*) FROM config_property").mapTo(Integer.class).one());
        assertThat(count).isEqualTo(4);
        Map<String, Object> module = db.jdbi().withHandle(h ->
                h.createQuery("SELECT spring_app_name, context_path FROM module").mapToMap().one());
        assertThat(module.get("spring_app_name")).isEqualTo("order-service");
        assertThat(module.get("context_path")).isEqualTo("/orders");
    }

    @Test
    void repersistReplacesRowsAndProfileOnlyNameDoesNotSetIdentity() {
        db.jdbi().useHandle(h -> {
            SpringConfigPersistence.persistModuleConfig(h, moduleId, List.of(
                    entry("spring.application.name", "old-name", null)));
            SpringConfigPersistence.persistModuleConfig(h, moduleId, List.of(
                    entry("spring.application.name", "prod-only", "prod")));
        });
        Integer count = db.jdbi().withHandle(h ->
                h.createQuery("SELECT count(*) FROM config_property").mapTo(Integer.class).one());
        assertThat(count).isEqualTo(1);
        Map<String, Object> module = db.jdbi().withHandle(h ->
                h.createQuery("SELECT spring_app_name FROM module").mapToMap().one());
        assertThat(module.get("spring_app_name")).isNull();
    }

    @Test
    void defaultProfilePropsFiltersAndLastWins() {
        Map<String, String> props = SpringConfigPersistence.defaultProfileProps(List.of(
                entry("a", "1", null), entry("a", "2", null), entry("b", "x", "prod")));
        assertThat(props).containsEntry("a", "2").doesNotContainKey("b");
    }
}
