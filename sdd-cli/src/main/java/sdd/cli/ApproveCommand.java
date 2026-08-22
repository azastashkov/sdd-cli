package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.db.Database;
import sdd.plan.approve.EstateYaml;
import sdd.plan.openspec.ChangeId;
import sdd.plan.openspec.EstateRead;
import sdd.plan.approve.GradleSmokeRunner;
import sdd.plan.approve.Hashes;
import sdd.plan.approve.LiveGit;
import sdd.plan.approve.ManifestHash;
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

    @Parameters(index = "0", description = "The reviewed <spec>.plan.md, or the change directory "
            + "openspec/changes/<change-id> (or just <change-id>)")
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
            // Three ways to name the same change: the plan.md that has always worked, the change
            // directory the workspace now carries, and a bare change id. The directory is the
            // point of the OpenSpec layout — everything the gate needs is inside it, so a reader
            // should be able to hand the gate the thing they were just reading.
            Path changeDir = resolveChangeDir(planPath);
            String name = planPath.getFileName().toString();
            if (changeDir == null && !name.endsWith(".plan.md")) {
                errWriter.println("error: approve expects a .plan.md file, a change directory, or "
                        + "a change id — no estate.yaml under " + planPath);
                return 1;
            }
            String planText;
            PlanDocument plan;
            if (changeDir != null) {
                String estateText = Files.readString(changeDir.resolve("estate.yaml"));
                planText = estateText;
                plan = fromTree(changeDir, estateText, outWriter);
            } else {
                planText = Files.readString(planPath);
                plan = withTreeResolutions(PlanMdParser.parse(planText), outWriter);
            }
            // From the tree's own snapshot when we came from a directory: there is no sibling
            // .md there, and estate.yaml carries the spec precisely because the rendered markdown
            // cannot express all of it.
            Path specPath = changeDir != null ? changeDir.resolve("estate.yaml")
                    : planPath.resolveSibling(
                            name.substring(0, name.length() - ".plan.md".length()) + ".md");
            String specText = Files.readString(specPath);
            NormalizedSpec parsedSpec = changeDir != null
                    ? EstateYaml.toSpec(specText) : SpecParser.parse(specText);
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
                // No SddConfig is loaded on this path, so a wrapper-less repo falls back to
                // $SDD_GRADLE then PATH rather than to a configured gradle_home.
                List<String> compileWarnings = new ArrayList<>();
                String specSha = Hashes.sha256(specText);
                String planSha = Hashes.sha256(planText);
                String json = PlanJson.compile(jdbi, plan, specSha, planSha, smoke, compileWarnings);
                // Still a flat <spec>.plan.json at the workspace root even when approving from a
                // tree: sdd implement, review, status and clean all take that path, and moving it
                // would be a second, unrelated migration bundled into this one.
                Path jsonPath = changeDir != null
                        ? workspace.resolve(sanitizeId(plan.specId()) + ".plan.json")
                        : planPath.resolveSibling(
                                name.substring(0, name.length() - ".md".length()) + ".json");
                Files.writeString(jsonPath, json);
                // Written from plan.json's own bytes, so the two cannot disagree about which repos
                // are in the change. Step one of moving the workspace to an OpenSpec layout: the
                // estate graph has to live in a file OpenSpec ignores, because the format has
                // nowhere to put it — and that has to be proven to carry everything the gates need
                // before any human-facing artifact moves.
                // Into the change directory when sdd plan wrote one — everything about a change
                // in one place, and it is where proposal.md tells a reader to look. Beside
                // plan.json otherwise: every plan approved before the OpenSpec view existed, and
                // every workspace that has not re-planned since.
                Path estateDir = changeDir != null ? changeDir
                        : workspace.resolve("openspec/changes/"
                                + ChangeId.of(plan.specId(), plan.planVersion()));
                Path estatePath;
                if (Files.isDirectory(estateDir)) {
                    estatePath = estateDir.resolve("estate.yaml");
                    // Merged into what the plan wrote rather than re-rendered, so the spec snapshot
                    // and everything else already in it survive verbatim. The manifest hash pins
                    // the whole directory, because the gate artifact is no longer one file — and
                    // estate.yaml itself is excluded, since the hash is written into it.
                    String artifacts = Files.exists(estatePath)
                            ? ManifestHash.of(estateDir, "estate.yaml") : "";
                    Files.writeString(estatePath, Files.exists(estatePath)
                            ? EstateYaml.approved(Files.readString(estatePath), json, artifacts)
                            : EstateYaml.render(json, plan));
                } else {
                    estatePath = jsonPath.resolveSibling(
                            name.substring(0, name.length() - ".plan.md".length()) + ".estate.yaml");
                    Files.writeString(estatePath, EstateYaml.render(json, plan));
                }
                for (String warning : compileWarnings) {
                    outWriter.println("warn: " + warning);
                }
                outWriter.println("plan approved: " + jsonPath);
                outWriter.println("estate: " + estatePath);
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

    /**
     * Question resolutions written in the workspace's OpenSpec change, folded into the plan.
     *
     * <p>Step three of moving the workspace to that layout, and the smallest piece of it that is
     * genuinely safe. A rendered change is not parseable back into a plan — {@code ## Why} merges
     * the spec's goal and background irreversibly, and attachments and sources have no home in the
     * format at all — so nothing here tries. A resolution is one line under a numbered question, an
     * exact grammar, and the only edit Gate 1 actually requires.
     *
     * <p>The tree WINS where both carry an answer. A human who opened design.md to answer a
     * blocking question expects that answer to count, and silently preferring the older file would
     * make the tree look editable while ignoring the edit.
     */
    private PlanDocument withTreeResolutions(PlanDocument plan, PrintWriter outWriter) {
        Path design = workspace.resolve("openspec/changes/"
                + ChangeId.of(plan.specId(), plan.planVersion()) + "/design.md");
        if (!Files.isRegularFile(design)) {
            return plan;
        }
        Map<Integer, String> answers;
        try {
            answers = EstateRead.resolutions(Files.readString(design));
        } catch (java.io.IOException e) {
            outWriter.println("warn: could not read " + design + ": " + e.getMessage());
            return plan;
        }
        if (answers.isEmpty()) {
            return plan;
        }
        List<PlanDocument.PlanQuestion> merged = new ArrayList<>();
        int taken = 0;
        for (PlanDocument.PlanQuestion question : plan.questions()) {
            String fromTree = answers.get(question.number());
            if (fromTree == null || fromTree.isBlank()) {
                merged.add(question);
                continue;
            }
            taken++;
            merged.add(new PlanDocument.PlanQuestion(question.number(), question.blocking(),
                    question.text(), fromTree));
        }
        outWriter.println("openspec: " + taken + " question resolution(s) read from "
                + design.getFileName());
        return new PlanDocument(plan.specId(), plan.planVersion(), plan.summary(), merged,
                plan.affected(), plan.excluded(), plan.order(), plan.contracts(), plan.steps(),
                plan.notes());
    }


    /**
     * The change directory named by the argument, or null when it names a plan.md.
     *
     * <p>Accepts the directory itself and a bare change id, and insists on an {@code estate.yaml}
     * inside it rather than merely on the directory existing: a change rendered before that file
     * was written cannot be approved from the tree, and saying so by name beats failing later with
     * a missing-key error from a YAML reader.
     */
    private Path resolveChangeDir(Path argument) {
        Path direct = Files.isRegularFile(argument.resolve("estate.yaml")) ? argument : null;
        if (direct != null) {
            return direct;
        }
        if (argument.getNameCount() != 1 || argument.toString().contains(".")) {
            return null;
        }
        Path byId = workspace.resolve("openspec/changes").resolve(argument.toString());
        return Files.isRegularFile(byId.resolve("estate.yaml")) ? byId : null;
    }

    /**
     * The plan a change directory describes, with the human's answers folded in.
     *
     * <p>Nothing is recovered from prose. The structure comes from estate.yaml, which sdd wrote;
     * the resolutions come from design.md, which is the one part of the rendered markdown whose
     * grammar is exact. That split is the whole reason this can be done safely at all — the
     * rendering merges the spec's goal and background irreversibly and has nowhere to put
     * attachments or sources, so a general parser would silently lose them.
     */
    private PlanDocument fromTree(Path changeDir, String estateText, PrintWriter outWriter)
            throws java.io.IOException {
        PlanDocument plan = EstateYaml.toPlanDocument(estateText);
        Path design = changeDir.resolve("design.md");
        if (!Files.isRegularFile(design)) {
            return plan;
        }
        Map<Integer, String> answers = EstateRead.resolutions(Files.readString(design));
        if (answers.isEmpty()) {
            return plan;
        }
        List<PlanDocument.PlanQuestion> merged = new ArrayList<>();
        for (PlanDocument.PlanQuestion question : plan.questions()) {
            String fromTree = answers.get(question.number());
            merged.add(fromTree == null || fromTree.isBlank() ? question
                    : new PlanDocument.PlanQuestion(question.number(), question.blocking(),
                            question.text(), fromTree));
        }
        outWriter.println("openspec: " + answers.size() + " question resolution(s) read from "
                + design.getFileName());
        return new PlanDocument(plan.specId(), plan.planVersion(), plan.summary(), merged,
                plan.affected(), plan.excluded(), plan.order(), plan.contracts(), plan.steps(),
                plan.notes());
    }


    /** The spec id as a filename, matching the run id sdd implement derives from the same value. */
    private static String sanitizeId(String specId) {
        return specId.replaceAll("[^A-Za-z0-9._-]", "-");
    }

}
