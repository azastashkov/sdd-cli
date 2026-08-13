package sdd.cli.review;

import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.Scheduler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The human terminal half of Gate 2 (design line 67): walk every repo in {@link Scheduler#sequence}
 * order whose decision is still PENDING, print its report line, and prompt approve/reject/redo/view
 * diff/skip/quit — dispatching to the SAME {@link Decisions} methods {@link DecisionCommand}'s
 * subcommands use (and, for approve, the same {@link DecisionCommand#squashAndRecord squash
 * follow-up}), so a human walking this loop and a script calling {@code sdd review approve} leave
 * the estate in identical shape. Persists after EVERY decision, not batched at the end — a crash or
 * an early {@code q} must lose nothing already decided — and re-renders {@code report.md} once the
 * walk ends, however it ended. Driven entirely by the injected reader/writer; never touches
 * {@code System.in} itself, which is what makes it testable without a terminal.
 */
public final class InteractiveReview {
    static final String PROMPT = "[a]pprove / [r]eject / [d]edo / [v]iew diff / [s]kip / [q]uit: ";

    private InteractiveReview() {
    }

    public static void run(BufferedReader in, PrintWriter out, PrintWriter err, RunContext run)
            throws IOException {
        Decisions decisions = new Decisions(run.store().readDecisions(run.runDir()));
        walk:
        for (String repo : Scheduler.sequence(run.plan().order())) {
            if (decisions.of(repo) != Decision.PENDING) {
                continue;
            }
            while (true) {
                out.println(reportLine(run, repo));
                out.print(PROMPT);
                out.flush();
                String line = in.readLine();
                if (line == null) {
                    break walk;   // stdin closed — treat exactly like 'q'
                }
                switch (line.strip().toLowerCase(Locale.ROOT)) {
                    case "a" -> {
                        if (approve(out, err, run, decisions, repo)) {
                            continue walk;
                        }
                        // refused (e.g. a blocked upstream) — reprompt the SAME repo rather than
                        // silently moving on with it left undecided.
                    }
                    case "r" -> {
                        record(out, run, decisions, repo, decisions.reject(repo, run.plan(), ""));
                        continue walk;
                    }
                    case "d" -> {
                        record(out, run, decisions, repo, decisions.redo(repo, run.plan(), ""));
                        continue walk;
                    }
                    case "v" -> printDiff(out, run, repo);
                    case "s" -> {
                        continue walk;   // leave PENDING, move to the next repo
                    }
                    case "q" -> {
                        break walk;
                    }
                    default -> out.println("unrecognized option: " + line);
                }
            }
        }
        out.println("review written: " + run.writeReport(run.collectDiffs(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), false));
    }

    /** Returns whether the approve applied. On success it also runs the same squash follow-up
     *  {@code sdd review approve} runs — the one piece of approve's behavior that lives outside
     *  {@link Decisions}. */
    private static boolean approve(PrintWriter out, PrintWriter err, RunContext run,
                                   Decisions decisions, String repo) {
        Decisions.Outcome outcome = decisions.approve(repo, run.plan(), run.state());
        if (!outcome.applied()) {
            out.println("refused: " + outcome.message());
            return false;
        }
        record(out, run, decisions, repo, outcome);
        DecisionCommand.Followup followup = DecisionCommand.squashAndRecord(run, repo, out, err);
        followup.trailer().forEach(out::println);
        return true;
    }

    /** Persists the decision (before anything else observable happens — a crash right after this
     *  call must not lose the verdict a human just gave), appends its event and any downstream
     *  downgrade events, and prints the same lines {@code DecisionCommand} prints. */
    private static void record(PrintWriter out, RunContext run, Decisions decisions, String repo,
                               Decisions.Outcome outcome) {
        run.store().writeDecisions(run.runDir(), decisions.asMap());
        Decision after = decisions.of(repo);
        run.store().appendEvent(run.runDir(), repo, Decision.PENDING, after, decisions.reasonOf(repo));
        for (String downgraded : outcome.downgraded()) {
            run.store().appendEvent(run.runDir(), downgraded, Decision.APPROVED, Decision.PENDING,
                    "upstream " + repo + " is " + after);
        }
        out.println(outcome.message());
        if (!outcome.downgraded().isEmpty()) {
            out.println("downgraded to PENDING (re-decide): " + String.join(", ", outcome.downgraded()));
        }
    }

    private static void printDiff(PrintWriter out, RunContext run, String repo) throws IOException {
        Path diffFile = run.store().reviewDir(run.runDir()).resolve(repo + ".diff");
        if (Files.exists(diffFile)) {
            String content = Files.readString(diffFile);
            out.println(content.isEmpty() ? "(no changes)" : content);
        } else {
            out.println("(no diff available for " + repo + ")");
        }
    }

    private static String reportLine(RunContext run, String repo) {
        RepoRun repoRun = run.byName().get(repo);
        RepoState state = repoRun == null ? null : repoRun.state();
        StringBuilder line = new StringBuilder();
        line.append(repo).append(": ").append(state == null ? "UNKNOWN" : state);
        if (repoRun != null && repoRun.checkpointSha() != null) {
            line.append(", checkpoint ").append(DecisionCommand.shortSha(repoRun.checkpointSha()));
        }
        return line.toString();
    }
}
