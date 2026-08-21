package sdd.core.config;

import sdd.core.http.TlsConfig;
import sdd.core.llm.ToolCallStyle;
import sdd.core.llm.WireFormat;

import java.time.Duration;
import java.util.Map;

/**
 * {@code apiKeyError}: when {@code sdd.yml}'s {@code api_key} references an unset {@code ${VAR}},
 * {@link ConfigLoader} does not fail the whole config load over it — a read-only Gate-2 command
 * (review, status, clean) never touches a model at all, so it would otherwise be blocked by a
 * credential it will never use. Instead {@code apiKey} is left null here and the message that
 * would have been thrown is captured in {@code apiKeyError}; {@code HttpChatModel} and
 * {@code EndpointProbe} are the only two consumers of {@code apiKey}, and each raises it — with
 * this exact text — at the point they are about to actually use the endpoint.
 *
 * <p>{@code tls}: an endpoint's {@code models.<name>.tls} block (mutual-TLS client-certificate
 * auth), parsed by {@link ConfigLoader} and consumed by {@code HttpChatModel}/{@code EndpointProbe}
 * the same way {@code apiKey} is — via {@code sdd.core.http.HttpClients#buildClient}. Null is the
 * common case (every endpoint that authenticates with only {@code api_key}, which is every
 * existing workspace); an endpoint may carry {@code api_key}, {@code tls}, both, or neither, since
 * the two schemes are independent and this feature does not touch the {@code api_key} path at all.
 * {@code TlsConfig} already carries its own deferred-credential field ({@code keyPasswordError})
 * for an unset {@code tls.key_password} {@code ${VAR}}, following exactly the {@code apiKeyError}
 * idiom above — see {@code TlsConfig}'s javadoc.
 *
 * <p>{@code wire}: which JSON dialect this endpoint's {@code /chat/completions} speaks, from
 * {@code models.<name>.wire}. Never null — {@link WireFormat#OPENAI} is the default and is the
 * shape sdd has always sent, so an endpoint that predates this setting is byte-identical on the
 * wire. {@code gigachat} selects the shape the corp GigaChat gateway is observed to receive from
 * clients that reach it without the {@code gpt2giga} proxy; see {@link WireFormat} for what
 * differs and why. Independent of {@code apiKey} and {@code tls}: how an endpoint is authenticated
 * says nothing about how it wants its messages spelled.
 *
 * <p>{@code toolCallStyle}: how this endpoint hands back a tool call, from
 * {@code models.<name>.tool_calls}. Never null — {@link ToolCallStyle#NATIVE} is the default and
 * is what every OpenAI-compatible endpoint claims. {@code text} is for a gateway that accepts
 * {@code tools} and then returns the call as ordinary content; see {@link ToolCallStyle} for the
 * measurement that forced it. Independent of {@code wire} — the gateway that needed this is on
 * {@code wire: openai}, which is the proof the two axes move separately.
 */
public record ModelEndpoint(
        String baseUrl,
        String model,
        String apiKey,
        int maxTokens,
        double temperature,
        Duration timeout,
        Map<String, Object> extraBody,
        String apiKeyError,
        TlsConfig tls,
        WireFormat wire,
        ToolCallStyle toolCallStyle) {
    public ModelEndpoint {
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
        wire = wire == null ? WireFormat.OPENAI : wire;
        toolCallStyle = toolCallStyle == null ? ToolCallStyle.NATIVE : toolCallStyle;
    }

    /**
     * The same endpoint pointed at a different model id, for a diagnostic that wants to try
     * several without rewriting {@code sdd.yml}.
     *
     * <p>Everything else is carried over deliberately — base URL, TLS, wire, tool-call style,
     * budgets — because the question being asked is which MODEL behaves differently on a transport
     * already known to work. Changing anything else would confound that.
     */
    public ModelEndpoint withModel(String replacement) {
        return new ModelEndpoint(baseUrl, replacement, apiKey, maxTokens, temperature, timeout,
                extraBody, apiKeyError, tls, wire, toolCallStyle);
    }

    /** Pre-{@code toolCallStyle} 10-argument shape, kept so every existing construction site (main
     *  and test) keeps compiling untouched: it defaults to {@link ToolCallStyle#NATIVE}, i.e. the
     *  only behaviour that existed before this setting. */
    public ModelEndpoint(String baseUrl, String model, String apiKey, int maxTokens, double temperature,
            Duration timeout, Map<String, Object> extraBody, String apiKeyError, TlsConfig tls,
            WireFormat wire) {
        this(baseUrl, model, apiKey, maxTokens, temperature, timeout, extraBody, apiKeyError, tls,
                wire, ToolCallStyle.NATIVE);
    }

    /** Pre-{@code wire} 9-argument shape, kept so every existing construction site (main and test)
     *  keeps compiling untouched: {@code wire} defaults to {@link WireFormat#OPENAI}, i.e. the
     *  request shape sdd sent before this setting existed. */
    public ModelEndpoint(String baseUrl, String model, String apiKey, int maxTokens, double temperature,
            Duration timeout, Map<String, Object> extraBody, String apiKeyError, TlsConfig tls) {
        this(baseUrl, model, apiKey, maxTokens, temperature, timeout, extraBody, apiKeyError, tls,
                WireFormat.OPENAI);
    }

    /** Pre-{@code tls} 8-argument shape, kept so every existing construction site (main and test)
     *  keeps compiling untouched: {@code tls} defaults to null, i.e. no client-certificate
     *  configuration — same pattern as the 7-argument overload below for {@code apiKeyError}. */
    public ModelEndpoint(String baseUrl, String model, String apiKey, int maxTokens, double temperature,
            Duration timeout, Map<String, Object> extraBody, String apiKeyError) {
        this(baseUrl, model, apiKey, maxTokens, temperature, timeout, extraBody, apiKeyError, null);
    }

    /** Pre-{@code apiKeyError} 7-argument shape, kept so every existing construction site (main and
     *  test) keeps compiling untouched: {@code apiKeyError} defaults to null, i.e. no deferred
     *  credential failure. */
    public ModelEndpoint(String baseUrl, String model, String apiKey, int maxTokens, double temperature,
            Duration timeout, Map<String, Object> extraBody) {
        this(baseUrl, model, apiKey, maxTokens, temperature, timeout, extraBody, null);
    }
}
