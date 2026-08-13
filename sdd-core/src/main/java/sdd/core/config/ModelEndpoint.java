package sdd.core.config;

import java.time.Duration;
import java.util.Map;

public record ModelEndpoint(
        String baseUrl,
        String model,
        String apiKey,
        int maxTokens,
        double temperature,
        Duration timeout,
        Map<String, Object> extraBody) {
    public ModelEndpoint {
        extraBody = extraBody == null ? Map.of() : Map.copyOf(extraBody);
    }
}
