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
                List.of("svc"), "TierResolver.resolve(String): String — planned delta", null, List.of());

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
                List.of(), "something about Gamma", null, List.of());

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c1")).contains("Alpha").contains("Beta");
    }

    @Test
    void declaredTypesSelectTheExtractionAndSuppressTheWholeSurfaceFallback() throws Exception {
        // A declared type that does not exist anywhere in the tree is the strongest divergence
        // signal there is; with declarations present, the whole-surface fallback must never kick
        // in to paper over it with a large, healthy-looking body.
        javaFile("src/main/java/com/acme/lib/Alpha.java",
                "package com.acme.lib;\npublic class Alpha { public void a() {} }\n");
        javaFile("src/main/java/com/acme/lib/Beta.java",
                "package com.acme.lib;\npublic class Beta { public void b() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of(), "something about Gamma", null,
                List.of("com.acme.lib.Ghost#ghost():void"));

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c1")).isNull();   // no whole-surface dump; nothing declared exists
    }

    @Test
    void declaredTypeSelectionExtractsOnlyTheDeclaredTypeNotEveryType() throws Exception {
        // Pins the positive half of the rule: a mutation that fell back to selecting `all` whenever
        // the declared selection is non-empty would still pass every other test in this class, since
        // they only ever prove the fallback is *suppressed*, never that the selection is *precise*.
        javaFile("src/main/java/com/acme/lib/Alpha.java",
                "package com.acme.lib;\npublic class Alpha { public void a() {} }\n");
        javaFile("src/main/java/com/acme/lib/Beta.java",
                "package com.acme.lib;\npublic class Beta { public void b() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of(), "something about Alpha", null,
                List.of("com.acme.lib.Alpha#a():void"));

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c1")).contains("Alpha").doesNotContain("Beta");
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
                List.of(), "GET /admin/spreads", null, List.of());

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c2")).contains("GET").contains("/admin/spreads");
    }

    @Test
    void depthOneModulesAreDiscovered() throws Exception {
        javaFile("core/src/main/java/com/acme/core/Deep.java",
                "package com.acme.core;\npublic class Deep { public void d() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c3", "java-api", "lib",
                List.of(), "Deep", null, List.of());

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c3")).contains("com.acme.core.Deep");
    }

    @Test
    void depthTwoModulesAreDiscoveredUnderANestingDirectory() throws Exception {
        // Real multi-repo layout: libs/common-model/src/main/java — a module two levels below
        // repoRoot, which the old depth-1-only scan could never find.
        javaFile("libs/common-model/src/main/java/com/acme/model/Money.java",
                "package com.acme.model;\npublic class Money { public String currency() { return \"USD\"; } }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c6", "java-api", "common-model",
                List.of(), "Money", null, List.of());

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c6")).contains("com.acme.model.Money");
    }

    @Test
    void depthTwoRestControllerModuleIsDiscovered() throws Exception {
        // services/pricing-a/src/main/java — another two-deep module, this time exercising the
        // REST-controller path rather than the java-api path.
        javaFile("services/pricing-a/src/main/java/com/acme/pricing/PricingController.java", """
                package com.acme.pricing;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class PricingController {
                    @GetMapping("/pricing/quote")
                    public String quote() { return ""; }
                }
                """);
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c7", "rest", "pricing-a",
                List.of(), "GET /pricing/quote", null, List.of());

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c7")).contains("GET").contains("/pricing/quote");
    }

    @Test
    void modulesUnderASkipDirAreNotScanned() throws Exception {
        // vendor/node_modules/leftpad/src/main/java looks like a module by directory shape, but
        // "node_modules" is a vendored/dependency directory (FileTools.SKIP_DIRS) that must never
        // be walked into as a module root. NB: unlike "node_modules", a bare "build" segment isn't
        // usable for this test — SourceParser.sourceRootsOf deliberately treats <module>/build/
        // generated as a legitimate extra source root for annotation-processor output, so it would
        // get pulled in anyway once repoRoot itself is recognized as a module.
        javaFile("vendor/node_modules/leftpad/src/main/java/com/acme/skip/ShouldSkip.java",
                "package com.acme.skip;\npublic class ShouldSkip { public void s() {} }\n");
        javaFile("src/main/java/com/acme/lib/Real.java",
                "package com.acme.lib;\npublic class Real { public void r() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c8", "java-api", "lib",
                List.of(), "something unmatched", null, List.of());

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c8")).contains("com.acme.lib.Real")
                .doesNotContain("com.acme.skip.ShouldSkip");
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
                List.of(), "consumes t.orders", null, List.of());

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
                List.of(), "Huge", null, List.of());

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c5")).hasSizeGreaterThan(4000).endsWith("…(truncated)");
        assertThat(actual.get("c5").length()).isLessThanOrEqualTo(4000 + "\n…(truncated)".length());
    }
}
