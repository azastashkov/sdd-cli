package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

class JarSolverCacheTest {
    @TempDir Path tmp;

    private Path writeTinyJar(String name) throws Exception {
        Path jar = tmp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("placeholder.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void samePathYieldsSameSolverInstance() throws Exception {
        Path jar = writeTinyJar("a.jar");
        JarSolverCache cache = new JarSolverCache();
        var first = cache.get(jar);
        var second = cache.get(jar);
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get()).isSameAs(second.get());
    }

    @Test
    void missingJarCachesFailureWithoutThrowing() {
        JarSolverCache cache = new JarSolverCache();
        Path ghost = tmp.resolve("no-such.jar");
        assertThat(cache.get(ghost)).isEmpty();
        assertThat(cache.get(ghost)).isEmpty(); // second call also clean, from failure cache
    }
}
