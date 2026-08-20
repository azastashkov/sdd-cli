## Context
This is one repository's slice of `spec-tier-invalidation-v1`. Tier updates consumed by pricing-core do not take effect until the service restarts, because the resolved tier is cached for the lifetime of the process.

## Goals / Non-Goals

**Goals:**
- R1: pricing-core must expose a way to invalidate a cached client tier so a tier update takes effect without a restart.

**Non-Goals:**
- Changing how tiers are computed.
- R2, which `svc-orders` covers in this change

## Decisions

### `tier-invalidation-api` — java-api, binary-compatible, provided to `svc-orders`

```
TierResolver gains:
  invalidate(clientId: String): void
resolveTier(String): ClientTier
```

Declared members, re-extracted from the implementation and checked:
- `com.trading.pricing.core.TierResolver#invalidate(String): void`

## Risks / Trade-offs
- `tier-invalidation-api` is declared binary-compatible: consumers build against this repository, so removing or re-signing an existing public member breaks them.
- C1: No schema change to the pricing database.
- Release action `minor`: consumers must be re-pinned once this lands.

## Open Questions
- None recorded.
