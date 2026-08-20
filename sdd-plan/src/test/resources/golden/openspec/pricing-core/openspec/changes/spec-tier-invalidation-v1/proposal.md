# Invalidate cached client tiers when a tier update arrives

## Why
Tier updates consumed by pricing-core do not take effect until the service restarts, because the resolved tier is cached for the lifetime of the process.

This repository is one slice of a change that spans 2 repositories, tracked under the shared change id `spec-tier-invalidation-v1`. Every repository it touches carries a change directory with that same id; the shared id is the only link between them.

## What Changes
Add an invalidate(clientId) entry point to TierResolver and drop the memoized entry in JdbcTierResolver. Keep the existing resolveTier signature untouched.

- Expose the `tier-invalidation-api` (java-api) interface consumed by `svc-orders`.
- Change `src/main/java/com/trading/pricing/core/JdbcTierResolver.java`.
- Publish a `minor` version bump.

## Capabilities

### New Capabilities
- `tier-resolution`: The `tier-resolution` behaviour of `pricing-core`. This description was generated from sdd specification `SPEC-TIER-INVALIDATION` and should be replaced with a durable statement of what this capability is for.

## Impact
- Repositories in `spec-tier-invalidation-v1`: `pricing-core` (this one), `svc-orders`.
- Execution order: `pricing-core` -> `svc-orders`.
- Release: `minor`.
- Generated from sdd plan `SPEC-TIER-INVALIDATION` version 1, against `a1b2c3d4`.
- If this repository has no `openspec/` project yet, run `openspec init` before applying this change.
