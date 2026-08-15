package sdd.agent.tool;

import java.util.Set;

/**
 * The agent's only path to a repo's build. One implementation per toolchain, and a repo has exactly
 * one, so the model is never offered a choice it has no basis for making.
 *
 * <p>Kept as separately-named tools ({@code run_gradle}, {@code run_npm}) rather than one
 * polymorphic {@code run_build}: the name and its task vocabulary are most of what a small model
 * has to go on, and a generic verb with an ecosystem-specific task list reads as a puzzle. It also
 * leaves the Gradle tool's advertised schema untouched, so nothing about the six Gradle repos'
 * conditioning changes.
 */
public interface BuildTool {

    /** The tool name the model sees and calls. */
    String toolName();

    /** The task parameter's description — the allowlist, as the model reads it. */
    String taskDescription();

    /**
     * What may be run, so callers can validate a plan's verification list without duplicating it.
     * Named {@code tasks} rather than {@code allowedTasks} only because {@link GradleTool} has long
     * exposed a static method by that name which many callers use.
     */
    Set<String> tasks();

    /** Model-facing output: tail-capped, because the end of a build log is where the error is. */
    String run(String task);

    /**
     * The whole log, head-preserving. Compilers print root-cause errors first, so anything that
     * scrapes the log must see its start rather than {@link #run}'s tail.
     */
    String runFull(String task);
}
