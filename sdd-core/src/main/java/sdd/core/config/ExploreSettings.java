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
public record ExploreSettings(int turns, long tokens, long wallSeconds, int contextSoftCap) {

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
        return new ExploreSettings(200, 8_000_000L, Duration.ofHours(2).toSeconds(), 200_000);
    }

    public Duration wall() {
        return Duration.ofSeconds(wallSeconds);
    }
}
