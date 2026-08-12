package sdd.agent.loop;

import java.time.Duration;

/** Per-attempt ceilings (design Component 3): turns, wall-clock, cumulative tokens. */
public record AgentBudget(int maxTurns, Duration maxWall, long maxTokens) {
    public static AgentBudget defaults() {
        return new AgentBudget(40, Duration.ofMinutes(45), 1_500_000L);
    }
}
