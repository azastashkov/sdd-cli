package sdd.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.toolchain.EnvPolicy;
import sdd.core.toolchain.Subprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * The agent's build access in an npm repo: {@code npm run <script>}, restricted to a fixed set of
 * scripts, with a per-repo environment and a hard timeout.
 *
 * <p>The allowlist is a FIXED SET intersected with what the repo actually defines — in that order,
 * and the order is a safety property rather than a style choice. Deriving it from
 * {@code package.json} would hand the model whatever the humans happen to have written there, and
 * a real estate's SDK defines
 * {@code "release": "npm test && npm version patch && npm publish && git push --follow-tags"}.
 * A model given that tool publishes to the public registry and pushes tags. {@code dev} is equally
 * disqualified for a duller reason: it starts a dev server and never exits, so it burns the entire
 * timeout every time.
 */
public final class NpmTool implements BuildTool {

    /**
     * The most a model may run, whatever a repo defines. Deliberately excludes {@code check}: that
     * is sdd's own internal default-verification token, resolved before it can reach a subprocess,
     * and inventing an npm script the humans do not have would be a lie in the tool surface.
     */
    static final Set<String> CANONICAL = new LinkedHashSet<>(
            List.of("build", "typecheck", "test", "lint"));

    static final int MAX_OUTPUT = 8000;
    static final int MAX_FULL_OUTPUT = 200_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path repoRoot;
    private final Path nodeHome;
    private final Duration timeout;
    private final Semaphore permits;
    private final Set<String> advertised;

    public NpmTool(Path repoRoot, Path nodeHome, Duration timeout) {
        this(repoRoot, nodeHome, timeout, null);
    }

    public NpmTool(Path repoRoot, Path nodeHome, Duration timeout, Semaphore permits) {
        this.repoRoot = repoRoot;
        this.nodeHome = nodeHome;
        this.timeout = timeout;
        this.permits = permits;
        this.advertised = advertised(repoRoot);
    }

    /** {@link #CANONICAL} narrowed to the scripts this repo defines. */
    public static Set<String> advertised(Path repoRoot) {
        Set<String> scripts = scriptsOf(repoRoot);
        Set<String> allowed = new LinkedHashSet<>();
        for (String candidate : CANONICAL) {
            if (scripts.contains(candidate)) {
                allowed.add(candidate);
            }
        }
        return allowed;
    }

    static Set<String> scriptsOf(Path repoRoot) {
        Path packageJson = repoRoot.resolve("package.json");
        if (!Files.isRegularFile(packageJson)) {
            return Set.of();
        }
        try {
            JsonNode scripts = JSON.readTree(Files.readString(packageJson)).path("scripts");
            Set<String> names = new LinkedHashSet<>();
            scripts.fieldNames().forEachRemaining(names::add);
            return names;
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    @Override
    public String toolName() {
        return "run_npm";
    }

    @Override
    public String taskDescription() {
        return advertised.isEmpty() ? "(this repo defines none of build|typecheck|test|lint)"
                : String.join("|", advertised);
    }

    @Override
    public Set<String> tasks() {
        return Set.copyOf(advertised);
    }

    @Override
    public String run(String script) {
        return execute(script, false);
    }

    @Override
    public String runFull(String script) {
        return execute(script, true);
    }

    private String execute(String script, boolean headPreserving) {
        if (!advertised.contains(script)) {
            // Naming what IS available matters: npm answers an unknown script with an opaque
            // ELIFECYCLE error, and a model given that will spend turns guessing.
            throw new ToolException("no npm script '" + script + "' in " + repoRoot
                    + " — available: " + (advertised.isEmpty() ? "(none)" : String.join(", ", advertised)));
        }
        if (!Files.isRegularFile(repoRoot.resolve("package.json"))) {
            throw new ToolException("no package.json in " + repoRoot);
        }
        if (permits != null) {
            permits.acquireUninterruptibly();
        }
        try {
            // No extra arguments are ever appended. npm attaches passthrough args to the END of the
            // whole script string, so for a script like `npm run test --workspaces` or
            // `node a.mjs && node b.mjs` they land on the wrong command entirely. Substitution for
            // npm is done by overlaying node_modules, never by flags.
            Subprocess.Outcome outcome = Subprocess.run(
                    List.of(npmExecutable(), "run", script),
                    repoRoot, EnvPolicy.scrubbedNode(nodeHome), timeout,
                    Subprocess.KillPolicy.PROCESS_TREE, "sdd-agent-npm");
            if (outcome.timedOut()) {
                return "timed out after " + timeout.toSeconds() + "s";
            }
            String output = headPreserving ? headCap(outcome.output()) : tailCap(outcome.output());
            return "exit " + outcome.exitCode() + "\n" + output;
        } catch (IOException e) {
            throw new ToolException("npm run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("npm run interrupted");
        } finally {
            if (permits != null) {
                permits.release();
            }
        }
    }

    /**
     * The npm to run, as an absolute path when {@code node_home} names one.
     *
     * <p>Resolving it here rather than relying on the child's PATH is necessary, not tidiness:
     * {@link ProcessBuilder} looks the command up on the PARENT process's PATH and ignores the
     * PATH in the environment it is handed. A configured node_home would otherwise be silently
     * disregarded when choosing which npm runs — the machine's default would win, and nothing
     * would say so.
     */
    private String npmExecutable() {
        if (nodeHome != null) {
            Path candidate = nodeHome.toAbsolutePath().resolve("bin").resolve("npm");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "npm";
    }

    private static String tailCap(String output) {
        return output.length() > MAX_OUTPUT
                ? "... (head omitted)\n" + output.substring(output.length() - MAX_OUTPUT) : output;
    }

    private static String headCap(String output) {
        return output.length() > MAX_FULL_OUTPUT
                ? output.substring(0, MAX_FULL_OUTPUT) + "\n... (tail omitted)" : output;
    }
}
