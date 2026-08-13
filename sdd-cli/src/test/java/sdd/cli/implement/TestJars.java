package sdd.cli.implement;

import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
