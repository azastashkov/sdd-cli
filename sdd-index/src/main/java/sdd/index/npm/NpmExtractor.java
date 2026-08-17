package sdd.index.npm;

import sdd.index.extract.BuildExtractor;
import sdd.index.extract.BuildModel;
import sdd.index.gradle.ExtractionException;
import sdd.index.store.Paths2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads an npm repo's build from its {@code package.json} files. No subprocess, no network, and no
 * {@code node} — everything needed is declared in files the repo checks in, so an npm repo indexes
 * identically on a machine that has never installed Node.
 *
 * <p>{@code node_modules} is never read. It records what someone installed, which is not a fact
 * about the repository, and trusting it would make the index depend on install state.
 */
public final class NpmExtractor implements BuildExtractor {
    public static final String BUILD_SYSTEM = "NPM";

    /** The group every npm artifact is stored under; see {@link #coordinateFor}. */
    public static final String NPM_GROUP = "npm";

    /** Directories that never contain a workspace package, whatever a glob says. */
    private static final Set<String> PRUNED =
            Set.of("node_modules", "dist", "build", "coverage", ".git", "out", "target");

    private static final int MAX_WORKSPACE_DEPTH = 6;

    @Override
    public boolean detects(Path repoDir) {
        return Files.isRegularFile(repoDir.resolve("package.json"));
    }

    @Override
    public String buildSystem() {
        return BUILD_SYSTEM;
    }

    @Override
    public BuildModel.Extract extract(Path repoDir) {
        try {
            return read(repoDir);
        } catch (IOException e) {
            throw new ExtractionException("cannot read npm build in " + repoDir + ": " + e.getMessage(), e);
        }
    }

    /**
     * The root package alone. Reached when the full read fails — a malformed workspace member, an
     * unglobbable pattern — and it is worth keeping the root's own dependencies rather than
     * recording nothing.
     */
    @Override
    public BuildModel.Extract fallback(Path repoDir) {
        try {
            PackageJson root = PackageJson.read(repoDir.resolve("package.json"));
            return new BuildModel.Extract(List.of(toModule(":", root, repoDir)), List.of());
        } catch (IOException | RuntimeException e) {
            return new BuildModel.Extract(List.of(), List.of());
        }
    }

    private BuildModel.Extract read(Path repoDir) throws IOException {
        PackageJson root = PackageJson.read(repoDir.resolve("package.json"));

        // Members first, so their names are known before the root's own edges are classified.
        Map<String, Path> memberDirs = new LinkedHashMap<>();     // module path -> directory
        Map<String, PackageJson> members = new LinkedHashMap<>();
        for (Path dir : resolveWorkspaceDirs(repoDir, root.workspacePatterns())) {
            PackageJson pkg = PackageJson.read(dir.resolve("package.json"));
            if (pkg.name() == null) {
                continue;   // a package with no name cannot be depended on, so it is not a module
            }
            String modulePath = ":" + Paths2.canonical(repoDir).relativize(dir).toString().replace('\\', '/');
            memberDirs.put(modulePath, dir);
            members.put(modulePath, pkg);
        }

        // A dependency naming a package declared inside this same repo is satisfied from live
        // source rather than the registry — the COMPOSITE relationship. That is not decided here:
        // ArtifactLinker resolves internal edges after every repo is persisted and marks the
        // same-repo ones, which is the only point where both ends are known.
        List<BuildModel.Module> modules = new ArrayList<>();
        // The root is always a module, even when it is a workspaces-only shell with no name, no
        // version and no sources: it is the anchor that artifact_overrides and version planning
        // address as ":", exactly like a Gradle root project that applies no plugins.
        modules.add(toModule(":", root, repoDir));
        members.forEach((path, pkg) -> modules.add(toModule(path, pkg, memberDirs.get(path))));
        return new BuildModel.Extract(modules, List.of());
    }

    private static BuildModel.Module toModule(String modulePath, PackageJson pkg, Path dir) {
        Map<String, BuildModel.DepScope> scopes = new LinkedHashMap<>();
        pkg.dependencyScopes().forEach((scope, deps) -> {
            List<BuildModel.DeclaredDep> declared = new ArrayList<>();
            deps.forEach((name, spec) -> {
                BuildModel.Coordinate c = coordinateFor(name);
                declared.add(new BuildModel.DeclaredDep(c.group(), c.name(), spec));
            });
            scopes.put(scope, new BuildModel.DepScope(declared, List.of(), List.of()));
        });

        // Every NAMED package gets a coordinate, private ones included. This is the name-to-module
        // index that dependency resolution reads, and npm workspaces let a sibling depend on a
        // private package by name — so omitting private packages would leave those intra-repo edges
        // permanently unresolved. `private` governs whether a registry will accept the package, not
        // whether anything in the estate can refer to it. A package with no name cannot be
        // referred to at all, so it gets nothing.
        List<BuildModel.Coordinate> publishes = pkg.name() == null
                ? List.of()
                : List.of(coordinateFor(pkg.name()));

        return new BuildModel.Module(modulePath, pkg.name(), NPM_GROUP, pkg.version(), dir,
                kindOf(pkg, dir), "TYPESCRIPT", publishes, scopes);
    }

    /**
     * npm's namespace is flat and globally unique, so the whole package name — scope included —
     * is the artifact name, under a constant {@code "npm"} group.
     *
     * <p>Splitting {@code @scope/pkg} into group {@code @scope} and name {@code pkg} was the
     * obvious alternative and is wrong: an unscoped package like {@code react} or {@code shell}
     * would then have an empty group, and {@code ArtifactRef.parse} requires both halves of a
     * {@code "grp:name"} reference to be non-empty — so those packages would become unaddressable
     * in specs. The constant group also makes a rendered reference
     * self-describing: {@code npm:@azastashkov/web-sdk}.
     */
    public static BuildModel.Coordinate coordinateFor(String packageName) {
        return new BuildModel.Coordinate(NPM_GROUP, packageName);
    }

    /**
     * SERVICE is something deployed, LIBRARY something consumed by another package, mirroring what
     * the Gradle extractor reads out of plugin ids.
     *
     * <p>A published package is a LIBRARY: {@code private} is npm's own statement that a package is
     * not for distribution, and the publish signals say it is set up to be imported. An application
     * is recognised by how it is served — an HTML entry point, a bundler config, or a script that
     * runs it — since npm has no equivalent of the Spring Boot plugin to key off.
     */
    private static String kindOf(PackageJson pkg, Path dir) {
        if (!pkg.isPrivate() && pkg.hasPublishSignals()) {
            return "LIBRARY";
        }
        if (pkg.isPrivate() && looksLikeApp(pkg, dir)) {
            return "SERVICE";
        }
        return "UNKNOWN";
    }

    private static boolean looksLikeApp(PackageJson pkg, Path dir) {
        if (Files.isRegularFile(dir.resolve("index.html"))) {
            return true;
        }
        for (String bundler : List.of("vite.config.ts", "vite.config.js", "vite.config.mts",
                "webpack.config.js", "rollup.config.js", "rollup.config.mjs")) {
            if (Files.isRegularFile(dir.resolve(bundler))) {
                return true;
            }
        }
        return pkg.scripts().containsKey("start") || pkg.scripts().containsKey("serve")
                || pkg.scripts().containsKey("preview");
    }

    /**
     * Expands the {@code workspaces} globs. npm patterns are path globs over directories
     * ({@code packages/*}), so they are matched with a {@link PathMatcher} against repo-relative
     * paths, pruning vendored and generated trees whatever the pattern says.
     */
    private static List<Path> resolveWorkspaceDirs(Path repoDir, List<String> patterns) throws IOException {
        if (patterns.isEmpty()) {
            return List.of();
        }
        // Walk the canonical path, not the one the scanner listed. Files.walk does not follow
        // symbolic links, and a workspace holds its repos as symlinks — so walking the link itself
        // yields exactly one entry, the link, and every workspaces monorepo silently indexes as a
        // single module. That is the real deployment shape, so it would have been the normal case
        // rather than an edge case. Paths2.canonical exists for this same class of mismatch
        // elsewhere in the pipeline.
        Path root = Paths2.canonical(repoDir);
        List<PathMatcher> matchers = patterns.stream()
                .map(p -> root.getFileSystem().getPathMatcher("glob:" + trimTrailingSlash(p)))
                .toList();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MAX_WORKSPACE_DEPTH)) {
            walk.filter(Files::isDirectory)
                    .filter(d -> !d.equals(root))
                    .filter(d -> !isPruned(root, d))
                    .filter(d -> Files.isRegularFile(d.resolve("package.json")))
                    .filter(d -> {
                        Path rel = root.relativize(d);
                        return matchers.stream().anyMatch(m -> m.matches(rel));
                    })
                    .sorted()
                    .forEach(found::add);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return found;
    }

    private static boolean isPruned(Path repoDir, Path dir) {
        for (Path part : repoDir.relativize(dir)) {
            if (PRUNED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String trimTrailingSlash(String pattern) {
        String p = pattern;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
