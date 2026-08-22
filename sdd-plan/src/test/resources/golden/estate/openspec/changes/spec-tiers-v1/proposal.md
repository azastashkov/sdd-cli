# Invalidate cached client tiers

## Why
Tier updates do not take effect until the service restarts.

Pricing caches the resolved tier for the process lifetime.

This change spans 2 repositories, tracked under the shared change id `spec-tiers-v1`. Each affected repository receives its own change directory with that same id when the plan is implemented.

## What Changes
Add invalidate() and call it on the event.

- `pricing-core`: covers R1, publishing a `minor` bump.
- `svc-orders`: covers R2.

## Capabilities

### New Capabilities
- `tier-consumption`: one repository's behaviour area in this change. Generated from sdd specification `SPEC-TIERS`; rename it to a durable behaviour area before applying.
- `tier-resolution`: one repository's behaviour area in this change. Generated from sdd specification `SPEC-TIERS`; rename it to a durable behaviour area before applying.

## Impact
- Repositories: `pricing-core`, `svc-orders`.
- Execution order: `pricing-core` -> `svc-orders`.
- `pricing-core` provides `tier-api` (java-api) to `svc-orders`.
- Generated from sdd specification `SPEC-TIERS`, plan version 1.
- `sdd plan approve` writes the machine-readable estate — affected set, execution order, dependency edges, contracts — to `estate.yaml` in this directory. OpenSpec has nowhere to put any of it.
- a drafter note
