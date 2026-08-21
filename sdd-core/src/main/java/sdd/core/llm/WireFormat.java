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
 * <p>{@link #GIGACHAT} is GigaChat's own function-calling protocol, behind a gateway that presents
 * an OpenAI face. A call and its
 * result must be spelled GigaChat's way. Measured by sending one three-message conversation ten
 * ways against a live gateway; exactly one shape was accepted, and the refusals named the rules:
 *
 * <ul>
 *   <li>An assistant turn carries a single {@code function_call: {name, arguments}}, not
 *       {@code tool_calls}. With {@code tool_calls}: <em>"every function result must have an
 *       assistant function call in history"</em>. {@code arguments} is an OBJECT here, unlike
 *       OpenAI's JSON string.</li>
 *   <li>A result is {@code role: "function"} with a {@code name} — pairing is by name, and there
 *       is no {@code tool_call_id}. With {@code role: "tool"}: <em>"function content must contain
 *       FunctionResult"</em>.</li>
 *   <li>Its {@code content} is a STRING that itself parses as JSON. A plain string:
 *       <em>"invalid function result … JSON parse error"</em>. An actual JSON object:
 *       {@code HTTP 400 "Your request contains invalid JSON syntax"}.</li>
 * </ul>
 *
 * <p>One call per assistant turn is all this protocol can express — {@code function_call} is a
 * single object, not an array. So a reply carrying several is truncated to the first on this wire;
 * see {@code HttpChatModel}. That loses work the model asked for, which is why it is confined to
 * the wire that cannot represent it.
 *
 * <p>{@code reasoning_content} is carried too: a real message field this gateway returns, rather
 * than {@code <think>} tags inline in {@code content} (which is what the {@code gpt2giga} proxy
 * produced, and what {@link ReasoningContent} exists to undo).
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

    /** Whether a call and its result are spelled GigaChat's way rather than OpenAI's. */
    boolean usesFunctionCall() {
        return this == GIGACHAT;
    }

    /** Whether an assistant turn carrying only tool calls still writes an (empty) {@code content}. */
    boolean assistantContentAlwaysPresent() {
        return this == GIGACHAT;
    }

    /**
     * Whether this wire can carry only ONE tool call per assistant turn, so the model must be
     * told to make one at a time.
     *
     * <p>Measured, and it is the difference between a survey running and dying on its second turn.
     * On a request that failed HTTP 500 twenty times out of twenty, sending
     * {@code function_call: "none"} or pinning a function made the SAME BYTES succeed 5/5 — so the
     * request was always valid and the 500 was what the model PRODUCED when left free to choose.
     * This protocol has one {@code function_call} field and no way to express a second, and a
     * model given a real task attempts several.
     *
     * <p>Neither of those diagnostics is shippable: one forbids tool calling, the other pins the
     * tool the agent is supposed to choose. Saying it in the prompt is, and it fixed the same
     * request 5/5 while leaving the choice to the model.
     *
     * <p>Public because the guidance has to reach a system prompt, which is assembled outside this
     * module. It is a property of the WIRE, not of the agent: on {@code openai} several calls per
     * turn are legitimate and useful, and telling a model otherwise would cost real parallelism.
     */
    public boolean oneCallPerTurn() {
        return this == GIGACHAT;
    }

    /** Whether {@code reasoning_content} is read off replies and sent back with the turn it came from. */
    boolean carriesReasoning() {
        return this == GIGACHAT;
    }

    /**
     * Whether declarations go out as GigaChat's {@code functions[]} rather than OpenAI's
     * {@code tools[]}.
     *
     * <p><b>Measured, and it is the difference between working and silently not.</b> The same
     * conversation was sent to one gateway twice, differing only in this key
     * ({@code gigachat-tools-vs-functions.sh}). Under {@code tools[]} the model answered "there is
     * no tool specified in the conversation… I don't have tools unless specified" — the gateway
     * accepted the request, returned HTTP 200, and passed the model NOTHING. Under
     * {@code functions[]} the same model returned a structured
     * {@code function_call: {"name": "sdd_probe_ack", …}}.
     *
     * <p>That one fact explains a long trail of wrong diagnoses: models appearing to "invent tool
     * names" were hallucinating a tool from the request's own wording, because they had been given
     * none. It is also why {@code gpt2giga} worked — translating {@code tools} into
     * {@code functions} was the thing that proxy did.
     */
    boolean declaresFunctions() {
        return this == GIGACHAT;
    }
}
