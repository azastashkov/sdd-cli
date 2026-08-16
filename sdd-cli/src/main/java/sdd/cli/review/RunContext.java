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
import sdd.core.diagnostics.Diagnostics;
import sdd.core.diagnostics.DiagnosticWriter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
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
                         SddConfig config, Map<String, Path> paths, DiagnosticWriter diagnostics) {

    /** Task 8: every pre-Task-8 caller (every existing test that builds a {@code RunContext}
     *  directly rather than through {@link #load}) keeps compiling unchanged, with diagnostics
     *  simply off — see {@link DiagnosticWriter}'s own "a null writer is a no-op" contract, which
     *  every {@code Bitbucket*}/{@code DecisionCommand} call site handling {@link #diagnostics}
     *  relies on. */
    public RunContext(String runId, Path runDir, RunStore store, PlanModel plan, RunState state,
                      SddConfig config, Map<String, Path> paths) {
        this(runId, runDir, store, plan, state, config, paths, null);
    }

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
        // Task 8: one diagnostics file for the whole invocation — "review", covering the plain
        // review AND its approve/reject/redo decision subcommands alike, since all four load a run
        // through this same method and B2 asks for one file per COMMAND invocation, not a finer
        // split by which of the four this particular process happens to be.
        DiagnosticWriter diagnostics = Diagnostics.open(workspace, "review", List.of("review"),
                config.atlassian(), InstantSource.system(), err);
        return new RunContext(runId, runDir, store, plan, state, config, paths, diagnostics);
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
     *  other process reading this run also sees. (That is not a concurrency guarantee: the decision
     *  commands write {@code decisions.json} through an optimistic retry, but their {@code
     *  state.json} write-back is an unguarded read-modify-write of a snapshot taken at command
     *  start — see {@link DecisionCommand}'s class javadoc for exactly what is and is not safe to
     *  run concurrently.) */
    public Path writeReport(Diffs diffs, Map<String, EstateRebuild.Result> rebuilds,
                            List<String> notLocallyVerified, List<String> stagingFailures,
                            List<String> restoreFailures, List<ContractRecheck.Finding> contracts,
                            RebuildScope rebuild) {
        return writeReport(reportInputs(diffs, rebuilds, notLocallyVerified, stagingFailures,
                restoreFailures, contracts, rebuild));
    }

    /** Like {@link #writeReport(Diffs, Map, List, List, List, List, RebuildScope)}, but for a
     *  caller that already built the {@link ReportInputs} via {@link #reportInputs} and needs to
     *  reuse the SAME object for something else — Task 5's Bitbucket pull-request description, via
     *  {@link ReviewReport#renderRepo}, which must render off the identical inputs {@code report.md}
     *  used or the two artifacts could disagree about what a repo's run produced. */
    public Path writeReport(ReportInputs in) {
        String report = ReviewReport.render(in);
        store.writeReview(runDir, "report.md", report);
        return store.reviewDir(runDir).resolve("report.md");
    }

    /** Builds the {@link ReportInputs} {@link #writeReport} renders — split out so a caller (Task
     *  5's Bitbucket integration) can build it once, hand it to {@link #writeReport(ReportInputs)}
     *  to produce {@code report.md}, and reuse the exact same object for the pull-request
     *  description afterward, rather than each independently re-deriving decisions/runbook/
     *  checkpoints and risking the two drifting apart. */
    public ReportInputs reportInputs(Diffs diffs, Map<String, EstateRebuild.Result> rebuilds,
                                     List<String> notLocallyVerified, List<String> stagingFailures,
                                     List<String> restoreFailures, List<ContractRecheck.Finding> contracts,
                                     RebuildScope rebuild) {
        Map<String, DecisionRecord> decisions = store.readDecisions(runDir);
        String runbook = ReleaseRunbook.render(plan, state);
        return new ReportInputs(runId, plan, state, diffs.stats(), rebuilds, notLocallyVerified,
                stagingFailures, restoreFailures, diffs.failures(), contracts, skippedGates(),
                decisions, checkpoints(decisions), runbook, rebuild);
    }

    /** Declared compatibility guarantees whose gate reached no verdict. Read here rather than
     *  passed in, because it comes from the run's own recorded artifacts and every caller of
     *  {@link #writeReport} would otherwise have to derive the same thing identically. */
    public List<SkippedGates.Skipped> skippedGates() {
        return SkippedGates.of(plan, state, store, runDir);
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
