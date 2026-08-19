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

## Touchpoints
- class: com.trading.messaging.Channels
- class: com.trading.tiers.ProviderClient
- repo: trading-candles

## Evidence
- The channel name `tier.update` is returned by Channels.tierUpdate() in the shared messaging library — trading-platform-libs/libs/common-messaging/src/main/java/com/trading/messaging/Channels.java:66
- ProviderClient publishes a TierUpdateEvent on tier.update after each committed mapping upsert — trading-core/services/tier-service/src/main/java/com/trading/tiers/ProviderClient.java:32
- auth-service subscribes with TierInvalidationListener.onMessage, which runs inline on the Lettuce pub/sub thread — trading-core/services/auth-service/src/main/java/com/trading/auth/TierInvalidationListener.java:38
- candle-service has its own TierInvalidationListener.onMessage on the same channel — trading-candles/services/candle-service/src/main/java/com/trading/candles/TierInvalidationListener.java:36
- order-service deliberately has no tier.update listener, so it must not be changed — trading-core/services/order-service/src/main/java/com/trading/orders/OrdersConfig.java:51
