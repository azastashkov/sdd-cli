package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractActualizerTest {
    @TempDir Path repo;

    private void javaFile(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void actualizesAJavaApiContractFromTheRealTree() throws Exception {
        javaFile("src/main/java/com/acme/lib/TierResolver.java", """
                package com.acme.lib;
                public class TierResolver {
                    public String resolve(String account) { return account; }
                    private int internal() { return 0; }
                }
                """);
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "TierResolver.resolve(String): String — planned delta", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c1"))
                .contains("com.acme.lib.TierResolver")
                .contains("resolve(String)")
                .doesNotContain("internal");   // private members never in the surface
    }

    @Test
    void unmatchedTypeNamesFallBackToTheWholeSurface() throws Exception {
        javaFile("src/main/java/com/acme/lib/Alpha.java",
                "package com.acme.lib;\npublic class Alpha { public void a() {} }\n");
        javaFile("src/main/java/com/acme/lib/Beta.java",
                "package com.acme.lib;\npublic class Beta { public void b() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of(), "something about Gamma", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c1")).contains("Alpha").contains("Beta");
    }

    @Test
    void actualizesARestContract() throws Exception {
        javaFile("src/main/java/com/acme/svc/SpreadController.java", """
                package com.acme.svc;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class SpreadController {
                    @GetMapping("/admin/spreads")
                    public String spreads() { return ""; }
                }
                """);
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c2", "rest", "svc",
                List.of(), "GET /admin/spreads", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c2")).contains("GET").contains("/admin/spreads");
    }

    @Test
    void depthOneModulesAreDiscovered() throws Exception {
        javaFile("core/src/main/java/com/acme/core/Deep.java",
                "package com.acme.core;\npublic class Deep { public void d() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c3", "java-api", "lib",
                List.of(), "Deep", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c3")).contains("com.acme.core.Deep");
    }

    @Test
    void actualizesAKafkaContract() throws Exception {
        javaFile("src/main/java/com/acme/svc/OrderListener.java", """
                package com.acme.svc;
                import org.springframework.kafka.annotation.KafkaListener;
                @KafkaListener(topics = "t.orders")
                public class OrderListener {
                }
                """);
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c4", "kafka", "svc",
                List.of(), "consumes t.orders", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c4")).contains("t.orders").contains("CONSUMER");
    }

    @Test
    void oversizedSurfacesAreCappedWithATruncationTail() throws Exception {
        StringBuilder src = new StringBuilder("package com.acme.lib;\npublic class Huge {\n");
        for (int i = 0; i < 200; i++) {
            src.append("    public String method").append(i).append("(String account) { return account; }\n");
        }
        src.append("}\n");
        javaFile("src/main/java/com/acme/lib/Huge.java", src.toString());
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c5", "java-api", "lib",
                List.of(), "Huge", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c5")).hasSizeGreaterThan(4000).endsWith("…(truncated)");
        assertThat(actual.get("c5").length()).isLessThanOrEqualTo(4000 + "\n…(truncated)".length());
    }
}
