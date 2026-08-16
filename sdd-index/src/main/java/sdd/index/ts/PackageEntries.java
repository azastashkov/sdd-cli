package sdd.index.ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a package's PUBLIC entry points back to the source files behind them.
 *
 * <p>This is the crux of cross-repo identity for TypeScript. A consumer writes
 * {@code import { Tick } from '@acme/web-sdk'}; the provider's knowledge of {@code Tick} comes from
 * {@code src/types.ts}, re-exported through {@code src/index.ts}, published as
 * {@code dist/index.d.ts}. Those are three different names for one thing, and unless the indexer
 * records the symbol under the name a CONSUMER would write, nothing joins the two repos.
 *
 * <p>Deliberately plain Java with no compiler involved. The exports map is JSON and the
 * {@code dist}-to-{@code src} inversion is path arithmetic, so keeping them here makes the
 * fiddliest, most estate-specific part of TypeScript support testable without Node.
 */
public final class PackageEntries {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Conditions in preference order. {@code types} first: it names the declaration file, which is
     *  the one that maps back to a source file rather than to a bundle. */
    private static final List<String> CONDITIONS = List.of("types", "import", "default", "require");

    private static final List<String> SOURCE_SUFFIXES =
            List.of(".ts", ".tsx", ".mts", ".cts");

    /** Suffixes an exports target may carry that can still lead back to source. */
    private static final List<String> MAPPABLE_SUFFIXES =
            List.of(".d.ts", ".d.mts", ".d.cts", ".ts", ".tsx", ".js", ".mjs", ".cjs");

    /**
     * @param specifier  what a consumer writes, e.g. {@code @acme/web-sdk} or
     *                   {@code @acme/web-sdk/contract}
     * @param sourceFile the file in the package's own sources that backs it
     */
    public record Entry(String specifier, Path sourceFile) {
    }

    /**
     * @param entries   public entry points that resolve to a readable source file
     * @param partial   true when something in the map could not be followed, so the package's
     *                  recorded surface is known to be incomplete rather than known to be small
     */
    public record Result(List<Entry> entries, boolean partial) {
        public Result {
            entries = List.copyOf(entries);
        }
    }

    private PackageEntries() {
    }

    public static Result of(Path packageDir, String packageName) {
        JsonNode manifest;
        try {
            manifest = MAPPER.readTree(Files.readString(packageDir.resolve("package.json")));
        } catch (IOException | RuntimeException e) {
            return new Result(List.of(), true);
        }
        Map<String, String> targets = targetsOf(manifest, packageName);
        Path outDir = tsconfigPath(packageDir, "outDir");
        Path rootDir = tsconfigPath(packageDir, "rootDir");

        List<Entry> entries = new ArrayList<>();
        boolean partial = false;
        for (Map.Entry<String, String> target : targets.entrySet()) {
            Path source = toSource(packageDir, target.getValue(), outDir, rootDir);
            if (source == null) {
                partial = true;
                continue;
            }
            entries.add(new Entry(target.getKey(), source));
        }
        return new Result(entries, partial);
    }

    /** Specifier to declared target path, for every export that could lead to TypeScript source. */
    private static Map<String, String> targetsOf(JsonNode manifest, String packageName) {
        Map<String, String> targets = new LinkedHashMap<>();
        JsonNode exports = manifest.get("exports");
        if (exports != null && exports.isObject()) {
            exports.fields().forEachRemaining(field -> {
                String key = field.getKey();
                if (key.equals("./package.json") || key.contains("*")) {
                    // package.json is not a surface, and a wildcard subpath names a family rather
                    // than a file — resolving it would mean guessing which members exist.
                    return;
                }
                String target = condition(field.getValue());
                if (target != null && mappable(target)) {
                    targets.put(specifierFor(packageName, key), target);
                }
            });
        }
        if (targets.isEmpty()) {
            // No exports map, or one that names only assets: fall back to the older fields. A
            // package whose whole surface is CSS legitimately has no TypeScript entry at all.
            String fallback = text(manifest, "types");
            if (fallback == null) {
                fallback = text(manifest, "main");
            }
            if (fallback != null && mappable(fallback)) {
                targets.put(packageName, fallback);
            }
        }
        return targets;
    }

    private static String condition(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (!value.isObject()) {
            return null;
        }
        for (String condition : CONDITIONS) {
            JsonNode nested = value.get(condition);
            if (nested != null) {
                String resolved = condition(nested);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private static String specifierFor(String packageName, String key) {
        return ".".equals(key) ? packageName : packageName + key.substring(1);
    }

    private static boolean mappable(String target) {
        return MAPPABLE_SUFFIXES.stream().anyMatch(target::endsWith);
    }

    /**
     * Walks a published target back to the source that produced it.
     *
     * <p>An exports map names build OUTPUT — {@code ./dist/index.d.ts} — which does not exist until
     * something is built and is a generated copy when it does. Indexing it would double every
     * symbol and make the knowledge base depend on whether anyone had run a build.
     */
    private static Path toSource(Path packageDir, String target, Path outDir, Path rootDir) {
        String relative = target.startsWith("./") ? target.substring(2) : target;
        String stripped = stripSuffix(relative);

        List<String> candidates = new ArrayList<>();
        if (outDir != null && (relative.startsWith(outDir + "/") || relative.startsWith(outDir.toString()))) {
            String withoutOut = stripped.substring(Math.min(stripped.length(), outDir.toString().length() + 1));
            if (rootDir != null) {
                candidates.add(rootDir + "/" + withoutOut);
            }
            candidates.add("src/" + withoutOut);
            candidates.add(withoutOut);
        }
        candidates.add(stripped);
        if (rootDir != null) {
            candidates.add(rootDir + "/" + stripped);
        }

        for (String candidate : candidates) {
            for (String suffix : SOURCE_SUFFIXES) {
                Path direct = packageDir.resolve(candidate + suffix);
                if (Files.isRegularFile(direct)) {
                    return canonical(direct);
                }
                Path index = packageDir.resolve(candidate).resolve("index" + suffix);
                if (Files.isRegularFile(index)) {
                    return canonical(index);
                }
            }
        }
        return null;
    }

    /**
     * Canonical, because the file list handed to the compiler is canonical and the two are matched
     * by path STRING. A workspace holds its repos as symlinks and its paths are relative, so an
     * entry recorded as {@code ./repo/src/index.ts} never matches the same file listed as
     * {@code /Users/.../repo/src/index.ts} — and every public export silently disappears while the
     * symbols themselves index fine, which looks like a package that exports nothing.
     */
    private static Path canonical(Path file) {
        try {
            return file.toRealPath();
        } catch (IOException e) {
            return file.toAbsolutePath().normalize();
        }
    }

    private static String stripSuffix(String path) {
        for (String suffix : List.of(".d.ts", ".d.mts", ".d.cts", ".ts", ".tsx",
                ".js", ".mjs", ".cjs")) {
            if (path.endsWith(suffix)) {
                return path.substring(0, path.length() - suffix.length());
            }
        }
        return path;
    }

    /** A tsconfig path option, or null. Read textually — only two scalars are needed. */
    static Path tsconfigPath(Path packageDir, String option) {
        Path tsconfig = packageDir.resolve("tsconfig.json");
        if (!Files.isRegularFile(tsconfig)) {
            return null;
        }
        try {
            JsonNode options = MAPPER.readTree(stripComments(Files.readString(tsconfig)))
                    .path("compilerOptions");
            String value = text(options, option);
            return value == null ? null : Path.of(trimSlashes(value));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** tsconfig.json permits comments; Jackson does not, by default. */
    private static String stripComments(String json) {
        return json.replaceAll("(?m)^\\s*//.*$", "").replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String trimSlashes(String value) {
        String out = value.startsWith("./") ? value.substring(2) : value;
        return out.endsWith("/") ? out.substring(0, out.length() - 1) : out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() || value.asText().isBlank() ? null : value.asText();
    }
}
