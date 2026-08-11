package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JarSolverCacheTest {
    @TempDir Path tmp;

    @Test
    void goodPathYieldsFreshInstancePerCall() throws Exception {
        Path jar = TestJars.tinyJar(tmp, "a.jar");
        JarSolverCache cache = new JarSolverCache();
        var first = cache.get(jar);
        var second = cache.get(jar);
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        // Solvers must never be shared: CombinedTypeSolver.add() re-parents whatever it is given,
        // and JarTypeSolver.setParent throws once a parent is set (JavaParser 3.26.2).
        assertThat(first.get()).isNotSameAs(second.get());
    }

    @Test
    void missingJarCachesFailureWithoutThrowing() {
        JarSolverCache cache = new JarSolverCache();
        Path ghost = tmp.resolve("no-such.jar");
        assertThat(cache.get(ghost)).isEmpty();
        assertThat(cache.get(ghost)).isEmpty(); // second call also clean, from failure cache
    }
}
