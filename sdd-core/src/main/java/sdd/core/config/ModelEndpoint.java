package sdd.core.config;

import sdd.core.http.TlsConfig;

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
        TlsConfig tls) {
    public ModelEndpoint {
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
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
