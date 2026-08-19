package sdd.agent.tool;

import sdd.core.llm.ToolCall;
import sdd.core.llm.ToolSpec;

import java.util.List;

/**
 * What {@link sdd.agent.loop.AgentLoop} needs from a tool set: what to advertise, and how to run
 * one call.
 *
 * <p>The loop is the same machine for a coding agent and a read-only explorer — budgets, strikes,
 * the wedge detector, the 400-eviction retry and the transcript are all tool-agnostic. Only the
 * tools differ, so only the tools are an interface. {@link Toolbox} is the implementation that
 * carries an edit tool and a build tool; the explorer's is read-only and has neither.
 */
public interface Tools {

    /** The schemas advertised to the model. {@code done} is included but never dispatched here. */
    List<ToolSpec> specs();

    /** Runs one call, or throws {@link MalformedCallException} / {@link ToolException}. */
    String dispatch(String name, String argsJson);

    /**
     * The name of the tool whose output the loop should wedge-check for a repeated failure, or null
     * when there is none.
     *
     * <p>The loop used to hard-code {@code "run_gradle"}, which silently exempted the npm toolchain
     * from a check written for both — and an explorer, which runs no build at all, would have had a
     * dead branch. Asking the tool set is the only place that knows.
     */
    default String buildToolName() {
        return null;
    }

    /**
     * A running summary of what this tool set has established, pinned into the context window after
     * every call, or null when there is nothing to say.
     *
     * <p>Eviction — and especially the full eviction an HTTP 400 forces — otherwise erases an
     * explorer's entire memory of a survey, since unlike a coding agent it has no on-disk artifact
     * to recover from. The digest is the one thing that survives, so it is the thing that has to
     * carry the answers rather than the transcript.
     */
    default String digest() {
        return null;
    }

    /**
     * Maps a call as the model made it onto the logical call the loop reasons about.
     *
     * <p>The identity by default. It exists because a tool set may not advertise one
     * declaration per operation: a set that multiplexes every operation through a single
     * declaration still has to reach {@code done} interception, wedge detection and the
     * transcript under the operation's own name, and translating here keeps all of that — and
     * every other caller — unaware that the wire shape differs at all.
     *
     * <p>The returned call MUST keep {@code id}: the tool-result pairing the endpoint expects
     * is keyed on it, and losing it breaks the conversation rather than one call.
     */
    default ToolCall route(ToolCall call) {
        return call;
    }
}
