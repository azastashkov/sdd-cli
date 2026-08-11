package sdd.index.store;

import org.jdbi.v3.core.Jdbi;

public final class UsageLinker {
    public record Report(int internalRefs, int prunedExternal) {}

    private UsageLinker() {}

    public static Report link(Jdbi jdbi) {
        return jdbi.inTransaction(h -> {
            h.execute("""
                    UPDATE api_usage SET target_module_id =
                      (SELECT MIN(jt.module_id) FROM java_type jt WHERE jt.fqcn = api_usage.target_fqcn)
                    WHERE EXISTS(SELECT 1 FROM java_type jt WHERE jt.fqcn = api_usage.target_fqcn)""");
            int pruned = h.createUpdate(
                            "DELETE FROM api_usage WHERE target_module_id IS NULL "
                                    + "OR target_module_id = from_module_id")
                    .execute();
            int internal = h.createQuery("SELECT count(*) FROM api_usage")
                    .mapTo(Integer.class).one();
            return new Report(internal, pruned);
        });
    }
}
