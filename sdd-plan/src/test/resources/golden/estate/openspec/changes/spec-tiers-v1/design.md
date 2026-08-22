## Context
The estate-wide view of `spec-tiers-v1`.

Touchpoints the specification named, resolved against the knowledge base:
- repo: `pricing-core`

Evidence:
- redis channel — pricing-core/src/main/java/Q.java:88

## Goals / Non-Goals

**Goals:**
- R1: pricing-core must expose a way to invalidate a tier.
- R2: svc-orders must invalidate on a tier-update event.

**Non-Goals:**
- Changing how tiers are computed.

## Decisions

### `tier-api` — java-api, binary-compatible, provided by `pricing-core`

```
TierResolver gains invalidate(String): void
```

Declared members:
- `com.acme.TierResolver#invalidate(String): void`

## Risks / Trade-offs
- C1: No schema change to the pricing database.

## Open Questions
- spec Q1: Who owns the tier config?
- Q1 [blocking]: Which tenant?
