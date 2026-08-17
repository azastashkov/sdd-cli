---
id: SPEC-M3B
title: Propagate the pricing-core live re-tier change to its consumers
owner: azastashkov
status: draft
---

## Goal
A change landed in trading-platform-libs in commit 8e54df6 ("live re-tier via
unsub-then-resub + tier.update consumer"). Every repository that builds against
pricing-core must be brought in line with it and rebuilt.

## Background
The change was made in the shared library only. Nobody has yet worked out which
consumers are affected or what they must do.

## Requirements
- R1: Determine what commit 8e54df6 changed in trading-platform-libs and align every consumer with it.
- R2: Every repository that builds against the changed library must continue to build.

## Acceptance Criteria
- A1: Consumers behave correctly against the new live re-tier flow.
- A2: The full estate rebuild is green.

## Touchpoints
- class: TierUpdateListener
- class: SubscriptionReconciler
