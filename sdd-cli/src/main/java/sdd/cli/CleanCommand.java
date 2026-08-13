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
import sdd.cli.review.DecisionRecord;
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
 * An APPROVED repo's branch is never a candidate, full stop — that work must survive.
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
                runDirs = allRunDirs();
            } else {
                runDirs = oneRunDir(err);
                if (runDirs == null) {
                    return 4;   // oneRunDir already explained why
                }
            }
            if (runDirs.isEmpty()) {
                out.println("nothing to clean");
                return 0;
            }

            RunStore store = RunStore.system();
            Map<String, Path> repoPaths = repoPaths();
            List<String> failures = new ArrayList<>();
            boolean anythingListed = false;
            for (Path runDir : runDirs) {
                anythingListed |= cleanOne(runDir, store, repoPaths, out, failures);
            }
            if (!anythingListed) {
                out.println("nothing to clean");
            }
            if (!failures.isEmpty()) {
                err.println("failed:");
                for (String failure : failures) {
                    err.println("  " + failure);
                }
                return 2;
            }
            return 0;
        } catch (RuntimeException | IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    /**
     * One run's worth of cleaning. Returns whether it had anything to clean at all — a fully
     * APPROVED run is silent and untouched, its run dir left exactly where it is. Every repo's
     * branch delete is individually try/caught into {@code failures} (per-repo isolation): one
     * repo's failure never strands the run's other repos, and one run's failure never strands
     * another run.
     */
    private boolean cleanOne(Path runDir, RunStore store, Map<String, Path> repoPaths, PrintWriter out,
                             List<String> failures) {
        String runId = runDir.getFileName().toString();
        PlanModel plan;
        RunState state;
        Map<String, DecisionRecord> decisions;
        try {
            plan = PlanJsonReader.read(Files.readString(runDir.resolve("plan.json")));
            state = store.readState(runDir);
            decisions = store.readDecisions(runDir);
        } catch (RuntimeException | IOException e) {
            failures.add(runId + ": " + e.getMessage());
            return true;   // a run we could not even read is not "nothing to clean"
        }

        Map<String, RepoRun> byName = new HashMap<>();
        for (RepoRun repoRun : state.repos()) {
            byName.put(repoRun.repo(), repoRun);
        }

        List<String> nonApproved = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (String repo : Scheduler.sequence(plan.order())) {
            DecisionRecord record = decisions.get(repo);
            Decision decision = record == null ? Decision.PENDING : record.decision();
            if (decision == Decision.APPROVED) {
                continue;   // never a candidate — that work must survive
            }
            nonApproved.add(repo);
            RepoRun repoRun = byName.get(repo);
            if (repoRun != null && repoRun.branch() != null) {
                candidates.add(new Candidate(repo, repoRun.branch()));
            }
        }
        if (nonApproved.isEmpty()) {
            return false;   // every repo here is APPROVED — nothing to clean
        }

        if (!force) {
            out.println("would delete (pass --force to apply):");
            for (Candidate candidate : candidates) {
                out.println("  " + candidate.repo() + "  " + candidate.branch());
            }
            out.println("  run dir: " + runDir);
            return true;
        }

        boolean allDeleted = true;
        for (Candidate candidate : candidates) {
            Path root = repoPaths.get(candidate.repo());
            try {
                if (root == null) {
                    throw new IllegalStateException("no repo path on record for " + candidate.repo());
                }
                String baseSha = plan.repo(candidate.repo()).map(PlanModel.PlanRepo::baseSha).orElse(null);
                if (baseSha != null && candidate.branch().equals(RunGit.currentBranch(root))) {
                    RunGit.checkout(root, baseSha);
                }
                RunGit.deleteBranch(root, candidate.branch());
                out.println("deleted " + candidate.repo() + "  " + candidate.branch());
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

    private List<Path> allRunDirs() throws IOException {
        Path runsDir = workspace.resolve(".sdd/runs");
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(runsDir)) {
            return children.filter(p -> Files.exists(p.resolve("state.json"))).sorted().toList();
        }
    }

    /** Null means "the caller should exit 4" (already explained to err) — an explicitly named plan
     *  with no run dir is a hard error, unlike scanning the whole workspace and finding nothing. */
    private List<Path> oneRunDir(PrintWriter err) throws IOException {
        String name = planJsonPath.getFileName().toString();
        if (!name.endsWith(".plan.json")) {
            err.println("error: clean expects a .plan.json file");
            return null;
        }
        PlanModel plan = PlanJsonReader.read(Files.readString(planJsonPath));
        String runId = sanitize(plan.specId()) + "-v" + plan.planVersion();
        Path runDir = workspace.resolve(".sdd/runs/" + runId);
        if (!Files.exists(runDir.resolve("state.json"))) {
            err.println("error: no run to clean at " + runDir);
            return null;
        }
        return List.of(runDir);
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

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String sanitize(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
