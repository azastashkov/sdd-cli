package sdd.index.source;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/** Builds real (tiny) jar files on disk so tests exercise JarTypeSolver against actual archives. */
final class TestJars {
    private TestJars() {}

    /** A valid, readable jar with no classes in it — enough for JarTypeSolver to open. */
    static Path tinyJar(Path dir, String name) throws Exception {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("placeholder.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
        return jar;
    }
}
