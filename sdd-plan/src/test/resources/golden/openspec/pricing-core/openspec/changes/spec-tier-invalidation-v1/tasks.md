## 1. Contracts

- [ ] 1.1 Expose `tier-invalidation-api` (java-api) consumed by `svc-orders`
- [ ] 1.2 Provide `com.trading.pricing.core.TierResolver#invalidate(String): void`
- [ ] 1.3 Keep `tier-invalidation-api` binary-compatible — do not remove or re-sign existing public members

## 2. Implementation

- [ ] 2.1 R1: pricing-core must expose a way to invalidate a cached client tier so a tier update takes effect without a restart.
- [ ] 2.2 Change `src/main/java/com/trading/pricing/core/JdbcTierResolver.java`

## 3. Verification

- [ ] 3.1 Run `./gradlew :pricing-core:test`
- [ ] 3.2 A1: A tier update for a client makes the next resolution return the new tier without a restart.
- [ ] 3.3 A3: The full estate rebuild is green.

## 4. Release

- [ ] 4.1 Apply a `minor` version bump
