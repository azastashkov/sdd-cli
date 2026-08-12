package sdd.agent.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkOrderTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/lib','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.TierResolver','CLASS',1,'src/main/java/com/acme/TierResolver.java')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.Internal','CLASS',0,'src/main/java/com/acme/Internal.java')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.TierResolverTest','CLASS',0,'src/test/java/com/acme/TierResolverTest.java')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.TierService','CLASS',1,'src/main/java/com/acme/TierService.java')");
            h.execute("INSERT INTO file_ref(repo_id, src_file, dst_file, ref_count) "
                    + "VALUES (1,'src/main/java/com/acme/TierResolver.java','src/main/java/com/acme/Internal.java',5)");
            h.execute("INSERT INTO repo_card(repo_id, card_md, card_line, model, input_hash, created_at) "
                    + "VALUES (1,'## Purpose\\nResolves loyalty tiers.','Tier library.','qwen','h','t')");
        });
    }

    private static RepoStep step() {
        return new RepoStep("lib-core", Path.of("/w/lib"), "Add tierFor(clientId) to TierResolver.",
                List.of("R1: Price response includes the customer tier."),
                List.of("TierResolver.java"),
                List.of(new ContractRef("C-1", "java-api", "lib-core", List.of("svc-a"),
                        "Tier tierFor(String clientId)")),
                List.of(),
                List.of("Run lib-core tests", "Verify a changed mapping is observed without restart"));
    }

    @Test
    void buildsALeanGroundedWorkOrder() {
        String wo = WorkOrder.build(db.jdbi(), step());

        assertThat(wo)
                .contains("Add tierFor(clientId) to TierResolver.")
                .contains("R1: Price response includes the customer tier.")
                .contains("Provides").contains("C-1 (java-api)").contains("Tier tierFor(String clientId)")
                .contains("Resolves loyalty tiers.")                                   // repo card
                .contains("src/main/java/com/acme/TierResolver.java" + WorkOrder.SEP + "seed")          // step file + api
                .contains("src/main/java/com/acme/Internal.java" + WorkOrder.SEP + "referenced (5)")    // file_ref hop
                .contains("src/main/java/com/acme/TierService.java" + WorkOrder.SEP + "api surface")    // api surface (not seed, not test)
                .contains("src/test/java/com/acme/TierResolverTest.java" + WorkOrder.SEP + "test")      // test heuristic
                .contains("Run lib-core tests");                                        // acceptance (human)
    }

    @Test
    void gracefulWithoutARepoCard() {
        db.jdbi().useHandle(h -> h.execute("DELETE FROM repo_card"));

        String wo = WorkOrder.build(db.jdbi(), step());

        assertThat(wo).contains("Add tierFor(clientId) to TierResolver.")
                .doesNotContain("null")
                .contains("(no repo card)");
    }

    @Test
    void capsTheManifestAndTruncatesTheCard() {
        // Seed >24 is_api=1 java_type rows
        db.jdbi().useHandle(h -> {
            for (int i = 0; i < 30; i++) {
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                        + "VALUES (1,'com.acme.Type" + i + "','CLASS',1,'src/main/java/com/acme/Type" + i + ".java')");
            }
            // Seed a repo_card.card_md longer than MAX_CARD_CHARS
            String longCard = "A".repeat(WorkOrder.MAX_CARD_CHARS) + "TAILMARKER";
            h.execute("UPDATE repo_card SET card_md = ? WHERE repo_id = 1",
                    longCard);
        });

        String wo = WorkOrder.build(db.jdbi(), step());

        // Count manifest lines (each starts with "- " and contains WorkOrder.SEP)
        String filesSection = wo.substring(wo.indexOf("## Files most likely relevant"));
        int manifestLines = (int) Arrays.stream(filesSection.split("\n"))
                .filter(line -> line.startsWith("- ") && line.contains(WorkOrder.SEP))
                .count();
        assertThat(manifestLines)
                .isLessThanOrEqualTo(WorkOrder.MAX_MANIFEST_FILES)
                .isGreaterThan(0);

        // Verify truncation: long As present but TAILMARKER absent
        assertThat(wo)
                .contains("A".repeat(100))  // has a run of As
                .doesNotContain("TAILMARKER");  // but not the tail
    }
}
