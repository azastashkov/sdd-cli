package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session parse(String source) throws Exception {
        Path f = repo.resolve("src/main/java/com/acme/K.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void listenerTopicsAndTemplateSendsAreExtracted() throws Exception {
        var session = parse("""
                package com.acme;
                import org.springframework.kafka.annotation.KafkaListener;
                public class K {
                    private static final String OUT_TOPIC = "orders.v1.placed";
                    private Object kafkaTemplate;
                    @KafkaListener(topics = "${kafka.in-topic}", groupId = "orders")
                    public void onMessage(String payload) {}
                    public void publish(String event) {
                        ((org.springframework.kafka.core.KafkaTemplate) kafkaTemplate)
                                .send(OUT_TOPIC, event);
                    }
                }
                """);
        KafkaExtractor.KafkaResult result = KafkaExtractor.extract(
                session, Map.of("kafka.in-topic", "orders.v1.incoming"), List.of(), List.of());

        assertThat(result.streamDetected()).isFalse();
        assertThat(result.uses()).hasSize(2);
        assertThat(result.uses()).anySatisfy(u -> {
            assertThat(u.role()).isEqualTo("CONSUMER");
            assertThat(u.topic()).isEqualTo("orders.v1.incoming");
            assertThat(u.resolution()).isEqualTo("PROPERTY");
            assertThat(u.groupId()).isEqualTo("orders");
            assertThat(u.payloadType()).isEqualTo("String");
        });
        assertThat(result.uses()).anySatisfy(u -> {
            assertThat(u.role()).isEqualTo("PRODUCER");
            assertThat(u.topic()).isEqualTo("orders.v1.placed");
            assertThat(u.resolution()).isEqualTo("CONSTANT");
        });
    }

    @Test
    void topicPatternAndDynamicTopicsAreRecordedAsDynamic() throws Exception {
        var session = parse("""
                package com.acme;
                import org.springframework.kafka.annotation.KafkaListener;
                public class K {
                    @KafkaListener(topicPattern = "orders\\\\..*")
                    public void onAny(String payload) {}
                }
                """);
        KafkaExtractor.KafkaResult result = KafkaExtractor.extract(session, Map.of(), List.of(), List.of());
        assertThat(result.uses()).singleElement().satisfies(u -> {
            assertThat(u.role()).isEqualTo("CONSUMER");
            assertThat(u.resolution()).isEqualTo("DYNAMIC");
            assertThat(u.topic()).contains("orders");
        });
    }

    @Test
    void classLevelKafkaListenerWithHandlersIsExtracted() throws Exception {
        var session = parse("""
                package com.acme;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.kafka.annotation.KafkaHandler;
                @KafkaListener(topics = "class.level.topic", groupId = "g1")
                public class K {
                    @KafkaHandler public void onA(String a) {}
                    @KafkaHandler public void onB(Integer b) {}
                }
                """);
        KafkaExtractor.KafkaResult result = KafkaExtractor.extract(session, Map.of(), List.of(), List.of());
        assertThat(result.uses()).singleElement().satisfies(u -> {
            assertThat(u.role()).isEqualTo("CONSUMER");
            assertThat(u.topic()).isEqualTo("class.level.topic");
            assertThat(u.groupId()).isEqualTo("g1");
            assertThat(u.payloadType()).isNull();
        });
    }

    @Test
    void streamDetectionByJarNameAndConfigKey() throws Exception {
        var session = parse("package com.acme;\npublic class K {}\n");
        assertThat(KafkaExtractor.extract(session, Map.of(),
                List.of(Path.of("/cache/spring-cloud-stream-4.1.0.jar")), List.of())
                .streamDetected()).isTrue();
        assertThat(KafkaExtractor.extract(session, Map.of(), List.of(),
                List.of("spring.cloud.stream.bindings.input.destination"))
                .streamDetected()).isTrue();
    }
}
