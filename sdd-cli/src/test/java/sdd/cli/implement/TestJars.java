package sdd.cli.implement;

import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Compiles one class and jars it — real bytecode for japicmp tests without touching Gradle.
 *  PUBLIC: ImplementCommandContractTest lives in package sdd.cli and imports this. */
public final class TestJars {
    private TestJars() {
    }

    public static Path jar(Path dir, String jarName, String className, String source) throws Exception {
        Path work = Files.createTempDirectory(dir, "jarsrc");
        Path src = work.resolve(className + ".java");
        Files.writeString(src, source);
        Path classes = Files.createDirectories(work.resolve("classes"));
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", classes.toString(), src.toString());
        if (rc != 0) {
            throw new IllegalStateException("fixture compile failed for " + className);
        }
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                try (var in = Files.newInputStream(file)) {
                    in.transferTo((OutputStream) out);
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Compiles every entry in {@code sources} together (so cross-references resolve and compilation
     *  succeeds) but packages every produced .class EXCEPT {@code omitClassName} — reproducing a real
     *  jar that references a third-party class not present in either archive (e.g. groovy.lang.Closure),
     *  which is what tripped japicmp on the live real-estate run. */
    public static Path jarOmitting(Path dir, String jarName, Map<String, String> sources,
                                   String omitClassName) throws Exception {
        Path work = Files.createTempDirectory(dir, "jarsrc");
        List<String> args = new ArrayList<>();
        args.add("-d");
        Path classes = Files.createDirectories(work.resolve("classes"));
        args.add(classes.toString());
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path src = work.resolve(entry.getKey() + ".java");
            Files.writeString(src, entry.getValue());
            args.add(src.toString());
        }
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) {
            throw new IllegalStateException("fixture compile failed for " + sources.keySet());
        }
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().equals(omitClassName + ".class"))
                    .toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                try (var in = Files.newInputStream(file)) {
                    in.transferTo((OutputStream) out);
                }
                out.closeEntry();
            }
        }
        return jar;
    }
}
