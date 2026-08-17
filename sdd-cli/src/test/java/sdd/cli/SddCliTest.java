package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import sdd.core.progress.Progress;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arming contract (design doc, "Arming"): {@code SddCli.main} is the ONLY site that ever
 * assigns {@link SddCli#progress}, and every subcommand resolves it by walking up to the root
 * command object via {@link SddCli#resolve(CommandSpec)} — never constructing a renderer itself
 * (P1).
 *
 * <p><b>No test here calls {@code SddCli.main}</b> — exactly the property the design doc's
 * "Arming" section relies on to say every pre-existing test is safe structurally: most build
 * {@code new CommandLine(new SddCli())} the same way {@code IndexCommandTest.java:35-41} already
 * does, so a root object exists but is never armed. The {@code --quiet} parsing tests below go
 * one step further and call {@link CommandLine#execute} on that same self-built {@code
 * CommandLine} — never {@code main}, so nothing here spawns a process or calls {@link
 * System#exit} — because {@code root.quiet}/{@code root.progress} set directly (as the other
 * tests in this class do) bypasses picocli's parser entirely and would not have caught {@code
 * --quiet} missing {@code scope = ScopeType.INHERIT}: without it, {@code sdd index --quiet} — the
 * form a user actually types — fails to parse, and only {@code sdd --quiet index} works. Same
 * footgun documented at {@code ReviewCommand.java:60-63} for {@code --workspace}.
 */
class SddCliTest {
    @TempDir
    Path workspace;

    @Test
    void anUnarmedRootResolvesToNoOp() {
        SddCli root = new SddCli();
        CommandLine cli = new CommandLine(root);
        CommandSpec doctorSpec = cli.getSubcommands().get("doctor").getCommandSpec();

        assertThat(SddCli.resolve(doctorSpec)).isSameAs(Progress.noOp());
    }

    @Test
    void anArmedRootResolvesThroughToWhateverTheFactoryOpens() {
        SddCli root = new SddCli();
        Progress sentinel = Progress.noOp();
        root.progress = () -> sentinel;
        CommandLine cli = new CommandLine(root);
        CommandSpec indexSpec = cli.getSubcommands().get("index").getCommandSpec();

        assertThat(SddCli.resolve(indexSpec)).isSameAs(sentinel);
    }

    /** P1/P5 belt-and-braces: even a factory that throws must not fail whatever command asked
     *  to resolve its progress. */
    @Test
    void aThrowingFactoryStillResolvesToNoOp() {
        SddCli root = new SddCli();
        root.progress = () -> {
            throw new RuntimeException("boom");
        };
        CommandLine cli = new CommandLine(root);
        CommandSpec reviewSpec = cli.getSubcommands().get("review").getCommandSpec();

        assertThat(SddCli.resolve(reviewSpec)).isSameAs(Progress.noOp());
    }

    @Test
    void quietParsesWhenGivenBeforeTheSubcommand() {
        SddCli root = new SddCli();
        CommandLine cli = new CommandLine(root);

        cli.execute("--quiet", "doctor", "--workspace", workspace.toString());

        assertThat(root.quiet).isTrue();
    }

    /** The form a user actually types, and the one that was silently broken without {@code scope
     *  = ScopeType.INHERIT}: picocli does not inherit parent options by default, so without it
     *  this parse fails with an unmatched-argument error and {@link SddCli#quiet} is never set. */
    @Test
    void quietParsesWhenGivenAfterTheSubcommandNotJustBeforeIt() {
        SddCli root = new SddCli();
        CommandLine cli = new CommandLine(root);

        cli.execute("doctor", "--workspace", workspace.toString(), "--quiet");

        assertThat(root.quiet).isTrue();
    }
}
