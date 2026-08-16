package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each plan repo's planned (post-run) version: the KB root-module version bumped by the plan's
 * version_action (design line 61 — {@code publishToMavenLocal -Pversion=<planned>}). Repos absent
 * from the returned map have no computable planned version (no KB root-module version, or a
 * non-semver version under a real bump action) — callers that need one treat absence as a
 * pre-flight problem. Numeric-suffix-preserving: 1.2.3-SNAPSHOT + minor = 1.3.0-SNAPSHOT.
 */
public final class PlannedVersions {
    private static final Pattern SEMVER = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(.*)");

    private PlannedVersions() {
    }

    public static Map<String, String> compute(Jdbi jdbi, PlanModel plan) {
        Map<String, String> planned = new LinkedHashMap<>();
        for (PlanModel.PlanRepo repo : plan.repos()) {
            String current = current(jdbi, repo.name());
            if (current == null || current.isBlank()) {
                continue;
            }
            String next = bump(current, repo.versionAction());
            if (next != null) {
                planned.put(repo.name(), next);
            }
        }
        return planned;
    }

    static String bump(String version, String action) {
        if (action == null || action.isBlank() || "none".equals(action)) {
            return version;
        }
        Matcher m = SEMVER.matcher(version);
        if (!m.matches()) {
            return null;
        }
        int major = Integer.parseInt(m.group(1));
        int minor = Integer.parseInt(m.group(2));
        int patch = Integer.parseInt(m.group(3));
        String suffix = m.group(4);
        return switch (action) {
            case "patch" -> major + "." + minor + "." + (patch + 1) + suffix;
            case "minor" -> major + "." + (minor + 1) + ".0" + suffix;
            case "major" -> (major + 1) + ".0.0" + suffix;
            default -> version;
        };
    }

    /**
     * The repo's current version: its root module's, or — when that has none — the single
     * publishable module's.
     *
     * <p>The fallback exists because "one version per repo" is a Gradle assumption that npm
     * workspaces break. A workspaces root is frequently a private shell with no version at all,
     * while the thing consumers actually depend on lives in a member package. Reading only the root
     * reports "no computable version" for such a repo and turns every pin onto it into a pre-flight
     * problem, when the version was sitting one module away.
     *
     * <p>Deliberately only when there is exactly ONE publishable module. Two would mean the repo
     * has two independent versions and no single answer to bump, which is a question for a human
     * rather than a default to pick.
     */
    public static String current(Jdbi jdbi, String repo) {
        String rootVersion = jdbi.withHandle(h -> h.createQuery(
                        "SELECT m.version FROM module m JOIN repo r ON r.id = m.repo_id "
                                + "WHERE r.name = :repo AND m.gradle_path = ':'")
                .bind("repo", repo)
                .mapTo(String.class)
                .findFirst()
                .orElse(null));
        if (rootVersion != null && !rootVersion.isBlank()) {
            return rootVersion;
        }
        java.util.List<String> published = jdbi.withHandle(h -> h.createQuery("""
                        SELECT m.version FROM module m
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :repo AND m.kind = 'LIBRARY'
                          AND m.version IS NOT NULL AND m.version <> ''
                        ORDER BY m.gradle_path""")
                .bind("repo", repo).mapTo(String.class).list());
        return published.size() == 1 ? published.get(0) : null;
    }
}
