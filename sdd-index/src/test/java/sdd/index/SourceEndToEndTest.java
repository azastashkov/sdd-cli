package sdd.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.testing.FixtureRepo;
import sdd.index.gradle.GradleModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceEndToEndTest {
    @TempDir Path ws;

    private static GradleModel.Extract extractFor(Path repoDir, String name, String grp,
                                                  List<String> plugins,
                                                  List<GradleModel.DeclaredDep> deps) {
        return new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", name, grp, "1.0", repoDir, plugins, false, List.of(),
                Map.of("compileClasspath",
                        new GradleModel.DepConfig(deps, List.of(), List.of())))),
                List.of());
    }

    @Test
    void fullPipelinePopulatesTypesUsagesFileRefsAndFts() {
        FixtureRepo.in(ws, "lib-pricing")
                .file("src/main/java/com/acme/pricing/PriceCalculator.java", """
                        package com.acme.pricing;
                        import lombok.Getter;
                        @Getter
                        public class PriceCalculator {
                            private String currency;
                            public String quote(String req) { return req; }
                        }
                        """)
                .commit("init");
        FixtureRepo.in(ws, "svc-orders")
                .file("src/main/java/com/acme/orders/OrderService.java", """
                        package com.acme.orders;
                        import com.acme.pricing.PriceCalculator;
                        public class OrderService {
                            public OrderHelper helper() { return new OrderHelper(); }
                        }
                        """)
                .file("src/main/java/com/acme/orders/OrderHelper.java",
                        "package com.acme.orders;\npublic class OrderHelper {}\n")
                .file("src/main/resources/application.yml", """
                        spring:
                          application:
                            name: order-service
                        server:
                          servlet:
                            context-path: /orders
                        kafka:
                          in-topic: orders.v1.incoming
                        """)
                .file("src/main/java/com/acme/orders/OrderController.java", """
                        package com.acme.orders;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/orders")
                        public class OrderController {
                            @GetMapping("/{id}") public String get(@PathVariable String id) { return id; }
                        }
                        """)
                .file("src/main/java/com/acme/orders/BillingClient.java", """
                        package com.acme.orders;
                        import org.springframework.cloud.openfeign.FeignClient;
                        import org.springframework.web.bind.annotation.PostMapping;
                        @FeignClient(name = "billing", path = "/pay")
                        public interface BillingClient {
                            @PostMapping("/charge") String charge(String req);
                        }
                        """)
                .file("src/main/java/com/acme/orders/Events.java", """
                        package com.acme.orders;
                        import org.springframework.kafka.annotation.KafkaListener;
                        public class Events {
                            private static final String OUT = "orders.v1.placed";
                            private Object kafkaTemplate;
                            @KafkaListener(topics = "${kafka.in-topic}")
                            public void in(String msg) {}
                            public void out(String e) {
                                ((org.springframework.kafka.core.KafkaTemplate) kafkaTemplate).send(OUT, e);
                            }
                        }
                        """)
                .commit("init");
        // billing-service: the endpoint that BillingClient's @FeignClient(name="billing", path="/pay")
        // + @PostMapping("/charge") resolves against. spring.application.name matches the Feign
        // client's target_hint ("billing") and no server.servlet.context-path is declared, so the
        // endpoint's norm_path is exactly "/pay/charge" — same as the client's — for a HIGH
        // FEIGN_NAME_PATH match.
        FixtureRepo.in(ws, "billing-service")
                .file("src/main/java/com/acme/billing/PayController.java", """
                        package com.acme.billing;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/pay")
                        public class PayController {
                            @PostMapping("/charge") public String charge(@RequestBody String req) { return req; }
                        }
                        """)
                .file("src/main/resources/application.yml", """
                        spring:
                          application:
                            name: billing
                        """)
                .commit("init");

        SddConfig config = new SddConfig(ws, "fts", Map.of(), Map.of(), List.of(), Map.of(), List.of());
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir -> {
                String name = repoDir.getFileName().toString();
                return switch (name) {
                    case "lib-pricing" -> extractFor(repoDir, "lib-pricing", "com.acme",
                            List.of("java-library", "maven-publish"), List.of());
                    case "billing-service" -> extractFor(repoDir, "billing-service", "com.acme",
                            List.of("java", "org.springframework.boot"), List.of());
                    default -> extractFor(repoDir, "svc-orders", "com.acme",
                            List.of("java", "org.springframework.boot"),
                            List.of(new GradleModel.DeclaredDep("com.acme", "lib-pricing", "1.0")));
                };
            });
            List<IndexService.RepoResult> results = service.run(config, db);

            assertThat(results).allSatisfy(r -> assertThat(r.parseStatus()).isEqualTo("OK"));
            // lombok-synthesized getter present
            Integer getterCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM api_member WHERE signature='getCurrency()' "
                            + "AND synthesized_by='lombok:@Getter'").mapTo(Integer.class).one());
            assertThat(getterCount).isEqualTo(1);
            // cross-repo usage linked to lib-pricing's module. PriceCalculator is only ever
            // *imported* by OrderService (never used as a field/return/param type in the fixture
            // body), so the widened ReferenceExtractor's ClassOrInterfaceType pass never touches
            // it: exactly one api_usage row (refKind IMPORT), unchanged by Task 2's widening.
            var usage = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT u.target_fqcn, m.repo_id FROM api_usage u
                            JOIN module m ON m.id = u.target_module_id""").mapToMap().list());
            assertThat(usage).hasSize(1);
            assertThat(usage.get(0)).containsEntry("target_fqcn", "com.acme.pricing.PriceCalculator");
            // intra-repo file ref OrderService -> OrderHelper. Now produced entirely by the
            // widened extractor (ObjectCreationExpr -> CALL, return-type ClassOrInterfaceType ->
            // TYPE): the artificial "import OrderHelper" that 2B-1 added to force this file_ref to
            // exist is gone from the fixture (removed above), and the count stays 1 because
            // fileRefCounts collapses all target hits for a given (src,dst) pair into one row.
            Integer fileRefCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM file_ref WHERE src_file LIKE '%OrderService.java' "
                            + "AND dst_file LIKE '%OrderHelper.java'").mapTo(Integer.class).one());
            assertThat(fileRefCount).isEqualTo(1);
            // FTS finds the calculator by split word
            assertThat(new FtsRetriever(db.jdbi()).search("calculator", 10)).isNotEmpty();
            // internalRefs counts api_usage rows with a resolved target_module_id; still exactly
            // the one PriceCalculator/IMPORT row from above.
            assertThat(service.lastUsageReport().internalRefs()).isEqualTo(1);
            Map<String, Object> module = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT m.spring_app_name, m.context_path FROM module m
                            JOIN repo r ON r.id = m.repo_id WHERE r.name='svc-orders'""")
                    .mapToMap().one());
            assertThat(module.get("spring_app_name")).isEqualTo("order-service");
            assertThat(module.get("context_path")).isEqualTo("/orders");
            // spring.application.name, server.servlet.context-path, kafka.in-topic — scoped to
            // svc-orders since billing-service's own application.yml (added below) now also
            // contributes a config_property row to the (otherwise unscoped) table.
            Integer propCount = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT count(*) FROM config_property c
                            JOIN module m ON m.id = c.module_id
                            JOIN repo r ON r.id = m.repo_id WHERE r.name='svc-orders'""")
                    .mapTo(Integer.class).one());
            assertThat(propCount).isEqualTo(3);
            // rest_endpoint norm_path prepends the module's context-path (from application.yml)
            // ahead of the class-level @RequestMapping and method-level @GetMapping paths. Scoped
            // to svc-orders since billing-service's PayController adds a second rest_endpoint row.
            Map<String, Object> endpoint = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT e.http_method, e.norm_path FROM rest_endpoint e
                            JOIN module m ON m.id = e.module_id
                            JOIN repo r ON r.id = m.repo_id WHERE r.name='svc-orders'""")
                    .mapToMap().one());
            assertThat(endpoint).containsEntry("http_method", "GET")
                    .containsEntry("norm_path", "/orders/api/orders/{}");
            // Feign client: target_hint resolves from @FeignClient's "name" attribute
            Integer feignCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM rest_client WHERE kind='FEIGN' AND target_hint='billing'")
                    .mapTo(Integer.class).one());
            assertThat(feignCount).isEqualTo(1);
            // Kafka: one CONSUMER topic resolved from the ${kafka.in-topic} placeholder (via
            // application.yml) and one PRODUCER topic resolved from Events' static final OUT
            // constant, sent through a field typed only by its cast to KafkaTemplate.
            var topics = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT t.name, r.role FROM kafka_role r JOIN kafka_topic t ON t.id=r.topic_id "
                            + "ORDER BY t.name").mapToMap().list());
            assertThat(topics).hasSize(2);
            assertThat(topics.get(0)).containsEntry("name", "orders.v1.incoming")
                    .containsEntry("role", "CONSUMER");
            assertThat(topics.get(1)).containsEntry("name", "orders.v1.placed")
                    .containsEntry("role", "PRODUCER");
            // Feign(name=billing, path=/pay, POST /charge) ↔ billing-service endpoint: HIGH edge
            Map<String, Object> edge = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT confidence, matched_by FROM rest_call_edge""").mapToMap().one());
            assertThat(edge).containsEntry("confidence", "HIGH")
                    .containsEntry("matched_by", "FEIGN_NAME_PATH");
            assertThat(Files.exists(ws.resolve(".sdd/curation-report.md"))).isTrue();
        }
    }
}
