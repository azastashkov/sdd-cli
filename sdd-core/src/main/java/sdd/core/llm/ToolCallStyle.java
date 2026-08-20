package sdd.core.llm;

import sdd.core.config.ConfigException;

import java.util.Locale;

/**
 * How an endpoint delivers a tool call it has decided to make.
 *
 * <p>{@link #NATIVE} is the default and the contract every OpenAI-compatible endpoint claims: the
 * call comes back in {@code message.tool_calls}, already parsed, with an id the endpoint issued.
 *
 * <p>{@link #TEXT} is for an endpoint that accepts {@code tools}, lets the model decide to call
 * one, and then hands the call back as ordinary content. Measured on a corp GigaChat gateway with
 * a live probe: the reply carried no {@code tool_calls} and no {@code function_call}, its
 * {@code finish_reason} was {@code stop}, and its {@code content} was the literal string
 * {@code {"name": "report_status", "arguments": {"status": "ok"}}} — the model complied, and only
 * the structuring step was missing. Explicit {@code tool_choice: auto}, {@code function_call:
 * auto} and {@code tool_choice: required} were each tried first and changed nothing.
 *
 * <p>Unstructured is strictly worse and this is not a mode to prefer: an id has to be invented, so
 * nothing has verified the gateway accepts the {@code tool_call_id} sent back with the result, and
 * a schema violation stops being something the endpoint reports. It exists because the alternative
 * on such a gateway is that {@code sdd implement} and {@code sdd explore} do not run at all.
 *
 * <p>Deliberately separate from {@link WireFormat}. That describes how a REQUEST is spelled; this
 * describes how a REPLY arrives, and the gateway that forced this one is on {@code wire: openai} —
 * proof that the two axes move independently.
 */
public enum ToolCallStyle {
    NATIVE,
    TEXT;

    /** Parses a {@code tool_calls:} value, naming the key and every accepted value on a miss. */
    public static ToolCallStyle parse(String where, String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + " must be one of native, text — got: " + value);
        }
    }
}
