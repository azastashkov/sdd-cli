package sdd.core.kb;

import org.jdbi.v3.core.Jdbi;
import sdd.core.route.Routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves a free-text or spec-authored value against the knowledge base, one definition shared
 * by sdd plan's touchpoint verification and sdd explain's entity lookup. Moved verbatim from
 * SeedFinder (sdd-plan) so both commands agree on what an entity is — the queries keep their
 * existing semantics, byte-for-byte, with one exception called out at {@link #resolveArtifact}.
 */
public final class KbEntities {
    private static final Pattern VERB = Pattern.compile("[A-Z]+");

    private KbEntities() {
    }

    public static Resolution resolve(Jdbi jdbi, EntityKind kind, String value) {
        List<EntityMatch> matches = switch (kind) {
            case REPO -> resolveRepo(jdbi, value);
            case ENDPOINT -> resolveEndpoint(jdbi, value);
            case TOPIC -> resolveTopic(jdbi, value);
            case CLASS -> resolveClass(jdbi, value);
            case ARTIFACT -> resolveArtifact(jdbi, value);
        };
        return new Resolution(kind, value, matches);
    }

    public static String missReason(EntityKind kind) {
        return switch (kind) {
            case REPO -> "no such repo in the knowledge base";
            case ENDPOINT -> "no endpoint matches";
            case TOPIC -> "no known topic with roles";
            case CLASS -> "no such type in the knowledge base";
            case ARTIFACT -> "not linked to any indexed module";
        };
    }

    public static String repoOfModule(Jdbi jdbi, long moduleId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT r.name FROM module m JOIN repo r ON r.id = m.repo_id WHERE m.id = :m")
                .bind("m", moduleId).mapTo(String.class).findOne().orElse(null));
    }

    public static List<String> repoNames(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("SELECT name FROM repo ORDER BY name")
                .mapTo(String.class).list());
    }

    public static List<String> topicNames(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("SELECT name FROM kafka_topic ORDER BY name")
                .mapTo(String.class).list());
    }

    /**
     * {@code "VERB /norm/path"} for every indexed endpoint, {@code ORDER BY} for determinism. A
     * NULL {@code http_method} renders {@code "ANY"} — the same spelling {@link #resolveEndpoint}
     * already uses for an unspecified verb, so this is one convention, not two. {@code DISTINCT}
     * because two repos can expose the same verb+path shape and this is a name vocabulary, not a
     * per-repo listing — a duplicate label would just be prompt noise.
     */
    public static List<String> endpointLabels(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT http_method, norm_path FROM rest_endpoint
                        WHERE norm_path IS NOT NULL ORDER BY norm_path, http_method""")
                .mapToMap().list()).stream()
                .map(row -> (row.get("http_method") == null ? "ANY" : String.valueOf(row.get("http_method")))
                        + " " + row.get("norm_path"))
                .toList();
    }

    private static List<EntityMatch> resolveRepo(Jdbi jdbi, String value) {
        return jdbi.withHandle(h -> h.createQuery("SELECT name FROM repo WHERE name = :n")
                .bind("n", value).mapTo(String.class).list()).stream()
                .map(name -> new EntityMatch(name, name, "repo"))
                .toList();
    }

    /**
     * Exact-match-first: a name the system itself offered in its vocabulary (e.g. from
     * {@link #endpointLabels}) must resolve back to the one row that produced it, not to every
     * row a fuzzy template match happens to also cover. {@code verb} is {@code null} when the
     * caller omitted it — that is deliberately distinct from an explicit {@code "ANY"}: omitted
     * matches every http_method at the exact path, while explicit ANY names only the
     * NULL-http_method row. Only when the exact pass finds nothing do we fall back to the
     * original fuzzy {@code templatesMatch}/{@code verbsCompatible} scan, unchanged, for a real
     * literal path (e.g. {@code /api/candles/7/symbols}) that no row matches exactly.
     */
    private static List<EntityMatch> resolveEndpoint(Jdbi jdbi, String value) {
        String verb = null;
        String path = value.strip();
        int space = path.indexOf(' ');
        if (space > 0 && VERB.matcher(path.substring(0, space)).matches()) {
            verb = path.substring(0, space);
            path = path.substring(space + 1).strip();
        }
        String touchNorm = Routes.normalize(path);
        List<Map<String, Object>> endpoints = jdbi.withHandle(h -> h.createQuery("""
                        SELECT e.http_method AS verb, e.norm_path AS norm, r.name AS repo
                        FROM rest_endpoint e
                        JOIN module m ON m.id = e.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE e.norm_path IS NOT NULL ORDER BY r.name""")
                .mapToMap().list());

        List<EntityMatch> exact = exactEndpointMatches(endpoints, touchNorm, verb);
        if (!exact.isEmpty()) {
            return exact;
        }

        String finalVerb = verb == null ? "ANY" : verb;
        List<EntityMatch> matches = new ArrayList<>();
        for (Map<String, Object> e : endpoints) {
            String endpointVerb = e.get("verb") == null ? "ANY" : String.valueOf(e.get("verb"));
            String norm = String.valueOf(e.get("norm"));
            if (Routes.templatesMatch(touchNorm, norm) && Routes.verbsCompatible(finalVerb, endpointVerb)) {
                matches.add(new EntityMatch(String.valueOf(e.get("repo")), endpointVerb + " " + norm, "rest_endpoint"));
            }
        }
        return matches;
    }

    private static List<EntityMatch> exactEndpointMatches(List<Map<String, Object>> endpoints, String touchNorm, String verb) {
        List<EntityMatch> exact = new ArrayList<>();
        for (Map<String, Object> e : endpoints) {
            String norm = String.valueOf(e.get("norm"));
            if (!touchNorm.equals(norm)) {
                continue;
            }
            String endpointVerb = e.get("verb") == null ? null : String.valueOf(e.get("verb"));
            boolean verbMatches = verb == null
                    || ("ANY".equals(verb) ? endpointVerb == null : verb.equals(endpointVerb));
            if (verbMatches) {
                exact.add(new EntityMatch(String.valueOf(e.get("repo")),
                        (endpointVerb == null ? "ANY" : endpointVerb) + " " + norm, "rest_endpoint"));
            }
        }
        return exact;
    }

    private static List<EntityMatch> resolveTopic(Jdbi jdbi, String value) {
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT r.name AS repo, kr.role AS role FROM kafka_role kr
                        JOIN kafka_topic t ON t.id = kr.topic_id
                        JOIN module m ON m.id = kr.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE t.name = :n ORDER BY r.name""")
                .bind("n", value).mapToMap().list());
        return rows.stream()
                .map(row -> new EntityMatch(String.valueOf(row.get("repo")), String.valueOf(row.get("role")), "kafka_role"))
                .toList();
    }

    private static List<EntityMatch> resolveClass(Jdbi jdbi, String value) {
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT r.name AS repo, t.fqcn AS fqcn FROM java_type t
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE t.fqcn = :v OR (:dotless AND t.fqcn LIKE '%.' || :v)
                        ORDER BY r.name""")
                .bind("v", value)
                .bind("dotless", !value.contains("."))
                .mapToMap().list());
        return rows.stream()
                .map(row -> new EntityMatch(String.valueOf(row.get("repo")), String.valueOf(row.get("fqcn")), "java_type"))
                .toList();
    }

    /**
     * The original {@code SeedFinder.artifactRepos} had no {@code ORDER BY} — the one
     * non-deterministic resolution branch. This extraction adds {@code ORDER BY r.name}: an
     * intentional determinism fix, not an accidental behavior change.
     */
    private static List<EntityMatch> resolveArtifact(Jdbi jdbi, String value) {
        Optional<ArtifactRef> ref = ArtifactRef.parse(value);
        if (ref.isEmpty()) {
            return List.of();
        }
        String grp = ref.get().grp();
        String name = ref.get().name();
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT r.name FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE a.grp = :g AND a.name = :n
                        ORDER BY r.name""")
                .bind("g", grp).bind("n", name).mapTo(String.class).list()).stream()
                .map(repo -> new EntityMatch(repo, value, "artifact"))
                .toList();
    }
}
