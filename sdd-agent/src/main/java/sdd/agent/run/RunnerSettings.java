package sdd.agent.run;

import sdd.agent.loop.AgentBudget;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;

/** Everything the runner needs beyond the model, KB, and step. */
public record RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock,
                             Path javaHome, Duration gradleTimeout, String verificationTask,
                             int maxTokensPerCall, String systemPrompt, List<String> gradleExtraArgs) {
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a careful senior engineer making a focused change to ONE repository. Use the
            tools to read files, make minimal edits, and run Gradle to check your work. Change
            only what the sub-spec requires. When it compiles and implements the sub-spec, call
            done(success). If blocked by a missing decision, call done(blocked) and explain.""";

    public RunnerSettings {
        gradleExtraArgs = List.copyOf(gradleExtraArgs);
    }

    public static RunnerSettings defaults(Path javaHome) {
        return defaults(javaHome, List.of());
    }

    public static RunnerSettings defaults(Path javaHome, List<String> gradleExtraArgs) {
        return new RunnerSettings(AgentBudget.defaults(), 80_000, InstantSource.system(), javaHome,
                Duration.ofMinutes(15), "check", 4096, DEFAULT_SYSTEM_PROMPT, gradleExtraArgs);
    }
}
