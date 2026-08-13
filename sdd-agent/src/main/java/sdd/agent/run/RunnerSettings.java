package sdd.agent.run;

import sdd.agent.loop.AgentBudget;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Everything the runner needs beyond the model, KB, and step. */
public record RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock,
                             Path javaHome, Duration gradleTimeout, List<String> verificationTasks,
                             int maxTokensPerCall, String systemPrompt, List<String> gradleExtraArgs,
                             Semaphore gradlePermits) {
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a careful senior engineer making a focused change to ONE repository. Use the
            tools to read files, make minimal edits, and run Gradle to check your work. Change
            only what the sub-spec requires. When it compiles and implements the sub-spec, call
            done(success). If blocked by a missing decision, call done(blocked) and explain.""";

    public RunnerSettings {
        verificationTasks = List.copyOf(verificationTasks);
        gradleExtraArgs = List.copyOf(gradleExtraArgs);
    }

    public static RunnerSettings defaults(Path javaHome) {
        return defaults(javaHome, List.of());
    }

    public static RunnerSettings defaults(Path javaHome, List<String> gradleExtraArgs) {
        return custom(javaHome, gradleExtraArgs, List.of("check"), null);
    }

    public static RunnerSettings custom(Path javaHome, List<String> gradleExtraArgs,
                                        List<String> verificationTasks, Semaphore gradlePermits) {
        return custom(javaHome, gradleExtraArgs, verificationTasks, gradlePermits, AgentBudget.defaults());
    }

    public static RunnerSettings custom(Path javaHome, List<String> gradleExtraArgs,
                                        List<String> verificationTasks, Semaphore gradlePermits,
                                        AgentBudget budget) {
        return new RunnerSettings(budget, 80_000, InstantSource.system(), javaHome,
                Duration.ofMinutes(15), verificationTasks, 4096, DEFAULT_SYSTEM_PROMPT,
                gradleExtraArgs, gradlePermits);
    }
}
