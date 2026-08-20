## Context
This is one repository's slice of `spec-tier-invalidation-v1`. Tier updates consumed by pricing-core do not take effect until the service restarts, because the resolved tier is cached for the lifetime of the process.

## Goals / Non-Goals

**Goals:**
- R2: svc-orders must invalidate the cached tier when it handles a tier-update event.

**Non-Goals:**
- Changing how tiers are computed.
- R1, which `pricing-core` covers in this change

## Decisions

### `tier-invalidation-api` — java-api, binary-compatible, consumed from `pricing-core`

```
TierResolver gains:
  invalidate(clientId: String): void
resolveTier(String): ClientTier
```

Declared members, re-extracted from the implementation and checked:
- `com.trading.pricing.core.TierResolver#invalidate(String): void`

## Risks / Trade-offs
- C1: No schema change to the pricing database.

## Open Questions
- None recorded.
