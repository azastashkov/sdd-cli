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

    /**
     * The one-call-per-turn guidance depends on the WIRE, while these factories key on the repo's
     * toolchain — so the prompt has to be replaceable after construction rather than threaded
     * through every factory and every call site.
     */
    @Test
    void withSystemPromptReplacesOnlyThePrompt() {
        RunnerSettings base = RunnerSettings.defaults(java.nio.file.Path.of("/jdk"));
        RunnerSettings changed = base.withSystemPrompt(base.systemPrompt() + " EXTRA");

        assertThat(changed.systemPrompt()).isEqualTo(base.systemPrompt() + " EXTRA");
        assertThat(changed.budget()).isEqualTo(base.budget());
        assertThat(changed.javaHome()).isEqualTo(base.javaHome());
        assertThat(changed.gradleTimeout()).isEqualTo(base.gradleTimeout());
        assertThat(changed.verificationTasks()).isEqualTo(base.verificationTasks());
        assertThat(changed.toolchain()).isEqualTo(base.toolchain());
        assertThat(changed.singleTool()).isEqualTo(base.singleTool());
        assertThat(changed.contextSoftCap()).isEqualTo(base.contextSoftCap());
    }

    /** The npm prompt is a different string and must survive the same round trip. */
    @Test
    void theNpmPromptAndNodeHomeSurviveAPromptChange() {
        RunnerSettings npm = RunnerSettings.npm(java.nio.file.Path.of("/node"),
                java.util.List.of("test"), null, sdd.agent.loop.AgentBudget.defaults());

        assertThat(npm.systemPrompt()).contains("npm scripts");
        assertThat(npm.withSystemPrompt(npm.systemPrompt() + " X").nodeHome())
                .isEqualTo(java.nio.file.Path.of("/node"));
    }

    /**
     * ONE definition of the sentence, shared by explore and implement. Two copies would drift, and
     * the drifted one would surface as an HTTP 500 with nothing in it naming a prompt.
     */
    @Test
    void theOneCallGuidanceHasASingleDefinition() {
        assertThat(Explorer.ONE_CALL_PER_TURN)
                .isEqualTo(sdd.core.llm.WireFormat.ONE_CALL_PER_TURN_GUIDANCE)
                .contains("exactly ONE tool per turn");
    }
}
