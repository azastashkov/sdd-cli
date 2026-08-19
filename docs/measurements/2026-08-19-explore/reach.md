# Estate reach — fts_symbol vs estate search

repos indexed: [trading-candles, trading-core, trading-ops, trading-platform-libs, trading-product-a, trading-product-b]

## `tier.update`

fts_symbol: 30 hits in [trading-platform-libs, trading-core, trading-candles]  (0 javadoc-only)
  - com.trading.messaging.Channels
  - com.trading.gateway.ProtocolJson
  - com.trading.messaging.RedisPublishers
  - com.trading.pricing.core.PricingCoreAutoConfiguration
  - com.trading.model.TierUpdateEvent

estate search: 27 files in [trading-candles, trading-core, trading-ops, trading-platform-libs]
  - trading-candles/services/candle-service/src/main/java/com/trading/candles/CandlesConfig.java
  - trading-candles/services/candle-service/src/main/java/com/trading/candles/TierInvalidationListener.java
  - trading-candles/services/candle-service/src/test/java/com/trading/candles/rest/CandleControllerIT.java
  - trading-core/services/auth-service/src/main/java/com/trading/auth/AuthWebConfig.java
  - trading-core/services/auth-service/src/main/java/com/trading/auth/TierInvalidationListener.java

## `tier.lvc.map`

fts_symbol: 30 hits in [trading-platform-libs, trading-core]  (0 javadoc-only)
  - com.trading.messaging.Channels
  - com.trading.model.TierCacheMeta
  - com.trading.messaging.Channels
  - com.trading.tiers.rest.TierReadService.MappingsResult
  - com.trading.tiers.repo.TierMappingRepository

estate search: 19 files in [trading-core, trading-ops, trading-platform-libs]
  - trading-core/README.md
  - trading-core/services/tier-service/build.gradle
  - trading-core/services/tier-service/src/main/java/com/trading/tiers/ProviderClient.java
  - trading-core/services/tier-service/src/main/java/com/trading/tiers/TierCacheSweeper.java
  - trading-core/services/tier-service/src/main/java/com/trading/tiers/rest/TierReadService.java

## `sweep-interval`

fts_symbol: 23 hits in [trading-core, trading-candles, trading-platform-libs]  (3 javadoc-only)
  - com.trading.orders.OrdersProperties
  - com.trading.tiers.TiersProperties.Cache
  - com.trading.orders.OrdersProperties
  - com.trading.orders.OrdersProperties
  - com.trading.tiers.TiersProperties.Cache

estate search: 12 files in [trading-core, trading-ops]
  - trading-core/services/order-service/src/main/resources/application.yml
  - trading-core/services/order-service/src/test/java/com/trading/orders/OrderServiceIT.java
  - trading-core/services/order-service/src/test/java/com/trading/orders/ProdFixWiringIT.java
  - trading-core/services/order-service/src/test/java/com/trading/orders/ProductDisabledIT.java
  - trading-core/services/order-service/src/test/java/com/trading/orders/SendFailureIT.java

## `refdata.clients`

fts_symbol: 30 hits in [trading-core, trading-platform-libs, trading-candles]  (4 javadoc-only)
  - com.trading.tiers.RefdataSeeder
  - com.trading.web.EntitlementService  [javadoc only]
  - com.trading.pricing.core.JdbcTierResolver  [javadoc only]
  - com.trading.mock.tiers.TierProviderProperties  [javadoc only]
  - com.trading.tiers.TiersConfig  [javadoc only]

estate search: 24 files in [trading-candles, trading-core, trading-ops, trading-platform-libs, trading-product-a, trading-product-b]
  - trading-candles/services/candle-service/src/test/java/com/trading/candles/rest/CandleControllerIT.java
  - trading-candles/services/candle-service/src/test/resources/refdata-init.sql
  - trading-core/docker-compose.core.yml
  - trading-core/services/auth-service/src/main/resources/db/migration/V1__refdata.sql
  - trading-core/services/auth-service/src/main/resources/db/migration/V2__seed.sql

## `TierCacheSweeper`

fts_symbol: 5 hits in [trading-core]  (1 javadoc-only)
  - com.trading.tiers.TierCacheSweeper
  - com.trading.tiers.TierCacheSweeper
  - com.trading.tiers.TierCacheSweeper
  - com.trading.tiers.TierCacheSweeper
  - com.trading.tiers.TiersProperties.Cache  [javadoc only]

estate search: 11 files in [trading-core, trading-ops]
  - trading-core/services/tier-service/build.gradle
  - trading-core/services/tier-service/src/main/java/com/trading/tiers/TierCacheSweeper.java
  - trading-core/services/tier-service/src/main/java/com/trading/tiers/TiersConfig.java
  - trading-core/services/tier-service/src/main/java/com/trading/tiers/TiersProperties.java
  - trading-core/services/tier-service/src/main/java/com/trading/tiers/rest/TierReadService.java

| term | fts repos | P | R | search repos | P | R | cite |
|---|---|---|---|---|---|---|---|
| `tier.update` | 3 [trading-platform-libs, trading-core, trading-candles] | 100% | 100% | 4 [trading-candles, trading-core, trading-ops, trading-platform-libs] | 75% | 100% | `trading-candles/services/candle-service/src/main/java/com/trading/candles/CandlesConfig.java` |
| `tier.lvc.map` | 2 [trading-platform-libs, trading-core] | 100% | 100% | 3 [trading-core, trading-ops, trading-platform-libs] | 67% | 100% | `trading-core/services/tier-service/src/main/java/com/trading/tiers/ProviderClient.java` |
| `sweep-interval` | 3 [trading-core, trading-candles, trading-platform-libs] | 33% | 100% | 2 [trading-core, trading-ops] | 50% | 100% | `trading-core/services/order-service/src/main/resources/application.yml` |
| `refdata.clients` | 3 [trading-core, trading-platform-libs, trading-candles] | 67% | 100% | 6 [trading-candles, trading-core, trading-ops, trading-platform-libs, trading-product-a, trading-product-b] | 33% | 100% | `trading-core/services/auth-service/src/main/resources/db/migration/V1__refdata.sql` |
| `TierCacheSweeper` | 1 [trading-core] | 100% | 100% | 2 [trading-core, trading-ops] | 50% | 100% | `trading-core/services/tier-service/src/main/java/com/trading/tiers/TierCacheSweeper.java` |
