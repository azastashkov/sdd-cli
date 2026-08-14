package sdd.cli;

import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Run-dir resolution shared by every read-mostly command over {@code <workspace>/.sdd/runs/*} — today
 * {@code clean} and {@code status}, both of which take an optional {@code <plan.json>} and otherwise
 * operate over every run dir in the workspace. Lives here rather than duplicated per command: a run
 * dir is "has a {@code state.json}", and a named plan resolves to a run dir the same way everywhere
 * {@code specId}/{@code planVersion} name a run — two independently-maintained copies of that logic
 * is exactly the kind of drift this project's review rubric flags.
 */
final class RunDirs {
    private RunDirs() {
    }

    /** Every run dir under {@code <workspace>/.sdd/runs} that has a {@code state.json}, sorted by
     *  directory name — the order every existing caller (clean) already relies on. A command that
     *  wants a different order (status wants newest-first) re-sorts what this returns; this stays
     *  the one place that decides what counts as "a run dir" in the first place. */
    static List<Path> all(Path workspace) throws IOException {
        Path runsDir = workspace.resolve(".sdd/runs");
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(runsDir)) {
            return children.filter(p -> Files.exists(p.resolve("state.json"))).sorted().toList();
        }
    }

    /** Resolves one explicitly named {@code <spec>.plan.json} to its run dir, or returns null having
     *  already printed the reason — worded with the caller's own verb (e.g. "clean", "status") — to
     *  {@code err}. Every caller turns a null into exit 4: an explicitly named plan with no run dir is
     *  a hard error, unlike scanning the whole workspace and finding nothing. */
    static Path one(Path workspace, Path planJsonPath, String verb, PrintWriter err) throws IOException {
        String name = planJsonPath.getFileName().toString();
        if (!name.endsWith(".plan.json")) {
            err.println("error: " + verb + " expects a .plan.json file");
            return null;
        }
        PlanModel plan = PlanJsonReader.read(Files.readString(planJsonPath));
        String runId = sanitize(plan.specId()) + "-v" + plan.planVersion();
        Path runDir = workspace.resolve(".sdd/runs/" + runId);
        if (!Files.exists(runDir.resolve("state.json"))) {
            err.println("error: no run to " + verb + " at " + runDir);
            return null;
        }
        return runDir;
    }

    private static String sanitize(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
