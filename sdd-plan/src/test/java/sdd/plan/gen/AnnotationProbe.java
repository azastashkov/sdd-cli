package sdd.plan.gen;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measures the accuracy of the {@code CODE_CHANGE_LIKELY} / {@code BUMP_REBUILD_ONLY} annotation,
 * and compares today's rule against two candidate replacements. Measurement only — nothing here is
 * production.
 *
 * <p>Today's rule, {@code Closure.usesApiOf}, is an <em>unfiltered</em> {@code count(*)} over
 * {@code api_usage} between a consumer/provider pair: it asks "does this repo use anything at all
 * from that repo", never "does it use the thing that changed". The two candidates are:
 *
 * <ul>
 *   <li><b>TYPE_FILTERED</b> — restrict the same query to the changed types. No schema change.
 *   <li><b>KIND_AWARE</b> — additionally read {@code api_usage.ref_kind}: an {@code EXTENDS} or
 *       {@code CALL} reference means the consumer can break, while {@code IMPORT}/{@code TYPE}
 *       alone means it only names the type. This is the first production-shaped read of
 *       {@code ref_kind}, which is currently written by one site and read by none.
 * </ul>
 *
 * <p>Member-level usage is deliberately NOT a candidate here. It would be the expensive fix, and
 * the point of this probe is to find out whether the cheap ones already suffice.
 */
final class AnnotationProbe {

    /** CODE_CHANGE_LIKELY / BUMP_REBUILD_ONLY per consumer, under one rule. */
    record Verdict(String rule, Map<String, String> byRepo) {}

    record Scored(String rule, int correct, int falseCodeChange, int falseRebuild,
                  List<String> mistakes) {}

    private AnnotationProbe() {
    }

    /** Today's behaviour: any usage of the provider at all. */
    static Verdict today(Jdbi jdbi, String providerRepo, Set<String> consumers) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : consumers) {
            int n = jdbi.withHandle(h -> h.createQuery("""
                            SELECT count(*) FROM api_usage u
                            JOIN module mc ON mc.id = u.from_module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            JOIN module mp ON mp.id = u.target_module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            WHERE rc.name = :c AND rp.name = :p""")
                    .bind("c", c).bind("p", providerRepo).mapTo(Integer.class).one());
            out.put(c, n > 0 ? "CODE_CHANGE_LIKELY" : "BUMP_REBUILD_ONLY");
        }
        return new Verdict("TODAY (unfiltered)", out);
    }

    /** Restrict to the types that actually changed. */
    static Verdict typeFiltered(Jdbi jdbi, Set<String> changedTypes, Set<String> consumers) {
        return byRule(jdbi, changedTypes, consumers, false, "TYPE_FILTERED");
    }

    /** Restrict to the changed types AND require a structural reference kind. */
    static Verdict kindAware(Jdbi jdbi, Set<String> changedTypes, Set<String> consumers) {
        return byRule(jdbi, changedTypes, consumers, true, "KIND_AWARE (reads ref_kind)");
    }

    private static Verdict byRule(Jdbi jdbi, Set<String> changedTypes, Set<String> consumers,
                                  boolean kindAware, String label) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String c : consumers) {
            int n = jdbi.withHandle(h -> h.createQuery("""
                            SELECT count(*) FROM api_usage u
                            JOIN module mc ON mc.id = u.from_module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            WHERE rc.name = :c
                              AND u.target_fqcn IN (<types>)
                              AND (:anyKind OR u.ref_kind IN ('EXTENDS', 'CALL'))""")
                    .bindList("types", List.copyOf(changedTypes))
                    .bind("c", c).bind("anyKind", !kindAware)
                    .mapTo(Integer.class).one());
            out.put(c, n > 0 ? "CODE_CHANGE_LIKELY" : "BUMP_REBUILD_ONLY");
        }
        return new Verdict(label, out);
    }

    static Scored score(Verdict verdict, Set<String> truthCodeChange) {
        int correct = 0;
        int falseCode = 0;
        int falseRebuild = 0;
        List<String> mistakes = new ArrayList<>();
        for (Map.Entry<String, String> e : verdict.byRepo().entrySet()) {
            boolean saysCode = "CODE_CHANGE_LIKELY".equals(e.getValue());
            boolean isCode = truthCodeChange.contains(e.getKey());
            if (saysCode == isCode) {
                correct++;
            } else if (saysCode) {
                falseCode++;
                mistakes.add(e.getKey() + ": said CODE_CHANGE_LIKELY, truth is rebuild-only");
            } else {
                falseRebuild++;
                mistakes.add(e.getKey() + ": said BUMP_REBUILD_ONLY, truth is code change"
                        + "  <-- the dangerous direction");
            }
        }
        return new Scored(verdict.rule(), correct, falseCode, falseRebuild, mistakes);
    }
}
