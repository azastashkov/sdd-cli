package sdd.index.testing;

import sdd.index.extract.BuildExtractor;
import sdd.index.extract.BuildModel;

import java.nio.file.Path;
import java.util.function.Function;

/**
 * A {@link BuildExtractor} backed by a plain function, so a test can drive the whole indexing
 * pipeline without running a build. Replaces the old single-method {@code IndexService.Extractor}
 * seam, which could no longer carry everything the pipeline needs to know — a repo's build system
 * is persisted, so a stub has to be able to name one.
 *
 * <p>{@link #detects} answers true for every repo: a stub is injected precisely because the test
 * wants it used, and making tests lay out convincing build files just to satisfy detection would
 * couple every pipeline test to Gradle's file layout.
 */
public final class StubBuildExtractor implements BuildExtractor {
    private final String buildSystem;
    private final Function<Path, BuildModel.Extract> extract;
    private final Function<Path, BuildModel.Extract> fallback;

    private StubBuildExtractor(String buildSystem,
                               Function<Path, BuildModel.Extract> extract,
                               Function<Path, BuildModel.Extract> fallback) {
        this.buildSystem = buildSystem;
        this.extract = extract;
        this.fallback = fallback;
    }

    /** Reports GRADLE, and returns an empty extract from its fallback. */
    public static StubBuildExtractor of(Function<Path, BuildModel.Extract> extract) {
        return new StubBuildExtractor("GRADLE", extract,
                dir -> new BuildModel.Extract(java.util.List.of(), java.util.List.of()));
    }

    /** For exercising the DEGRADED path, where extraction throws and the fallback answers. */
    public static StubBuildExtractor of(Function<Path, BuildModel.Extract> extract,
                                        Function<Path, BuildModel.Extract> fallback) {
        return new StubBuildExtractor("GRADLE", extract, fallback);
    }

    public static StubBuildExtractor of(String buildSystem,
                                        Function<Path, BuildModel.Extract> extract,
                                        Function<Path, BuildModel.Extract> fallback) {
        return new StubBuildExtractor(buildSystem, extract, fallback);
    }

    @Override
    public boolean detects(Path repoDir) {
        return true;
    }

    @Override
    public String buildSystem() {
        return buildSystem;
    }

    @Override
    public BuildModel.Extract extract(Path repoDir) {
        return extract.apply(repoDir);
    }

    @Override
    public BuildModel.Extract fallback(Path repoDir) {
        return fallback.apply(repoDir);
    }
}
