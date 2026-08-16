package sdd.core.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which build system owns a repository. Not a language: a repo's toolchain decides how it is built
 * and verified, while the language of its sources decides which reader can say anything about them.
 * The two happen to agree across this estate, and keeping them separate is what stops that
 * coincidence being baked in.
 */
public enum Toolchain {
    GRADLE,
    NPM,
    UNKNOWN;

    /**
     * Reads the toolchain off the filesystem.
     *
     * <p>Gradle is probed first, deliberately: a Spring service that ships a {@code package.json}
     * to build its frontend assets is a Gradle repo, and the reverse ordering would quietly
     * reclassify it and change how it is built and verified.
     *
     * <p>This is the last resort in the resolution order — an explicit {@code sdd.yml} setting and
     * the knowledge base's recorded {@code build_system} both come first — but it is what lets the
     * execution path work on a repo that has not been indexed yet, and what keeps it right when the
     * knowledge base is stale.
     */
    public static Toolchain detect(Path repoRoot) {
        if (Files.isExecutable(repoRoot.resolve("gradlew"))
                || Files.isRegularFile(repoRoot.resolve("settings.gradle"))
                || Files.isRegularFile(repoRoot.resolve("settings.gradle.kts"))
                || Files.isRegularFile(repoRoot.resolve("build.gradle"))
                || Files.isRegularFile(repoRoot.resolve("build.gradle.kts"))) {
            return GRADLE;
        }
        if (Files.isRegularFile(repoRoot.resolve("package.json"))) {
            return NPM;
        }
        return UNKNOWN;
    }

    /** Parses a value recorded in {@code repo.build_system}; UNKNOWN for null or anything else. */
    public static Toolchain of(String recorded) {
        if (recorded == null) {
            return UNKNOWN;
        }
        return switch (recorded) {
            case "GRADLE" -> GRADLE;
            case "NPM" -> NPM;
            default -> UNKNOWN;
        };
    }
}
