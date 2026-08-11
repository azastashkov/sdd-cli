package sdd.index.store;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Path canonicalization shared by everything that has to compare or relativize paths captured by
 * different parts of the pipeline. The sides disagree about symlinks: repo directories come from
 * {@link sdd.index.scan.WorkspaceScanner}'s directory listing (not symlink-resolved), while
 * project directories come from Gradle's own {@code projectDir.absolutePath}, which Gradle
 * canonicalizes internally. On macOS this shows up as {@code /var/...} vs. the real
 * {@code /private/var/...} for the same directory, which breaks both the composite-build match in
 * {@link ArtifactLinker} (string equality) and repo-relative source paths (relativize).
 */
public final class Paths2 {
    private Paths2() {}

    /**
     * Resolves a path to its canonical (symlink-free) form, falling back to a plain absolute,
     * normalized path when the path does not exist on disk (e.g. fixture paths in unit tests), so
     * behavior for non-existent paths is unchanged.
     */
    public static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** {@link #canonical(Path)} rendered for storage/comparison as a string. */
    public static String canonicalString(Path p) {
        return canonical(p).toString();
    }
}
