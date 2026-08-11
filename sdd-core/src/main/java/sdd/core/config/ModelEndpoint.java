package sdd.core.config;

import java.time.Duration;

public record ModelEndpoint(
        String baseUrl,
        String model,
        String apiKey,
        int maxTokens,
        double temperature,
        Duration timeout) {}
