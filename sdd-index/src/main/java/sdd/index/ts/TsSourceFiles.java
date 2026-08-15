package sdd.index.ts;

import sdd.index.store.Paths2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which files in a package the indexer reads.
 *
 * <p>Scoped to match the Java side rather than to match TypeScript's own notion of a program: the
 * Java extractor indexes {@code src/main/java} and nothing else, so tests, generated output and
 * build configuration are all outside what the estate's facts are drawn from. A test file's
 * invented endpoints are not estate facts, and indexing them would put paths in the knowledge base
 * that no deployed code ever calls.
 */
public final class TsSourceFiles {

    private static final Set<String> EXTENSIONS = Set.of(".ts", ".tsx", ".mts", ".cts");

    /**
     * Never descended into, whatever any config says. {@code node_modules} alone is tens of
     * thousands of files in this estate, and {@code dist} holds generated copies of the very
     * sources being read — indexing both would double every symbol and slow a repo to a crawl.
     */
    private static final Set<String> PRUNED = Set.of(
            "node_modules", "dist", "build", "coverage", "out", ".git", ".sdd", ".idea", "target");

    private static final int MAX_DEPTH = 12;

    private TsSourceFiles() {
    }

    public static List<Path> discover(Path moduleDir) {
        return discover(moduleDir, List.of());
    }

    /**
     * @param otherModuleDirs every other module directory in the same repo. A workspaces monorepo
     *                        nests its packages inside the root module's directory, so without this
     *                        the root's walk swallows all of them and every call site in the repo is
     *                        recorded twice — once against the package that makes it and once
     *                        against the root — inflating caller lists with a repo that only
     *                        contains the caller.
     */
    public static List<Path> discover(Path moduleDir, java.util.Collection<Path> otherModuleDirs) {
        Path root = Paths2.canonical(moduleDir);
        List<Path> nested = otherModuleDirs.stream()
                .map(Paths2::canonical)
                .filter(d -> !d.equals(root) && d.startsWith(root))
                .toList();
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(f -> !isPruned(root, f))
                    .filter(f -> nested.stream().noneMatch(f::startsWith))
                    .filter(TsSourceFiles::isSource)
                    .sorted()
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
        return found;
    }

    private static boolean isPruned(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative.getParent() == null ? relative : relative.getParent()) {
            if (PRUNED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        if (!EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return false;
        }
        if (name.endsWith(".d.ts")) {
            return false;   // a generated or hand-written declaration, not a source of call sites
        }
        // Tests and bundler configuration describe how the code is exercised and built, not what
        // the deployed application does.
        return !name.contains(".test.") && !name.contains(".spec.")
                && !name.contains(".config.") && !name.contains(".fixture.");
    }
}
