---
id: SPEC-M1A
title: Rename the tier-update counter emitted by pricing-core
owner: azastashkov
status: draft
---

## Goal
The counter `pricing.tier.updates.received` is misnamed: it counts tier-update
messages consumed, not received, and dashboards built on it read wrong. Rename it
to `pricing.tier.updates.consumed` and keep every consumer of the shared library
building.

## Requirements
- R1: Rename the metric `pricing.tier.updates.received` to `pricing.tier.updates.consumed` where it is emitted.
- R2: Every repository that builds against the changed shared library must continue to build.

## Acceptance Criteria
- A1: No source in the estate emits `pricing.tier.updates.received` any more.
- A2: The full estate rebuild is green.
