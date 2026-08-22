# tier-consumption

## Purpose
The `tier-consumption` behaviour of `svc-orders`. This description was generated from sdd specification `SPEC-TIERS` and should be replaced with a durable statement of what this capability is for.

## ADDED Requirements

### Requirement: svc-orders must invalidate on a tier-update event
`svc-orders` SHALL satisfy R2 of `SPEC-TIERS`: svc-orders must invalidate on a tier-update event.

Traceability: sdd spec `SPEC-TIERS`, plan version 1, requirement R2.

#### Scenario: A2 — The estate rebuild is green
- **WHEN** `SPEC-TIERS` is implemented in `svc-orders`
- **THEN** The estate rebuild is green.
