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
        return analyze(jdbi, retriever, spec, planner, modelName, maxTokens, List.of());
    }

    /**
     * @param changes a {@code --since} change set, or empty. Each repo with changed indexed types
     *     contributes a SEED (not a candidate): a repo whose tracked files changed is a fact, and
     *     candidates are for guesses. Its changed types join the anchor set, which is what lets the
     *     annotation ask "does this consumer use the thing that changed" and what promotes those
     *     types past the drafter's evidence budget.
     */
    public static ImpactResult analyze(Jdbi jdbi, Retriever retriever, NormalizedSpec spec,
                                       ChatModel planner, String modelName, int maxTokens,
                                       List<ChangeSet.RepoChange> changes) {
        SeedFinder.SeedScan scan = SeedFinder.find(jdbi, retriever, spec);
        ModelSeeder.SeedingOutcome seeding = ModelSeeder.seed(jdbi, spec, scan.seeds(),
                scan.candidates(), planner, modelName, maxTokens);
        boolean modelUnavailable = seeding.unavailable();

        Map<String, Seed> candidateByRepo = new LinkedHashMap<>();
        for (Seed candidate : scan.candidates()) {
            candidateByRepo.putIfAbsent(candidate.repo(), candidate);
        }
        List<Seed> seeds = new ArrayList<>(scan.seeds());
        Set<String> anchorTypes = new LinkedHashSet<>(scan.anchorTypes());
        for (ChangeSet.RepoChange change : changes) {
            if (change.mapped().isEmpty()) {
                continue;
            }
            change.mapped().forEach(f -> anchorTypes.add(f.fqcn()));
            List<String> names = change.mapped().stream()
                    .map(f -> f.fqcn().substring(f.fqcn().lastIndexOf('.') + 1)).sorted().toList();
            seeds.add(new Seed(change.repo(), "git", "changed since " + change.fromSha() + ": "
                    + change.files().size() + " files, " + change.mapped().size() + " types ("
                    + String.join(", ", names) + ")"));
        }
        Set<String> seedKeys = new LinkedHashSet<>();
        for (Seed seed : seeds) {
            seedKeys.add(seed.repo() + "|" + seed.source() + "|" + seed.detail());
        }
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            Seed candidate = candidateByRepo.get(modelSeed.repo());
            if (candidate != null) {
                addSeed(seeds, seedKeys, candidate);   // model confirmed the FTS candidate: keep its evidence
            }
            String detail = modelSeed.covers().isEmpty()
                    ? modelSeed.reason()
                    : "covers " + String.join(",", modelSeed.covers()) + "; " + modelSeed.reason();
            addSeed(seeds, seedKeys, new Seed(modelSeed.repo(), "model", detail));
        }

        Set<String> touchpointRepos = new LinkedHashSet<>();
        for (Seed seed : scan.seeds()) {
            touchpointRepos.add(seed.repo());
        }
        for (ChangeSet.RepoChange change : changes) {
            if (!change.mapped().isEmpty()) {
                touchpointRepos.add(change.repo());
            }
        }
        Set<String> candidateRepos = new LinkedHashSet<>();
        for (Seed candidate : scan.candidates()) {
            candidateRepos.add(candidate.repo());
        }
        Set<String> modelRepos = new LinkedHashSet<>();
        Map<String, List<String>> coversByRepo = new LinkedHashMap<>();
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            modelRepos.add(modelSeed.repo());
            List<String> list = coversByRepo.computeIfAbsent(modelSeed.repo(), k -> new ArrayList<>());
            for (String id : modelSeed.covers()) {
                if (!list.contains(id)) {
                    list.add(id);
                }
            }
        }

        List<String> discrepancies = new ArrayList<>();
        Set<String> modelOnlyFlagged = new LinkedHashSet<>();
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            if (!touchpointRepos.contains(modelSeed.repo()) && !candidateRepos.contains(modelSeed.repo())) {
                if (!modelOnlyFlagged.contains(modelSeed.repo())) {
                    discrepancies.add("model-only: " + modelSeed.repo() + " (" + modelSeed.reason() + ")");
                    modelOnlyFlagged.add(modelSeed.repo());
                }
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
                Set<String> reasonsAdded = new LinkedHashSet<>();
                for (Seed seed : seeds) {
                    if (seed.repo().equals(root)) {
                        String reasonLine = seed.source() + " " + seed.detail();
                        if (!reasonsAdded.contains(reasonLine)) {
                            reasons.add(reasonLine);
                            reasonsAdded.add(reasonLine);
                        }
                    }
                }
                affected.add(new AffectedRepo(root, "seed", "SEED",
                        coversByRepo.getOrDefault(root, List.of()), reasons));
            }
            expansion = Closure.expand(jdbi, roots, anchorTypes);
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
                discrepancies, problems, warnings, anchorTypes);
    }

    private static void addSeed(List<Seed> seeds, Set<String> seedKeys, Seed seed) {
        if (seedKeys.add(seed.repo() + "|" + seed.source() + "|" + seed.detail())) {
            seeds.add(seed);
        }
    }
}
