package sdd.index.npm;

import sdd.index.gradle.ConsumptionMode;

/**
 * How an npm dependency specifier is consumed. The npm sibling of {@code ModeClassifier}, and it
 * exists because that one encodes Maven version grammar: under Gradle's rules {@code ^0.2.1} has
 * no {@code +}, does not end {@code -SNAPSHOT} and does not start {@code [}, {@code (} or
 * {@code latest.}, so every caret range in the estate would be classified PINNED — precisely
 * backwards, and silently.
 *
 * <p>{@code BOM_MANAGED} and {@code SNAPSHOT} are unreachable here by construction: npm has no
 * dependency-management import, and a specifier is never absent (an empty value is {@code "*"},
 * which is dynamic). Prerelease versions like {@code 1.0.0-beta.1} are exact, not snapshots — npm
 * resolves them to that exact build.
 */
public final class NpmModeClassifier {
    private NpmModeClassifier() {
    }

    /**
     * @param workspaceSibling true when the dependency resolves to another package in the same
     *                         repo's workspaces, which npm materialises as a symlink to live source
     *                         — the same "built from source next door" relationship a Gradle
     *                         included build expresses
     */
    public static ConsumptionMode classify(String spec, boolean workspaceSibling) {
        if (workspaceSibling) {
            return ConsumptionMode.COMPOSITE;
        }
        if (spec == null || spec.isBlank()) {
            return ConsumptionMode.DYNAMIC;     // an absent specifier means "*"
        }
        String s = spec.trim();
        // Protocol specifiers that point at something other than a registry version. file:/link:/
        // portal:/workspace: all mean "use that source tree", which is COMPOSITE regardless of
        // whether the target happens to sit inside this repo's declared workspaces.
        if (s.startsWith("file:") || s.startsWith("link:") || s.startsWith("portal:")
                || s.startsWith("workspace:")) {
            return ConsumptionMode.COMPOSITE;
        }
        if (s.startsWith("npm:") || s.startsWith("git+") || s.startsWith("git:")
                || s.startsWith("github:") || s.contains("://")) {
            return ConsumptionMode.DYNAMIC;     // aliases and remote refs are not a pinned version
        }
        if (isExactVersion(s)) {
            return ConsumptionMode.PINNED;
        }
        return ConsumptionMode.DYNAMIC;
    }

    /**
     * An exact semver and nothing else: no range operator, no wildcard, no whitespace (which would
     * mean a compound range like {@code ">=1 <2"}), no {@code ||} union.
     */
    private static boolean isExactVersion(String s) {
        if (s.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        return s.matches("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.\\-+]+)?");
    }
}
