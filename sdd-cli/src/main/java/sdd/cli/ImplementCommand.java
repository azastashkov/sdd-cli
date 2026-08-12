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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

@Command(name = "implement",
        description = "Execute an approved plan.json across the estate (single attempt per repo)")
public final class ImplementCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", description = "The approved <spec>.plan.json")
    Path planJsonPath;

    @Spec CommandSpec spec;

    ChatModel coderForTest;   // test seam — mirrors ApproveCommand.smokeForTest

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

                PreFlight.Result preflight = PreFlight.check(steps, plan);
                if (!preflight.ok()) {
                    for (String problem : preflight.problems()) {
                        err.println("problem: " + problem);
                    }
                    return 4;
                }

                ModelEndpoint coderEndpoint = config.models().get("coder");
                ChatModel coder = coderForTest != null ? coderForTest : new HttpChatModel(coderEndpoint);
                String coderName = coderEndpoint.model();
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = steps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    return RunnerSettings.defaults(javaHome);
                };

                String runId = sanitize(plan.specId()) + "-v" + plan.planVersion();
                RunStore store = RunStore.system();
                Path runDir = store.create(workspace, runId, planText);
                Orchestrator orchestrator = new Orchestrator(new RepoStepRunner(jdbi), coder, coderName,
                        settingsFor, store, java.time.InstantSource.system());
                Orchestrator.RunResult result = orchestrator.run(runDir, plan, steps);

                for (var repo : result.state().repos()) {
                    out.println(repo.repo() + ": " + repo.state()
                            + (repo.detail() == null || repo.detail().isBlank() ? "" : " — " + repo.detail()));
                }
                out.println("run " + runId + " " + (result.exitCode() == 0 ? "COMPLETE" : "PARTIAL")
                        + " (state: " + runDir.resolve("state.json") + ")");
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
