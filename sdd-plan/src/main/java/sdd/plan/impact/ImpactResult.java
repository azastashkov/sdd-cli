package sdd.plan.impact;

import java.util.List;

/**
 * The full impact-analysis outcome. excluded = candidate seeds that neither the model selected
 * nor the graph required (recorded with reasons — never silently dropped). discrepancies =
 * model/graph disagreements, surfaced per the design ("never silently resolved").
 */
public record ImpactResult(List<Seed> seeds, List<AffectedRepo> affected, List<Seed> excluded,
                           List<String> cycles, List<String> discrepancies,
                           List<String> problems, List<String> warnings) {
    public ImpactResult {
        seeds = List.copyOf(seeds);
        affected = List.copyOf(affected);
        excluded = List.copyOf(excluded);
        cycles = List.copyOf(cycles);
        discrepancies = List.copyOf(discrepancies);
        problems = List.copyOf(problems);
        warnings = List.copyOf(warnings);
    }
}
