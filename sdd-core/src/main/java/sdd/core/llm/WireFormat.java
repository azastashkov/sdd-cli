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
 * <p>{@link #GIGACHAT} is that same OpenAI tool-calling protocol as the corp GigaChat gateway
 * actually receives it. It exists because sdd reached that gateway through the {@code gpt2giga}
 * proxy, which translates OpenAI {@code tools} into GigaChat's native {@code functions} — and that
 * translation is where sdd's function calling failed by declaration count (measured: one
 * declaration 20/20, six 13/20, nine 0/20, all with fast uniform HTTP 500s). Captured requests from
 * a client that talks to the gateway directly show it accepting {@code tools}/{@code tool_calls}
 * unchanged, with several parallel calls per assistant turn. So the proxy is removable, and what
 * this enum encodes is the three ways that captured traffic differs from what sdd was sending:
 *
 * <ol>
 *   <li>{@code user} and {@code tool} messages carry {@code content} as an array of
 *       {@code {"type":"text","text":…}} parts. {@code system} and {@code assistant} carry a plain
 *       string — the asymmetry is in the traffic, not an invention here.</li>
 *   <li>An assistant turn always carries a {@code content} string, even when its whole payload is
 *       {@code tool_calls}. Omitting the key is legal OpenAI and is what sdd did.</li>
 *   <li>{@code reasoning_content} is a first-class message field, returned by the endpoint and sent
 *       back with the assistant turn it belongs to, rather than {@code <think>} tags inline in
 *       {@code content} (which is what the proxy produced, and which {@link ReasoningContent}
 *       exists to undo).</li>
 * </ol>
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

    /** Whether this role's {@code content} is written as an array of typed parts. */
    boolean partsFor(String role) {
        return this == GIGACHAT && ("user".equals(role) || "tool".equals(role));
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
