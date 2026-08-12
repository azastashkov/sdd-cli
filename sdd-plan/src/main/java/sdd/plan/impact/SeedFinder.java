package sdd.plan.impact;

import org.jdbi.v3.core.Jdbi;
import sdd.core.retrieve.Hit;
import sdd.core.retrieve.Retriever;
import sdd.core.route.Routes;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Impact stage A (design): deterministic pre-seed. Touchpoints resolve against the KB — hints
 * verified, never trusted; misses become problems. Requirement free text goes through the
 * Retriever and yields CANDIDATES (not seeds): candidates only enter the affected set if the
 * model confirms them or the graph requires them; the rest surface as excluded.
 */
public final class SeedFinder {
    private static final Pattern VERB = Pattern.compile("[A-Z]+");
    private static final int FTS_LIMIT = 8;

    public record SeedScan(List<Seed> seeds, List<Seed> candidates, List<String> problems) {
        public SeedScan {
            seeds = List.copyOf(seeds);
            candidates = List.copyOf(candidates);
            problems = List.copyOf(problems);
        }
    }

    private SeedFinder() {
    }

    public static SeedScan find(Jdbi jdbi, Retriever retriever, NormalizedSpec spec) {
        List<Seed> seeds = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Touchpoint touchpoint : spec.touchpoints()) {
            List<String> repos = resolve(jdbi, touchpoint);
            String label = touchpoint.kind().key() + ":" + touchpoint.value();
            if (repos.isEmpty()) {
                problems.add("touchpoint " + label + ": " + missReason(touchpoint));
            } else {
                for (String repo : repos) {
                    seeds.add(new Seed(repo, "touchpoint", label));
                }
            }
        }
        Set<String> seededRepos = new LinkedHashSet<>(seeds.stream().map(Seed::repo).toList());
        List<Seed> candidates = new ArrayList<>();
        Set<String> candidateRepos = new LinkedHashSet<>();
        for (SpecItem requirement : spec.requirements()) {
            for (Hit hit : retriever.search(requirement.text(), FTS_LIMIT)) {
                String repo = repoOfModule(jdbi, hit.moduleId());
                if (repo == null || seededRepos.contains(repo) || !candidateRepos.add(repo)) {
                    continue;
                }
                candidates.add(new Seed(repo, "fts", requirement.id() + " hit: " + hit.identifier()));
            }
        }
        return new SeedScan(seeds, candidates, problems);
    }

    private static List<String> resolve(Jdbi jdbi, Touchpoint touchpoint) {
        return switch (touchpoint.kind()) {
            case REPO -> jdbi.withHandle(h -> h.createQuery(
                            "SELECT name FROM repo WHERE name = :n")
                    .bind("n", touchpoint.value()).mapTo(String.class).list());
            case ENDPOINT -> endpointRepos(jdbi, touchpoint.value());
            case TOPIC -> jdbi.withHandle(h -> h.createQuery("""
                            SELECT DISTINCT r.name FROM kafka_role kr
                            JOIN kafka_topic t ON t.id = kr.topic_id
                            JOIN module m ON m.id = kr.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE t.name = :n ORDER BY r.name""")
                    .bind("n", touchpoint.value()).mapTo(String.class).list());
            case CLASS -> jdbi.withHandle(h -> h.createQuery("""
                            SELECT DISTINCT r.name FROM java_type t
                            JOIN module m ON m.id = t.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE t.fqcn = :v OR (:dotless AND t.fqcn LIKE '%.' || :v)
                            ORDER BY r.name""")
                    .bind("v", touchpoint.value())
                    .bind("dotless", !touchpoint.value().contains("."))
                    .mapTo(String.class).list());
            case ARTIFACT -> artifactRepos(jdbi, touchpoint.value());
        };
    }

    private static List<String> endpointRepos(Jdbi jdbi, String value) {
        String verb = "ANY";
        String path = value.strip();
        int space = path.indexOf(' ');
        if (space > 0 && VERB.matcher(path.substring(0, space)).matches()) {
            verb = path.substring(0, space);
            path = path.substring(space + 1).strip();
        }
        String touchNorm = Routes.normalize(path);
        String finalVerb = verb;
        List<Map<String, Object>> endpoints = jdbi.withHandle(h -> h.createQuery("""
                        SELECT e.http_method AS verb, e.norm_path AS norm, r.name AS repo
                        FROM rest_endpoint e
                        JOIN module m ON m.id = e.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE e.norm_path IS NOT NULL ORDER BY r.name""")
                .mapToMap().list());
        Set<String> repos = new LinkedHashSet<>();
        for (Map<String, Object> e : endpoints) {
            String endpointVerb = e.get("verb") == null ? "ANY" : String.valueOf(e.get("verb"));
            if (Routes.templatesMatch(touchNorm, String.valueOf(e.get("norm")))
                    && Routes.verbsCompatible(finalVerb, endpointVerb)) {
                repos.add(String.valueOf(e.get("repo")));
            }
        }
        return List.copyOf(repos);
    }

    private static List<String> artifactRepos(Jdbi jdbi, String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return List.of();
        }
        String grp = value.substring(0, colon);
        String name = value.substring(colon + 1);
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT r.name FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE a.grp = :g AND a.name = :n""")
                .bind("g", grp).bind("n", name).mapTo(String.class).list());
    }

    private static String missReason(Touchpoint touchpoint) {
        return switch (touchpoint.kind()) {
            case REPO -> "no such repo in the knowledge base";
            case ENDPOINT -> "no endpoint matches";
            case TOPIC -> "no known topic with roles";
            case CLASS -> "no such type in the knowledge base";
            case ARTIFACT -> "not linked to any indexed module";
        };
    }

    private static String repoOfModule(Jdbi jdbi, long moduleId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT r.name FROM module m JOIN repo r ON r.id = m.repo_id WHERE m.id = :m")
                .bind("m", moduleId).mapTo(String.class).findOne().orElse(null));
    }
}
