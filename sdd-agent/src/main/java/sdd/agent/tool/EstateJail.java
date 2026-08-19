package sdd.agent.tool;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A read-only path jail spanning many repository roots, addressed as {@code <repo>/<relative>}.
 *
 * <p><b>It delegates; it does not reimplement.</b> One {@link PathJail} is held per repo and the
 * first path segment selects it, so the containment check, the {@code .git} ban and the
 * {@code toRealPath} symlink guard all apply per-root exactly as they already do — byte for byte
 * the same code, with no new security surface, and {@code sdd implement}'s single-root jail
 * untouched.
 *
 * <p><b>Why not jail the workspace directory instead.</b> Repo paths come from {@code repo.path} and
 * are absolute and arbitrary — nothing guarantees they share a parent. A single workspace root would
 * also re-expose every repo's {@code .git}, any nested repo, and any symlinked checkout, none of
 * which the per-repo jails allow.
 *
 * <p><b>Why the repo prefix is required.</b> Resolving a bare {@code src/Foo.java} by trying every
 * root would make the same argument mean different files depending on iteration order, across an
 * estate where dozens of repos have that path. An unknown or missing prefix is an error naming what
 * to do about it, which is the only answer that stays true at 53 repos.
 */
public final class EstateJail {
    private final Map<String, PathJail> jails = new LinkedHashMap<>();

    /** @param roots repo name → checkout root, typically {@code SELECT name, path FROM repo} */
    public EstateJail(Map<String, Path> roots) {
        roots.forEach((repo, root) -> jails.put(repo, new PathJail(root)));
    }

    /** The repo names this jail admits, in insertion order. */
    public Set<String> repos() {
        return jails.keySet();
    }

    /** The root of one repo, for a caller that walks it directly (see {@code EstateSearch}). */
    public Path root(String repo) {
        return jailFor(repo).root();
    }

    /** Resolves {@code <repo>/<relative>} to an existing file, or throws. */
    public Path resolveExisting(String path) {
        int slash = path.indexOf('/');
        String repo = slash < 0 ? path : path.substring(0, slash);
        String relative = slash < 0 ? "." : path.substring(slash + 1);
        try {
            return jailFor(repo).resolveExisting(relative.isEmpty() ? "." : relative);
        } catch (ToolException e) {
            // The delegate speaks in repo-relative terms, but the model wrote an estate path — an
            // unqualified "no such file: src/Foo.java" across 53 repos names nothing.
            throw new ToolException(e.getMessage() + "  (in repo " + repo + ")");
        }
    }

    private PathJail jailFor(String repo) {
        PathJail jail = jails.get(repo);
        if (jail == null) {
            throw new ToolException("unknown repo '" + repo
                    + "' — paths are <repo>/<path>; call list_repos for the names");
        }
        return jail;
    }
}
