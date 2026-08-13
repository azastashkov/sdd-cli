package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.cli.implement.Orchestrator;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.PreFlight;
import sdd.cli.implement.RepoStepResolver;
import sdd.cli.implement.Resume;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.index.gradle.GradleExtractor;
import sdd.plan.approve.Hashes;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Known limitation: a hard-killed process (SIGKILL, power loss) leaves the lock file behind — only
 * in-process exits reach the orchestrator's {@code finally} release — so {@code --resume} after a hard
 * crash aborts with the existing "already in progress … remove the lock to override" message. Manual
 * lock removal is the deliberate escape hatch this phase; lock-staleness detection is 4C-3b territory.
 */
@Command(name = "implement",
        description = "Execute an approved plan.json across the estate (single attempt per repo)",
        exitCodeOnInvalidInput = 4)
public final class ImplementCommand implements Callable<Integer> {
    private static final long RUN_TOKEN_BUDGET = 30_000_000L;   // design line 59; sdd.yml override is 4C-3b

    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--resume", description = "Resume a paused or crashed run of this plan from its checkpoints")
    boolean resume;

    @Parameters(index = "0", description = "The approved <spec>.plan.json")
    Path planJsonPath;

    @Spec CommandSpec spec;

    ChatModel coderForTest;        // test seam — mirrors ApproveCommand.smokeForTest
    ChatModel escalationForTest;   // test seam for the attempt-2 model

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            String name = planJsonPath.getFileName().toString();
            if (!name.endsWith(".plan.json")) {
                err.println("error: implement expects a .plan.json file");
                return 4;
            }
            String planText = Files.readString(planJsonPath);
            PlanModel plan = PlanJsonReader.read(planText);
            PlanJsonReader.validate(plan);
            Path specPath = planJsonPath.resolveSibling(
                    name.substring(0, name.length() - ".plan.json".length()) + ".md");
            String specText = Files.readString(specPath);
            NormalizedSpec parsedSpec = SpecParser.parse(specText);
            if (!plan.specSha256().isEmpty() && !Hashes.sha256(specText).equals(plan.specSha256())) {
                out.println("warn: spec " + specPath.getFileName() + " has changed since approval — "
                        + "requirement text may not match the plan");
            }
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                err.println("error: knowledge base is empty — run sdd index first");
                return 4;
            }
            SddConfig config = ConfigLoader.load(workspace);
            try (Database db = Database.open(workspace)) {
                Jdbi jdbi = db.jdbi();
                Map<String, Path> paths = new HashMap<>();
                jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                        .forEach(row -> paths.put(String.valueOf(row.get("name")),
                                Path.of(String.valueOf(row.get("path"))))));
                Map<String, RepoStep> steps = RepoStepResolver.resolve(plan, parsedSpec, paths);

                String runId = sanitize(plan.specId()) + "-v" + plan.planVersion();
                RunStore store = RunStore.system();
                Path runDir = workspace.resolve(".sdd/runs/" + runId);
                RunState initialState = null;
                if (resume) {
                    if (!Files.exists(runDir.resolve("state.json"))) {
                        err.println("error: no run to resume at " + runDir);
                        return 4;
                    }
                    planText = Files.readString(runDir.resolve("plan.json"));   // snapshots, not live files
                    plan = PlanJsonReader.read(planText);
                    PlanJsonReader.validate(plan);
                    specText = Files.readString(runDir.resolve("spec.md"));
                    parsedSpec = SpecParser.parse(specText);
                    steps = RepoStepResolver.resolve(plan, parsedSpec, paths);
                    Resume.Prep prep = Resume.prepare(store.readState(runDir), steps);
                    if (!prep.problems().isEmpty()) {
                        prep.problems().forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
                    PreFlight.Result gate = PreFlight.checkResume(steps, plan, prep.state());
                    if (!gate.ok()) {
                        gate.problems().forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
                    store.acquireLock(runDir);   // LAST, after every gate: everything above is read-only,
                                                 // so no abort path between acquire and the orchestrator's
                                                 // finally-release can leak the lock and wedge future resumes
                    initialState = prep.state();
                } else {
                    if (Files.exists(runDir.resolve("state.json"))) {
                        err.println("error: run " + runId + " already exists — resume with --resume, "
                                + "or delete " + runDir + " to start over");
                        return 4;
                    }
                    PreFlight.Result preflight = PreFlight.check(steps, plan);
                    if (!preflight.ok()) {
                        preflight.problems().forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
                    runDir = store.create(workspace, runId, planText, specText);
                }

                final PlanModel activePlan = plan;
                final Map<String, RepoStep> activeSteps = steps;
                ModelEndpoint coderEndpoint = config.models().get("coder");
                ModelEndpoint plannerEndpoint = config.models().get("planner");
                ChatModel coder = coderForTest != null ? coderForTest : new HttpChatModel(coderEndpoint);
                ChatModel escalation = escalationForTest != null ? escalationForTest
                        : coderForTest != null ? coderForTest : new HttpChatModel(plannerEndpoint);
                String coderName = coderEndpoint.model();
                String escalationName = plannerEndpoint.model();
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = activeSteps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    List<String> extraArgs = sdd.cli.implement.Propagation.includeBuildArgs(
                            repo, activePlan.edges(), paths);
                    return RunnerSettings.defaults(javaHome, extraArgs);
                };

                Orchestrator orchestrator = new Orchestrator(new RepoStepRunner(jdbi), coder, coderName,
                        escalation, escalationName, settingsFor, store, RUN_TOKEN_BUDGET);
                Orchestrator.RunResult result = initialState == null
                        ? orchestrator.run(runDir, activePlan, activeSteps)
                        : orchestrator.run(runDir, activePlan, activeSteps, initialState);

                for (var repo : result.state().repos()) {
                    out.println(repo.repo() + ": " + repo.state()
                            + (repo.detail() == null || repo.detail().isBlank() ? "" : " — " + repo.detail()));
                }
                String label = result.exitCode() == 0 ? "COMPLETE"
                        : result.exitCode() == 3 ? "PAUSED" : "PARTIAL";
                out.println("run " + runId + " " + label
                        + " (state: " + runDir.resolve("state.json") + ")");
                if (result.exitCode() == 3) {
                    out.println("paused: " + result.state().pausedReason());
                    if (result.state().pausedReason().contains("token budget")) {
                        // tokensSpent persists across --resume, so a budget pause re-pauses immediately;
                        // honesty over a dead-end hint until 4C-3b makes the budget configurable.
                        out.println("the run token budget is fixed this phase — delete " + runDir
                                + " to start over, or wait for 4C-3b's configurable budget");
                    } else {
                        out.println("resume with: sdd implement --resume " + planJsonPath);
                    }
                }
                return result.exitCode();
            }
        } catch (RuntimeException | java.io.IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    private static String sanitize(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
