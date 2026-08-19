---
id: SPEC-E6
title: Stop invalidating entitlement caches on every tier change
owner: azastashkov
status: draft
---

## Goal
The `tier.update` fan-out is too broad: every service holding an entitlement cache drops it
whenever any client's tier changes, so a single change invalidates unrelated clients. Narrow the
notification so a listener can decide whether the change affects it.

## Requirements
- R1: The `tier.update` payload must carry enough to identify which client changed.
- R2: Every service that listens on `tier.update` must invalidate only the affected client.
- R3: The publisher and every listener must stay in step — no listener may be left decoding an old payload.

## Acceptance Criteria
- A1: A tier change for one client leaves other clients' cached entitlements intact.
- A2: The estate rebuilds green.
