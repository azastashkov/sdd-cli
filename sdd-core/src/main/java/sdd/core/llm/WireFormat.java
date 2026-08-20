package sdd.core.llm;

import sdd.core.config.ConfigException;

import java.util.Locale;

/**
 * Which JSON dialect an endpoint's {@code /chat/completions} speaks.
 *
 * <p>{@link #OPENAI} is the shape sdd has always sent and stays the default: {@code content} is a
 * string on every role, an assistant turn that carries only {@code tool_calls} omits {@code content}
 * entirely, and a model's thinking is not part of the conversation.
 *
 * <p>{@link #GIGACHAT} is the same protocol as a corp GigaChat gateway actually validates it. That
 * gateway presents an OpenAI face over GigaChat semantics: it takes {@code tools} and
 * {@code tool_calls} unchanged, but checks the messages against GigaChat's own rules. One rule has
 * been measured, by hitting it: a tool result must be a **JSON object**, not free text —
 * {@code HTTP 422 INVALID_PARAMS: function content must contain FunctionResult}. Note what that
 * error implies and what it does not: the gateway had already accepted a {@code role: "tool"}
 * message AS a function message, so the role mapping is its problem, not ours; only the content
 * shape is.
 *
 * <p>So this wire wraps a tool result that is not already a JSON object, and carries
 * {@code reasoning_content} — a real message field this gateway returns, rather than
 * {@code <think>} tags inline in {@code content} (which is what the {@code gpt2giga} proxy produced,
 * and what {@link ReasoningContent} exists to undo).
 *
 * <p><b>What this wire deliberately no longer does.</b> It first shipped sending {@code user} and
 * {@code tool} content as {@code [{"type":"text","text":…}]} parts, copied from captured traffic.
 * That returned {@code HTTP 400 "Your request contains invalid JSON syntax"} — because the capture
 * was a DIFFERENT service surface in the same estate (an IDE assistant's backend, serving different
 * models), not this gateway. The behaviour was removed rather than left behind a flag: a capture
 * proves what THAT url accepts and nothing more, and keeping a measured-wrong option under a name
 * an operator will reach for is worse than not offering it.
 *
 * <p>Deliberately a description of a wire shape and nothing else: it selects no host, no
 * credentials and no tool set. An endpoint chooses it with {@code models.<name>.wire}.
 */
public enum WireFormat {
    OPENAI,
    GIGACHAT;

    /**
     * Parses a {@code wire:} value, naming the config key and every accepted value on a miss —
     * an operator who typed {@code giga} should not have to read this file to learn what to type.
     */
    public static WireFormat parse(String where, String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + " must be one of openai, gigachat — got: " + value);
        }
    }

    /**
     * Whether a tool result that is not already a JSON object is wrapped in one.
     *
     * <p>Wrapped, not replaced: the text is what the model has to read to keep working, so it is
     * carried through verbatim under a key rather than summarized or discarded.
     */
    boolean wrapsToolResults() {
        return this == GIGACHAT;
    }

    /** Whether an assistant turn carrying only tool calls still writes an (empty) {@code content}. */
    boolean assistantContentAlwaysPresent() {
        return this == GIGACHAT;
    }

    /** Whether {@code reasoning_content} is read off replies and sent back with the turn it came from. */
    boolean carriesReasoning() {
        return this == GIGACHAT;
    }
}
