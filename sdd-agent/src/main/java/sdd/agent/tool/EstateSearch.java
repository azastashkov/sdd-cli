package sdd.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Regex search across every repo of the estate, with a quota per repo.
 *
 * <p><b>Why not reuse {@link FileTools#search}.</b> That one walks a single root, sorts the whole
 * file list and spends one global 100-hit budget in path order. Point it at 53 repos and the budget
 * is exhausted inside the alphabetically-first repo or two; every other repo returns nothing and the
 * output says only {@code "... (more matches omitted)"}. A model reading that concludes the term
 * does not exist elsewhere, which is the exact false negative this whole feature exists to remove.
 *
 * <p><b>So the quota is per repo and every suppression is named.</b> Each repo gets its own hit
 * allowance, and any repo that hits it is reported by name with its count. "Truncated in repo X" and
 * "absent from repo X" are different answers and must never render the same — the same principle
 * {@code type_supertype.resolution} follows for unresolved supertypes.
 *
 * <p>Read-only by construction: it holds an {@link EstateJail}, which has no writable resolve.
 */
public final class EstateSearch {
    /** Per repo, not global — see the class note. */
    static final int MAX_HITS_PER_REPO = 20;
    static final int MAX_TOTAL_HITS = 200;
    static final int MAX_SEARCHED_FILE_BYTES = 1_000_000;
    static final int MAX_HIT_CHARS = 300;
    static final int MAX_OUTPUT_BYTES = 32768;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "build", ".gradle", ".sdd", ".idea",
            "node_modules", "dist", "target", "out", ".venv", "venv", "__pycache__", ".m2",
            ".cache", ".next", "vendor", "coverage", ".terraform", ".mvn", "bin", "obj");

    /**
     * How long one search may run before it reports what it has.
     *
     * <p>Without it a search over a large repo is not slow, it is INVISIBLE: no output, no model
     * call, no way to tell it apart from a hang. A partial result that says it is partial is
     * strictly better than an unbounded wait, and the agent can narrow and retry — which is
     * exactly what the message tells it to do.
     */
    static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(45);

    private final EstateJail jail;
    private final Duration deadline;

    public EstateSearch(EstateJail jail) {
        this(jail, DEFAULT_DEADLINE);
    }

    public EstateSearch(EstateJail jail, Duration deadline) {
        this.jail = jail;
        this.deadline = deadline;
    }

    /**
     * What a search returned: the text the model sees, and the estate paths it names.
     *
     * <p>{@code paths} exists so a caller can record what the model was actually shown. An
     * explorer's citation gate needs to know a file was surfaced this run, and re-parsing the
     * rendered text to find out would make a display format load-bearing.
     */
    public record Result(String rendered, List<String> paths) {
        public Result {
            paths = List.copyOf(paths);
        }
    }

    /**
     * @param regex   Java regex, matched per line
     * @param repo    optional single repo to restrict to; null or blank searches the whole estate
     * @param glob    optional glob over the repo-relative path, e.g. {@code **}{@code /*.sql}
     */
    public String search(String regex, String repo, String glob) {
        return find(regex, repo, glob).rendered();
    }

    /** @see #search(String, String, String) */
    public Result find(String regex, String repo, String glob) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ToolException("bad regex: " + e.getMessage());
        }
        PathMatcher matcher = null;
        if (glob != null && !glob.isBlank()) {
            try {
                matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                throw new ToolException("bad path glob: " + e.getMessage());
            }
        }
        List<String> repos = new ArrayList<>(jail.repos());
        if (repo != null && !repo.isBlank()) {
            if (!repos.contains(repo)) {
                throw new ToolException("unknown repo '" + repo
                        + "' — call list_repos for the names");
            }
            repos = List.of(repo);
        }

        // Insertion-ordered so a caller reading `paths` sees them in the order the model did.
        Set<String> shown = new java.util.LinkedHashSet<>();
        Map<String, List<String>> hitsByRepo = new LinkedHashMap<>();
        Map<String, Integer> capped = new LinkedHashMap<>();
        int total = 0;
        List<String> stoppedAt = new ArrayList<>();
        long expiry = System.nanoTime() + deadline.toNanos();
        boolean timedOut = false;
        for (String name : repos) {
            if (total >= MAX_TOTAL_HITS || timedOut) {
                stoppedAt.add(name);
                continue;
            }
            List<String> hits = new ArrayList<>();
            int found = scanRepo(name, pattern, matcher, hits, shown, expiry);
            if (System.nanoTime() > expiry) {
                timedOut = true;
            }
            if (found > hits.size()) {
                capped.put(name, found);
            }
            if (!hits.isEmpty()) {
                hitsByRepo.put(name, hits);
                total += hits.size();
            }
        }
        return new Result(render(repos, hitsByRepo, capped, stoppedAt, timedOut, deadline),
                List.copyOf(shown));
    }

    /** @return how many lines matched in this repo, which may exceed what was collected */
    private int scanRepo(String repo, Pattern pattern, PathMatcher glob, List<String> hits,
                         Set<String> shown, long expiry) {
        Path root = jail.root(repo);
        int found = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> !skipped(root, p))
                    .sorted()
                    .toList();
            for (Path file : files) {
                if (System.nanoTime() > expiry) {
                    return found;
                }
                Path rel = root.relativize(file);
                if (glob != null && !glob.matches(rel)) {
                    continue;
                }
                found += scanFile(repo, rel, file, pattern, hits, shown);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new ToolException("search failed in " + repo + ": " + e.getMessage());
        }
        return found;
    }

    private static int scanFile(String repo, Path rel, Path file, Pattern pattern, List<String> hits,
                                Set<String> shown) {
        try {
            if (Files.size(file) > MAX_SEARCHED_FILE_BYTES) {
                return 0;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String path = repo + "/" + rel.toString().replace('\\', '/');
            int found = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (!pattern.matcher(lines.get(i)).find()) {
                    continue;
                }
                found++;
                // Keep counting past the quota: the count is what turns a truncation into a
                // reportable fact instead of silence.
                if (hits.size() >= MAX_HITS_PER_REPO) {
                    continue;
                }
                String line = lines.get(i);
                hits.add(path + ":" + (i + 1) + ": "
                        + (line.length() > MAX_HIT_CHARS ? line.substring(0, MAX_HIT_CHARS) + "…" : line));
                shown.add(path);
            }
            return found;
        } catch (IOException e) {
            // unreadable or non-UTF-8 file — skipped, consistent with a best-effort text search
            return 0;
        }
    }

    private static boolean skipped(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String render(List<String> searched, Map<String, List<String>> hitsByRepo,
                                 Map<String, Integer> capped, List<String> stoppedAt,
                                 boolean timedOut, Duration deadline) {
        StringBuilder out = new StringBuilder();
        for (var entry : hitsByRepo.entrySet()) {
            for (String hit : entry.getValue()) {
                if (out.length() + hit.length() + 1 > MAX_OUTPUT_BYTES) {
                    out.append("(output truncated at ").append(MAX_OUTPUT_BYTES)
                            .append(" bytes — narrow the regex or pass a repo)\n");
                    return out.toString();
                }
                out.append(hit).append('\n');
            }
        }
        if (hitsByRepo.isEmpty()) {
            // Naming the repos searched is the point: "no matches" over an unstated scope is the
            // ambiguity that lets a model read a filtered search as an estate-wide absence.
            out.append("no matches in: ").append(String.join(", ", searched)).append('\n');
        }
        capped.forEach((repo, found) -> out.append("(").append(repo).append(": showing ")
                .append(MAX_HITS_PER_REPO).append(" of ").append(found)
                .append(" matches — pass repo=").append(repo).append(" to see more)\n"));
        if (!stoppedAt.isEmpty()) {
            out.append("(not searched, ").append(timedOut ? "time limit reached" : "total hit cap reached")
                    .append(": ").append(String.join(", ", stoppedAt)).append(")\n");
        }
        if (timedOut) {
            out.append("(search stopped after ").append(deadline.toSeconds())
                    .append("s — results are partial; narrow the regex, pass repo=, or pass a glob)\n");
        }
        return out.toString();
    }
}
