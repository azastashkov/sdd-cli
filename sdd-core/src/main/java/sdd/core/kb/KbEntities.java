package sdd.core.kb;

import org.jdbi.v3.core.Jdbi;
import sdd.core.route.Routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static List<EntityMatch> resolveRepo(Jdbi jdbi, String value) {
        return jdbi.withHandle(h -> h.createQuery("SELECT name FROM repo WHERE name = :n")
                .bind("n", value).mapTo(String.class).list()).stream()
                .map(name -> new EntityMatch(name, name, "repo"))
                .toList();
    }

    private static List<EntityMatch> resolveEndpoint(Jdbi jdbi, String value) {
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
                        WHERE a.grp = :g AND a.name = :n
                        ORDER BY r.name""")
                .bind("g", grp).bind("n", name).mapTo(String.class).list()).stream()
                .map(repo -> new EntityMatch(repo, value, "artifact"))
                .toList();
    }
}
