package sdd.cli.progress;

import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The live/plain/off ladder (design doc, "Deciding live vs plain vs off"). Order matters — each
 * rung is an explicit escape hatch that a human or CI set on purpose, so it is checked before the
 * version-fragile {@link ConsoleSupport} check, which can therefore only narrow an {@code auto}
 * answer, never override one already settled:
 *
 * <ol>
 *   <li>{@code SDD_PROGRESS} = {@code off}/{@code plain}/{@code live}/{@code auto} (default
 *       {@code auto}) — consistent with {@code SDD_NODE} ({@code NodeLocator.java:36}). Any
 *       value other than the four recognized ones is treated as {@code auto} rather than
 *       rejected: a typo'd escape hatch should fall through to the rest of the ladder, not fail
 *       the whole command over a progress bar.
 *   <li>{@code TERM} unset or {@code dumb} → plain.
 *   <li>{@code CI} set (to anything) → plain — deterministic on any JDK, ahead of the JDK-8295803
 *       console wrinkle {@link ConsoleSupport} exists for.
 *   <li>Otherwise, {@link ConsoleSupport#isTerminal()}.
 * </ol>
 *
 * <p>{@code isTerminal} is a {@link BooleanSupplier} rather than a direct {@code boolean} so it
 * is evaluated only when the first three rungs decline to answer — the same reason {@link
 * #decide} takes a plain {@link Map} snapshot of the environment rather than reading {@code
 * System.getenv()} itself: both keep this method pure and trivially testable without a real
 * terminal or process environment.
 */
final class ProgressEnvironment {
    enum Mode {
        OFF, PLAIN, LIVE
    }

    private ProgressEnvironment() {
    }

    static Mode decide(Map<String, String> env, BooleanSupplier isTerminal) {
        String explicit = env.get("SDD_PROGRESS");
        if (explicit != null) {
            Mode fromEnv = switch (explicit.toLowerCase(Locale.ROOT)) {
                case "off" -> Mode.OFF;
                case "plain" -> Mode.PLAIN;
                case "live" -> Mode.LIVE;
                default -> null; // "auto", or an unrecognized value — fall through the ladder
            };
            if (fromEnv != null) {
                return fromEnv;
            }
        }
        String term = env.get("TERM");
        if (term == null || term.equals("dumb")) {
            return Mode.PLAIN;
        }
        if (env.get("CI") != null) {
            return Mode.PLAIN;
        }
        return isTerminal.getAsBoolean() ? Mode.LIVE : Mode.PLAIN;
    }
}
