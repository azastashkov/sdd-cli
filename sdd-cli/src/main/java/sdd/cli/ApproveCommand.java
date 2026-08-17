package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.db.Database;
import sdd.plan.approve.GradleSmokeRunner;
import sdd.plan.approve.Hashes;
import sdd.plan.approve.LiveGit;
import sdd.plan.approve.PlanDocument;
import sdd.plan.approve.PlanJson;
import sdd.plan.approve.PlanMdParser;
import sdd.plan.approve.PlanValidator;
import sdd.plan.approve.SmokeRunner;
import sdd.plan.source.SourceBullet;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecValidator;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "approve", description = "Validate the reviewed plan.md and compile plan.json (Gate 1)")
public final class ApproveCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", description = "The reviewed <spec>.plan.md")
    Path planPath;

    @Option(names = "--no-comment", description = "Suppress the Jira write-back comment even when "
            + "atlassian.write_back: comment is configured")
    boolean noComment;

    @Spec CommandSpec spec;

    SmokeRunner smokeForTest;   // test seam — mirrors plannerForTest

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        try {
            String name = planPath.getFileName().toString();
            if (!name.endsWith(".plan.md")) {
                errWriter.println("error: approve expects a .plan.md file");
                return 1;
            }
            String planText = Files.readString(planPath);
            PlanDocument plan = PlanMdParser.parse(planText);
            Path specPath = planPath.resolveSibling(
                    name.substring(0, name.length() - ".plan.md".length()) + ".md");
            String specText = Files.readString(specPath);
            NormalizedSpec parsedSpec = SpecParser.parse(specText);
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
                Jdbi jdbi = db.jdbi();
                Map<String, LiveGit.State> liveStates = new HashMap<>();
                List<String> gitProblems = new ArrayList<>();
                Map<String, String> paths = new HashMap<>();
                jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                        .forEach(row -> paths.put(String.valueOf(row.get("name")),
                                String.valueOf(row.get("path")))));
                for (PlanDocument.PlanRepo repo : plan.affected()) {
                    String path = paths.get(repo.repo());
                    if (path == null) {
                        gitProblems.add("repo " + repo.repo() + " is not in the knowledge base");
                        continue;
                    }
                    try {
                        liveStates.put(repo.repo(), LiveGit.state(Path.of(path)));
                    } catch (IllegalStateException e) {
                        gitProblems.add(e.getMessage());
                    }
                }
                PlanValidator.Verdict verdict = PlanValidator.validate(jdbi, plan, parsedSpec, liveStates);
                for (String warning : verdict.warnings()) {
                    outWriter.println("warn: " + warning);
                }
                List<String> problems = new ArrayList<>(gitProblems);
                problems.addAll(verdict.problems());
                if (!problems.isEmpty()) {
                    for (String problem : problems) {
                        errWriter.println("problem: " + problem);
                    }
                    return 1;
                }
                SmokeRunner smoke = smokeForTest != null ? smokeForTest : new GradleSmokeRunner();
                List<String> compileWarnings = new ArrayList<>();
                String specSha = Hashes.sha256(specText);
                String planSha = Hashes.sha256(planText);
                String json = PlanJson.compile(jdbi, plan, specSha, planSha, smoke, compileWarnings);
                Path jsonPath = planPath.resolveSibling(
                        name.substring(0, name.length() - ".md".length()) + ".json");
                Files.writeString(jsonPath, json);
                for (String warning : compileWarnings) {
                    outWriter.println("warn: " + warning);
                }
                outWriter.println("plan approved: " + jsonPath);
                outWriter.println("spec sha256: " + specSha);
                outWriter.println("plan sha256: " + planSha);
                // Task 4 (Gate 1 write-back): strictly AFTER plan.json is durably written above,
                // and via JiraWriteBack — which never throws — so a Jira outage can never turn
                // this successful approval into a failed one. jsonPath's existence is exactly the
                // artifact the brief says must never be lost to a Jira problem.
                commentOnJiraSources(parsedSpec, plan, outWriter, errWriter);
                return 0;
            }
        } catch (RuntimeException | java.io.IOException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }

    /** Task 4 brief section 3's Gate-1 wording, verbatim: {@code sdd: plan approved for
     *  `<spec-id>` — `<N>` repos affected, execution order: `<repo>, <repo>, …`}. A no-op (no
     *  config load, no output) when the spec has no Jira sources — see
     *  {@code JiraWriteBack.post}'s short-circuit — which is the normal case for a hand-written or
     *  free-text-derived spec, not an error. */
    private void commentOnJiraSources(NormalizedSpec parsedSpec, PlanDocument plan,
            PrintWriter outWriter, PrintWriter errWriter) {
        List<String> jiraKeys = SourceBullet.jiraIssueKeys(parsedSpec.sources());
        if (jiraKeys.isEmpty()) {
            return;
        }
        List<String> order = new ArrayList<>();
        for (List<String> unit : plan.order()) {
            order.addAll(unit);
        }
        String body = "sdd: plan approved for `" + parsedSpec.id() + "` — `" + plan.affected().size()
                + "` repos affected, execution order: `" + String.join(", ", order) + "`";
        JiraWriteBack.post(workspace, jiraKeys, noComment, body, outWriter, errWriter);
    }
}
