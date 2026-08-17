package sdd.core.toolchain;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides how Gradle is invoked in one repository: the repo's own wrapper when it has one,
 * otherwise a configured Gradle.
 *
 * <p><b>Wrapper first, always.</b> A wrapper pins the Gradle version its repo was written for, and
 * this estate spans several. Preferring one configured Gradle over a present wrapper would run a
 * 6.9 repo on 8.x and fail harder, and less legibly, than the missing wrapper it was meant to fix.
 * The fallback exists because <em>requiring</em> a wrapper is what turned "this repo builds with the
 * Gradle on my machine" into a hard stop — six call sites each raised their own
 * "no executable gradle wrapper" problem before any build was attempted.
 *
 * <p>Resolution order mirrors {@code NodeLocator}, deliberately: {@code gradle_home} from
 * {@code sdd.yml}, then {@code SDD_GRADLE}, then {@code PATH}. And it inherits that class's rule
 * that a <em>configured</em> toolchain which is missing is an error worth reporting rather than a
 * reason to quietly run a different one — otherwise the machine's default wins with nothing to say
 * so.
 *
 * <p>Resolving an absolute path rather than leaning on the child's PATH is necessary, not
 * tidiness: {@link ProcessBuilder} looks a command up on the PARENT's PATH and ignores the PATH in
 * the environment it is handed, and every Gradle subprocess here runs under a scrubbed environment.
 */
public final class GradleLauncher {

    /**
     * @param executable what to exec — {@code "./gradlew"} for a wrapper (relative, because every
     *     call site already runs with the repo root as its working directory), or an absolute path
     *     to a configured Gradle
     * @param wrapper whether the repo's own wrapper was chosen, which callers report so a human can
     *     tell which Gradle actually ran
     * @param problem why nothing could be resolved, or null when {@link #found()} is true
     */
    public record Resolution(String executable, boolean wrapper, String problem) {
        public boolean found() {
            return executable != null;
        }
    }

    private GradleLauncher() {
    }

    /**
     * @param gradleHome the configured {@code gradle_home}, or null. When set, only
     *     {@code <gradleHome>/bin/gradle} is considered.
     */
    public static Resolution resolve(Path repoRoot, Path gradleHome) {
        if (Files.isExecutable(repoRoot.resolve("gradlew"))) {
            return new Resolution("./gradlew", true, null);
        }
        if (gradleHome != null) {
            Path candidate = gradleHome.toAbsolutePath().resolve("bin").resolve("gradle");
            return Files.isExecutable(candidate)
                    ? new Resolution(candidate.toString(), false, null)
                    : new Resolution(null, false, "no gradle wrapper in " + repoRoot
                            + " and no executable gradle at " + candidate
                            + " (from gradle_home in sdd.yml)");
        }
        String fromEnv = System.getenv("SDD_GRADLE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path candidate = Path.of(fromEnv);
            return Files.isExecutable(candidate)
                    ? new Resolution(candidate.toAbsolutePath().toString(), false, null)
                    : new Resolution(null, false, "no gradle wrapper in " + repoRoot
                            + " and no executable gradle at " + candidate + " (from $SDD_GRADLE)");
        }
        String pathVar = System.getenv("PATH");
        if (pathVar != null) {
            for (String dir : pathVar.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve("gradle");
                if (Files.isExecutable(candidate)) {
                    return new Resolution(candidate.toAbsolutePath().toString(), false, null);
                }
            }
        }
        return new Resolution(null, false, "no gradle wrapper in " + repoRoot
                + " and no gradle on PATH — set gradle_home in sdd.yml or export SDD_GRADLE");
    }
}
