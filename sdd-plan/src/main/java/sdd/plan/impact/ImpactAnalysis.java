package sdd.plan.impact;

import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.ChatModel;
import sdd.core.retrieve.Retriever;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three-stage impact analysis (design Component 2): A deterministic pre-seed, B assistive
 * model seeding, C deterministic closure. Model/graph discrepancies are recorded and surfaced,
 * never silently resolved.
 */
public final class ImpactAnalysis {

    private ImpactAnalysis() {
    }

    public static ImpactResult analyze(Jdbi jdbi, Retriever retriever, NormalizedSpec spec,
                                       ChatModel planner, String modelName, int maxTokens) {
        SeedFinder.SeedScan scan = SeedFinder.find(jdbi, retriever, spec);
        ModelSeeder.SeedingOutcome seeding = ModelSeeder.seed(jdbi, spec, scan.seeds(),
                scan.candidates(), planner, modelName, maxTokens);
        boolean modelUnavailable = seeding.seeds().isEmpty()
                && seeding.warnings().stream().anyMatch(w -> w.contains("model seeding unavailable"));

        List<Seed> seeds = new ArrayList<>(scan.seeds());
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            String detail = modelSeed.covers().isEmpty()
                    ? modelSeed.reason()
                    : "covers " + String.join(",", modelSeed.covers()) + "; " + modelSeed.reason();
            seeds.add(new Seed(modelSeed.repo(), "model", detail));
        }

        Set<String> touchpointRepos = new LinkedHashSet<>();
        for (Seed seed : scan.seeds()) {
            touchpointRepos.add(seed.repo());
        }
        Set<String> candidateRepos = new LinkedHashSet<>();
        for (Seed candidate : scan.candidates()) {
            candidateRepos.add(candidate.repo());
        }
        Set<String> modelRepos = new LinkedHashSet<>();
        Map<String, List<String>> coversByRepo = new LinkedHashMap<>();
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            modelRepos.add(modelSeed.repo());
            coversByRepo.computeIfAbsent(modelSeed.repo(), k -> new ArrayList<>())
                    .addAll(modelSeed.covers());
        }

        List<String> discrepancies = new ArrayList<>();
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            if (!touchpointRepos.contains(modelSeed.repo()) && !candidateRepos.contains(modelSeed.repo())) {
                discrepancies.add("model-only: " + modelSeed.repo() + " (" + modelSeed.reason() + ")");
            }
        }
        for (String seeded : touchpointRepos) {
            // suppressed ONLY when the model was unavailable — a model that answered with an
            // empty selection genuinely disagrees with the graph, and that must surface
            if (!modelUnavailable && !modelRepos.contains(seeded)) {
                discrepancies.add("model omitted seeded repo: " + seeded);
            }
        }

        Set<String> roots = new LinkedHashSet<>(touchpointRepos);
        roots.addAll(modelRepos);

        List<String> problems = new ArrayList<>(scan.problems());
        List<String> warnings = new ArrayList<>(seeding.warnings());

        List<AffectedRepo> affected = new ArrayList<>();
        Closure.Expansion expansion;
        if (roots.isEmpty()) {
            problems.add("no seeds: add touchpoints to the spec or check the knowledge base");
            expansion = new Closure.Expansion(List.of(), List.of(), List.of());
        } else {
            for (String root : roots) {
                List<String> reasons = new ArrayList<>();
                for (Seed seed : seeds) {
                    if (seed.repo().equals(root)) {
                        reasons.add(seed.source() + " " + seed.detail());
                    }
                }
                affected.add(new AffectedRepo(root, "seed", "SEED",
                        coversByRepo.getOrDefault(root, List.of()), reasons));
            }
            expansion = Closure.expand(jdbi, roots);
            affected.addAll(expansion.added());
        }
        warnings.addAll(expansion.warnings());

        if (modelUnavailable) {
            warnings.add("coverage unknown: model seeding unavailable");
        } else {
            Set<String> covered = new LinkedHashSet<>();
            coversByRepo.values().forEach(covered::addAll);
            for (SpecItem requirement : spec.requirements()) {
                if (!covered.contains(requirement.id())) {
                    problems.add("no repo covers " + requirement.id());
                }
            }
        }

        Set<String> affectedRepos = new LinkedHashSet<>();
        for (AffectedRepo repo : affected) {
            affectedRepos.add(repo.repo());
        }
        List<Seed> excluded = new ArrayList<>();
        for (Seed candidate : scan.candidates()) {
            if (!affectedRepos.contains(candidate.repo())) {
                excluded.add(new Seed(candidate.repo(), candidate.source(),
                        candidate.detail() + " — not selected by model, not required by graph"));
            }
        }

        return new ImpactResult(seeds, affected, excluded, expansion.cycles(),
                discrepancies, problems, warnings);
    }
}
