package sdd.cli.implement;

import sdd.agent.tool.GradleTool;
import sdd.agent.tool.NpmTool;
import sdd.core.toolchain.Toolchain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What actually gets run to verify a repo, resolved once.
 *
 * <p>{@code ImplementCommand} and {@code RebuildPass} used to carry hand-mirrored copies of this —
 * the latter's javadoc said so out loud — which is exactly the arrangement where a fix lands on one
 * side and Gate 2 quietly verifies something different from what the agent was held to.
 */
public final class VerificationTasks {

    private VerificationTasks() {
    }

    /**
     * A plan's verification list, narrowed to what the toolchain can actually run, with the
     * toolchain's default when the plan says nothing runnable.
     *
     * <p>The subtle branch is the last one: a plan whose verification list is entirely prose ("the
     * blotter still reconciles") still means "verify this repo normally". Reading it as "run
     * nothing" would silently disable verification for every repo whose acceptance criteria were
     * written for humans.
     */
    public static List<String> resolve(Toolchain toolchain, Path repoRoot,
                                       List<String> planVerification, List<String> exclusions) {
        List<String> defaults = defaultsFor(toolchain, repoRoot);
        Set<String> runnable = allowedFor(toolchain, repoRoot);

        List<String> tasks = new ArrayList<>(planVerification.isEmpty() ? defaults : planVerification);
        tasks.retainAll(runnable);
        if (!planVerification.isEmpty() && tasks.isEmpty()) {
            tasks = new ArrayList<>(defaults);
        }
        tasks.removeAll(exclusions);
        return List.copyOf(tasks);
    }

    /**
     * The plan's verification entries this toolchain cannot run, in plan order — what the Gate-1
     * warning reports as kept-for-acceptance prose.
     */
    public static List<String> notRunnable(Toolchain toolchain, Path repoRoot,
                                           List<String> planVerification) {
        Set<String> runnable = allowedFor(toolchain, repoRoot);
        return planVerification.stream().filter(entry -> !runnable.contains(entry)).toList();
    }

    /** What the runnable entries would have been, named for the toolchain that runs them. */
    public static String runnableLabel(Toolchain toolchain) {
        return toolchain == Toolchain.NPM ? "npm scripts" : "gradle tasks";
    }

    private static Set<String> allowedFor(Toolchain toolchain, Path repoRoot) {
        return toolchain == Toolchain.NPM ? NpmTool.advertised(repoRoot) : GradleTool.allowedTasks();
    }

    /**
     * What "verify this repo" means when a plan does not say.
     *
     * <p>Gradle has one answer, {@code check}, because the ecosystem has a conventional aggregate
     * task. npm has no such convention, so the default is read from the scripts the repo defines:
     * a type gate if there is one, then the tests.
     *
     * <p>{@code typecheck} is preferred over {@code build} where both exist, and the reason is
     * specific: a Vite build strips types with esbuild and never checks them, so it can succeed on
     * code that does not type-check. Where a repo has no {@code typecheck}, {@code build} is used
     * because for a library it usually IS {@code tsc}. Two gaps this leaves are real and are
     * reported rather than hidden — bundling is not exercised for an app whose build is Vite, and
     * type errors inside test files are caught by neither, since tsconfig excludes them and the
     * test runner transpiles without checking.
     */
    static List<String> defaultsFor(Toolchain toolchain, Path repoRoot) {
        if (toolchain != Toolchain.NPM) {
            return List.of("check");
        }
        Set<String> scripts = NpmTool.scriptsOf(repoRoot);
        List<String> defaults = new ArrayList<>();
        if (scripts.contains("typecheck")) {
            defaults.add("typecheck");
        } else if (scripts.contains("build")) {
            defaults.add("build");
        }
        if (scripts.contains("test")) {
            defaults.add("test");
        }
        return List.copyOf(defaults);
    }
}
