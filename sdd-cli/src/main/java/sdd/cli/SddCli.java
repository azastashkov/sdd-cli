package sdd.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import sdd.cli.progress.ProgressArming;
import sdd.core.progress.Progress;

import java.io.PrintWriter;

@Command(name = "sdd",
        description = "Spec-Driven Development pipeline for multi-repo estates",
        mixinStandardHelpOptions = true,
        version = "sdd 0.1.0",
        subcommands = {DoctorCommand.class, IndexCommand.class, PlanCommand.class,
                ExploreCommand.class, ImplementCommand.class, ReviewCommand.class, CleanCommand.class,
                StatusCommand.class})
public final class SddCli {
    // scope = INHERIT because picocli does NOT inherit parent options: without it, only
    // "sdd --quiet index" would parse, and "sdd index --quiet" — the form a user actually
    // types — would fail with an unmatched-argument error. Same footgun ReviewCommand.java:61-63
    // documents for --workspace.
    @Option(names = "--quiet", scope = CommandLine.ScopeType.INHERIT,
            description = "Disable the progress indicator, regardless of "
                    + "SDD_PROGRESS or whether the console is a terminal")
    boolean quiet;

    /**
     * The ONE field {@code main} ever assigns (design doc, "Arming") — {@code null} ("unarmed")
     * until it does. A subcommand never reads this directly; it calls {@link
     * #resolve(CommandSpec)}, which walks up to whichever {@link SddCli} instance is the root of
     * its own command tree. Package-private, mirroring {@code DoctorCommand.clockForTest}/
     * {@code PlanCommand.plannerForTest} — a test seam in shape, though here what it is seamed
     * against is "was this process real {@code main}", not a fake collaborator.
     */
    Progress.Factory progress;

    /**
     * Arms {@link #progress} from the real environment and a real {@link System#err}, then runs
     * exactly one subcommand — the only place in this codebase that does either. A custom {@link
     * CommandLine.IExecutionStrategy} is used (rather than arming before {@link
     * CommandLine#execute}) specifically so {@code --quiet} — parsed onto this same root object —
     * is already populated by the time arming runs: picocli parses the full command line,
     * including every subcommand option, before invoking any {@code call()}, and the execution
     * strategy is the hook that sees the parsed result before dispatch.
     */
    public static void main(String[] args) {
        SddCli root = new SddCli();
        CommandLine cli = new CommandLine(root);
        cli.setExecutionStrategy(parseResult -> {
            root.progress = ProgressArming.factory(root.quiet, System.getenv(), new PrintWriter(System.err, true));
            return new CommandLine.RunLast().execute(parseResult);
        });
        System.exit(cli.execute(args));
    }

    /**
     * How every in-scope subcommand reaches its {@link Progress} (design doc, "Arming"):
     * {@link CommandSpec#root()} to the {@link SddCli} instance heading this command's own tree,
     * then whatever it was armed with — {@link Progress#noOp()} if that is absent (never armed,
     * true of every existing test per the design doc's "Arming" section) or itself unarmed (root
     * exists but {@link #main} never ran). Never throws: a subcommand asking "is progress on"
     * must never itself become the reason a command fails, so even a factory whose {@link
     * Progress.Factory#open()} throws degrades to {@link Progress#noOp()} rather than
     * propagating.
     */
    static Progress resolve(CommandSpec spec) {
        try {
            Object rootUserObject = spec.root().userObject();
            if (rootUserObject instanceof SddCli cli && cli.progress != null) {
                Progress opened = cli.progress.open();
                return opened != null ? opened : Progress.noOp();
            }
        } catch (RuntimeException e) {
            // See javadoc: progress resolution itself must never fail the caller.
        }
        return Progress.noOp();
    }
}
