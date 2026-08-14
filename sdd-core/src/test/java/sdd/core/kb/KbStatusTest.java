package sdd.core.kb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same string-producing logic as sdd-plan's Closure.statusWarnings — OpenQuestions matches on
 * "indexed with status" in that string, so it must not drift a single character.
 */
class KbStatusTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind, gradle_status, parse_status, indexed_at) "
                    + "VALUES ('svc-orders','/w/o','SERVICE','DEGRADED','OK','2026-08-01T00:00:00Z')");
            h.execute("INSERT INTO repo(name, path, kind, gradle_status, parse_status, indexed_at) "
                    + "VALUES ('svc-billing','/w/b','SERVICE','OK','FAILED','2026-08-05T00:00:00Z')");
            h.execute("INSERT INTO repo(name, path, kind, indexed_at) VALUES ('lib-core','/w/l','LIBRARY','2026-08-03T00:00:00Z')");
        });
    }

    @Test
    void reportsGradleStatusWhenDegraded() {
        var warnings = KbStatus.warnings(db.jdbi(), Set.of("svc-orders"));

        assertThat(warnings).containsExactly(
                "affected repo svc-orders indexed with status DEGRADED — downgrade confidence in its facts");
    }

    @Test
    void prefersGradleStatusOverParseStatusWhenGradleIsOk() {
        // gradle_status = OK, parse_status = FAILED -> the reported status is the parse status
        var warnings = KbStatus.warnings(db.jdbi(), Set.of("svc-billing"));

        assertThat(warnings).containsExactly(
                "affected repo svc-billing indexed with status FAILED — downgrade confidence in its facts");
    }

    @Test
    void onlyAffectedReposProduceWarnings() {
        var warnings = KbStatus.warnings(db.jdbi(), Set.of("lib-core"));

        assertThat(warnings).isEmpty();
    }

    @Test
    void warningsAreOrderedByRepoName() {
        var warnings = KbStatus.warnings(db.jdbi(), Set.of("svc-orders", "svc-billing"));

        assertThat(warnings).extracting(w -> w.split(" ")[2]).containsExactly("svc-billing", "svc-orders");
    }

    @Test
    void provenanceReportsRepoCountAndIndexedAtRange() {
        Provenance p = KbStatus.provenance(db.jdbi());

        assertThat(p.repoCount()).isEqualTo(3);
        assertThat(p.earliestIndexedAt()).isEqualTo("2026-08-01T00:00:00Z");
        assertThat(p.latestIndexedAt()).isEqualTo("2026-08-05T00:00:00Z");
    }
}
