package sdd.cli.progress;

import sdd.core.progress.Progress;

import java.io.PrintWriter;
import java.time.InstantSource;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * What {@code SddCli.main} calls — the ONLY site that ever assigns a live/plain {@link
 * Progress.Factory} (design doc, "Arming"). {@code --quiet} is checked ahead of {@link
 * ProgressEnvironment#decide}'s ladder rather than folded into it: it is a command-line choice,
 * not an environment one, and the ladder's own tests ({@link ProgressEnvironmentTest}) stay
 * about the environment alone.
 *
 * <p>{@code err} is captured here, in the lambda each factory closes over — never appearing in
 * {@link Progress.Factory#open()}'s own signature — which is what keeps a {@link PrintWriter}
 * out of every {@link Progress} type all the way down to {@code sdd-core}.
 *
 * <p>Public because {@code SddCli} (a different package, so this arming logic stays out of the
 * root command class itself) is the only real caller; the {@code isTerminal} overload exists so
 * {@link ProgressArmingTest} can drive the console rung of the ladder deterministically, the same
 * reason {@link ProgressEnvironment#decide} itself takes a {@link BooleanSupplier}.
 */
public final class ProgressArming {
    private ProgressArming() {
    }

    /** The real entry point: {@code SddCli.main}'s console check, wired to {@link
     *  ConsoleSupport#isTerminal()}. */
    public static Progress.Factory factory(boolean quiet, Map<String, String> env, PrintWriter err) {
        return factory(quiet, env, err, ConsoleSupport::isTerminal);
    }

    public static Progress.Factory factory(boolean quiet, Map<String, String> env, PrintWriter err,
            BooleanSupplier isTerminal) {
        if (quiet) {
            return Progress::noOp;
        }
        ProgressEnvironment.Mode mode = ProgressEnvironment.decide(env, isTerminal);
        return switch (mode) {
            case LIVE -> () -> new LiveProgress(err, InstantSource.system());
            case PLAIN -> () -> new PlainProgress(err);
            case OFF -> Progress::noOp;
        };
    }
}
