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

    /** One successfully applied edit — recorded only after the write lands (a syntax-reverted or
     *  failed edit never reaches this). action is "create" when the search block was empty, else "edit". */
    public record AppliedEdit(String path, String action, int searchLines, int replaceLines) {
    }

    public FileTools(PathJail jail) {
        this.jail = jail;
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
        if (path.endsWith(".java")) {
            var error = JavaSyntax.firstError(updated);
            if (error.isPresent()) {
                throw new ToolException("edit rejected — result has a syntax error: " + error.get());
            }
        }
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
