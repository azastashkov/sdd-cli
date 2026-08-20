## 1. Upstream

- [ ] 1.1 Wait for `pricing-core` to land `tier-invalidation-api` in `spec-tier-invalidation-v1` — this repository consumes it

## 2. Implementation

- [ ] 2.1 R2: svc-orders must invalidate the cached tier when it handles a tier-update event.
- [ ] 2.2 Change `src/main/java/com/trading/orders/TierUpdateHandler.java`

## 3. Verification

- [ ] 3.1 Run `./gradlew :svc-orders:test`
- [ ] 3.2 A2: svc-orders no longer serves a stale tier after a tier-update event.
- [ ] 3.3 A3: The full estate rebuild is green.
