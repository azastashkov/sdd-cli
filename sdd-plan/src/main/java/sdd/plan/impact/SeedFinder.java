package sdd.plan.impact;

import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.EntityKind;
import sdd.core.kb.KbEntities;
import sdd.core.kb.Resolution;
import sdd.core.retrieve.Hit;
import sdd.core.retrieve.Retriever;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Impact stage A (design): deterministic pre-seed. Touchpoints resolve against the KB — hints
 * verified, never trusted; misses become problems. Requirement free text goes through the
 * Retriever and yields CANDIDATES (not seeds): candidates only enter the affected set if the
 * model confirms them or the graph requires them; the rest surface as excluded. Touchpoint
 * resolution itself lives in sdd.core.kb.KbEntities.
 */
public final class SeedFinder {
    private static final int FTS_LIMIT = 8;

    /**
     * @param anchorTypes the fully-qualified types this spec is anchored on, from its type-shaped
     *     touchpoints. Distinct from seeds, which are repos: an anchor names the thing that is
     *     changing, which is what lets downstream stages ask "does this consumer use the thing that
     *     changed" rather than "does it use anything at all". Always the RESOLVED fqcn, never the
     *     spelling the spec used, because a spec may legitimately write a bare simple name.
     */
    public record SeedScan(List<Seed> seeds, List<Seed> candidates, List<String> problems,
                           Set<String> anchorTypes) {
        public SeedScan {
            seeds = List.copyOf(seeds);
            candidates = List.copyOf(candidates);
            problems = List.copyOf(problems);
            anchorTypes = Set.copyOf(anchorTypes);
        }

        public SeedScan(List<Seed> seeds, List<Seed> candidates, List<String> problems) {
            this(seeds, candidates, problems, Set.of());
        }
    }

    private SeedFinder() {
    }

    public static SeedScan find(Jdbi jdbi, Retriever retriever, NormalizedSpec spec) {
        List<Seed> seeds = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> anchorTypes = new LinkedHashSet<>();
        for (Touchpoint touchpoint : spec.touchpoints()) {
            EntityKind kind = toEntityKind(touchpoint.kind());
            Resolution resolution = KbEntities.resolve(jdbi, kind, touchpoint.value());
            String label = touchpoint.kind().key() + ":" + touchpoint.value();
            if (resolution.isEmpty()) {
                problems.add("touchpoint " + label + ": " + KbEntities.missReason(kind));
            } else {
                for (String repo : resolution.repos()) {
                    seeds.add(new Seed(repo, "touchpoint", label));
                }
                // Only type-shaped kinds contribute anchors, and the fqcn is read off the matched
                // row rather than off the touchpoint's text. EntityMatch.source names the table the
                // row came from, which is what makes "is this a type" a fact rather than a guess.
                if (kind == EntityKind.CLASS || kind == EntityKind.SYMBOL) {
                    resolution.matches().stream()
                            .filter(m -> "java_type".equals(m.source()))
                            .forEach(m -> anchorTypes.add(m.detail()));
                }
            }
        }
        Set<String> seededRepos = new LinkedHashSet<>(seeds.stream().map(Seed::repo).toList());
        List<Seed> candidates = new ArrayList<>();
        Set<String> candidateRepos = new LinkedHashSet<>();
        for (SpecItem requirement : spec.requirements()) {
            for (Hit hit : retriever.search(requirement.text(), FTS_LIMIT)) {
                String repo = KbEntities.repoOfModule(jdbi, hit.moduleId());
                if (repo == null || seededRepos.contains(repo) || !candidateRepos.add(repo)) {
                    continue;
                }
                candidates.add(new Seed(repo, "fts", requirement.id() + " hit: " + hit.identifier()));
            }
        }
        return new SeedScan(seeds, candidates, problems, anchorTypes);
    }

    private static EntityKind toEntityKind(Touchpoint.Kind kind) {
        return switch (kind) {
            case REPO -> EntityKind.REPO;
            case ENDPOINT -> EntityKind.ENDPOINT;
            case TOPIC -> EntityKind.TOPIC;
            case CLASS -> EntityKind.CLASS;
            case ARTIFACT -> EntityKind.ARTIFACT;
        };
    }
}
