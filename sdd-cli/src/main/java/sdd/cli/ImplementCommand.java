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
import sdd.cli.implement.MavenLocalInit;
import sdd.cli.implement.MavenLocalPublisher;
import sdd.cli.implement.Orchestrator;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.PlannedVersions;
import sdd.cli.implement.PreFlight;
import sdd.cli.implement.Propagation;
import sdd.cli.implement.PropagationPlanner;
import sdd.cli.implement.RepoPropagation;
import sdd.cli.implement.RepoStepResolver;
import sdd.cli.implement.Resume;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.EndpointProbe;
import sdd.core.llm.HttpChatModel;
import sdd.core.llm.ThrottledChatModel;
import sdd.index.gradle.GradleExtractor;
import sdd.plan.approve.Hashes;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Known limitation: a hard-killed process (SIGKILL, power loss) leaves the lock file behind — only
 * in-process exits reach the orchestrator's {@code finally} release — so {@code --resume} after a hard
 * crash aborts with the existing "already in progress … remove the lock to override" message. Manual
 * lock removal is the deliberate escape hatch this phase; lock-staleness detection is 4C-3b territory.
 */
@Command(name = "implement",
        description = "Execute an approved plan.json across the estate",
        exitCodeOnInvalidInput = 4)
public final class ImplementCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--resume", description = "Resume a paused or crashed run of this plan from its checkpoints")
    boolean resume;

    @Option(names = "--wait-endpoint",
            description = "After an endpoint pause, poll the model endpoints and auto-resume when they answer")
    boolean waitEndpoint;

    @Parameters(index = "0", description = "The approved <spec>.plan.json")
    Path planJsonPath;

    @Spec CommandSpec spec;

    ChatModel coderForTest;        // test seam — mirrors ApproveCommand.smokeForTest
    ChatModel escalationForTest;   // test seam for the attempt-2 model

    Function<ModelEndpoint, EndpointProbe.ProbeResult> probeForTest;   // test seam; null = real probe
    long waitPollMillis = 30_000;

    private String lastPausedReason;
    private SddConfig lastConfig;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        while (true) {
            Integer exit = runPlan(out, err);
            if (exit == 3 && waitEndpoint && lastPausedReason != null
                    && lastPausedReason.startsWith("model endpoint unavailable")) {
                waitForEndpoints(out);
                resume = true;   // state.json now exists; re-enter through the snapshot path
                continue;
            }
            return exit;
        }
    }

    private void waitForEndpoints(PrintWriter out) {
        out.println("waiting for model endpoints to answer (--wait-endpoint)...");
        Function<ModelEndpoint, EndpointProbe.ProbeResult> probe =
                probeForTest != null ? probeForTest : EndpointProbe::probe;
        List<ModelEndpoint> endpoints = List.of(lastConfig.models().get("coder"),
                lastConfig.models().get("planner"));
        while (true) {
            try {
                Thread.sleep(waitPollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean allUp = endpoints.stream().allMatch(endpoint -> probe.apply(endpoint).ok());
            if (allUp) {
                out.println("endpoints answering — resuming");
                return;
            }
        }
    }

    private Integer runPlan(PrintWriter out, PrintWriter err) {
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
            lastConfig = config;
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
                Map<String, RepoPropagation> propagation = Map.of();
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
                    for (String repo : steps.keySet()) {
                        List<String> planned = plan.step(repo).map(PlanModel.PlanStep::verification)
                                .filter(v -> !v.isEmpty()).orElse(List.of("check"));
                        List<String> excluded = config.verificationExclusions().getOrDefault(repo, List.of());
                        if (!excluded.isEmpty() && excluded.containsAll(planned)) {
                            out.println("warn: " + repo + ": all verification tasks excluded by sdd.yml — "
                                    + "will be marked not locally verified");
                        }
                    }
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
                    Map<String, RepoPropagation> snapshot = store.readPropagation(runDir);
                    if (snapshot != null) {
                        propagation = snapshot;
                    } else {
                        // pre-4C-3b run dir: fall back to live planning (KB drift caveat applies)
                        List<String> propagationProblems = new ArrayList<>();
                        propagation = PropagationPlanner.plan(jdbi, plan, runDir,
                                PlannedVersions.compute(jdbi, plan), propagationProblems);
                        if (!propagationProblems.isEmpty()) {
                            propagationProblems.forEach(p -> err.println("problem: " + p));
                            return 4;
                        }
                    }
                    store.acquireLock(runDir);   // LAST, after every gate: everything above is read-only,
                                                 // so no abort path between acquire and the orchestrator's
                                                 // finally-release can leak the lock and wedge future resumes
                    initialState = prep.state();
                } else {
                    for (String repo : steps.keySet()) {
                        List<String> planned = plan.step(repo).map(PlanModel.PlanStep::verification)
                                .filter(v -> !v.isEmpty()).orElse(List.of("check"));
                        List<String> excluded = config.verificationExclusions().getOrDefault(repo, List.of());
                        if (!excluded.isEmpty() && excluded.containsAll(planned)) {
                            out.println("warn: " + repo + ": all verification tasks excluded by sdd.yml — "
                                    + "will be marked not locally verified");
                        }
                    }
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
                    List<String> propagationProblems = new ArrayList<>();
                    propagation = PropagationPlanner.plan(jdbi, plan, runDir,
                            PlannedVersions.compute(jdbi, plan), propagationProblems);
                    if (!propagationProblems.isEmpty()) {
                        propagationProblems.forEach(p -> err.println("problem: " + p));
                        return 4;
                    }
                    runDir = store.create(workspace, runId, planText, specText);
                    try {
                        store.writePropagation(runDir, propagation);
                        for (Map.Entry<String, RepoPropagation> entry : propagation.entrySet()) {
                            RepoPropagation.PublishSpec publish = entry.getValue().publish();
                            if (publish != null
                                    && publish.version().equals(PlannedVersions.current(jdbi, entry.getKey()))) {
                                out.println("warn: " + entry.getKey() + " republishes its current version "
                                        + publish.version() + " — consumers may resolve a stale released artifact");
                            }
                        }
                    } catch (RuntimeException e) {
                        store.releaseLock(runDir);
                        throw e;
                    }
                }

                final PlanModel activePlan = plan;
                final Map<String, RepoStep> activeSteps = steps;
                final Path activeRunDir = runDir;
                final Map<String, RepoPropagation> activePropagation = propagation;
                if (!Propagation.mavenLocalArgs(activePlan.edges(),
                        MavenLocalInit.scriptPath(activeRunDir)).isEmpty()) {
                    try {
                        MavenLocalInit.write(activeRunDir);
                    } catch (RuntimeException e) {
                        store.releaseLock(activeRunDir);   // post-lock write must not leak the lock on failure
                        throw e;
                    }
                }
                ModelEndpoint coderEndpoint = config.models().get("coder");
                ModelEndpoint plannerEndpoint = config.models().get("planner");
                Semaphore gradlePermits = new Semaphore(config.run().gradleWorkers());
                Semaphore modelPermits = new Semaphore(config.run().modelConcurrency());
                ChatModel coder = coderForTest != null ? coderForTest : new HttpChatModel(coderEndpoint);
                ChatModel escalation = escalationForTest != null ? escalationForTest
                        : coderForTest != null ? coderForTest : new HttpChatModel(plannerEndpoint);
                coder = new ThrottledChatModel(coder, modelPermits);
                escalation = new ThrottledChatModel(escalation, modelPermits);
                String coderName = coderEndpoint.model();
                String escalationName = plannerEndpoint.model();
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = activeSteps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    List<String> extraArgs = new ArrayList<>(Propagation.includeBuildArgs(
                            repo, activePlan.edges(), paths));
                    extraArgs.addAll(Propagation.mavenLocalArgs(
                            activePlan.edges(), MavenLocalInit.scriptPath(activeRunDir)));
                    List<String> tasks = new ArrayList<>(activePlan.step(repo)
                            .map(PlanModel.PlanStep::verification)
                            .filter(v -> !v.isEmpty())
                            .orElse(List.of("check")));
                    tasks.removeAll(config.verificationExclusions().getOrDefault(repo, List.of()));
                    return RunnerSettings.custom(javaHome, extraArgs, tasks, gradlePermits);
                };

                Orchestrator orchestrator = new Orchestrator(new RepoStepRunner(jdbi), coder, coderName,
                        escalation, escalationName, settingsFor, store, config.run().tokenBudget(),
                        activePropagation, new MavenLocalPublisher());
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
                    lastPausedReason = result.state().pausedReason();
                    out.println("paused: " + result.state().pausedReason());
                    if (result.state().pausedReason().contains("token budget")) {
                        out.println("raise run.token_budget in sdd.yml, then: sdd implement --resume "
                                + planJsonPath);
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
