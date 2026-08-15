package sdd.index.extract;

import java.nio.file.Path;

/**
 * Reads one repository's build into the neutral {@link BuildModel}. One implementation per build
 * system; {@code IndexService} picks the first whose {@link #detects} answers yes.
 *
 * <p>Both extraction methods exist because a build can fail to run without the repo being
 * unreadable: {@link #extract} is the accurate answer and may be expensive and may throw, while
 * {@link #fallback} is the cheap declared-only answer that must never throw. That split is what
 * lets a repo be indexed DEGRADED rather than FAILED.
 */
public interface BuildExtractor {

    /**
     * Whether this extractor claims the repo. Must be cheap and filesystem-only — no subprocess, no
     * network — because it runs for every extractor against every repo on every index.
     *
     * <p>Order matters where a repo could match twice: a Spring service that ships a
     * {@code package.json} for its frontend assets is a Gradle repo, and the Gradle extractor is
     * consulted first so it stays one.
     */
    boolean detects(Path repoDir);

    /** Stable identifier persisted to {@code repo.build_system}: {@code GRADLE} or {@code NPM}. */
    String buildSystem();

    /**
     * The accurate model, by whatever means the build system requires.
     *
     * @throws sdd.index.gradle.ExtractionException when the build could not be read; the caller
     *                                              falls back to {@link #fallback} and records
     *                                              DEGRADED
     */
    BuildModel.Extract extract(Path repoDir);

    /**
     * A declared-only model derived without running the build. Returns an empty extract rather than
     * throwing when it can determine nothing — an empty result is meaningful (the caller keeps the
     * previous, now stale, picture instead of deleting it) whereas an exception here would turn a
     * degraded repo into a failed one.
     */
    BuildModel.Extract fallback(Path repoDir);
}
