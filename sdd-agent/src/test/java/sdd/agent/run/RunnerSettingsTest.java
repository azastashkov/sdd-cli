package sdd.agent.run;

import org.junit.jupiter.api.Test;
import sdd.agent.loop.AgentBudget;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerSettingsTest {
    @Test
    void fiveArgCustomCarriesTheGivenBudgetThrough() {
        AgentBudget budget = new AgentBudget(3, Duration.ofMinutes(45), 1_500_000L);

        RunnerSettings settings = RunnerSettings.custom(null, List.of(), List.of("check"), null, budget);

        assertThat(settings.budget()).isEqualTo(budget);
    }

    @Test
    void fourArgCustomStillDefaultsTheBudget() {
        RunnerSettings settings = RunnerSettings.custom(null, List.of(), List.of("check"), null);

        assertThat(settings.budget()).isEqualTo(AgentBudget.defaults());
    }
}
