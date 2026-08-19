package sdd.core.config;

import java.util.List;

/** The sdd.yml run: section — implement-time throttles and the run-wide token budget
 *  (design lines 59-60: run budget, gradle_workers/model_concurrency semaphores).
 *  escalationLadder names, in attempt order, the models: keys the orchestrator escalates through on a
 *  failed attempt (Amendment 2026-08-13: N-tier configurable escalation ladder). */
public record RunSettings(int gradleWorkers, int modelConcurrency, long tokenBudget, int agentTurns,
                           long agentTokens, List<String> escalationLadder,
                           /**
                            * Advertise the coding agent's six operations as ONE tool declaration
                            * carrying an {@code action} argument. For an endpoint whose function
                            * calling degrades with the size of the declaration set — measured on
                            * one gateway, an identical request succeeded 20/20 with one
                            * declaration, 13/20 with six and 0/20 with nine. Off by default: six
                            * schemas tell the model what each operation takes.
                            */
                           boolean singleTool) {
    public RunSettings {
        escalationLadder = List.copyOf(escalationLadder);
    }

    /** Pre-{@code singleTool} shape, kept so existing construction sites compile untouched. */
    public RunSettings(int gradleWorkers, int modelConcurrency, long tokenBudget, int agentTurns,
                       long agentTokens, List<String> escalationLadder) {
        this(gradleWorkers, modelConcurrency, tokenBudget, agentTurns, agentTokens,
                escalationLadder, false);
    }

    public static RunSettings defaults() {
        return new RunSettings(2, 2, 30_000_000L, 40, 1_500_000L, List.of("coder", "planner"), false);
    }
}
