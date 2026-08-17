---
id: SPEC-101
title: Tier-based spread adjustment for streamed prices
owner: azastashkov
status: draft
---

## Goal
Prices streamed to clients must include a per-tier spread adjustment so that
higher loyalty tiers receive tighter spreads.

## Requirements
- R1: Resolve the client tier for every price subscription using the existing tier mappings.
- R2: Apply a configurable per-tier spread adjustment in the pricing pipeline before publication.
- R3: Expose the effective spread configuration for verification through the admin surface.

## Acceptance Criteria
- A1: A subscription from a tier-2 client receives prices with the tier-2 spread applied.
- A2: Changing a tier mapping takes effect for new subscriptions without restart.

## Constraints
- C1: No change to the wire format of published price messages.

## Touchpoints
- repo: trading-product-a
- endpoint: GET /api/tiers/mappings
- class: JdbcTierResolver
