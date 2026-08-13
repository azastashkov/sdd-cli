package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlannedVersionsTest {
    @TempDir Path ws;

    @Test
    void bumpPolicy() {
        assertThat(PlannedVersions.bump("1.2.3", "none")).isEqualTo("1.2.3");
        assertThat(PlannedVersions.bump("1.2.3", null)).isEqualTo("1.2.3");
        assertThat(PlannedVersions.bump("1.2.3", "patch")).isEqualTo("1.2.4");
        assertThat(PlannedVersions.bump("1.2.3", "minor")).isEqualTo("1.3.0");
        assertThat(PlannedVersions.bump("1.2.3", "major")).isEqualTo("2.0.0");
        assertThat(PlannedVersions.bump("1.2.3-SNAPSHOT", "minor")).isEqualTo("1.3.0-SNAPSHOT");
        assertThat(PlannedVersions.bump("2024.10", "patch")).isNull();   // unparseable + real bump
        assertThat(PlannedVersions.bump("2024.10", "none")).isEqualTo("2024.10");
    }

    @Test
    void computeReadsTheRootModuleVersionAndSkipsUnversionedRepos() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, grp, name, version, kind) "
                        + "VALUES (1, ':', 'com.acme', 'lib', '1.2.3', 'LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('nover', '/w/nover', 'LIBRARY')");
            });
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                            new PlanModel.PlanRepo("nover", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("nover")), List.of(), List.of(), List.of());

            Map<String, String> planned = PlannedVersions.compute(db.jdbi(), plan);

            assertThat(planned).containsEntry("lib", "1.3.0").doesNotContainKey("nover");
        }
    }
}
