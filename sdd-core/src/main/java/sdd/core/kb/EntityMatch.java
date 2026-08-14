package sdd.core.kb;

import java.util.Objects;

/**
 * One KB row that matched an entity resolution. {@code detail} describes the matched row
 * (e.g. {@code "GET /api/tiers/{}"}, {@code "com.trading.pricing.core.TierResolver"});
 * {@code source} names the KB table it came from (e.g. {@code "rest_endpoint"}, {@code "java_type"}).
 */
public record EntityMatch(String repo, String detail, String source) {
    public EntityMatch {
        Objects.requireNonNull(repo);
        Objects.requireNonNull(detail);
        Objects.requireNonNull(source);
    }
}
