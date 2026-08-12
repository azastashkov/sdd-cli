package sdd.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
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
        Path candidate = root.resolve(relative).normalize();
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
}
