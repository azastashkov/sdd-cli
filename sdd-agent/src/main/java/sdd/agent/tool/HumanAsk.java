package sdd.agent.tool;

import java.util.List;

/**
 * Puts one question to a human and returns the answer, or {@code null} when nobody answered.
 *
 * <p><b>{@code null} is the only failure signal, deliberately.</b> It covers "there is no terminal",
 * "stdin is closed", "the run is in CI" and "somebody pressed enter on an empty line" alike — the
 * same collapse {@code InteractiveReview} makes when {@code readLine()} returns null and it treats
 * the result exactly like a quit. One signal means one code path in the tool, and no way for a
 * caller to invent a fourth outcome the agent has never been taught to handle.
 *
 * <p>Implemented in {@code sdd-cli}, where the terminal is. Nothing in {@code sdd-agent} may see a
 * {@code PrintWriter}, a {@code Progress} or {@code System.in} — the same discipline that keeps a
 * writer out of {@code Progress.Factory.open()}, and the reason this interface names none of them.
 *
 * @see ExploreTools#specs() which advertises the tool only when an asker is attached
 */
@FunctionalInterface
public interface HumanAsk {

    /**
     * @param question one question, answerable in a sentence
     * @param options  suggested answers, possibly empty; a human may ignore them
     * @return the answer, or null when no human answered
     */
    String ask(String question, List<String> options);
}
