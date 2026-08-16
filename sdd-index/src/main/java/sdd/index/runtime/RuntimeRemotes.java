package sdd.index.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.index.store.Paths2;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reads checked-in remotes manifests — the {@code {"remotes":[{"name","url","enabled"}]}} a host
 * application fetches at run time to discover which bundles to load.
 *
 * <p>This is a pure reading of a file and nothing else. It records that a repo declares a remote
 * called {@code mfe_a} served from {@code /mfe/a/trading-mfe-a.js}; it does NOT conclude that the
 * repo named {@code trading-mfe-a} builds it. That resemblance is a naming convention, and an edge
 * invented from a convention is indistinguishable in the knowledge base from one that was read out
 * of a build file. The mapping is supplied by a human in {@code sdd.yml}; see
 * {@code RuntimeEdgeLinker}.
 */
public final class RuntimeRemotes {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> PRUNED =
            Set.of("node_modules", "dist", "build", "coverage", "out", ".git", ".sdd", "target");
    private static final int MAX_DEPTH = 8;

    /** @param sourceFile repo-relative, so the report can point at the file that declared it */
    public record Remote(String name, String url, boolean enabled, String sourceFile) {
    }

    private RuntimeRemotes() {
    }

    public static List<Remote> read(Path repoDir) {
        Path root = Paths2.canonical(repoDir);
        List<Remote> remotes = new ArrayList<>();
        for (Path manifest : manifests(root)) {
            String relative = root.relativize(manifest).toString().replace('\\', '/');
            try {
                JsonNode node = MAPPER.readTree(Files.readString(manifest)).path("remotes");
                if (!node.isArray()) {
                    continue;
                }
                for (JsonNode remote : node) {
                    String name = remote.path("name").asText(null);
                    String url = remote.path("url").asText(null);
                    if (name == null || url == null || name.isBlank() || url.isBlank()) {
                        continue;
                    }
                    remotes.add(new Remote(name, url, remote.path("enabled").asBoolean(true), relative));
                }
            } catch (IOException | RuntimeException e) {
                // A file named remotes.json that is not this shape is simply not a manifest.
            }
        }
        return remotes;
    }

    public static void persist(Jdbi jdbi, long repoId, List<Remote> remotes) {
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM runtime_remote WHERE repo_id = :r").bind("r", repoId).execute();
            for (Remote remote : remotes) {
                h.createUpdate("""
                                INSERT INTO runtime_remote(repo_id, name, url, enabled, source_file)
                                VALUES (:r, :n, :u, :e, :f)
                                ON CONFLICT(repo_id, name, source_file) DO UPDATE SET
                                  url = excluded.url, enabled = excluded.enabled""")
                        .bind("r", repoId).bind("n", remote.name()).bind("u", remote.url())
                        .bind("e", remote.enabled() ? 1 : 0).bind("f", remote.sourceFile())
                        .execute();
            }
        });
    }

    private static List<Path> manifests(Path root) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals("remotes.json"))
                    .filter(f -> !isPruned(root, f))
                    .sorted()
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
        return found;
    }

    private static boolean isPruned(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative.getParent() == null ? relative : relative.getParent()) {
            if (PRUNED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
