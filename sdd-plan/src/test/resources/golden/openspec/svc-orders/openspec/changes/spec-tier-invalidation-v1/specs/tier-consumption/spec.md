# tier-consumption

## Purpose
The `tier-consumption` behaviour of `svc-orders`. This description was generated from sdd specification `SPEC-TIER-INVALIDATION` and should be replaced with a durable statement of what this capability is for.

## ADDED Requirements

### Requirement: svc-orders must invalidate the cached tier when it handles a tier-update event
`svc-orders` SHALL satisfy R2 of `SPEC-TIER-INVALIDATION`: svc-orders must invalidate the cached tier when it handles a tier-update event.

Traceability: sdd spec `SPEC-TIER-INVALIDATION`, plan version 1, requirement R2.

#### Scenario: A2 — svc-orders no longer serves a stale tier after a tier-update event
- **WHEN** `SPEC-TIER-INVALIDATION` is implemented in `svc-orders`
- **THEN** svc-orders no longer serves a stale tier after a tier-update event.

#### Scenario: A3 — The full estate rebuild is green
- **WHEN** `SPEC-TIER-INVALIDATION` is implemented in `svc-orders`
- **THEN** The full estate rebuild is green.
