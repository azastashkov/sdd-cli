package sdd.core.config;

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
 */
public record ModelEndpoint(
        String baseUrl,
        String model,
        String apiKey,
        int maxTokens,
        double temperature,
        Duration timeout,
        Map<String, Object> extraBody,
        String apiKeyError) {
    public ModelEndpoint {
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
    }

    /** Pre-{@code apiKeyError} 7-argument shape, kept so every existing construction site (main and
     *  test) keeps compiling untouched: {@code apiKeyError} defaults to null, i.e. no deferred
     *  credential failure. */
    public ModelEndpoint(String baseUrl, String model, String apiKey, int maxTokens, double temperature,
            Duration timeout, Map<String, Object> extraBody) {
        this(baseUrl, model, apiKey, maxTokens, temperature, timeout, extraBody, null);
    }
}
