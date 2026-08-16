package sdd.cli.implement;

import sdd.core.ts.TsSidecar;
import sdd.index.npm.PackageJson;
import sdd.index.ts.PackageEntries;
import sdd.index.ts.TsSourceFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The npm counterpart of {@link JarBuilder}: writes a repo's declaration files so the same tree can
 * be compared against itself before and after an edit.
 *
 * <p>A baseline has to be captured as an ARTIFACT rather than re-derived later, because the
 * candidate's sources overwrite the baseline's in the same working tree — exactly why the japicmp
 * gate builds a baseline jar up front instead of comparing two checkouts.
 *
 * <p>Declarations are emitted by the pinned compiler, never by the repo's own {@code tsc}: an
 * estate whose repos each carry a different TypeScript would produce a comparison whose verdict
 * depended on which two versions happened to meet.
 */
public final class DtsBuilder {

    /** One package's emitted entry declaration, and where its sources came from. */
    public record Emitted(String packageName, Path entryDts) {
    }

    /**
     * @param ok       false only when nothing could be emitted at all; a package that individually
     *                 failed is absent from {@code packages} and named in {@code log}
     * @param packages one entry per package whose declarations were written
     */
    public record Result(boolean ok, List<Emitted> packages, String log) {
        public Result {
            packages = List.copyOf(packages);
        }
    }

    private final Path nodeHome;

    public DtsBuilder(Path nodeHome) {
        this.nodeHome = nodeHome;
    }

    /**
     * @param outDir a directory per run phase — baseline and candidate must never share one, or
     *               the second emit silently overwrites the first and the gate compares a tree
     *               against itself and always passes
     */
    public Result build(Path repoRoot, Path outDir) {
        Optional<TsSidecar> sidecar = TsSidecar.create(nodeHome);
        if (sidecar.isEmpty()) {
            return new Result(false, List.of(), "no node available to emit declarations");
        }
        Path canonicalRoot = canonical(repoRoot);
        List<Path> packageDirs = NpmPackages.roots(canonicalRoot);
        if (packageDirs.isEmpty()) {
            return new Result(false, List.of(), "no package.json found in " + repoRoot);
        }

        List<Emitted> emitted = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path packageDir : packageDirs) {
            String name;
            try {
                name = PackageJson.read(packageDir.resolve("package.json")).name();
            } catch (Exception e) {
                continue;
            }
            if (name == null) {
                continue;   // a nameless package.json is a workspaces shell, with no surface
            }
            PackageEntries.Result entries = PackageEntries.of(packageDir, name);
            Path entry = entries.entries().stream()
                    .filter(e -> e.specifier().equals(name))
                    .map(PackageEntries.Entry::sourceFile)
                    .findFirst().orElse(null);
            if (entry == null) {
                // No root entry point: the package publishes subpaths only, or nothing at all.
                // Not a failure — a design-system package whose whole surface is CSS is normal.
                continue;
            }
            List<Path> files = TsSourceFiles.discover(packageDir, packageDirs);
            if (files.isEmpty()) {
                continue;
            }
            Path packageOut = outDir.resolve(slug(name));
            Path rootDir = PackageEntries.tsconfigPath(packageDir, "rootDir");
            TsSidecar.Result result = sidecar.get().emitDeclarations(canonicalRoot, files, entry,
                    rootDir == null ? null : packageDir.resolve(rootDir), packageOut);
            if (!result.ok()) {
                problems.add(name + ": " + result.error());
                continue;
            }
            emitted.add(new Emitted(name, Path.of(result.json().path("entryDts").asText())));
        }
        return new Result(!emitted.isEmpty(), emitted, String.join("; ", problems));
    }

    /** A package name is a filesystem path here, and a scoped one carries a slash. */
    private static String slug(String packageName) {
        return packageName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** Visible for the gate, which needs the same emitted-package layout to pair the two sides. */
    static boolean isEmpty(Path outDir) {
        return !Files.isDirectory(outDir);
    }
}
