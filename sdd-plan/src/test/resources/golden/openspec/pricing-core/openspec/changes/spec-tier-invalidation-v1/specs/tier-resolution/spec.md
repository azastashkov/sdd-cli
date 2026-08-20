# tier-resolution

## Purpose
The `tier-resolution` behaviour of `pricing-core`. This description was generated from sdd specification `SPEC-TIER-INVALIDATION` and should be replaced with a durable statement of what this capability is for.

## ADDED Requirements

### Requirement: pricing-core must expose a way to invalidate a cached client tier so a tier…
`pricing-core` SHALL satisfy R1 of `SPEC-TIER-INVALIDATION`: pricing-core must expose a way to invalidate a cached client tier so a tier update takes effect without a restart.

Traceability: sdd spec `SPEC-TIER-INVALIDATION`, plan version 1, requirement R1.

#### Scenario: A1 — A tier update for a client makes the next resolution return the new tier…
- **WHEN** `SPEC-TIER-INVALIDATION` is implemented in `pricing-core`
- **THEN** A tier update for a client makes the next resolution return the new tier without a restart.

#### Scenario: A3 — The full estate rebuild is green
- **WHEN** `SPEC-TIER-INVALIDATION` is implemented in `pricing-core`
- **THEN** The full estate rebuild is green.
