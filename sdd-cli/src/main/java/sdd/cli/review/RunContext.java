package sdd.cli.review;

import org.jdbi.v3.core.Jdbi;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.implement.Scheduler;
import sdd.core.config.ConfigLoader;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything a Gate-2 command needs off disk — the frozen plan, the run's state, the estate's repo
 * paths, the config — plus the two artifact-producing steps every review path shares. It lives here
 * rather than on {@code ReviewCommand} so that reading a run is not a public utility surface
 * hanging off a picocli command class: {@code sdd review}, its three decision subcommands and the
 * commands after them all load a run the same way, and a divergence between them would mean two
 * humans looking at the same run through different eyes.
 */
public record RunContext(String runId, Path runDir, RunStore store, PlanModel plan, RunState state,
                         SddConfig config, Map<String, Path> paths) {

    /** Per-repo diffs written to the review dir, plus the repos whose diff could not be produced. */
    public record Diffs(Map<String, RunGit.DiffStat> stats, List<String> failures) {
    }

    /**
     * Loads the run named by {@code planJsonPath}, or returns null having already printed the
     * reason to {@code err} — every caller turns that into exit 4. The knowledge-base handle is
     * opened and closed here: nothing downstream of this needs it, and holding it open across an
     * estate rebuild would pin the db for minutes.
     */
    public static RunContext load(Path workspace, Path planJsonPath, PrintWriter err) throws IOException {
        String name = planJsonPath.getFileName().toString();
        if (!name.endsWith(".plan.json")) {
            err.println("error: review expects a .plan.json file");
            return null;
        }
        PlanModel cliPlan = PlanJsonReader.read(Files.readString(planJsonPath));
        String runId = sanitize(cliPlan.specId()) + "-v" + cliPlan.planVersion();
        Path runDir = workspace.resolve(".sdd/runs/" + runId);
        if (!Files.exists(runDir.resolve("state.json"))) {
            err.println("error: no run to review at " + runDir);
            return null;
        }
        if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
            err.println("error: knowledge base is empty — run sdd index first");
            return null;
        }

        RunStore store = RunStore.system();
        // The frozen copy, not the caller's file: the plan on disk may have been re-approved since.
        PlanModel plan = PlanJsonReader.read(Files.readString(runDir.resolve("plan.json")));
        PlanJsonReader.validate(plan);
        RunState state = store.readState(runDir);
        SddConfig config = ConfigLoader.load(workspace);

        Map<String, Path> paths = new HashMap<>();
        try (Database db = Database.open(workspace)) {
            Jdbi jdbi = db.jdbi();
            jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                    .forEach(row -> paths.put(String.valueOf(row.get("name")),
                            Path.of(String.valueOf(row.get("path"))))));
        }
        return new RunContext(runId, runDir, store, plan, state, config, paths);
    }

    /** Writes {@code <repo>.diff} for every SUCCEEDED repo with a resolvable checkpoint. */
    public Diffs collectDiffs() {
        Map<String, RepoRun> byName = byName();
        Map<String, RunGit.DiffStat> diffStats = new LinkedHashMap<>();
        List<String> diffFailures = new ArrayList<>();
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoRun repoRun = byName.get(repo);
            Path root = paths.get(repo);
            if (repoRun == null || repoRun.state() != RepoState.SUCCEEDED
                    || repoRun.checkpointSha() == null || root == null) {
                continue;
            }
            String baseSha = plan.repo(repo).orElseThrow().baseSha();
            // An unresolvable checkpoint sha (pruned run branch, gc'd object, stale KB repo
            // path) must not abort the whole review — it's a per-repo reporting gap, not a
            // verification failure. Record it and keep going so the report still gets out.
            try {
                store.writeReview(runDir, repo + ".diff",
                        RunGit.diff(root, baseSha, repoRun.checkpointSha()));
                diffStats.put(repo, RunGit.diffStat(root, baseSha, repoRun.checkpointSha()));
            } catch (RuntimeException e) {
                diffFailures.add(repo + ": " + e.getMessage());
            }
        }
        return new Diffs(diffStats, diffFailures);
    }

    /** Renders and writes {@code report.md}, returning its path. Decisions re-run this so the
     *  artifact a human hands to a colleague reflects the run as it stands now, not a pre-decision
     *  snapshot — which is also why the decisions it renders are re-read from disk here rather than
     *  threaded in: every caller has just persisted them, and disk is the one shared truth any
     *  other process reading this run also sees. (That is not a concurrency guarantee — the
     *  decision commands assume a single writer at a time; see {@link DecisionCommand}.) */
    public Path writeReport(Diffs diffs, Map<String, EstateRebuild.Result> rebuilds,
                            List<String> notLocallyVerified, List<String> stagingFailures,
                            List<String> restoreFailures, List<ContractRecheck.Finding> contracts,
                            RebuildScope rebuild) {
        Map<String, DecisionRecord> decisions = store.readDecisions(runDir);
        String runbook = ReleaseRunbook.render(plan, state);
        String report = ReviewReport.render(new ReportInputs(runId, plan, state, diffs.stats(),
                rebuilds, notLocallyVerified, stagingFailures, restoreFailures, diffs.failures(),
                contracts, decisions, checkpoints(decisions), runbook, rebuild));
        store.writeReview(runDir, "report.md", report);
        return store.reviewDir(runDir).resolve("report.md");
    }

    /**
     * Where each SUCCEEDED repo's run branch actually is, as of report time.
     *
     * @param drift        rendered lines for branches that have moved off the recorded checkpoint;
     *                     their presence fails the review
     * @param branchGone   repos whose run branch does not exist at all any more — NOT drift (see
     *                     {@link #checkpoints}), but the runbook below tells a human to merge that
     *                     branch, so the report may not stay silent about it either
     */
    public record Checkpoints(List<String> drift, Set<String> branchGone) {
        public static Checkpoints none() {
            return new Checkpoints(List.of(), Set.of());
        }
    }

    /**
     * Reads every SUCCEEDED repo's run branch and compares it with the checkpoint recorded in
     * {@code state.json} — the diffs, diffstats and runbook all describe that checkpoint, so a
     * moved branch means the report describes a tree the branch no longer carries. Read here rather
     * than in {@link ReviewReport} because it is the one report input that must come off the
     * estate's git; the renderer stays a pure function of what it is handed.
     *
     * <p>Two exclusions from {@code drift}, both load-bearing:
     * <ul>
     *   <li><b>APPROVED repos.</b> {@code sdd review approve} deliberately squashes the run branch
     *       and rewrites the checkpoint. Flagging that would mean the tool accusing its own approve
     *       of tampering, and no run could exit 0 again after a single approval.</li>
     *   <li><b>A branch that no longer resolves</b> ({@code branchHead} is {@code ""}). An
     *       unresolvable sha is already reported as a diff failure, and "the branch moved" is not
     *       something the code knows — only that there is nothing left to compare. It is reported
     *       as {@code branchGone} instead, which does not fail the review.</li>
     * </ul>
     * A repo whose git cannot be read at all is skipped entirely: nothing was observed, so nothing
     * is claimed.
     */
    public Checkpoints checkpoints(Map<String, DecisionRecord> decisions) {
        Map<String, RepoRun> byName = byName();
        List<String> drift = new ArrayList<>();
        Set<String> branchGone = new LinkedHashSet<>();
        for (String repo : Scheduler.sequence(plan.order())) {
            RepoRun repoRun = byName.get(repo);
            Path root = paths.get(repo);
            if (repoRun == null || repoRun.state() != RepoState.SUCCEEDED || root == null
                    || repoRun.branch() == null || repoRun.checkpointSha() == null) {
                continue;
            }
            String head;
            try {
                head = RunGit.branchHead(root, repoRun.branch());
            } catch (RuntimeException e) {
                continue;
            }
            if (head.isEmpty()) {
                branchGone.add(repo);
                continue;
            }
            DecisionRecord record = decisions.get(repo);
            if (head.equals(repoRun.checkpointSha())
                    || (record != null && record.decision() == Decision.APPROVED)) {
                continue;
            }
            drift.add(repo + ": branch " + repoRun.branch() + " is at " + Shas.shortSha(head)
                    + ", checkpoint was " + Shas.shortSha(repoRun.checkpointSha())
                    + " — diffs and runbook describe the checkpoint");
        }
        return new Checkpoints(drift, branchGone);
    }

    public Map<String, RepoRun> byName() {
        Map<String, RepoRun> byName = new LinkedHashMap<>();
        for (RepoRun repoRun : state.repos()) {
            byName.put(repoRun.repo(), repoRun);
        }
        return byName;
    }

    private static String sanitize(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
