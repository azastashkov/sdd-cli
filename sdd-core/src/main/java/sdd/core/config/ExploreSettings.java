package sdd.core.config;

import java.time.Duration;

/**
 * The {@code sdd.yml} {@code explore:} section — ceilings for {@code sdd explore}'s read-only
 * estate walk.
 *
 * <p><b>These bound termination, not cost.</b> The estate this was built for has unbounded model
 * access, so nothing here exists to save tokens; an unbounded loop is a hang, and a run with no
 * ceiling is not reproducible. The defaults are accordingly far above {@link RunSettings}' coding
 * agent — 40 turns is nowhere near enough to roam 53 repos — but finite.
 *
 * <p>{@code wallSeconds} is the one that has no counterpart in {@code run:}: {@code AgentBudget}
 * already carries a wall clock, and until now nothing could configure it, so a wedged endpoint
 * could hold the ceiling for the full 45 minutes with no way to say otherwise.
 */
public record ExploreSettings(int turns, long tokens, long wallSeconds, int contextSoftCap,
                              /**
                               * Advertise the explorer's operations as ONE multiplexed tool
                               * declaration instead of nine. A workaround for an endpoint whose
                               * function-calling path degrades as the declaration set grows —
                               * measured on one gateway, identifier-shaped text in the message
                               * failed 3 of 3 attempts at five declarations and 0 of 3 at one.
                               * Off by default: nine declarations with their own schemas is the
                               * better interface everywhere it works.
                               */
                              boolean singleTool) {

    /** Pre-{@code singleTool} shape, kept so existing construction sites compile untouched. */
    public ExploreSettings(int turns, long tokens, long wallSeconds, int contextSoftCap) {
        this(turns, tokens, wallSeconds, contextSoftCap, false);
    }

    public ExploreSettings {
        if (turns < 1) {
            throw new ConfigException("explore.turns must be at least 1, got '" + turns + "'");
        }
        if (tokens < 1) {
            throw new ConfigException("explore.tokens must be at least 1, got '" + tokens + "'");
        }
        if (wallSeconds < 1) {
            throw new ConfigException("explore.wall_seconds must be at least 1, got '" + wallSeconds + "'");
        }
        if (contextSoftCap < 1) {
            throw new ConfigException("explore.context_soft_cap must be at least 1, got '"
                    + contextSoftCap + "'");
        }
    }

    public static ExploreSettings defaults() {
        return new ExploreSettings(200, 8_000_000L, Duration.ofHours(2).toSeconds(), 200_000, false);
    }

    public Duration wall() {
        return Duration.ofSeconds(wallSeconds);
    }
}
