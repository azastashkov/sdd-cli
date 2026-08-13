package sdd.core.config;

import java.util.List;

/** The sdd.yml run: section — implement-time throttles and the run-wide token budget
 *  (design lines 59-60: run budget, gradle_workers/model_concurrency semaphores).
 *  escalationLadder names, in attempt order, the models: keys the orchestrator escalates through on a
 *  failed attempt (Amendment 2026-08-13: N-tier configurable escalation ladder). */
public record RunSettings(int gradleWorkers, int modelConcurrency, long tokenBudget, int agentTurns,
                           long agentTokens, List<String> escalationLadder) {
    public RunSettings {
        escalationLadder = List.copyOf(escalationLadder);
    }

    public static RunSettings defaults() {
        return new RunSettings(2, 2, 30_000_000L, 40, 1_500_000L, List.of("coder", "planner"));
    }
}
