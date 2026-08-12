# Phase 4A — Agent Loop Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the single-repo, single-attempt coding agent in a new `sdd-agent` module: six tools behind a path jail, a native tool-call loop over the `ChatModel` seam, usage-based context accounting with deterministic eviction, and turn/time/token budgets with malformed-call and wedge termination.

**Architecture:** New module `sdd-agent` (depends on sdd-core + javaparser). `sdd.agent.tool` holds the guardrails (`PathJail`, `JavaSyntax`) and the tools (`FileTools`: read/list/search/edit; `GradleTool`: env-scrubbed subprocess); `Toolbox` assembles them into OpenAI `ToolSpec` schemas + a `dispatch(name, argsJson)`. `sdd.agent.loop` holds `ContextWindow` (usage accounting + eviction) and `AgentLoop` (the native tool-call loop producing an `AgentOutcome`). Everything is driven through `ChatModel`, so `ScriptedChatModel` scripts every test. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` Component 3 (agent loop + tools + guardrails + context management + budgets).

**Tech Stack:** Java 21, JavaParser (`javaparser-symbol-solver-core`, catalogued — transitively provides `javaparser-core`), Jackson (tool-argument JSON), the existing `sdd.core.llm` seam. NO new catalog entries.

## Global Constraints

- Java 21; no new catalog entries; `sdd-agent` depends only on sdd-core (+ javaparser/jackson libs). This phase is a LIBRARY — no CLI, no config parsing, no `SddConfig` change (budgets are a record with design defaults; config wiring lands in a later phase when a real caller exists).
- Scope is SINGLE-REPO, SINGLE-ATTEMPT: the loop terminates with a typed outcome; it does NOT run verification tasks, retry, escalate, restart on context exhaustion, or construct work orders (all Phase 4B/4C). `done` ends the loop; the caller decides what happens next.
- Deterministic-first / testing seam: the ONLY model interaction is `ChatModel.complete`; every test scripts it with `ScriptedChatModel`. The agent has ZERO git verbs and no generic shell — only the six tools.
- Guardrails (design): path jail via `toRealPath` prefix check on existing paths + logical `normalize()`+`startsWith` on all paths; any `.git` path component denied; `run_gradle` is an env-scrubbed subprocess with an allowlisted task set, per-repo `JAVA_HOME`, and `--no-daemon`, hard-timeout with a process-tree kill (`process.descendants()` + `destroyForcibly`).
- The six tools (design): `read_file` (≤400 lines AND ≤16384 bytes, whichever first, truncation-marked), `list_files`, `search` (pure-Java regex walk, deterministic order, capped), `apply_edit` (one search/replace block; exact match then one whitespace-lenient line-stripped fallback; creation = empty search block; `.java` edits syntax-checked via JavaParser and auto-reverted on failure), `run_gradle` (allowlisted tasks only; `--no-configuration-cache -q`; output tail-capped), `done(result, summary)` (result ∈ `success|blocked`).
- Context management (design): endpoint `usage`-based accounting (authoritative `prompt_tokens`); 80k soft cap; deterministic oldest-first eviction of TOOL-RESULT messages only, replaced by stubs; always preserved: system prompt, work order (first user message), all assistant messages, the last `run_gradle` result, the 2 latest `read_file` results, and ALL `apply_edit` results. No model summarization. Over cap with nothing evictable → `CONTEXT_EXHAUSTED`.
- Budgets (design): 40 turns / 45 min / 1.5M cumulative tokens per attempt; 3 consecutive malformed/no-op turns → `MALFORMED`; identical (tool,args) repeated 3× consecutively → `WEDGED`; two consecutive identical `run_gradle` outputs → `WEDGED`. Wall clock is injected via `java.time.InstantSource` for deterministic tests.
- Every tool result is a STRING returned to the model; tool-level failures (`ToolException`) are returned as content and reset the malformed streak; only unparseable args / unknown tool / missing required arg (`MalformedCallException`) count as malformed strikes.
- Never read or print `.env`. Never push. Full `./gradlew build` before any commit touching more than one module.

---

## File Structure

**Task 1:** `sdd-agent/build.gradle.kts` (deps); `sdd-agent/src/main/java/sdd/agent/tool/{ToolException,MalformedCallException,PathJail,JavaSyntax}.java` + tests
**Task 2:** `sdd-agent/src/main/java/sdd/agent/tool/FileTools.java` (read_file, list_files, search) + test
**Task 3:** `FileTools.java` (apply_edit) + test
**Task 4:** `sdd-agent/src/main/java/sdd/agent/tool/GradleTool.java` + test
**Task 5:** `sdd-agent/src/main/java/sdd/agent/tool/Toolbox.java` + test
**Task 6:** `sdd-agent/src/main/java/sdd/agent/loop/ContextWindow.java` + test
**Task 7:** `sdd-agent/src/main/java/sdd/agent/loop/{AgentBudget,AgentOutcome,AgentLoop}.java` + test

---

### Task 1: Module bootstrap + guardrails (PathJail, JavaSyntax)

**Files:**
- Modify: `sdd-agent/build.gradle.kts`
- Create: `sdd-agent/src/main/java/sdd/agent/tool/ToolException.java`, `MalformedCallException.java`, `PathJail.java`, `JavaSyntax.java`
- Test: `sdd-agent/src/test/java/sdd/agent/tool/PathJailTest.java`, `JavaSyntaxTest.java`

**Interfaces:**
- Produces (Tasks 2-7 depend on these):
  - `public class ToolException extends RuntimeException` — ctor `(String)`; a well-formed tool call that failed legitimately.
  - `public class MalformedCallException extends RuntimeException` — ctor `(String)`; unparseable args / unknown tool / missing arg (counts as a strike).
  - `public final class PathJail` — ctor `(Path root)`; `Path resolve(String relative)` (logical jail: normalize + startsWith root + no `.git` component; `ToolException` on violation); `Path resolveExisting(String relative)` (resolve + require existence + `toRealPath` re-check inside root); `Path root()`.
  - `public final class JavaSyntax` — `static Optional<String> firstError(String source)` (empty = parses; else first JavaParser problem message).

- [ ] **Step 1: Extend the build file.**

```kotlin
plugins { `java-library` }
dependencies {
    api(project(":sdd-core"))
    implementation(libs.jackson)
    implementation(libs.javaparser.symbol.solver)
    testImplementation(libs.bundles.test)
    testImplementation(testFixtures(project(":sdd-core")))
    testRuntimeOnly(libs.junit.launcher)
}
```

- [ ] **Step 2: Write the failing tests.** `PathJailTest.java`:

```java
package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathJailTest {
    @TempDir Path root;

    @Test
    void resolvesInsidePathsAndRejectsEscapesAndDotGit() {
        PathJail jail = new PathJail(root);

        assertThat(jail.resolve("src/A.java")).isEqualTo(root.resolve("src/A.java").normalize());
        assertThatThrownBy(() -> jail.resolve("../outside"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThatThrownBy(() -> jail.resolve("src/../../x"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThatThrownBy(() -> jail.resolve(".git/config"))
                .isInstanceOf(ToolException.class).hasMessageContaining(".git");
        assertThatThrownBy(() -> jail.resolve("modules/.git/HEAD"))
                .isInstanceOf(ToolException.class).hasMessageContaining(".git");
    }

    @Test
    void resolveExistingFollowsSymlinksBackIntoTheJail() throws Exception {
        Path outside = Files.createDirectory(root.resolveSibling("outside-" + root.getFileName()));
        Files.writeString(outside.resolve("secret.txt"), "x");
        Files.createSymbolicLink(root.resolve("link.txt"), outside.resolve("secret.txt"));
        PathJail jail = new PathJail(root);

        // logical resolve passes (name is inside), but resolveExisting's toRealPath escapes → rejected
        assertThatThrownBy(() -> jail.resolveExisting("link.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("escapes the repo");
        assertThatThrownBy(() -> jail.resolveExisting("missing.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no such file");
    }
}
```

`JavaSyntaxTest.java`:

```java
package sdd.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSyntaxTest {
    @Test
    void acceptsValidAndReportsFirstProblemForInvalid() {
        assertThat(JavaSyntax.firstError("class A { void m() {} }")).isEmpty();
        assertThat(JavaSyntax.firstError("class A { void m( {} }")).isPresent();
    }
}
```

- [ ] **Step 3: Run — expect COMPILE FAILURE.** Implement the four files.

`ToolException.java`:

```java
package sdd.agent.tool;

/** A well-formed tool call that failed legitimately (file not found, no match, ...). */
public class ToolException extends RuntimeException {
    public ToolException(String message) {
        super(message);
    }
}
```

`MalformedCallException.java`:

```java
package sdd.agent.tool;

/** Unparseable arguments, unknown tool, or a missing required argument — a malformed strike. */
public class MalformedCallException extends RuntimeException {
    public MalformedCallException(String message) {
        super(message);
    }
}
```

`PathJail.java`:

```java
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
```

`JavaSyntax.java`:

```java
package sdd.agent.tool;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;

import java.util.Optional;

/** Parses a Java source string for syntax errors — the gate apply_edit runs before keeping a .java edit. */
public final class JavaSyntax {

    private JavaSyntax() {
    }

    public static Optional<String> firstError(String source) {
        try {
            StaticJavaParser.parse(source);
            return Optional.empty();
        } catch (ParseProblemException e) {
            return Optional.of(e.getProblems().isEmpty()
                    ? "syntax error" : e.getProblems().get(0).getMessage());
        }
    }
}
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 5: Full build (new module deps), then commit**

```bash
./gradlew build
git add sdd-agent
git commit -m "feat: sdd-agent bootstrap with path jail and java syntax gate"
```

---

### Task 2: FileTools — read_file, list_files, search

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/tool/FileTools.java`
- Test: `sdd-agent/src/test/java/sdd/agent/tool/FileToolsReadTest.java`

**Interfaces:**
- Produces: `public final class FileTools` — ctor `(PathJail jail)`; `String readFile(String path)`; `String listFiles(String dir)`; `String search(String regex)`. (apply_edit added in Task 3.)
- Consumes: `PathJail` (Task 1).
- Caps (constants, package-visible for tests): `MAX_READ_LINES = 400`, `MAX_READ_BYTES = 16384`, `MAX_SEARCH_HITS = 100`, `MAX_SEARCHED_FILE_BYTES = 1_000_000`.
- `search` walks the repo skipping `.git`, `build`, `.gradle`, `.sdd`, `.idea` directories, matches the regex per line over regular files under the byte cap, emits `<relpath>:<lineno>: <line>` in path-then-line order, capped; a match count footer when truncated.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolsReadTest {
    @TempDir Path root;
    private FileTools tools;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/a"));
        Files.writeString(root.resolve("src/main/java/a/A.java"), "class A {\n  int loyaltyTier;\n}\n");
        Files.writeString(root.resolve("src/main/java/a/B.java"), "class B {}\n");
        Files.createDirectories(root.resolve("build/classes"));
        Files.writeString(root.resolve("build/classes/ignore.txt"), "loyaltyTier here too\n");
        tools = new FileTools(new PathJail(root));
    }

    @Test
    void readFileReturnsContentAndCapsAt400LinesOr16Kb() throws Exception {
        assertThat(tools.readFile("src/main/java/a/A.java")).contains("int loyaltyTier;");

        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append("line ").append(i).append('\n');
        }
        Files.writeString(root.resolve("big.txt"), big.toString());
        String read = tools.readFile("big.txt");
        assertThat(read).contains("line 0").contains("line 399")
                .doesNotContain("line 400")
                .contains("truncated");

        assertThatThrownBy(() -> tools.readFile("nope.txt"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no such file");
    }

    @Test
    void listFilesSortsAndMarksDirectories() {
        String listing = tools.listFiles("src/main/java/a");
        assertThat(listing).isEqualTo("A.java\nB.java\n");

        assertThat(tools.listFiles("src/main/java")).isEqualTo("a/\n");
    }

    @Test
    void searchWalksSourceSkipsBuildDirsAndIsDeterministic() {
        String hits = tools.search("loyaltyTier");

        assertThat(hits).isEqualTo("src/main/java/a/A.java:2:   int loyaltyTier;\n");
        assertThat(hits).doesNotContain("build/");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `FileTools.java`:

```java
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
    private static final Set<String> SKIP_DIRS = Set.of(".git", "build", ".gradle", ".sdd", ".idea");

    private final PathJail jail;

    public FileTools(PathJail jail) {
        this.jail = jail;
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StringBuilder out = new StringBuilder();
        for (String hit : hits) {
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
                    hits.add(rel + ":" + (i + 1) + ": " + lines.get(i));
                }
            }
        } catch (IOException e) {
            // unreadable/binary file — skip silently, consistent with a best-effort text search
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: agent read_file, list_files, search tools"
```

---

### Task 3: FileTools — apply_edit

**Files:**
- Modify: `sdd-agent/src/main/java/sdd/agent/tool/FileTools.java` (add `applyEdit`)
- Test: `sdd-agent/src/test/java/sdd/agent/tool/FileToolsEditTest.java`

**Interfaces:**
- Produces: `String applyEdit(String path, String searchBlock, String replaceBlock)` on `FileTools` — returns `"created <path>"` or `"edited <path>"`; `ToolException` on: creation over an existing non-empty file, missing target, no match, ambiguous match, `.java` result failing syntax. The edit branch resolves via `resolveExisting` (symlink jail on the write path).
- Rules: empty `searchBlock` = creation (target must be absent or empty). Non-empty: exact substring replace if the block occurs exactly once; else a whitespace-lenient fallback matching a consecutive run of lines whose `strip()`ed forms equal the search block's stripped lines (must be unique). `.java` targets: after applying, `JavaSyntax.firstError` on the new full content; on error, restore the original bytes (or delete a just-created file) and throw.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileToolsEditTest {
    @TempDir Path root;
    private FileTools tools;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("src"));
        tools = new FileTools(new PathJail(root));
    }

    @Test
    void createsANewFileWithAnEmptySearchBlock() throws Exception {
        String result = tools.applyEdit("src/New.java", "", "class New {}\n");

        assertThat(result).isEqualTo("created src/New.java");
        assertThat(Files.readString(root.resolve("src/New.java"))).isEqualTo("class New {}\n");
    }

    @Test
    void exactSubstringEditsApply() throws Exception {
        Files.writeString(root.resolve("src/A.java"), "class A {\n    int x = 1;\n}\n");

        assertThat(tools.applyEdit("src/A.java", "int x = 1;", "int x = 2;"))
                .isEqualTo("edited src/A.java");
        assertThat(Files.readString(root.resolve("src/A.java"))).contains("int x = 2;");

        // an unindented needle is still an exact substring of the indented line
        assertThat(tools.applyEdit("src/A.java", "int x = 2;", "int x = 3;"))
                .isEqualTo("edited src/A.java");
        assertThat(Files.readString(root.resolve("src/A.java"))).contains("int x = 3;");
    }

    @Test
    void multiLineBlockWithDifferentIndentationMatchesViaLenientFallback() throws Exception {
        Files.writeString(root.resolve("src/A.java"),
                "class A {\n    void m() {\n        int x = 1;\n        int y = 2;\n    }\n}\n");
        // the two-line needle has NO indentation, so exact indexOf fails and the line-stripped
        // lenient path runs (its multi-line match + splice/rejoin was previously uncovered)
        String search = "int x = 1;\nint y = 2;";

        assertThat(tools.applyEdit("src/A.java", search, "int x = 10;\nint y = 20;"))
                .isEqualTo("edited src/A.java");
        assertThat(Files.readString(root.resolve("src/A.java")))
                .contains("int x = 10;").contains("int y = 20;");
    }

    @Test
    void javaEditThatBreaksSyntaxIsRevertedAndReported() throws Exception {
        Path a = root.resolve("src/A.java");
        Files.writeString(a, "class A {\n    int x = 1;\n}\n");

        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int x = 1;", "int x = ;"))
                .isInstanceOf(ToolException.class).hasMessageContaining("syntax");
        assertThat(Files.readString(a)).isEqualTo("class A {\n    int x = 1;\n}\n");   // unchanged
    }

    @Test
    void missingAmbiguousAndCreateOverExistingFail() throws Exception {
        Files.writeString(root.resolve("src/A.java"), "class A {\n  int x;\n  int x;\n}\n");

        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int y;", "int z;"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no match");
        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "int x;", "int q;"))
                .isInstanceOf(ToolException.class).hasMessageContaining("ambiguous");
        assertThatThrownBy(() -> tools.applyEdit("src/A.java", "", "class A {}"))
                .isInstanceOf(ToolException.class).hasMessageContaining("already exists");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Add to `FileTools` (imports already cover Files/IOException; add none new):

```java
    public String applyEdit(String path, String searchBlock, String replaceBlock) {
        boolean creating = searchBlock.isEmpty();
        // creation resolves logically (parent may not exist yet); an edit uses resolveExisting so
        // the toRealPath symlink jail applies to the WRITE path, not just reads.
        Path file = creating ? jail.resolve(path) : jail.resolveExisting(path);
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
        return (creating ? "created " : "edited ") + path;
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
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: agent apply_edit with lenient match and java syntax revert"
```

---

### Task 4: GradleTool — env-scrubbed allowlisted subprocess

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/tool/GradleTool.java`
- Test: `sdd-agent/src/test/java/sdd/agent/tool/GradleToolTest.java`

**Interfaces:**
- Produces: `public final class GradleTool` — ctor `(Path repoRoot, Path javaHome, Duration timeout)` (`javaHome` nullable); `String run(String task)` — validates the task against `ALLOWED` (else `ToolException`), runs `./gradlew <task> --no-configuration-cache --no-daemon -q` in the repo root, and returns `"exit <code>\n<tail up to MAX_OUTPUT chars>"`. `static final Set<String> ALLOWED = {help, compileJava, classes, testClasses, assemble, check, test, build}`. `static final int MAX_OUTPUT = 8000`.
- Guardrails: `builder.environment().clear()` then re-add only `PATH, HOME, LANG, TMPDIR` (from the JVM env if present) plus `JAVA_HOME` when `javaHome != null`; `redirectErrorStream(true)` + `redirectOutput(temp)`; `waitFor(timeout)` → kill the process AND its descendants (`process.descendants().forEach(ProcessHandle::destroyForcibly)` then `process.destroyForcibly()`) + `"timed out after <N>s"`; missing wrapper → `ToolException`; temp log deleted in `finally` (mirror `GradleSmokeRunner`).

- [ ] **Step 1: Write the failing tests** (stub `gradlew` scripts, no real Gradle):

```java
package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradleToolTest {
    @TempDir Path repo;

    private void wrapper(String script) throws Exception {
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void disallowedTaskNeverRuns() {
        assertThatThrownBy(() -> new GradleTool(repo, null, Duration.ofSeconds(5)).run("publishToMavenLocal"))
                .isInstanceOf(ToolException.class).hasMessageContaining("not allowed");
    }

    @Test
    void runsAllowedTaskAndReturnsExitAndOutput() throws Exception {
        wrapper("echo building; echo done; exit 0");

        String result = new GradleTool(repo, null, Duration.ofSeconds(5)).run("compileJava");

        assertThat(result).startsWith("exit 0\n").contains("building").contains("done");
    }

    @Test
    void scrubsEnvironmentButKeepsJavaHomeWhenProvided() throws Exception {
        wrapper("echo JH=$JAVA_HOME; echo LEAK=$SDD_SECRET");
        // set a secret in the inherited env via a wrapper that reads it — since ProcessBuilder
        // starts from the JVM env, the scrub must drop SDD_SECRET while JAVA_HOME is injected.
        Path fakeJdk = Files.createDirectory(repo.resolve("jdk"));

        String result = new GradleTool(repo, fakeJdk, Duration.ofSeconds(5)).run("help");

        assertThat(result).contains("JH=" + fakeJdk).contains("LEAK=\n");   // secret scrubbed to empty
    }

    @Test
    void timesOutAndReportsIt() throws Exception {
        wrapper("sleep 30");

        String result = new GradleTool(repo, null, Duration.ofSeconds(2)).run("test");

        assertThat(result).contains("timed out after 2s");
    }

    @Test
    void missingWrapperFails() {
        assertThatThrownBy(() -> new GradleTool(repo, null, Duration.ofSeconds(5)).run("help"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no gradle wrapper");
    }
}
```

(The `LEAK` assertion relies on `SDD_SECRET` being unset in the JVM env, so the scrubbed child also lacks it and echoes empty — this proves the scrub does not ADD leaks; a stronger check would set `SDD_SECRET` in the JVM, which JUnit cannot do portably, so the test pins the scrubbed-child behavior for a variable absent by construction. The env allowlist is additionally asserted by `JH` being present only because we injected it.)

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `GradleTool.java`:

```java
package sdd.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The agent's only path to the build: an env-scrubbed subprocess running an ALLOWLISTED Gradle
 * task with a per-repo JAVA_HOME and a hard timeout (design Component 3 guardrails). No generic
 * shell, no arbitrary tasks, no inherited secrets.
 */
public final class GradleTool {
    static final Set<String> ALLOWED = Set.of("help", "compileJava", "classes", "testClasses",
            "assemble", "check", "test", "build");
    static final int MAX_OUTPUT = 8000;
    private static final List<String> KEEP_ENV = List.of("PATH", "HOME", "LANG", "TMPDIR");

    private final Path repoRoot;
    private final Path javaHome;
    private final Duration timeout;

    public GradleTool(Path repoRoot, Path javaHome, Duration timeout) {
        this.repoRoot = repoRoot;
        this.javaHome = javaHome;
        this.timeout = timeout;
    }

    public String run(String task) {
        if (!ALLOWED.contains(task)) {
            throw new ToolException("gradle task not allowed: " + task);
        }
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            throw new ToolException("no gradle wrapper in " + repoRoot);
        }
        Path log = null;
        try {
            log = Files.createTempFile("sdd-agent-gradle", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", task,
                    "--no-configuration-cache", "--no-daemon", "-q"));
            builder.directory(repoRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            scrubEnvironment(builder.environment());
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return "timed out after " + timeout.toSeconds() + "s";
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (output.length() > MAX_OUTPUT) {
                output = "... (head omitted)\n" + output.substring(output.length() - MAX_OUTPUT);
            }
            return "exit " + process.exitValue() + "\n" + output;
        } catch (IOException e) {
            throw new ToolException("gradle run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("gradle run interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    private void scrubEnvironment(Map<String, String> env) {
        Map<String, String> keep = new java.util.HashMap<>();
        for (String name : KEEP_ENV) {
            String value = System.getenv(name);
            if (value != null) {
                keep.put(name, value);
            }
        }
        env.clear();
        env.putAll(keep);
        if (javaHome != null) {
            env.put("JAVA_HOME", javaHome.toAbsolutePath().toString());
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: agent run_gradle env-scrubbed allowlisted subprocess"
```

---

### Task 5: Toolbox — schemas + dispatch

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/tool/Toolbox.java`
- Test: `sdd-agent/src/test/java/sdd/agent/tool/ToolboxTest.java`

**Interfaces:**
- Produces: `public final class Toolbox` — ctor `(FileTools files, GradleTool gradle)`; `List<ToolSpec> specs()` (all six schemas incl. `done`, in a fixed order); `String dispatch(String name, String argsJson)` — parses `argsJson` with Jackson, routes to the tool, returns its string result. `done` is NOT dispatchable here (the loop intercepts it) → dispatching `"done"` throws `MalformedCallException`. Unknown tool, unparseable JSON, or a missing required field → `MalformedCallException`; a tool's own `ToolException` propagates unchanged.
- Consumes: `FileTools` (Tasks 2-3), `GradleTool` (Task 4), `sdd.core.llm.ToolSpec`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.llm.ToolSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolboxTest {
    @TempDir Path root;
    private Toolbox toolbox;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(root.resolve("A.java"), "class A {}\n");
        toolbox = new Toolbox(new FileTools(new PathJail(root)),
                new GradleTool(root, null, Duration.ofSeconds(5)));
    }

    @Test
    void specsCoverAllSixToolsInAFixedOrder() {
        assertThat(toolbox.specs()).extracting(ToolSpec::name).containsExactly(
                "read_file", "list_files", "search", "apply_edit", "run_gradle", "done");
    }

    @Test
    void dispatchRoutesArgsToTheRightTool() {
        assertThat(toolbox.dispatch("read_file", "{\"path\": \"A.java\"}")).contains("class A {}");
        assertThat(toolbox.dispatch("apply_edit",
                "{\"path\": \"B.java\", \"search\": \"\", \"replace\": \"class B {}\\n\"}"))
                .isEqualTo("created B.java");
    }

    @Test
    void malformedAndUnknownCallsThrowMalformed_doneIsNotDispatchable() {
        assertThatThrownBy(() -> toolbox.dispatch("read_file", "not json"))
                .isInstanceOf(MalformedCallException.class);
        assertThatThrownBy(() -> toolbox.dispatch("read_file", "{}"))
                .isInstanceOf(MalformedCallException.class).hasMessageContaining("path");
        assertThatThrownBy(() -> toolbox.dispatch("frobnicate", "{}"))
                .isInstanceOf(MalformedCallException.class).hasMessageContaining("unknown tool");
        assertThatThrownBy(() -> toolbox.dispatch("done", "{\"result\":\"success\"}"))
                .isInstanceOf(MalformedCallException.class);
    }

    @Test
    void toolFailuresPropagateAsToolException() {
        assertThatThrownBy(() -> toolbox.dispatch("read_file", "{\"path\": \"nope.java\"}"))
                .isInstanceOf(ToolException.class).hasMessageContaining("no such file");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `Toolbox.java`:

```java
package sdd.agent.tool;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.llm.ToolSpec;

import java.util.List;

/**
 * Assembles the file and gradle tools into OpenAI tool schemas and a name-routed dispatch.
 * `done` is advertised so the model can call it, but the AgentLoop intercepts it — dispatching
 * `done` here is a programming error and surfaces as a malformed call.
 */
public final class Toolbox {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FileTools files;
    private final GradleTool gradle;

    public Toolbox(FileTools files, GradleTool gradle) {
        this.files = files;
        this.gradle = gradle;
    }

    public List<ToolSpec> specs() {
        return List.of(
                new ToolSpec("read_file", "Read a file's contents (capped).",
                        obj("path", "string", "Repo-relative file path")),
                new ToolSpec("list_files", "List the entries of a directory.",
                        obj("dir", "string", "Repo-relative directory path")),
                new ToolSpec("search", "Regex-search the repo's text files.",
                        obj("regex", "string", "A Java regular expression")),
                new ToolSpec("apply_edit",
                        "Replace a search block with a replacement (empty search = create the file).",
                        editSchema()),
                new ToolSpec("run_gradle", "Run one allowlisted Gradle task.",
                        obj("task", "string", "help|compileJava|classes|testClasses|assemble|check|test|build")),
                new ToolSpec("done", "Finish: result is 'success' or 'blocked'.",
                        doneSchema()));
    }

    public String dispatch(String name, String argsJson) {
        JsonNode args = parse(name, argsJson);
        return switch (name) {
            case "read_file" -> files.readFile(str(args, "path"));
            case "list_files" -> files.listFiles(str(args, "dir"));
            case "search" -> files.search(str(args, "regex"));
            case "apply_edit" -> files.applyEdit(str(args, "path"), str(args, "search"), str(args, "replace"));
            case "run_gradle" -> gradle.run(str(args, "task"));
            case "done" -> throw new MalformedCallException("done is handled by the loop, not dispatched");
            default -> throw new MalformedCallException("unknown tool: " + name);
        };
    }

    private static JsonNode parse(String name, String argsJson) {
        try {
            JsonNode node = JSON.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if (!node.isObject()) {
                throw new MalformedCallException("arguments for " + name + " must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new MalformedCallException("unparseable arguments for " + name + ": " + e.getOriginalMessage());
        }
    }

    private static String str(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual()) {
            throw new MalformedCallException("missing required string argument: " + field);
        }
        return value.asText();
    }

    private static String obj(String field, String type, String desc) {
        return """
                {"type":"object","properties":{"%s":{"type":"%s","description":"%s"}},"required":["%s"]}"""
                .formatted(field, type, desc, field);
    }

    private static String editSchema() {
        return """
                {"type":"object","properties":{"path":{"type":"string"},"search":{"type":"string"},\
                "replace":{"type":"string"}},"required":["path","search","replace"]}""";
    }

    private static String doneSchema() {
        return """
                {"type":"object","properties":{"result":{"type":"string","enum":["success","blocked"]},\
                "summary":{"type":"string"}},"required":["result","summary"]}""";
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: toolbox with tool schemas and name-routed dispatch"
```

---

### Task 6: ContextWindow — usage accounting + eviction

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/loop/ContextWindow.java`
- Test: `sdd-agent/src/test/java/sdd/agent/loop/ContextWindowTest.java`

**Interfaces:**
- Produces: `public final class ContextWindow` — ctor `(int softCapTokens)`; `void addSystem(String)`, `void addWorkOrder(String)`, `void addAssistant(ChatMessage)`, `void addToolResult(String toolCallId, String toolName, String content)`; `List<ChatMessage> messages()`; `int evictIfOverCap(int promptTokens)` (returns count of tool results stubbed; 0 if under cap or nothing evictable).
- Eviction rule (design): when `promptTokens > softCap`, replace every EVICTABLE tool-result entry with `ChatMessage.tool(id, "[evicted: <toolName> result]")`. Evictable = a tool result that is NOT: an `apply_edit` result, the LAST `run_gradle` result, or among the 2 LATEST `read_file` results. System/work-order/assistant entries never evict. Already-stubbed entries are skipped.
- Consumes: `sdd.core.llm.ChatMessage`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.loop;

import org.junit.jupiter.api.Test;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ToolCall;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowTest {

    private static ChatMessage assistantCall(String id, String tool) {
        return new ChatMessage("assistant", null, List.of(new ToolCall(id, tool, "{}")), null);
    }

    @Test
    void underCapEvictsNothing() {
        ContextWindow cw = new ContextWindow(80_000);
        cw.addSystem("sys");
        cw.addWorkOrder("do the thing");
        cw.addAssistant(assistantCall("c1", "read_file"));
        cw.addToolResult("c1", "read_file", "contents");

        assertThat(cw.evictIfOverCap(1000)).isZero();
        assertThat(cw.messages()).hasSize(4);
    }

    @Test
    void overCapStubsOnlyEvictableToolResultsPreservingTheRules() {
        ContextWindow cw = new ContextWindow(80_000);
        cw.addSystem("sys");
        cw.addWorkOrder("wo");
        // 3 reads (only the last 2 preserved), 2 gradle (only the last preserved), 1 edit (always)
        cw.addAssistant(assistantCall("r1", "read_file")); cw.addToolResult("r1", "read_file", "READ1");
        cw.addAssistant(assistantCall("g1", "run_gradle")); cw.addToolResult("g1", "run_gradle", "GRADLE1");
        cw.addAssistant(assistantCall("r2", "read_file")); cw.addToolResult("r2", "read_file", "READ2");
        cw.addAssistant(assistantCall("e1", "apply_edit")); cw.addToolResult("e1", "apply_edit", "EDIT1");
        cw.addAssistant(assistantCall("s1", "search")); cw.addToolResult("s1", "search", "SEARCH1");
        cw.addAssistant(assistantCall("r3", "read_file")); cw.addToolResult("r3", "read_file", "READ3");
        cw.addAssistant(assistantCall("g2", "run_gradle")); cw.addToolResult("g2", "run_gradle", "GRADLE2");

        int evicted = cw.evictIfOverCap(90_000);

        // evictable: READ1 (older than last-2 reads), GRADLE1 (older gradle), SEARCH1 → 3 stubbed
        assertThat(evicted).isEqualTo(3);
        List<String> toolContents = cw.messages().stream()
                .filter(m -> m.role().equals("tool")).map(ChatMessage::content).toList();
        assertThat(toolContents).contains("[evicted: read_file result]", "[evicted: run_gradle result]",
                "[evicted: search result]", "READ2", "READ3", "EDIT1", "GRADLE2")
                .doesNotContain("READ1", "GRADLE1", "SEARCH1");

        // second pass: nothing left evictable
        assertThat(cw.evictIfOverCap(90_000)).isZero();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `ContextWindow.java`:

```java
package sdd.agent.loop;

import sdd.core.llm.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * The agent's conversation history with usage-based eviction (design Component 3): when the
 * endpoint reports prompt_tokens over the soft cap, the oldest tool results that are not
 * load-bearing (not an edit, not the last build, not a recent read) are replaced with stubs.
 * No model summarization; deterministic given the same history and token count.
 */
public final class ContextWindow {
    private final int softCapTokens;
    private final List<Entry> entries = new ArrayList<>();

    private static final class Entry {
        ChatMessage message;
        final String toolName;   // null unless this is a tool-result entry
        boolean stubbed;

        Entry(ChatMessage message, String toolName) {
            this.message = message;
            this.toolName = toolName;
        }
    }

    public ContextWindow(int softCapTokens) {
        this.softCapTokens = softCapTokens;
    }

    public void addSystem(String content) {
        entries.add(new Entry(ChatMessage.system(content), null));
    }

    public void addWorkOrder(String content) {
        entries.add(new Entry(ChatMessage.user(content), null));
    }

    public void addAssistant(ChatMessage message) {
        entries.add(new Entry(message, null));
    }

    public void addToolResult(String toolCallId, String toolName, String content) {
        entries.add(new Entry(ChatMessage.tool(toolCallId, content), toolName));
    }

    public List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(e.message);
        }
        return out;
    }

    public int evictIfOverCap(int promptTokens) {
        if (promptTokens <= softCapTokens) {
            return 0;
        }
        int lastGradle = lastIndexOfTool("run_gradle");
        List<Integer> latestReads = latestIndicesOfTool("read_file", 2);
        int evicted = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.toolName == null || e.stubbed) {
                continue;
            }
            boolean preserve = e.toolName.equals("apply_edit")
                    || (e.toolName.equals("run_gradle") && i == lastGradle)
                    || (e.toolName.equals("read_file") && latestReads.contains(i));
            if (!preserve) {
                e.message = ChatMessage.tool(e.message.toolCallId(), "[evicted: " + e.toolName + " result]");
                e.stubbed = true;
                evicted++;
            }
        }
        return evicted;
    }

    private int lastIndexOfTool(String toolName) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (toolName.equals(entries.get(i).toolName)) {
                return i;
            }
        }
        return -1;
    }

    private List<Integer> latestIndicesOfTool(String toolName, int count) {
        List<Integer> found = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && found.size() < count; i--) {
            if (toolName.equals(entries.get(i).toolName)) {
                found.add(i);
            }
        }
        return found;
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: context window with usage-based deterministic eviction"
```

---

### Task 7: AgentLoop — the native tool-call loop

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/loop/AgentBudget.java`, `AgentOutcome.java`, `AgentLoop.java`
- Test: `sdd-agent/src/test/java/sdd/agent/loop/AgentLoopTest.java`

**Interfaces:**
- Produces:
  - `public record AgentBudget(int maxTurns, java.time.Duration maxWall, long maxTokens)` with `static AgentBudget defaults()` = `(40, Duration.ofMinutes(45), 1_500_000L)`.
  - `public enum AgentResult { DONE, BLOCKED, BUDGET_TURNS, BUDGET_TIME, BUDGET_TOKENS, MALFORMED, WEDGED, CONTEXT_EXHAUSTED }`.
  - `public record AgentOutcome(AgentResult result, String summary, int turns, long tokens, List<String> events)`.
  - `public final class AgentLoop` — ctor `(ChatModel model, Toolbox toolbox, AgentBudget budget, int contextSoftCap, InstantSource clock)`; `AgentOutcome run(String systemPrompt, String workOrder, String modelName, int maxTokensPerCall)`.
- Consumes: `ChatModel`/`ChatRequest`/`ChatResponse`/`ChatMessage`/`ToolCall`/`Usage` (sdd.core.llm), `Toolbox`, `ContextWindow`, `MalformedCallException`, `ToolException`.
- Loop: at the TOP of each iteration (BEFORE the model call) check budgets in order — turn (`turns >= maxTurns` → `BUDGET_TURNS`), wall (`clock.instant()` past `start + maxWall` → `BUDGET_TIME`), token (`total >= maxTokens` → `BUDGET_TOKENS`) — so a `done` on the last permitted turn is processed, not discarded. Then build `ChatRequest(modelName, window.messages(), toolbox.specs(), maxTokensPerCall, 0.0)`; `model.complete`; `turns++`; add response tokens to the running total. Then `window.evictIfOverCap(usage.promptTokens())`; if it returns 0 while `promptTokens > contextSoftCap` → `CONTEXT_EXHAUSTED`. Then process the response:
  - If it has tool calls: append the assistant message; for each call left-to-right: if `name=="done"` → parse `{result, summary}`; valid → return `DONE`/`BLOCKED` immediately (no tool result needed); invalid → append a tool error result, strike. Else dispatch via `toolbox` → append the tool result; `MalformedCallException` → append its message as the tool result + strike; `ToolException` → append its message as the tool result (a legitimate failure, resets strikes). Track wedges (below). A well-formed dispatch or valid done resets the strike streak.
  - If it has NO tool calls (content only): append the assistant message + a user nudge `"Call a tool or done — do not answer in prose."`; count as a strike.
  - After processing: 3 consecutive strikes → `MALFORMED`.
- Wedge detection: keep the previous `(toolName + " " + argsJson)` signature; if the SAME signature occurs on 3 consecutive dispatched tool calls → `WEDGED`. Separately, keep the previous `run_gradle` output; two consecutive `run_gradle` results with identical content → `WEDGED`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.loop;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sdd.agent.tool.FileTools;
import sdd.agent.tool.GradleTool;
import sdd.agent.tool.PathJail;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopTest {
    @TempDir Path root;
    private Toolbox toolbox;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(root.resolve("A.java"), "class A {}\n");
        toolbox = new Toolbox(new FileTools(new PathJail(root)),
                new GradleTool(root, null, Duration.ofSeconds(5)));
    }

    private static ChatResponse call(String id, String tool, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, tool, args)), null), "tool_calls", new Usage(10, 5));
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(ChatMessage.assistant(content), "stop", new Usage(10, 5));
    }

    private AgentLoop loop(ScriptedChatModel model, AgentBudget budget, InstantSource clock) {
        return new AgentLoop(model, toolbox, budget, 80_000, clock);
    }

    @Test
    void readsThenEditsThenDoneSucceeds() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "{\"path\":\"A.java\"}"),
                call("2", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"added field\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "add a field to A", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.DONE);
        assertThat(outcome.summary()).isEqualTo("added field");
        assertThat(outcome.turns()).isEqualTo(3);
        assertThat(model.requests().get(1).messages()).anySatisfy(m ->
                assertThat(m.content()).contains("class A {}"));   // read result fed back
    }

    @Test
    void threeConsecutiveMalformedCallsTerminate() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "not json"),
                call("2", "frobnicate", "{}"),
                text("I think I should give up.")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.MALFORMED);
    }

    @Test
    void identicalActionRepeatedThreeTimesIsWedged() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "read_file", "{\"path\":\"A.java\"}"),
                call("2", "read_file", "{\"path\":\"A.java\"}"),
                call("3", "read_file", "{\"path\":\"A.java\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.WEDGED);
    }

    @Test
    void turnBudgetTerminates() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "list_files", "{\"dir\":\".\"}")));

        AgentOutcome outcome = loop(model, new AgentBudget(1, Duration.ofMinutes(45), 1_500_000L),
                InstantSource.system()).run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.BUDGET_TURNS);
        assertThat(outcome.turns()).isEqualTo(1);
    }

    @Test
    void malformedDoneStrikesThenSucceedsOnRetry() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"maybe\",\"summary\":\"huh\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"ok now\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.DONE);
        assertThat(outcome.summary()).isEqualTo("ok now");
    }

    @Test
    void identicalGradleOutputTwiceIsWedged() throws Exception {
        Path gradlew = root.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\necho BUILD FAILED\nexit 1\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "run_gradle", "{\"task\":\"build\"}"),
                call("2", "run_gradle", "{\"task\":\"build\"}")));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.WEDGED);
    }

    @Test
    void contextExhaustedWhenOverCapWithNothingEvictable() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("thinking out loud"), "stop", new Usage(90_000, 5))));

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), InstantSource.system())
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.CONTEXT_EXHAUSTED);
    }

    @Test
    void wallClockBudgetTerminates() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "list_files", "{\"dir\":\".\"}"),
                call("2", "list_files", "{\"dir\":\".\"}")));
        Instant t0 = Instant.parse("2026-08-12T00:00:00Z");
        // advances 40 minutes per reading: turn 1 at +40min (under 45), terminate check on turn 2
        InstantSource clock = new InstantSource() {
            private int calls = 0;
            @Override public Instant instant() { return t0.plusSeconds(40L * 60 * calls++); }
        };

        AgentOutcome outcome = loop(model, AgentBudget.defaults(), clock)
                .run("sys", "wo", "qwen", 4096);

        assertThat(outcome.result()).isEqualTo(AgentResult.BUDGET_TIME);
    }
}
```

(The wall-clock test's `InstantSource` returns `t0` at construction time (start = call 0) and advances on each subsequent read; the loop reads the clock once per turn after the model call, so turn 2's reading crosses 45 minutes. If the implementation reads the clock a different number of times, adjust the increment — the pin is "time budget fires", so tune the stub to the implementation's clock-read count during GREEN.)

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement the three files.

`AgentBudget.java`:

```java
package sdd.agent.loop;

import java.time.Duration;

/** Per-attempt ceilings (design Component 3): turns, wall-clock, cumulative tokens. */
public record AgentBudget(int maxTurns, Duration maxWall, long maxTokens) {
    public static AgentBudget defaults() {
        return new AgentBudget(40, Duration.ofMinutes(45), 1_500_000L);
    }
}
```

`AgentOutcome.java`:

```java
package sdd.agent.loop;

import java.util.List;

/** The terminal state of one agent attempt. summary carries the done summary or the stop reason. */
public record AgentOutcome(AgentResult result, String summary, int turns, long tokens,
                           List<String> events) {
    public AgentOutcome {
        events = List.copyOf(events);
    }
}
```

Add the enum as a top-level type `AgentResult.java`:

```java
package sdd.agent.loop;

public enum AgentResult {
    DONE, BLOCKED, BUDGET_TURNS, BUDGET_TIME, BUDGET_TOKENS, MALFORMED, WEDGED, CONTEXT_EXHAUSTED
}
```

`AgentLoop.java`:

```java
package sdd.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.agent.tool.MalformedCallException;
import sdd.agent.tool.ToolException;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

/**
 * The native tool-call loop (design Component 3, single attempt): drive the ChatModel, dispatch
 * its tool calls through the Toolbox, feed results back, and stop on done or on a budget /
 * malformed / wedge / context-exhaustion condition. No retries, no verification — the caller
 * (Phase 4B/4C) owns those.
 */
public final class AgentLoop {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STRIKES = 3;
    private static final int WEDGE_REPEAT = 3;

    private final ChatModel model;
    private final Toolbox toolbox;
    private final AgentBudget budget;
    private final int contextSoftCap;
    private final InstantSource clock;

    public AgentLoop(ChatModel model, Toolbox toolbox, AgentBudget budget, int contextSoftCap,
                     InstantSource clock) {
        this.model = model;
        this.toolbox = toolbox;
        this.budget = budget;
        this.contextSoftCap = contextSoftCap;
        this.clock = clock;
    }

    public AgentOutcome run(String systemPrompt, String workOrder, String modelName, int maxTokensPerCall) {
        ContextWindow window = new ContextWindow(contextSoftCap);
        window.addSystem(systemPrompt);
        window.addWorkOrder(workOrder);
        List<String> events = new ArrayList<>();
        Instant start = clock.instant();
        long tokens = 0;
        int turns = 0;
        int strikes = 0;
        String lastSignature = null;
        int sameSignature = 0;
        String lastGradleOutput = null;

        while (true) {
            if (turns >= budget.maxTurns()) {
                return outcome(AgentResult.BUDGET_TURNS, "turn budget reached", turns, tokens, events);
            }
            if (!clock.instant().isBefore(start.plus(budget.maxWall()))) {
                return outcome(AgentResult.BUDGET_TIME, "time budget reached", turns, tokens, events);
            }
            if (tokens >= budget.maxTokens()) {
                return outcome(AgentResult.BUDGET_TOKENS, "token budget reached", turns, tokens, events);
            }

            ChatResponse response = model.complete(new ChatRequest(modelName, window.messages(),
                    toolbox.specs(), maxTokensPerCall, 0.0));
            turns++;
            tokens += response.usage().promptTokens() + response.usage().completionTokens();

            if (window.evictIfOverCap(response.usage().promptTokens()) == 0
                    && response.usage().promptTokens() > contextSoftCap) {
                return outcome(AgentResult.CONTEXT_EXHAUSTED, "context exhausted", turns, tokens, events);
            }

            ChatMessage message = response.message();
            if (message.toolCalls().isEmpty()) {
                window.addAssistant(message);
                window.addWorkOrder("Call a tool or done — do not answer in prose.");
                events.add("turn " + turns + ": no tool call");
                if (++strikes >= MAX_STRIKES) {
                    return outcome(AgentResult.MALFORMED, "no tool calls", turns, tokens, events);
                }
                continue;
            }

            window.addAssistant(message);
            for (ToolCall call : message.toolCalls()) {
                if (call.name().equals("done")) {
                    AgentOutcome done = tryDone(call, turns, tokens, events);
                    if (done != null) {
                        return done;
                    }
                    window.addToolResult(call.id(), "done", "malformed done — provide result and summary");
                    if (++strikes >= MAX_STRIKES) {
                        return outcome(AgentResult.MALFORMED, "malformed done", turns, tokens, events);
                    }
                    continue;
                }

                String signature = call.name() + " " + call.argumentsJson();
                sameSignature = signature.equals(lastSignature) ? sameSignature + 1 : 1;
                lastSignature = signature;
                if (sameSignature >= WEDGE_REPEAT) {
                    return outcome(AgentResult.WEDGED, "identical action repeated", turns, tokens, events);
                }

                try {
                    String result = toolbox.dispatch(call.name(), call.argumentsJson());
                    window.addToolResult(call.id(), call.name(), result);
                    strikes = 0;
                    if (call.name().equals("run_gradle")) {
                        if (result.equals(lastGradleOutput)) {
                            return outcome(AgentResult.WEDGED, "identical build output", turns, tokens, events);
                        }
                        lastGradleOutput = result;
                    }
                } catch (MalformedCallException e) {
                    window.addToolResult(call.id(), call.name(), "malformed call: " + e.getMessage());
                    events.add("turn " + turns + ": malformed " + call.name());
                    if (++strikes >= MAX_STRIKES) {
                        return outcome(AgentResult.MALFORMED, e.getMessage(), turns, tokens, events);
                    }
                } catch (ToolException e) {
                    window.addToolResult(call.id(), call.name(), "error: " + e.getMessage());
                    strikes = 0;
                }
            }
        }
    }

    private AgentOutcome tryDone(ToolCall call, int turns, long tokens, List<String> events) {
        try {
            JsonNode args = JSON.readTree(call.argumentsJson());
            String result = args.path("result").asText();
            String summary = args.path("summary").asText();
            if (result.equals("success")) {
                return outcome(AgentResult.DONE, summary, turns, tokens, events);
            }
            if (result.equals("blocked")) {
                return outcome(AgentResult.BLOCKED, summary, turns, tokens, events);
            }
            return null;
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return null;
        }
    }

    private static AgentOutcome outcome(AgentResult result, String summary, int turns, long tokens,
                                        List<String> events) {
        return new AgentOutcome(result, summary, turns, tokens, events);
    }
}
```

- [ ] **Step 3: Run — expect PASS** (tune the wall-clock stub's increment during GREEN so the time budget fires on the expected turn, per the note above).
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 4: Full build, then commit**

```bash
./gradlew build
git add sdd-agent/src
git commit -m "feat: agent loop with budgets, strikes, and wedge detection"
```

---

## Verification

1. `./gradlew build` — all modules green (sdd-agent joins the build).
2. `AgentLoopTest.readsThenEditsThenDoneSucceeds` proves the whole native tool-call cycle (read → edit → done) with results fed back through the ChatModel seam; the budget/strike/wedge tests prove each terminal condition; `ContextWindowTest` and the tool tests pin the guardrails and caps.
3. This module has no CLI and no live model dependency yet — 4B (work orders, verification, restart digest, attempt retries) and 4C (orchestration, run state, propagation, config wiring) consume `AgentLoop`.

## Self-Review (completed at write time)

1. **Spec coverage (Component 3, single-repo single-attempt slice):** ChatModel seam + native tool-calls → Task 7; six tools with their exact caps/semantics → Tasks 2-5 (read 400-line/16KB, list, regex search, apply_edit exact+lenient+java-revert+create, run_gradle allowlisted, done) ; path jail + .git denial + toRealPath → Task 1; env-scrubbed subprocess + per-repo JAVA_HOME + timeout+destroyForcibly → Task 4; usage-based accounting + 80k cap + deterministic tool-result eviction with the exact preservation set + no summarization → Task 6; 40/45m/1.5M budgets + 3-strike malformed + identical-action + identical-build-signature wedges → Task 7. Deferred to 4B (recorded): work-order construction, run-the-verification-tasks-on-done, 2-done→verify-fail cycle, CONTEXT_EXHAUSTED fresh-restart digest, attempt-2 escalation; deferred to 4C: orchestration, run state, propagation flags, `tool_protocol: fenced` fallback, and SddConfig/agent-config wiring. The 80k cap and 4k-token gradle-output structured compaction: the cap is here; the structured javac/JUnit compaction is 4B.
2. **Placeholder scan:** none; the two GREEN-tuning notes (env-leak variable absent-by-construction; wall-clock stub increment) are explicit calibration instructions, not gaps.
3. **Recorded 4A limitations (structured surfacing lands in 4B):** the identical-build-signature wedge compares the model-facing gradle output, which `run_gradle` tail-caps at 8000 chars — a partial fix whose only visible change fell in the omitted head could false-positive; a stable per-error signature arrives with 4B's structured (JUnit-XML) compaction. Relatedly, `run_gradle("test")` under `-q` prints only a report-path pointer, not per-test failures, so real test-failure surfacing also waits on 4B's JUnit-XML read (design's "JUnit XML, never console-scraped"). Not resetting `lastGradleOutput` on `apply_edit` is deliberate — the detector's whole job is catching "edited, rebuilt, nothing moved."
4. **Adversarial critique pass (2 independent critics vs the real codebase, findings folded in):** the budget checks moved to the TOP of the loop (a `done` on the last permitted turn was being discarded — 40 turns meant 39 usable); `apply_edit`'s edit branch now resolves via `resolveExisting` so the `toRealPath` symlink jail covers the WRITE path, not just reads; `run_gradle`'s timeout now does a process-tree kill (`descendants()` + `destroyForcibly`) with `--no-daemon` so a real Gradle daemon can't orphan; the mislabeled "lenient" edit test was corrected and a genuine multi-line lenient test added (the lenient reconstruction path had zero positive coverage); malformed-`done`, identical-build-signature WEDGED, and CONTEXT_EXHAUSTED loop-terminal tests added. Critics empirically ran the macOS `/var` `@TempDir` path-jail (works — no fix), `GradleTool`'s subprocess/scrub/timeout, and every `ScriptedChatModel` call count.
5. **Type consistency:** `PathJail.resolve/resolveExisting` across Tasks 1-4; `FileTools(read/list/search/applyEdit)` across 2-3-5; `GradleTool.run` across 4-5; `Toolbox(specs/dispatch)` across 5-7; `ContextWindow(add*/messages/evictIfOverCap)` across 6-7; `AgentBudget/AgentOutcome/AgentResult/AgentLoop.run` in 7; `ToolException` vs `MalformedCallException` strike semantics consistent between Toolbox (Task 5) and AgentLoop (Task 7).
