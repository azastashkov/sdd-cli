package sdd.index.source;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class SourceParser {
    public record ParsedUnit(Path file, String relPath, CompilationUnit cu) {}
    public record Session(List<ParsedUnit> units, List<String> issues) {}

    private SourceParser() {}

    /** {@code src/main/java} plus {@code build/generated} when present, for a single module. */
    public static List<Path> sourceRootsOf(Path moduleDir) {
        List<Path> roots = new ArrayList<>();
        Path main = moduleDir.resolve("src/main/java");
        if (Files.isDirectory(main)) {
            roots.add(main);
        }
        Path generated = moduleDir.resolve("build/generated");
        if (Files.isDirectory(generated)) {
            roots.add(generated);
        }
        return roots;
    }

    public static Session parseModule(Path repoRoot, Path moduleDir, List<Path> classpathJars) {
        return parseModule(repoRoot, moduleDir, classpathJars, new JarSolverCache());
    }

    public static Session parseModule(Path repoRoot, Path moduleDir, List<Path> classpathJars,
                                      JarSolverCache jarCache) {
        List<Path> roots = sourceRootsOf(moduleDir);
        if (roots.isEmpty()) {
            return new Session(List.of(), List.of());
        }

        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        for (Path root : roots) {
            solver.add(new JavaParserTypeSolver(root));
        }
        for (Path jar : classpathJars) {
            jarCache.get(jar).ifPresent(solver::add);
        }
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(solver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        return parseRoots(repoRoot, roots, new JavaParser(config));
    }

    /**
     * Parses a single module's source roots with an already-built configuration — used by
     * {@link RepoSolver}, where one {@code ParserConfiguration} (and its shared symbol solver) is
     * built once for the whole repo and reused across every module's parse.
     */
    public static Session parseModule(Path repoRoot, Path moduleDir, ParserConfiguration config) {
        List<Path> roots = sourceRootsOf(moduleDir);
        if (roots.isEmpty()) {
            return new Session(List.of(), List.of());
        }
        return parseRoots(repoRoot, roots, new JavaParser(config));
    }

    private static Session parseRoots(Path repoRoot, List<Path> roots, JavaParser parser) {
        List<ParsedUnit> units = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(f -> {
                    String rel = repoRoot.relativize(f).toString().replace('\\', '/');
                    try {
                        var result = parser.parse(f);
                        if (result.isSuccessful() && result.getResult().isPresent()) {
                            units.add(new ParsedUnit(f, rel, result.getResult().get()));
                        } else {
                            issues.add(rel + ": " + result.getProblems());
                        }
                    } catch (Exception | StackOverflowError e) {
                        // The symbol solver recurses on deeply generic or mutually referential
                        // types and overflows the stack on real code. By the time this catch runs
                        // the stack has unwound, so the file is recorded as an issue and the walk
                        // continues — one pathological file must not cost us the whole module.
                        // Named explicitly instead of catching Error: OOM must still be fatal.
                        issues.add(rel + ": " + describe(e));
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Session(List.copyOf(units), List.copyOf(issues));
    }

    /** StackOverflowError carries no message, so fall back to the type name. */
    private static String describe(Throwable t) {
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
