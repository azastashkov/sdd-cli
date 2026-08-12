package sdd.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The agent's filesystem confinement (design Component 3 guardrails): every tool path is
 * normalized and required to stay under the repo root, `.git` is off-limits, and existing
 * paths are re-checked through `toRealPath` so a symlink cannot smuggle an outside target in.
 */
public final class PathJail {
    private final Path root;

    public PathJail(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path resolve(String relative) {
        Path candidate;
        try {
            candidate = root.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            throw new ToolException("invalid path: " + relative);
        }
        if (!candidate.startsWith(root)) {
            throw new ToolException("path escapes the repo: " + relative);
        }
        for (Path part : root.relativize(candidate)) {
            if (part.toString().equals(".git")) {
                throw new ToolException(".git is off-limits: " + relative);
            }
        }
        return candidate;
    }

    public Path resolveExisting(String relative) {
        Path candidate = resolve(relative);
        if (!Files.exists(candidate)) {
            throw new ToolException("no such file: " + relative);
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root.toRealPath())) {
                throw new ToolException("path escapes the repo: " + relative);
            }
            return real;
        } catch (IOException e) {
            throw new ToolException("cannot resolve " + relative + ": " + e.getMessage());
        }
    }

    /**
     * Resolves a path that may not exist yet (e.g. a new file `apply_edit` is about to create).
     * If the target already exists, this defers to {@link #resolveExisting} so a symlinked TARGET
     * is still realpath-checked. If it doesn't exist, the logical candidate alone isn't enough —
     * a repo-committed symlink standing in for a not-yet-existing PARENT directory (e.g.
     * `docs -> /outside`) would pass the logical `resolve()` check while actually writing outside
     * the jail — so this walks up to the nearest EXISTING ancestor directory and realpath-checks
     * *that* instead.
     */
    public Path resolveCreatable(String relative) {
        Path candidate = resolve(relative);
        if (Files.exists(candidate)) {
            return resolveExisting(relative);
        }
        Path ancestor = candidate.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new ToolException("path escapes the repo: " + relative);
        }
        try {
            if (!ancestor.toRealPath().startsWith(root.toRealPath())) {
                throw new ToolException("path escapes the repo: " + relative);
            }
        } catch (IOException e) {
            throw new ToolException("cannot resolve " + relative + ": " + e.getMessage());
        }
        return candidate;
    }
}
