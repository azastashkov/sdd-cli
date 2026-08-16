package sdd.cli.implement;

import org.jdbi.v3.core.Jdbi;

import java.nio.file.Path;
import java.util.List;

/**
 * The npm packages a repo publishes, read from the knowledge base.
 *
 * <p>A repo is not one package. A workspaces monorepo publishes several from directories that are
 * not its root, and {@code npm pack} has to run where the package.json actually is — so the
 * directory travels with the name rather than being assumed.
 */
public final class NpmPackages {

    /** @param packageDir the package's own directory, which for a workspace member is not the root */
    public record Publishable(String packageName, Path packageDir) {
    }

    private NpmPackages() {
    }

    /**
     * Every directory in a repo holding a {@code package.json}, which is what makes a directory an
     * npm package. One definition, shared by contract actualization and the compat gate, so the
     * two can never disagree about what the repo contains.
     *
     * <p>Same depth cap and skip list as the Java module walk: a workspaces monorepo nests its
     * packages one or two deep, and {@code node_modules} holds thousands of manifests that are
     * somebody else's packages.
     */
    public static List<Path> roots(Path repoRoot) {
        List<Path> roots = new java.util.ArrayList<>();
        collectRoots(repoRoot, 0, roots);
        return roots.stream().sorted().toList();
    }

    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(".git", "build",
            ".gradle", ".sdd", ".idea", "node_modules", "dist", "target", "coverage");
    private static final int MAX_DEPTH = 4;

    private static void collectRoots(Path dir, int depth, List<Path> roots) {
        if (java.nio.file.Files.isRegularFile(dir.resolve("package.json"))) {
            roots.add(dir);
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        try (var children = java.nio.file.Files.list(dir)) {
            children.filter(java.nio.file.Files::isDirectory)
                    .filter(child -> !SKIP_DIRS.contains(child.getFileName().toString()))
                    .forEach(child -> collectRoots(child, depth + 1, roots));
        } catch (java.io.IOException e) {
            // unreadable directory: fall through with whatever was found so far
        }
    }

    public static List<Publishable> publishableOf(Jdbi jdbi, String repo) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT a.name AS package_name, r.path AS repo_path, m.gradle_path AS module_path
                        FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :repo AND a.grp = 'npm' AND m.kind = 'LIBRARY'
                        ORDER BY a.name""")
                .bind("repo", repo)
                .map((rs, ctx) -> new Publishable(rs.getString("package_name"),
                        // module.gradle_path is ":" for the repo root and ":<dir>" for a member;
                        // the leading colon is the separator, not part of the path.
                        resolveModuleDir(rs.getString("repo_path"), rs.getString("module_path"))))
                .list());
    }

    /**
     * A package's own runtime {@code dependencies}, which an overlay does NOT install.
     *
     * <p>peerDependencies and devDependencies are deliberately excluded: a peer is the consumer's
     * responsibility by definition, and a dev dependency is not needed to import the package. Only
     * a runtime dependency can make an overlaid package fail where a published one would work.
     */
    public static List<String> runtimeDependencies(Path packageDir) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(java.nio.file.Files.readString(packageDir.resolve("package.json")));
            com.fasterxml.jackson.databind.JsonNode deps = root.path("dependencies");
            List<String> names = new java.util.ArrayList<>();
            deps.fieldNames().forEachRemaining(names::add);
            return List.copyOf(names);
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    static Path resolveModuleDir(String repoPath, String modulePath) {
        Path root = Path.of(repoPath);
        if (modulePath == null || modulePath.isBlank() || ":".equals(modulePath)) {
            return root;
        }
        return root.resolve(modulePath.startsWith(":") ? modulePath.substring(1) : modulePath);
    }
}
