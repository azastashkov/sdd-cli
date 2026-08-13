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

    /** The repo's current KB root-module version, or null when unindexed. */
    public static String current(Jdbi jdbi, String repo) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT m.version FROM module m JOIN repo r ON r.id = m.repo_id "
                                + "WHERE r.name = :repo AND m.gradle_path = ':'")
                .bind("repo", repo)
                .mapTo(String.class)
                .findFirst()
                .orElse(null));
    }
}
