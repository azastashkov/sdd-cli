# tier-resolution

## Purpose
The `tier-resolution` behaviour of `pricing-core`. This description was generated from sdd specification `SPEC-TIERS` and should be replaced with a durable statement of what this capability is for.

## ADDED Requirements

### Requirement: pricing-core must expose a way to invalidate a tier
`pricing-core` SHALL satisfy R1 of `SPEC-TIERS`: pricing-core must expose a way to invalidate a tier.

Traceability: sdd spec `SPEC-TIERS`, plan version 1, requirement R1.

#### Scenario: A1 — The next resolution returns the new tier
- **WHEN** `SPEC-TIERS` is implemented in `pricing-core`
- **THEN** The next resolution returns the new tier.
