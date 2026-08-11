package sdd.index.source;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.nio.file.Path;
import java.util.List;

/**
 * One solver per repo: all modules' source roots plus the jar union.
 * Each JarTypeSolver is constructed fresh here and parented exactly once —
 * sharing instances across solvers is unsupported on JavaParser 3.26.2.
 */
public final class RepoSolver {
    private RepoSolver() {}

    public static ParserConfiguration configFor(List<Path> sourceRoots, List<Path> uniqueClasspathJars) {
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        for (Path root : sourceRoots) {
            solver.add(new JavaParserTypeSolver(root));
        }
        for (Path jar : uniqueClasspathJars) {
            try {
                solver.add(new JarTypeSolver(jar));
            } catch (Exception ignored) {
                // unreadable jar — resolve without it
            }
        }
        return new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(solver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
    }
}
