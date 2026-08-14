package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RunGit;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;
import sdd.cli.implement.Scheduler;
import sdd.cli.review.Decision;
import sdd.core.db.Database;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * Discards the branches for work that never got APPROVED (design line 21/94): {@code sdd implement}
 * never restores a repo's original branch, so every non-approved repo is normally left sitting right
 * on its run branch — this is what removes it, and the run dir alongside it, once a human is done
 * deciding. Deleting is the one genuinely irreversible operation in this phase, so it is gated
 * behind {@code --force}; without it, this only ever prints what it would do and changes nothing.
 * An APPROVED repo's branch is never a candidate, full stop — that work must survive. Nor is a
 * repo whose decision token could not be parsed: {@link RunStore#readDecisions} degrades an
 * unrecognized token to PENDING for {@code review} (a safe default — PENDING just means "ask
 * again"), but the identical default would be unsafe here, where PENDING means "eligible for
 * deletion" — so this reads the raw tokens itself and refuses to touch one it cannot parse.
 *
 * <p>Both hand-editable files are treated with the same suspicion. {@code state.json}'s branch
 * name is what a forced delete is pointed at, so a record naming anything outside this run's own
 * {@code sdd/<runId>/} namespace — {@code main}, most alarmingly — is refused rather than obeyed.
 */
@Command(name = "clean",
        description = "Delete unapproved run branches and their run dir",
        exitCodeOnInvalidInput = 4)
public final class CleanCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--force", description = "Actually delete; without this, only print what would happen")
    boolean force;

    @Parameters(index = "0", arity = "0..1",
            description = "A specific <spec>.plan.json; default: every run dir in the workspace")
    Path planJsonPath;

    @Spec CommandSpec spec;

    private record Candidate(String repo, String branch) {
    }

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            List<Path> runDirs;
            if (planJsonPath == null) {
                runDirs = RunDirs.all(workspace);
            } else {
                Path runDir = RunDirs.one(workspace, planJsonPath, "clean", err);
                if (runDir == null) {
                    return 4;   // RunDirs.one already explained why
                }
                runDirs = List.of(runDir);
            }
            if (runDirs.isEmpty()) {
                out.println("nothing to clean");
                return 0;
            }

            RunStore store = RunStore.system();
            Map<String, Path> repoPaths = repoPaths();
            List<String> failures = new ArrayList<>();
            boolean anythingListed = false;
            boolean anyLocked = false;
            for (Path runDir : runDirs) {
                // Deciding — and therefore this run's decisions.json and run branches — is still
                // live while sdd implement holds the lock; deleting anything out from under it
                // would be the same race DecisionCommand already refuses for the scripted path.
                if (store.isLockHeld(runDir)) {
                    err.println("error: run " + runDir.getFileName() + " is in progress (lock held) "
                            + "— wait for sdd implement to finish");
                    anyLocked = true;
                    continue;
                }
                anythingListed |= cleanOne(runDir, store, repoPaths, out, err, failures);
            }
            // Diagnostics for the runs that COULD be processed must reach the human even when
            // another run was locked — a locked sibling forcing exit 4 must not silently swallow a
            // genuine per-repo failure (or the "nothing else to report" line) from an unlocked one.
            if (!anythingListed) {
                out.println("nothing to clean");
            }
            if (!failures.isEmpty()) {
                err.println("failed:");
                for (String failure : failures) {
                    err.println("  " + failure);
                }
            }
            if (anyLocked) {
                return 4;
            }
            return failures.isEmpty() ? 0 : 2;
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    /**
     * One run's worth of cleaning. Returns whether this run was reported on at all — a fully
     * APPROVED run is untouched, its run dir left exactly where it is, but it still says so: it is
     * a run that existed and was decided, which is not the same thing as the workspace-level
     * "nothing to clean". Every repo's branch delete is individually try/caught into
     * {@code failures} (per-repo isolation): one repo's failure never strands the run's other
     * repos, and one run's failure never strands another run.
     */
    private boolean cleanOne(Path runDir, RunStore store, Map<String, Path> repoPaths, PrintWriter out,
                             PrintWriter err, List<String> failures) {
        String runId = runDir.getFileName().toString();
        PlanModel plan;
        RunState state;
        Map<String, String> tokens;
        try {
            plan = PlanJsonReader.read(Files.readString(runDir.resolve("plan.json")));
            state = store.readState(runDir);
            tokens = store.readDecisionTokens(runDir);
        } catch (RuntimeException | IOException e) {
            failures.add(runId + ": " + e.getMessage());
            return true;   // a run we could not even read is not "nothing to clean"
        }

        Map<String, RepoRun> byName = new HashMap<>();
        for (RepoRun repoRun : state.repos()) {
            byName.put(repoRun.repo(), repoRun);
        }

        List<String> nonApproved = new ArrayList<>();
        List<String> neverReviewed = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        List<String> corrupted = new ArrayList<>();
        List<String> foreign = new ArrayList<>();
        for (String repo : Scheduler.sequence(plan.order())) {
            String token = tokens.get(repo);
            Decision decision;
            if (token == null || token.isEmpty()) {
                decision = Decision.PENDING;   // never recorded at all — genuinely never reviewed
            } else {
                try {
                    decision = Decision.valueOf(token);
                } catch (IllegalArgumentException e) {
                    // Ruling: never delete a branch whose decision token we could not parse — it
                    // might have been APPROVED before a hand edit or a future-versioned write.
                    corrupted.add(repo + " (unrecognized decision \"" + token + "\")");
                    continue;
                }
            }
            if (decision == Decision.APPROVED) {
                continue;   // never a candidate — that work must survive
            }
            nonApproved.add(repo);
            if (decision == Decision.PENDING) {
                neverReviewed.add(repo);
            }
            RepoRun repoRun = byName.get(repo);
            if (repoRun != null && repoRun.branch() != null) {
                // Same rule as the unparseable decision token above, applied to the OTHER
                // hand-editable file: state.json's branch name is passed straight to a forced
                // delete, so a corrupted or hand-edited record naming "main" would destroy main.
                // Only a branch this very run minted is ever a candidate.
                if (repoRun.branch().startsWith(runBranchPrefix(runId))) {
                    candidates.add(new Candidate(repo, repoRun.branch()));
                } else {
                    foreign.add(repo + " (state.json names branch \"" + repoRun.branch() + "\", not a "
                            + runBranchPrefix(runId) + "… branch of this run)");
                }
            }
        }
        for (String c : corrupted) {
            failures.add(runId + "/" + c + " — refusing to delete a branch whose decision could not "
                    + "be parsed; leaving it and the run dir untouched");
        }
        for (String c : foreign) {
            failures.add(runId + "/" + c + " — refusing to delete a branch that is not this run's "
                    + "own; leaving it and the run dir untouched");
        }
        if (nonApproved.isEmpty()) {
            if (corrupted.isEmpty()) {
                // Not "nothing to clean": there WAS a run here, all of it approved. Its dir is the
                // only record of what was approved — report, diffs, contracts, runbook, events —
                // and ratified (g)'s "plus the run dir" is about discarding an abandoned run, not
                // shredding the audit trail of a successful one. So it stays, and says so.
                out.println(runId + " is fully approved — nothing to delete, run dir kept: " + runDir);
            }
            return true;   // all-APPROVED (plus maybe an unparseable one) — the corrupted entries
                            // were already reported above
        }

        if (!neverReviewed.isEmpty()) {
            out.println("warning: " + neverReviewed.size() + " never-reviewed (PENDING) repo(s) in "
                    + runId + " will be deleted: " + String.join(", ", neverReviewed));
        }

        if (!force) {
            out.println("would delete (pass --force to apply):");
            for (Candidate candidate : candidates) {
                out.println("  " + candidate.repo() + "  " + candidate.branch());
            }
            out.println("  run dir: " + runDir);
            return true;
        }

        // A repo whose decision we could not parse — or whose recorded branch is not this run's —
        // blocks the run dir the same way a failed branch delete does: the run is not fully
        // cleaned while something in it is still ambiguous or deliberately left alone.
        boolean allDeleted = corrupted.isEmpty() && foreign.isEmpty();
        for (Candidate candidate : candidates) {
            Path root = repoPaths.get(candidate.repo());
            try {
                if (root == null) {
                    throw new IllegalStateException("no repo path on record for " + candidate.repo());
                }
                String baseSha = plan.repo(candidate.repo()).map(PlanModel.PlanRepo::baseSha).orElse(null);
                boolean wasCheckedOut = baseSha != null
                        && candidate.branch().equals(RunGit.currentBranch(root));
                if (wasCheckedOut) {
                    RunGit.checkout(root, baseSha);
                }
                RunGit.deleteBranch(root, candidate.branch());
                out.println("deleted " + candidate.repo() + "  " + candidate.branch());
                if (wasCheckedOut) {
                    out.println("left " + candidate.repo() + " detached at " + shortSha(baseSha));
                }
            } catch (RuntimeException e) {
                allDeleted = false;
                failures.add(runId + "/" + candidate.repo() + ": " + e.getMessage());
            }
        }
        if (allDeleted) {
            try {
                deleteRecursively(runDir);
                out.println("deleted run dir: " + runDir);
            } catch (IOException e) {
                failures.add(runId + ": cannot delete run dir " + runDir + ": " + e.getMessage());
            }
        }
        return true;
    }

    private Map<String, Path> repoPaths() {
        Map<String, Path> paths = new HashMap<>();
        if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
            return paths;
        }
        try (Database db = Database.open(workspace)) {
            Jdbi jdbi = db.jdbi();
            jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                    .forEach(row -> paths.put(String.valueOf(row.get("name")),
                            Path.of(String.valueOf(row.get("path"))))));
        }
        return paths;
    }

    /** {@code state.json} is deleted LAST (and the dir itself only after that) so a crash mid-delete
     *  leaves a stub that {@link #allRunDirs} still finds on a later scan — deleting it first (or in
     *  whatever order {@code Files.walk} happens to yield) would leave an orphaned, invisible-forever
     *  directory the moment a delete failed partway through. */
    private static void deleteRecursively(Path dir) throws IOException {
        Path stateJson = dir.resolve("state.json");
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> !p.equals(dir) && !p.equals(stateJson))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Files.deleteIfExists(stateJson);
        Files.delete(dir);
    }

    /** Every branch {@code sdd implement} mints is {@code Orchestrator}'s
     *  {@code "sdd/" + runId + "/" + slug(repo)}. Matching the run id as well as the {@code sdd/}
     *  namespace means a state.json borrowed or edited from a DIFFERENT run cannot get this run's
     *  {@code --force} to delete a branch that run is still using. */
    private static String runBranchPrefix(String runId) {
        return "sdd/" + runId + "/";
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }
}
