## 1. pricing-core

- [ ] 1.1 Provide `tier-api` for the repositories that consume it
- [ ] 1.2 R1: pricing-core must expose a way to invalidate a tier.
- [ ] 1.3 Change `src/main/java/TierResolver.java`
- [ ] 1.4 Run `:pricing-core:test`
- [ ] 1.5 Apply a `minor` version bump

## 2. svc-orders

- [ ] 2.1 Consume `tier-api` once its provider has landed
- [ ] 2.2 R2: svc-orders must invalidate on a tier-update event.
- [ ] 2.3 Change `src/main/java/Handler.java`
- [ ] 2.4 Run `:svc-orders:test`
