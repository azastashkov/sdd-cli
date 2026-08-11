package sdd.index.store;

import org.jdbi.v3.core.Jdbi;

public final class UsageLinker {
    /**
     * @param internalRefs   usage rows resolved to a module of another repo in the estate
     * @param prunedSelfRefs usage rows deleted because the target resolved back to the module
     *                       that declared them (a module referencing itself is not an edge)
     */
    public record Report(int internalRefs, int prunedSelfRefs) {}

    private UsageLinker() {}

    /**
     * Re-derives api_usage.target_module_id from the java_type table. Deliberately
     * non-destructive: only self-references are deleted, and an unresolved target leaves the row
     * with a NULL target rather than removing it. Deleting unresolved rows would be irreversible —
     * the row is only rewritten when its OWN repo is re-indexed, so a target repo that was
     * transiently missing (first-run parse failure, a repo indexed later in the pass) would take
     * the source repo's edges down with it and never give them back, because the source repo's
     * fingerprint is unchanged and it skips forever.
     *
     * <p>Idempotent by construction: the reset clears links that no longer hold before the match
     * re-establishes the ones that do, so repeated runs converge on the same picture.
     */
    public static Report link(Jdbi jdbi) {
        return jdbi.inTransaction(h -> {
            h.execute("UPDATE api_usage SET target_module_id = NULL");
            h.execute("""
                    UPDATE api_usage SET target_module_id =
                      (SELECT MIN(jt.module_id) FROM java_type jt WHERE jt.fqcn = api_usage.target_fqcn)
                    WHERE EXISTS(SELECT 1 FROM java_type jt WHERE jt.fqcn = api_usage.target_fqcn)""");
            int prunedSelfRefs = h.createUpdate(
                            "DELETE FROM api_usage WHERE target_module_id = from_module_id")
                    .execute();
            int internal = h.createQuery(
                            "SELECT count(*) FROM api_usage WHERE target_module_id IS NOT NULL")
                    .mapTo(Integer.class).one();
            return new Report(internal, prunedSelfRefs);
        });
    }
}
