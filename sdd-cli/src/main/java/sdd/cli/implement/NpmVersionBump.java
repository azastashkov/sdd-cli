package sdd.cli.implement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Moves a consumer's declared dependency on a provider when the provider's version changes.
 *
 * <p>Most of the time this does nothing, and that is the correct outcome. Nearly every npm
 * dependency is declared as a RANGE, and a range that already admits the new version needs no edit
 * — rewriting {@code ^0.2.1} to {@code ^0.2.2} would be churn in a diff a human has to review.
 *
 * <p>The case that matters is a range that STOPS admitting the provider once it moves: {@code ^0.2.1}
 * against a planned {@code 0.3.0}. Left alone the consumer silently keeps resolving the old package,
 * and the release looks fine while shipping a version pairing nobody tested. Where the range's shape
 * is understood the range is widened minimally; where it is not, nothing is edited and the problem
 * is reported, because a specifier grammar this expressive is not somewhere to guess.
 *
 * <p>Edits replace only the specifier's own characters. Round-tripping through a JSON parser
 * would reformat key order, indentation and trailing newline, turning a one-line version change into
 * a whole-file diff.
 */
public final class NpmVersionBump {

    /** Every scope a dependency can be declared in; all are rewritten. */
    private static final List<String> SCOPES = List.of(
            "dependencies", "devDependencies", "peerDependencies", "optionalDependencies");

    private static final Set<String> PRUNED = Set.of(
            "node_modules", "dist", "build", "coverage", "out", ".git", ".sdd", "target");

    private static final int MAX_DEPTH = 8;


    private NpmVersionBump() {
    }

    /** What happened, so the caller can report an unmade edit as loudly as a made one. */
    public record Result(List<Path> edited, List<String> warnings) {
        public Result {
            edited = List.copyOf(edited);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * @param packageName the provider's npm package name, e.g. {@code @acme/web-sdk}
     * @param newVersion  the provider's planned version
     */
    public static Result apply(Path repoRoot, String packageName, String newVersion) {
        List<Path> edited = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Path manifest : manifests(repoRoot)) {
            if (rewrite(manifest, packageName, newVersion, warnings)) {
                edited.add(manifest);
            }
        }
        // A lockfile records exact resolutions and is regenerated, not edited: a hand-patched one is
        // internally inconsistent in ways npm resolves unpredictably, which is worse than an absent
        // edit. Nothing sdd runs consumes it — it never runs `npm ci` — so saying so is enough.
        if (!edited.isEmpty()) {
            for (Path manifest : edited) {
                Path lock = manifest.resolveSibling("package-lock.json");
                if (Files.isRegularFile(lock)) {
                    warnings.add(lock + " was not updated — run npm install before releasing");
                }
            }
        }
        return new Result(edited, warnings);
    }

    /**
     * Rewrites in place by character offset rather than line by line.
     *
     * <p>Layout-independent on purpose: npm writes package.json multi-line, but a hand-written or
     * minified one is legal, and a version bump that silently skipped such a file would leave the
     * consumer pinned to the old provider with nothing reported — the exact failure this whole
     * mechanism exists to prevent. Only the specifier's own characters are replaced, so
     * indentation, key order and trailing newline all survive.
     */
    private static boolean rewrite(Path manifest, String packageName, String newVersion,
                                   List<String> warnings) {
        String text;
        try {
            text = Files.readString(manifest, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }
        Pattern declaration = Pattern.compile(
                "(\"" + Pattern.quote(packageName) + "\"\\s*:\\s*\")([^\"]*)(\")");
        StringBuilder out = new StringBuilder(text);
        boolean changed = false;
        // Right-to-left so an edit never shifts the offsets of the blocks still to be examined.
        List<int[]> blocks = dependencyBlocks(text);
        for (int b = blocks.size() - 1; b >= 0; b--) {
            int[] block = blocks.get(b);
            Matcher m = declaration.matcher(text.substring(block[0], block[1]));
            List<int[]> hits = new ArrayList<>();
            List<String> specs = new ArrayList<>();
            while (m.find()) {
                hits.add(new int[]{block[0] + m.start(2), block[0] + m.end(2)});
                specs.add(m.group(2));
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                String replacement =
                        widened(specs.get(i), newVersion, packageName, manifest, warnings);
                if (replacement != null && !replacement.equals(specs.get(i))) {
                    out.replace(hits.get(i)[0], hits.get(i)[1], replacement);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return false;
        }
        try {
            Files.writeString(manifest, out.toString(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The character ranges of each dependency object. Bounding the search this way is what stops a
     * package's OWN {@code "version"} — or a same-named key anywhere else in the file — being
     * mistaken for a declaration of it.
     */
    private static List<int[]> dependencyBlocks(String text) {
        List<int[]> blocks = new ArrayList<>();
        for (String scope : SCOPES) {
            Matcher opener = Pattern.compile("\"" + scope + "\"\\s*:\\s*\\{").matcher(text);
            while (opener.find()) {
                int open = text.indexOf('{', opener.start());
                int close = matchingBrace(text, open);
                if (close > open) {
                    blocks.add(new int[]{open, close});
                }
            }
        }
        blocks.sort((a, b) -> Integer.compare(a[0], b[0]));
        return blocks;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * The specifier this declaration should carry, or null to leave it alone.
     *
     * <p>Returns the original when the range already admits the new version — the common case, and
     * the reason this usually edits nothing.
     */
    static String widened(String spec, String newVersion, String packageName, Path manifest,
                          List<String> warnings) {
        String trimmed = spec.strip();
        if (trimmed.startsWith("workspace:") || trimmed.startsWith("file:")
                || trimmed.startsWith("link:") || trimmed.startsWith("portal:")) {
            return null;   // already points at source; a version has no meaning here
        }
        if (trimmed.equals(newVersion)) {
            return null;
        }
        if (isExact(trimmed)) {
            return newVersion;   // a pin, moved
        }
        if (trimmed.startsWith("^") || trimmed.startsWith("~")) {
            String base = trimmed.substring(1);
            if (!isExact(base)) {
                return reportUnknown(spec, newVersion, packageName, manifest, warnings);
            }
            return admits(trimmed.charAt(0), base, newVersion)
                    ? null                                   // still in range: no churn
                    : trimmed.charAt(0) + newVersion;        // range broke: widen minimally
        }
        return reportUnknown(spec, newVersion, packageName, manifest, warnings);
    }

    private static String reportUnknown(String spec, String newVersion, String packageName,
                                        Path manifest, List<String> warnings) {
        warnings.add(manifest + " declares " + packageName + "@" + spec
                + " but the planned version is " + newVersion
                + " — this range's shape is not understood, so it was left unedited;"
                + " widen it by hand if it no longer admits the new version");
        return null;
    }

    /** Whether a caret or tilde range over {@code base} still admits {@code candidate}. */
    static boolean admits(char operator, String base, String candidate) {
        int[] b = parse(base);
        int[] c = parse(candidate);
        if (b == null || c == null) {
            return false;
        }
        if (compare(c, b) < 0) {
            return false;   // older than the floor
        }
        if (operator == '~') {
            return c[0] == b[0] && c[1] == b[1];   // patch-level changes only
        }
        // Caret: the leftmost NON-ZERO element is what is held fixed, which is why ^0.2.1 does not
        // admit 0.3.0 — for a 0.x package the minor is the breaking-change axis.
        if (b[0] != 0) {
            return c[0] == b[0];
        }
        if (b[1] != 0) {
            return c[0] == 0 && c[1] == b[1];
        }
        return c[0] == 0 && c[1] == 0 && c[2] == b[2];
    }

    private static boolean isExact(String s) {
        return parse(s) != null;
    }

    /** Strictly {@code major.minor.patch}, with an optional prerelease/build suffix. */
    private static int[] parse(String version) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matcher(version.strip());
        return m.matches()
                ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3))}
                : null;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static List<Path> manifests(Path repoRoot) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals("package.json"))
                    .filter(f -> !isPruned(repoRoot, f))
                    .sorted()
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
        return found;
    }

    private static boolean isPruned(Path repoRoot, Path file) {
        Path relative = repoRoot.relativize(file);
        for (Path part : relative.getParent() == null ? relative : relative.getParent()) {
            if (PRUNED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
