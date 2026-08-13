package sdd.cli.implement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrator-owned, run-scoped jar assembly: {@code ./gradlew assemble --no-configuration-cache
 * --no-daemon -q}, then collects every {@code *.jar} under {@code build/libs} (repo root and its
 * depth-1 modules), excluding {@code -sources}/{@code -javadoc} jars, into a caller-supplied
 * directory. Mirrors {@link MavenLocalPublisher}: env-scrubbed (PATH/HOME/LANG/TMPDIR + JAVA_HOME),
 * never model-reachable, same log shape ("exit N\n…" / "timed out after Ns") so InfraClassifier
 * patterns apply unchanged. Feeds japicmp baseline/candidate jars for the compat gate (design line 62).
 */
public final class JarBuilder {
    private static final List<String> KEEP_ENV = List.of("PATH", "HOME", "LANG", "TMPDIR");
    private static final int MAX_LOG = 200_000;

    private final Duration timeout;

    public JarBuilder() {
        this(Duration.ofMinutes(10));
    }

    public JarBuilder(Duration timeout) {
        this.timeout = timeout;
    }

    public record Result(boolean ok, List<Path> jars, String log) {
    }

    public Result build(Path repoRoot, Path javaHome, Path outDir) {
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, List.of(), "no gradle wrapper in " + repoRoot);
        }
        Path log = null;
        try {
            log = Files.createTempFile("sdd-jarbuild", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", "assemble",
                    "--no-configuration-cache", "--no-daemon", "-q"));
            builder.directory(repoRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            scrub(builder.environment(), javaHome);
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return new Result(false, List.of(), "timed out after " + timeout.toSeconds() + "s");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (output.length() > MAX_LOG) {
                output = output.substring(0, MAX_LOG);
            }
            boolean ok = process.exitValue() == 0;
            List<Path> jars = ok ? collectJars(repoRoot, outDir) : List.of();
            return new Result(ok, jars, "exit " + process.exitValue() + "\n" + output);
        } catch (IOException e) {
            return new Result(false, List.of(), "build failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, List.of(), "interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    private static List<Path> collectJars(Path repoRoot, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        List<Path> copied = new ArrayList<>();
        List<Path> libDirs = new ArrayList<>();
        libDirs.add(repoRoot.resolve("build/libs"));
        try (var children = Files.list(repoRoot)) {
            children.filter(Files::isDirectory)
                    .map(child -> child.resolve("build/libs"))
                    .sorted()
                    .forEach(libDirs::add);
        }
        for (Path libDir : libDirs) {
            if (!Files.isDirectory(libDir)) {
                continue;
            }
            try (var jars = Files.list(libDir)) {
                for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    String name = jar.getFileName().toString();
                    if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) {
                        continue;
                    }
                    copied.add(Files.copy(jar, outDir.resolve(name), StandardCopyOption.REPLACE_EXISTING));
                }
            }
        }
        return copied;
    }

    private static void scrub(Map<String, String> env, Path javaHome) {
        Map<String, String> keep = new HashMap<>();
        for (String name : KEEP_ENV) {
            String value = System.getenv(name);
            if (value != null) {
                keep.put(name, value);
            }
        }
        env.clear();
        env.putAll(keep);
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome.toAbsolutePath().toString());
        }
    }
}
