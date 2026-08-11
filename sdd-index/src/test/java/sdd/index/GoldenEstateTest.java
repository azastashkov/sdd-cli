package sdd.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.testing.FixtureRepo;
import sdd.index.gradle.GradleModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the full knowledge base produced by a fixed two-repo estate (lib-pricing + svc-orders) —
 * the same fixture shape as {@link SourceEndToEndTest}, copied verbatim so its content, and
 * therefore the dump, stays fixed — against the checked-in golden dump {@code
 * src/test/resources/golden/estate.json}. Runs the full {@link IndexService} (repo cards off) and
 * compares {@link DbDump#canonicalJson}'s output byte-for-byte. Any change to the pipeline's
 * output shape — intentional or not — shows up here as a diff.
 *
 * <p>To regenerate after an intentional change: run with {@code -Dsdd.regenGolden=true} (wired
 * through by {@code sdd-index/build.gradle.kts}):
 * <pre>{@code
 * ./gradlew :sdd-index:test --tests "sdd.index.GoldenEstateTest" -Dsdd.regenGolden=true
 * }</pre>
 * That overwrites the golden resource and fails on purpose ("golden regenerated — rerun") so the
 * regenerated file always gets a genuine green run and a manual diff review before it is trusted.
 * Inspect the regenerated JSON for sanity (no absolute paths, no timestamps) before committing it.
 */
class GoldenEstateTest {
    private static final Path GOLDEN = Path.of("src/test/resources/golden/estate.json");
    private static final Path ACTUAL_OUT = Path.of("build/golden-actual.json");

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
    void dumpMatchesGolden() throws IOException {
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

        SddConfig config = new SddConfig(ws, "fts", Map.of(), Map.of(), List.of(), Map.of(), List.of());
        String actual;
        try (Database db = Database.open(ws)) {
            IndexService.Extractor stubExtractor = repoDir -> {
                String name = repoDir.getFileName().toString();
                return name.equals("lib-pricing")
                        ? extractFor(repoDir, "lib-pricing", "com.acme",
                                List.of("java-library", "maven-publish"), List.of())
                        : extractFor(repoDir, "svc-orders", "com.acme",
                                List.of("java", "org.springframework.boot"),
                                List.of(new GradleModel.DeclaredDep("com.acme", "lib-pricing", "1.0")));
            };
            IndexService service = new IndexService(stubExtractor, null, null);
            service.run(config, db);
            actual = DbDump.canonicalJson(db.jdbi(), ws);
        }

        if (Boolean.getBoolean("sdd.regenGolden")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
            fail("golden regenerated — rerun without -Dsdd.regenGolden to confirm it is green, "
                    + "then inspect and commit " + GOLDEN.toAbsolutePath());
        }

        if (!Files.exists(GOLDEN)) {
            fail("golden file missing at " + GOLDEN.toAbsolutePath() + ". Regenerate it: "
                    + "./gradlew :sdd-index:test --tests \"sdd.index.GoldenEstateTest\" -Dsdd.regenGolden=true"
                    + " — then inspect the generated JSON for sanity (no absolute paths, no "
                    + "timestamps) and commit it.");
        }
        String expected = Files.readString(GOLDEN, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            Files.createDirectories(ACTUAL_OUT.getParent());
            Files.writeString(ACTUAL_OUT, actual, StandardCharsets.UTF_8);
            fail("golden mismatch: expected " + GOLDEN.toAbsolutePath() + " but the actual dump was "
                    + "written to " + ACTUAL_OUT.toAbsolutePath() + " — diff the two. If the change "
                    + "is intentional, regenerate with "
                    + "./gradlew :sdd-index:test --tests \"sdd.index.GoldenEstateTest\" -Dsdd.regenGolden=true"
                    + ", inspect the regenerated JSON for sanity, then commit it.");
        }
    }
}
