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
                             Semaphore gradlePermits, sdd.core.toolchain.Toolchain toolchain,
                             Path nodeHome,
                             /** Fallback Gradle for a repo with no wrapper; null = $SDD_GRADLE then PATH. */
                             Path gradleHome,
                             /** One multiplexed tool declaration instead of six — see {@code Toolbox}. */
                             boolean singleTool) {

    /** Pre-{@code singleTool} shape, kept so existing construction sites compile untouched. */
    public RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock,
                          Path javaHome, Duration gradleTimeout, List<String> verificationTasks,
                          int maxTokensPerCall, String systemPrompt, List<String> gradleExtraArgs,
                          Semaphore gradlePermits, sdd.core.toolchain.Toolchain toolchain,
                          Path nodeHome, Path gradleHome) {
        this(budget, contextSoftCap, clock, javaHome, gradleTimeout, verificationTasks,
                maxTokensPerCall, systemPrompt, gradleExtraArgs, gradlePermits, toolchain,
                nodeHome, gradleHome, false);
    }
    /** Pre-{@code gradleHome} shape, so existing construction sites compile untouched. */
    public RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock,
                          Path javaHome, Duration gradleTimeout, List<String> verificationTasks,
                          int maxTokensPerCall, String systemPrompt, List<String> gradleExtraArgs,
                          Semaphore gradlePermits, sdd.core.toolchain.Toolchain toolchain,
                          Path nodeHome) {
        this(budget, contextSoftCap, clock, javaHome, gradleTimeout, verificationTasks,
                maxTokensPerCall, systemPrompt, gradleExtraArgs, gradlePermits, toolchain,
                nodeHome, null);
    }

    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a careful senior engineer making a focused change to ONE repository. Use the
            tools to read files, make minimal edits, and run Gradle to check your work. Change
            only what the sub-spec requires. When it compiles and implements the sub-spec, call
            done(success). If blocked by a missing decision, call done(blocked) and explain.""";

    /**
     * The npm sibling of {@link #DEFAULT_SYSTEM_PROMPT}, same four sentences with the one word that
     * differs. The prompt is per-repo, built alongside the work order, so a mixed estate never has
     * to describe both toolchains at once.
     */
    public static final String NPM_SYSTEM_PROMPT = """
            You are a careful senior engineer making a focused change to ONE repository. Use the
            tools to read files, make minimal edits, and run npm scripts to check your work. Change
            only what the sub-spec requires. When it type-checks and implements the sub-spec, call
            done(success). If blocked by a missing decision, call done(blocked) and explain.""";

    /**
     * The same settings with a different system prompt — for guidance that depends on the WIRE
     * rather than on the repo, which is what these factories key on.
     */
    public RunnerSettings withSystemPrompt(String replacement) {
        return new RunnerSettings(budget, contextSoftCap, clock, javaHome, gradleTimeout,
                verificationTasks, maxTokensPerCall, replacement, gradleExtraArgs, gradlePermits,
                toolchain, nodeHome, gradleHome, singleTool);
    }

    public RunnerSettings {
        verificationTasks = List.copyOf(verificationTasks);
        gradleExtraArgs = List.copyOf(gradleExtraArgs);
        toolchain = toolchain == null ? sdd.core.toolchain.Toolchain.GRADLE : toolchain;
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
        return custom(javaHome, gradleExtraArgs, verificationTasks, gradlePermits, budget, false);
    }

    public static RunnerSettings custom(Path javaHome, List<String> gradleExtraArgs,
                                        List<String> verificationTasks, Semaphore gradlePermits,
                                        AgentBudget budget, boolean singleTool) {
        return new RunnerSettings(budget, 80_000, InstantSource.system(), javaHome,
                Duration.ofMinutes(15), verificationTasks, 4096, DEFAULT_SYSTEM_PROMPT,
                gradleExtraArgs, gradlePermits, sdd.core.toolchain.Toolchain.GRADLE, null, null,
                singleTool);
    }

    /** The npm counterpart: no JDK, a node home instead, and the npm prompt. */
    public static RunnerSettings npm(Path nodeHome, List<String> verificationTasks,
                                     Semaphore permits, AgentBudget budget) {
        return npm(nodeHome, verificationTasks, permits, budget, false);
    }

    public static RunnerSettings npm(Path nodeHome, List<String> verificationTasks,
                                     Semaphore permits, AgentBudget budget, boolean singleTool) {
        return new RunnerSettings(budget, 80_000, InstantSource.system(), null,
                Duration.ofMinutes(15), verificationTasks, 4096, NPM_SYSTEM_PROMPT,
                List.of(), permits, sdd.core.toolchain.Toolchain.NPM, nodeHome, null, singleTool);
    }
}
