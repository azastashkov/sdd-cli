package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFileParserTest {
    @TempDir Path repo;

    private Path resources() throws Exception {
        Path dir = repo.resolve("src/main/resources");
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void flattensYamlWithProfilesFilenameAndDocumentBased() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("application.yml"), """
                spring:
                  application:
                    name: order-service
                server:
                  servlet:
                    context-path: /orders
                billing:
                  base-url: http://billing:8080
                  retries: 3
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                billing:
                  base-url: https://billing.prod
                """);
        Files.writeString(res.resolve("application-dev.yml"), "billing:\n  base-url: http://localhost\n");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);

        assertThat(r.issues()).isEmpty();
        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("spring.application.name");
            assertThat(e.value()).isEqualTo("order-service");
            assertThat(e.profile()).isNull();
            assertThat(e.sourceFile()).isEqualTo("src/main/resources/application.yml");
        });
        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("billing.base-url");
            assertThat(e.value()).isEqualTo("https://billing.prod");
            assertThat(e.profile()).isEqualTo("prod");
        });
        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("billing.base-url");
            assertThat(e.profile()).isEqualTo("dev");
        });
        assertThat(r.entries()).anySatisfy(e ->
                assertThat(e.key()).isEqualTo("billing.retries"));
        assertThat(r.entries()).noneSatisfy(e ->
                assertThat(e.key()).isEqualTo("spring.config.activate.on-profile"));
    }

    @Test
    void parsesPropertiesFilesAndLists() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("application.properties"), "kafka.topic=orders.v1\n");
        Files.writeString(res.resolve("bootstrap.yml"), "servers:\n  - a\n  - b\n");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);

        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("kafka.topic");
            assertThat(e.value()).isEqualTo("orders.v1");
        });
        assertThat(r.entries()).anySatisfy(e -> assertThat(e.key()).isEqualTo("servers[0]"));
        assertThat(r.entries()).anySatisfy(e -> assertThat(e.key()).isEqualTo("servers[1]"));
    }

    @Test
    void hyphenatedProfileInFilenameIsRecognised() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("application-us-east.yml"), "region:\n  id: us-east-1\n");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);

        assertThat(r.issues()).isEmpty();
        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("region.id");
            assertThat(e.value()).isEqualTo("us-east-1");
            assertThat(e.profile()).isEqualTo("us-east");
        });
    }

    @Test
    void unparseableYamlBecomesIssueNotException() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("application.yml"), "key: [unclosed\n  broken");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);

        assertThat(r.entries()).isEmpty();
        assertThat(r.issues()).hasSize(1);
        assertThat(r.issues().get(0)).contains("application.yml");
    }

    @Test
    void applicationWinsOverBootstrapInDefaultProfileProps() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("bootstrap.yml"), "shared.key: from-bootstrap\n");
        Files.writeString(res.resolve("application.yml"), "shared.key: from-application\n");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);
        Map<String, String> defaults = SpringConfigPersistence.defaultProfileProps(r.entries());

        assertThat(defaults).containsEntry("shared.key", "from-application");
    }

    @Test
    void missingResourcesDirYieldsEmptyResult() {
        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);
        assertThat(r.entries()).isEmpty();
        assertThat(r.issues()).isEmpty();
    }
}
