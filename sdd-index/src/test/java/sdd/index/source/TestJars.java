package sdd.index.source;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
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

    static boolean compilerAvailable() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    /**
     * Compiles {@code source} (declaring {@code simpleName}) and packages the resulting class files
     * into a jar — a stand-in for an estate jar published by another repo.
     */
    static Path compiledJar(Path dir, String jarName, String simpleName, String source)
            throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system java compiler; guard with assumeTrue");
        }
        Path srcDir = Files.createDirectories(dir.resolve(jarName + "-src"));
        Path classesDir = Files.createDirectories(dir.resolve(jarName + "-classes"));
        Path srcFile = srcDir.resolve(simpleName + ".java");
        Files.writeString(srcFile, source);
        int rc = compiler.run(null, null, null,
                "-d", classesDir.toString(), srcFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("javac failed with exit code " + rc);
        }
        Path jar = dir.resolve(jarName);
        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            classFiles = walk.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path c : classFiles) {
                out.putNextEntry(new ZipEntry(
                        classesDir.relativize(c).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(c));
                out.closeEntry();
            }
        }
        return jar;
    }
}
