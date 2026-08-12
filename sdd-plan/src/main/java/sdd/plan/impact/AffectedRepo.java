package sdd.plan.impact;

import java.util.List;
import java.util.Objects;

/**
 * One repo in the affected set. role: seed | dependent | contract | bom-site.
 * annotation: SEED | CODE_CHANGE_LIKELY | BUMP_REBUILD_ONLY | PENDING_CONTRACT |
 * BOM_DECLARATION_SITE — annotations describe, they never limit propagation (design M1).
 */
public record AffectedRepo(String repo, String role, String annotation,
                           List<String> covers, List<String> reasons) {
    public AffectedRepo {
        Objects.requireNonNull(repo);
        Objects.requireNonNull(role);
        Objects.requireNonNull(annotation);
        covers = List.copyOf(covers);
        reasons = List.copyOf(reasons);
    }
}
