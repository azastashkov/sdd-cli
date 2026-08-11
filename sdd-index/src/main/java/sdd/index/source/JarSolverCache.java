package sdd.index.source;

import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.resolution.TypeSolver;
import sdd.index.store.Paths2;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Per-repo cache: one JarTypeSolver per jar path; failures cached as empty. */
public final class JarSolverCache {
    private final Map<String, Optional<TypeSolver>> cache = new HashMap<>();

    public Optional<TypeSolver> get(Path jar) {
        return cache.computeIfAbsent(Paths2.canonicalString(jar), key -> {
            try {
                return Optional.of(new JarTypeSolver(jar));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
