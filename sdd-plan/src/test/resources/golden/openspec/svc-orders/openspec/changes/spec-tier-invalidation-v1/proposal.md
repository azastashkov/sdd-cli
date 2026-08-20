# Invalidate cached client tiers when a tier update arrives

## Why
Tier updates consumed by pricing-core do not take effect until the service restarts, because the resolved tier is cached for the lifetime of the process.

This repository is one slice of a change that spans 2 repositories, tracked under the shared change id `spec-tier-invalidation-v1`. Every repository it touches carries a change directory with that same id; the shared id is the only link between them.

## What Changes
Call TierResolver.invalidate from the tier-update handler.

- Consume the `tier-invalidation-api` (java-api) interface from `pricing-core`.
- Change `src/main/java/com/trading/orders/TierUpdateHandler.java`.

## Capabilities

### New Capabilities
- `tier-consumption`: The `tier-consumption` behaviour of `svc-orders`. This description was generated from sdd specification `SPEC-TIER-INVALIDATION` and should be replaced with a durable statement of what this capability is for.

## Impact
- Repositories in `spec-tier-invalidation-v1`: `pricing-core`, `svc-orders` (this one).
- Execution order: `pricing-core` -> `svc-orders`.
- Depends on `pricing-core` landing `tier-invalidation-api` first; do not implement this repository before it.
- Release: `none`.
- Generated from sdd plan `SPEC-TIER-INVALIDATION` version 1, against `b2c3d4e5`.
- If this repository has no `openspec/` project yet, run `openspec init` before applying this change.
