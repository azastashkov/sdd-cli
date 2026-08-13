package sdd.core.config;

/** The sdd.yml run: section — implement-time throttles and the run-wide token budget
 *  (design lines 59-60: run budget, gradle_workers/model_concurrency semaphores). */
public record RunSettings(int gradleWorkers, int modelConcurrency, long tokenBudget) {
    public static RunSettings defaults() {
        return new RunSettings(2, 2, 30_000_000L);
    }
}
