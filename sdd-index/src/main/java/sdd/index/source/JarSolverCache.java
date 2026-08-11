package sdd.index.source;

import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.resolution.TypeSolver;
import sdd.index.store.Paths2;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Per-repo memory of which classpath jars are unreadable, so an unreadable jar is opened once and
 * skipped on every later request instead of being retried per module.
 *
 * <p>Solver instances are deliberately NOT shared. {@code CombinedTypeSolver.add} re-parents every
 * solver handed to it, and {@link JarTypeSolver#setParent} throws
 * {@code IllegalStateException("This TypeSolver already has a parent.")} once a parent is set
 * (JavaParser 3.26.2) — so handing one instance to two modules of the same repo sinks the whole
 * repo's parse. Every {@code get} for a known-good path therefore builds a fresh solver; only
 * failures are cached.
 */
public final class JarSolverCache {
    private final Set<String> unreadable = new HashSet<>();

    public Optional<TypeSolver> get(Path jar) {
        String key = Paths2.canonicalString(jar);
        if (unreadable.contains(key)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new JarTypeSolver(jar));
        } catch (Exception e) {
            unreadable.add(key);
            return Optional.empty();
        }
    }
}
