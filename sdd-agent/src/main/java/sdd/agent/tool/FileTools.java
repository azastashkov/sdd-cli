package sdd.agent.tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** The agent's read/search/edit tools, all confined by a PathJail. */
public final class FileTools {
    static final int MAX_READ_LINES = 400;
    static final int MAX_READ_BYTES = 16384;
    static final int MAX_SEARCH_HITS = 100;
    static final int MAX_SEARCHED_FILE_BYTES = 1_000_000;
    static final int MAX_HIT_CHARS = 300;
    static final int MAX_SEARCH_BYTES = 16384;
    private static final Set<String> SKIP_DIRS = Set.of(".git", "build", ".gradle", ".sdd", ".idea",
            "node_modules", "dist", "target");

    private final PathJail jail;
    private final List<AppliedEdit> appliedEdits = new ArrayList<>();
    /** Optional: absent when node could not be found, in which case TS edits are not gated. */
    private final java.util.Optional<sdd.core.ts.TsSidecar> tsSidecar;
    /** Recorded once per attempt so a missing gate is visible rather than merely absent. */
    private String tsGateUnavailable;

    /** One successfully applied edit — recorded only after the write lands (a syntax-reverted or
     *  failed edit never reaches this). action is "create" when the search block was empty, else "edit". */
    public record AppliedEdit(String path, String action, int searchLines, int replaceLines) {
    }

    /** No TypeScript gate: the Java and JSON checks still apply. */
    public FileTools(PathJail jail) {
        this(jail, java.util.Optional.empty());
    }

    public FileTools(PathJail jail, java.util.Optional<sdd.core.ts.TsSidecar> tsSidecar) {
        this.jail = jail;
        this.tsSidecar = tsSidecar;
    }

    /** An immutable snapshot of every edit successfully applied so far, in application order. */
    public List<AppliedEdit> appliedEdits() {
        return List.copyOf(appliedEdits);
    }

    public String readFile(String path) {
        Path file = jail.resolveExisting(path);
        if (Files.isDirectory(file)) {
            throw new ToolException(path + " is a directory");
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("cannot read " + path + ": " + e.getMessage());
        }
        List<String> lines = content.lines().toList();
        boolean truncated = false;
        if (lines.size() > MAX_READ_LINES) {
            lines = lines.subList(0, MAX_READ_LINES);
            truncated = true;
        }
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() + line.length() + 1 > MAX_READ_BYTES) {
                truncated = true;
                break;
            }
            out.append(line).append('\n');
        }
        if (truncated) {
            out.append("... (truncated)\n");
        }
        return out.toString();
    }

    public String listFiles(String dir) {
        Path target = jail.resolveExisting(dir);
        if (!Files.isDirectory(target)) {
            throw new ToolException(dir + " is not a directory");
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> children = Files.list(target)) {
            children.sorted().forEach(child ->
                    names.add(child.getFileName() + (Files.isDirectory(child) ? "/" : "")));
        } catch (IOException | UncheckedIOException e) {
            throw new ToolException("cannot list " + dir + ": " + e.getMessage());
        }
        return String.join("\n", names) + (names.isEmpty() ? "" : "\n");
    }

    public String search(String regex) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ToolException("bad regex: " + e.getMessage());
        }
        Path root = jail.root();
        List<String> hits = new ArrayList<>();
        boolean[] truncated = {false};
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> skipDirs(root, p) == null)
                    .sorted()
                    .toList();
            for (Path file : files) {
                if (hits.size() >= MAX_SEARCH_HITS) {
                    truncated[0] = true;
                    break;
                }
                scanFile(root, file, pattern, hits, truncated);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new ToolException("search failed: " + e.getMessage());
        }
        StringBuilder out = new StringBuilder();
        for (String hit : hits) {
            if (out.length() + hit.length() + 1 > MAX_SEARCH_BYTES) {
                truncated[0] = true;
                break;
            }
            out.append(hit).append('\n');
        }
        if (truncated[0]) {
            out.append("... (more matches omitted)\n");
        }
        return out.toString();
    }

    private static String skipDirs(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (SKIP_DIRS.contains(part.toString())) {
                return part.toString();
            }
        }
        return null;
    }

    private static void scanFile(Path root, Path file, Pattern pattern, List<String> hits,
                                 boolean[] truncated) {
        try {
            if (Files.size(file) > MAX_SEARCHED_FILE_BYTES) {
                return;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String rel = root.relativize(file).toString().replace('\\', '/');
            for (int i = 0; i < lines.size(); i++) {
                if (hits.size() >= MAX_SEARCH_HITS) {
                    truncated[0] = true;
                    return;
                }
                if (pattern.matcher(lines.get(i)).find()) {
                    String line = lines.get(i);
                    String shown = line.length() > MAX_HIT_CHARS
                            ? line.substring(0, MAX_HIT_CHARS) + "…"
                            : line;
                    hits.add(rel + ":" + (i + 1) + ": " + shown);
                }
            }
        } catch (IOException e) {
            // unreadable/binary file — skip silently, consistent with a best-effort text search
        }
    }

    public String applyEdit(String path, String searchBlock, String replaceBlock) {
        boolean creating = searchBlock.isEmpty();
        // creation resolves via resolveCreatable (parent may not exist yet, but a symlinked parent
        // must still be caught); an edit uses resolveExisting so the toRealPath symlink jail
        // applies to the WRITE path, not just reads.
        Path file = creating ? jail.resolveCreatable(path) : jail.resolveExisting(path);
        String original;
        if (creating) {
            if (Files.exists(file) && !readOrEmpty(file).isEmpty()) {
                throw new ToolException("cannot create " + path + ": file already exists");
            }
            original = "";
        } else {
            original = readOrEmpty(file);
        }
        String updated = creating ? replaceBlock : applyBlock(path, original, searchBlock, replaceBlock);
        syntaxGate(path, updated);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("cannot write " + path + ": " + e.getMessage());
        }
        appliedEdits.add(new AppliedEdit(path, creating ? "create" : "edit",
                lineCount(searchBlock), lineCount(replaceBlock)));
        return (creating ? "created " : "edited ") + path;
    }

    /**
     * Refuses an edit that would leave the file unparseable. The point is not correctness — a
     * type error is the build's job — but that a syntactically broken file makes every subsequent
     * read and edit the agent performs meaningless, and it usually cannot tell.
     *
     * <p>Java is checked in process. TypeScript is checked by the sidecar, which parses the single
     * file with the real compiler: no program, no type checking, no module resolution, so it costs
     * about a millisecond. A hand-rolled bracket heuristic was rejected because valid TypeScript
     * defeats it — {@code a < b, c > (d)} and JSX both read as unbalanced — and a false rejection
     * is strictly worse than no gate, since the agent cannot fix an error that is not there.
     *
     * <p>JSON is checked because package.json is now load-bearing: a malformed one breaks
     * dependency reading, version bumping and every npm invocation, with an error that points
     * nowhere near the edit.
     *
     * <p>When the TypeScript gate cannot run it FAILS OPEN, loudly and once. Rejecting an edit
     * because the checker is missing would wedge the agent against an error it has no way to
     * resolve, burning the whole escalation ladder; the real gate is the type-check verification,
     * which is exit-code driven and cannot be fooled by an absent syntax check.
     */
    private void syntaxGate(String path, String updated) {
        if (path.endsWith(".java")) {
            var error = JavaSyntax.firstError(updated);
            if (error.isPresent()) {
                throw new ToolException("edit rejected — result has a syntax error: " + error.get());
            }
            return;
        }
        if (path.endsWith(".json")) {
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(updated);
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                throw new ToolException("edit rejected — result is not valid JSON: "
                        + e.getOriginalMessage());
            }
            return;
        }
        if (!isTypeScript(path)) {
            return;
        }
        if (tsSidecar.isEmpty()) {
            noteTsGateUnavailable("node not found");
            return;
        }
        var result = tsSidecar.get().syntaxCheck(path, updated);
        if (!result.ok()) {
            noteTsGateUnavailable(result.error());
            return;
        }
        if (!result.json().path("ok").asBoolean(true)) {
            throw new ToolException("edit rejected — result has a syntax error: "
                    + result.json().path("error").asText());
        }
    }

    private static boolean isTypeScript(String path) {
        return path.endsWith(".ts") || path.endsWith(".tsx")
                || path.endsWith(".mts") || path.endsWith(".cts");
    }

    private void noteTsGateUnavailable(String reason) {
        if (tsGateUnavailable == null) {
            tsGateUnavailable = reason;
        }
    }

    /**
     * Null unless the TypeScript syntax gate could not run, in which case this is why. The runner
     * surfaces it as an agent event so an unchecked edit is a recorded fact rather than silence.
     */
    public String tsGateUnavailable() {
        return tsGateUnavailable;
    }

    private static int lineCount(String text) {
        return (int) text.lines().count();
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new ToolException("cannot read " + file + ": " + e.getMessage());
        }
    }

    private static String applyBlock(String path, String original, String search, String replace) {
        int first = original.indexOf(search);
        if (first >= 0) {
            if (original.indexOf(search, first + 1) >= 0) {
                throw new ToolException("ambiguous edit in " + path + ": search block occurs more than once");
            }
            return original.substring(0, first) + replace + original.substring(first + search.length());
        }
        return lenient(path, original, search, replace);
    }

    private static String lenient(String path, String original, String search, String replace) {
        List<String> haystack = original.lines().toList();
        List<String> needle = search.lines().map(String::strip).toList();
        int match = -1;
        for (int i = 0; i + needle.size() <= haystack.size(); i++) {
            boolean all = true;
            for (int j = 0; j < needle.size(); j++) {
                if (!haystack.get(i + j).strip().equals(needle.get(j))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                if (match >= 0) {
                    throw new ToolException("ambiguous edit in " + path + ": search block matches more than once");
                }
                match = i;
            }
        }
        if (match < 0) {
            throw new ToolException("no match for the search block in " + path);
        }
        List<String> result = new ArrayList<>(haystack.subList(0, match));
        result.addAll(replace.lines().toList());
        result.addAll(haystack.subList(match + needle.size(), haystack.size()));
        String joined = String.join("\n", result);
        return original.endsWith("\n") ? joined + "\n" : joined;
    }
}
