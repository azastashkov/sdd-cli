package sdd.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.testing.FixtureRepo;
import sdd.index.gradle.GradleModel;

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
                .commit("init");

        SddConfig config = new SddConfig(ws, "fts", Map.of(), Map.of(), List.of(), Map.of());
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir -> {
                String name = repoDir.getFileName().toString();
                return name.equals("lib-pricing")
                        ? extractFor(repoDir, "lib-pricing", "com.acme",
                                List.of("java-library", "maven-publish"), List.of())
                        : extractFor(repoDir, "svc-orders", "com.acme",
                                List.of("java", "org.springframework.boot"),
                                List.of(new GradleModel.DeclaredDep("com.acme", "lib-pricing", "1.0")));
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
        }
    }
}
