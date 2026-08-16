package sdd.core.kb;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Index-health facts about the KB: degraded/failed/stale repos among an affected set, and overall
 * provenance. {@link #warnings} is moved verbatim from sdd-plan's Closure.statusWarnings — its
 * string is matched by OpenQuestions on {@code "indexed with status"}, so it must not drift.
 */
public final class KbStatus {
    private KbStatus() {
    }

    public static List<String> warnings(Jdbi jdbi, Set<String> repos) {
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT name, gradle_status, parse_status FROM repo
                        WHERE (gradle_status IN ('DEGRADED','FAILED','STALE_OK','UNSUPPORTED')
                               OR parse_status IN ('DEGRADED','FAILED','STALE_OK','UNSUPPORTED'))
                        ORDER BY name""")
                .mapToMap().list());
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name"));
            if (repos.contains(name)) {
                String status = row.get("gradle_status") != null
                        && !"OK".equals(row.get("gradle_status"))
                        ? String.valueOf(row.get("gradle_status"))
                        : String.valueOf(row.get("parse_status"));
                warnings.add("affected repo " + name + " indexed with status " + status
                        + " — downgrade confidence in its facts");
            }
        }
        return warnings;
    }

    public static Provenance provenance(Jdbi jdbi) {
        return jdbi.withHandle(h -> {
            int repoCount = h.createQuery("SELECT count(*) FROM repo").mapTo(Integer.class).one();
            String earliest = h.createQuery("SELECT min(indexed_at) FROM repo")
                    .mapTo(String.class).findOne().orElse(null);
            String latest = h.createQuery("SELECT max(indexed_at) FROM repo")
                    .mapTo(String.class).findOne().orElse(null);
            return new Provenance(repoCount, earliest, latest);
        });
    }
}
