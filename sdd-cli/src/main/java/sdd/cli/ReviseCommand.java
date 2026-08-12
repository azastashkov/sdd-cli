package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.core.retrieve.FtsRetriever;
import sdd.plan.approve.PlanDocument;
import sdd.plan.approve.PlanMdParser;
import sdd.plan.gen.ExecutionOrder;
import sdd.plan.gen.OpenQuestions;
import sdd.plan.gen.PlanDrafter;
import sdd.plan.gen.PlanMdRenderer;
import sdd.plan.gen.Question;
import sdd.plan.gen.SafeWrite;
import sdd.plan.impact.ImpactAnalysis;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecValidator;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "revise", description = "Regenerate the plan with prior Q&A folded in, bumping plan_version")
public final class ReviseCommand implements Callable<Integer> {
    private static final int SEED_MAX_ATTEMPTS = 2;

    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", description = "The existing <spec>.plan.md to revise")
    Path planPath;

    @Spec CommandSpec spec;

    ChatModel plannerForTest;

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        try {
            String name = planPath.getFileName().toString();
            if (!name.endsWith(".plan.md")) {
                errWriter.println("error: revise expects a .plan.md file");
                return 1;
            }
            SddConfig config = ConfigLoader.load(workspace);
            PlanDocument old = PlanMdParser.parse(Files.readString(planPath));
            Path specPath = planPath.resolveSibling(
                    name.substring(0, name.length() - ".plan.md".length()) + ".md");
            NormalizedSpec parsedSpec = SpecParser.parse(Files.readString(specPath));
            List<String> specProblems = SpecValidator.problems(parsedSpec);
            if (!specProblems.isEmpty()) {
                for (String problem : specProblems) {
                    errWriter.println("problem: " + problem);
                }
                return 1;
            }
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                errWriter.println("error: knowledge base is empty — run sdd index first");
                return 1;
            }
            try (Database db = Database.open(workspace)) {
                ModelEndpoint planner = config.models().get("planner");
                ChatModel model = plannerForTest != null ? plannerForTest
                        : new HttpChatModel(planner, SEED_MAX_ATTEMPTS);
                ImpactResult result = ImpactAnalysis.analyze(db.jdbi(),
                        new FtsRetriever(db.jdbi()), parsedSpec, model, planner.model(),
                        planner.maxTokens());
                List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);
                List<Question> questions = OpenQuestions.detect(db.jdbi(), result);
                StringBuilder priorQa = new StringBuilder();
                for (PlanDocument.PlanQuestion question : old.questions()) {
                    priorQa.append("- Q").append(question.number())
                            .append(question.blocking() ? " [blocking]: " : ": ")
                            .append(question.text()).append('\n');
                    if (question.resolution() != null && !question.resolution().isBlank()) {
                        priorQa.append("  resolved: ").append(question.resolution()).append('\n');
                    }
                }
                PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), parsedSpec, result, order,
                        priorQa.toString(), model, planner.model(), planner.maxTokens());
                int newVersion = old.planVersion() + 1;
                String planMd = PlanMdRenderer.render(parsedSpec, result, order, questions,
                        draft, newVersion);
                Path backup = SafeWrite.writeWithBackup(planPath, planMd);
                outWriter.println("plan revised (version " + newVersion + "): " + planPath);
                if (backup != null) {
                    outWriter.println("previous version backed up: " + backup);
                }
                outWriter.println("review and edit the plan, then run: sdd plan approve");
                return 0;
            }
        } catch (RuntimeException | java.io.IOException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }
}
