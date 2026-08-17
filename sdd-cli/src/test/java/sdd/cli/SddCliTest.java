package sdd.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import sdd.core.progress.Progress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arming contract (design doc, "Arming"): {@code SddCli.main} is the ONLY site that ever
 * assigns {@link SddCli#progress}, and every subcommand resolves it by walking up to the root
 * command object via {@link SddCli#resolve(CommandSpec)} — never constructing a renderer itself
 * (P1).
 *
 * <p><b>Neither test here calls {@code SddCli.main} or spawns {@code sdd}</b> — exactly the
 * property the design doc's "Arming" section relies on to say every pre-existing test is safe
 * structurally: they build {@code new CommandLine(new SddCli())} the same way {@code
 * IndexCommandTest.java:35-41} already does, so a root object exists but is never armed.
 */
class SddCliTest {
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
}
