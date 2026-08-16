package sdd.cli.implement;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Subprocess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrator-owned, run-scoped jar assembly: {@code ./gradlew assemble <extraArgs...>
 * --no-configuration-cache --no-daemon -q} — {@code extraArgs} is the caller's INCLUDE_BUILD/init-script
 * substitution flags (same slot as {@link sdd.agent.tool.GradleTool}), so mid-chain providers that also
 * consume an upstream build against the right substituted tree instead of a stale published one. Then
 * collects every {@code *.jar} under {@code build/libs} (repo root and its
 * depth-1 modules), excluding {@code -sources}/{@code -javadoc} jars, into a caller-supplied
 * directory. Mirrors {@link MavenLocalPublisher}: env-scrubbed (PATH/HOME/LANG/TMPDIR + JAVA_HOME),
 * never model-reachable, same log shape ("exit N\n…" / "timed out after Ns") so InfraClassifier
 * patterns apply unchanged. Feeds japicmp baseline/candidate jars for the compat gate (design line 62).
 */
public final class JarBuilder {
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

    public Result build(Path repoRoot, Path javaHome, Path outDir, List<String> extraArgs) {
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            return new Result(false, List.of(), "no gradle wrapper in " + repoRoot);
        }
        try {
            List<String> command = new ArrayList<>();
            command.add("./gradlew");
            command.add("assemble");
            command.addAll(extraArgs);   // orchestrator-appended substitution flags (same slot as GradleTool)
            command.add("--no-configuration-cache");
            command.add("--no-daemon");
            command.add("-q");
            Subprocess.Outcome outcome = Subprocess.run(command, repoRoot,
                    EnvPolicy.scrubbedJvm(javaHome), timeout,
                    Subprocess.KillPolicy.PROCESS_TREE, "sdd-jarbuild");
            if (outcome.timedOut()) {
                return new Result(false, List.of(), "timed out after " + timeout.toSeconds() + "s");
            }
            String output = outcome.output();
            if (output.length() > MAX_LOG) {
                output = output.substring(0, MAX_LOG);
            }
            boolean ok = outcome.exitCode() == 0;
            List<Path> jars = List.of();
            if (ok) {
                try {
                    jars = collectJars(repoRoot, outDir);
                } catch (IOException e) {
                    return new Result(false, List.of(), "jar collection failed after exit 0: " + e.getMessage());
                }
            }
            return new Result(ok, jars, "exit " + outcome.exitCode() + "\n" + output);
        } catch (IOException e) {
            return new Result(false, List.of(), "build failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, List.of(), "interrupted");
        }
    }

    /** A {@code build/libs} directory to scan, tagged with its owning module's directory name
     * ({@code null} for repoRoot itself, which is scanned first and so never needs disambiguation). */
    private record LibDir(Path path, String moduleDirName) {
    }

    private static List<Path> collectJars(Path repoRoot, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        List<Path> copied = new ArrayList<>();
        List<LibDir> libDirs = new ArrayList<>();
        libDirs.add(new LibDir(repoRoot.resolve("build/libs"), null));
        try (var children = Files.list(repoRoot)) {
            children.filter(Files::isDirectory)
                    .sorted()
                    .forEach(child -> libDirs.add(
                            new LibDir(child.resolve("build/libs"), child.getFileName().toString())));
        }
        for (LibDir libDir : libDirs) {
            if (!Files.isDirectory(libDir.path())) {
                continue;
            }
            try (var jars = Files.list(libDir.path())) {
                for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    String name = jar.getFileName().toString();
                    if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) {
                        continue;
                    }
                    String targetName = name;
                    if (libDir.moduleDirName() != null && Files.exists(outDir.resolve(targetName))) {
                        targetName = libDir.moduleDirName() + "-" + name;
                    }
                    copied.add(Files.copy(jar, outDir.resolve(targetName), StandardCopyOption.REPLACE_EXISTING));
                }
            }
        }
        return copied;
    }

}
