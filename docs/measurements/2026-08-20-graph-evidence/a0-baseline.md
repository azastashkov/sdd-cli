# A0 — baseline and go/no-go for the type-level reference graph

Run 2026-08-20 against `~/projects/github/sdd-measure/probe-6repo` (six trading repos, symlinked),
KB `schema_version = 6`, zero model calls, via `PlanFactsHarness` with new `anchor types:` and
`probe classes:` instrumentation. Corpus: `docs/measurements/2026-08-19-explore/specs`.

## Result: the go/no-go FAILED, and for a reason the plan did not anticipate.

### 1. Anchor sets

| case | mode | `result.anchorTypes()` |
|---|---|---|
| case-6-redis-channel | deterministic | `[]` |
| case-6-redis-channel | declared | `[]` |
| case-6-redis-channel-explored | deterministic | `[com.trading.messaging.Channels, com.trading.tiers.ProviderClient]` |
| case-6-redis-channel-explored | declared | same |
| case-7-config-key | both | `[]` |
| case-8-db-table | both | `[]` |

The **bare** case-6 spec has no `## Touchpoints` section, so nothing produces an anchor. A graph
walk from an empty anchor set is a no-op by construction. **Plan metric #1 (bare case-6, 0 → ≥3) is
unreachable by any selection change**, independent of everything below.

### 2. The decisive finding: the target classes are ABSENT from the KB, not truncated

`case-6-redis-channel-explored`, declared mode, probe counts in the composed prompt:

| class | occurrences | rank among rendered type lines |
|---|---|---|
| `TierInvalidationListener` | 4 | **ABSENT** |
| `Channels` | 18 | 1 |
| `ProviderClient` | 9 | 42 |
| `OrdersConfig` | 1 | **ABSENT** |

All four `TierInvalidationListener` occurrences are in the human's own `## Evidence` prose. It
appears in **zero** KB evidence lines. Querying the KB directly:

```
sqlite> SELECT r.name, t.fqcn FROM java_type t JOIN module m ON m.id=t.module_id
        JOIN repo r ON r.id=m.repo_id
        WHERE t.fqcn LIKE '%TierInvalidationListener' OR t.fqcn LIKE '%.OrdersConfig';
(0 rows)
```

Both classes exist in source:

```
trading-candles/services/candle-service/src/main/java/com/trading/candles/TierInvalidationListener.java:23:class TierInvalidationListener {
trading-core/services/auth-service/src/main/java/com/trading/auth/TierInvalidationListener.java:25:class TierInvalidationListener {
trading-core/services/order-service/src/main/java/com/trading/orders/OrdersConfig.java:36:class OrdersConfig {
```

**They are package-private.** `ApiSurfaceExtractor.extract` (`sdd-index/.../source/ApiSurfaceExtractor.java:48-51`)
emits a type only when `isPublic() || PROTECTED || (!isPrivate() && nested in an interface or
annotation)`. A top-level package-private class fails all three and never reaches `java_type`.

### 3. Scale of the blind spot

Top-level package-private types in `src/main/java`, by repo:

| repo | main java files | files declaring a package-private top-level type |
|---|---|---|
| trading-candles | 23 | 6 |
| trading-core | 120 | **44 (37%)** |
| trading-ops | 12 | 0 |
| trading-platform-libs | 104 | 2 |
| trading-product-a | 2 | 0 |
| trading-product-b | 2 | 0 |
| **total** | **263** | **52 (19%)** |

Indexed `java_type` counts corroborate: trading-core 110 rows against 120 main files, candles 18
against 23.

## What this invalidates

The 2026-08-19 conclusion — *"perfect seeding still never names the classes"* — is confirmed as a
symptom but its cause was misattributed. It is not a ranking or budget failure and **not** something
a reference graph fixes. Those classes are not in the knowledge base.

Critically, the planned V7 design makes `type_ref.from_type_id` a real FK into `java_type` and
explicitly accepts that *"only a type that reaches `java_type` can be a source node"*. So the graph
as designed **provably could not surface these classes either** — it would inherit the same blind
spot at both ends of every edge.

## Consequence for the plan

Indexing package-private top-level types is a **prerequisite**, not an alternative, to the graph
work. Until it lands, 19% of this estate's types (37% of trading-core's) can be neither a source nor
a target node, and the graph's own success metrics are unmeasurable.

Encouraging signs that survive: the explored case does produce a real two-element anchor set, and
`ProviderClient` sits at rank 42 — evidence that a distance-based promotion has something to work
with once the corpus is complete.

## Blast radius of the prerequisite (surveyed, not yet implemented)

`is_api` is used in production **only as an ordering key** (`ORDER BY t.is_api DESC` in
`PlanDrafter:424,444` and `WorkOrder:98`) and as a flag in `ChangeSet:98-107`. There is no
`WHERE is_api = 1` filter left in production, so newly indexed package-private types would sort
*after* API types rather than displacing them. Affected: `fts_symbol` corpus size,
`KbEntities.resolveClass`, the golden dump, and — needing care — `ReferenceExtractor`'s
`repoTypeIndex`, which decides whether a reference becomes a `file_ref` or an `api_usage` row.

---

# A-1 — result of indexing top-level package-private types

Change: `ApiSurfaceExtractor.isExtractedType` now admits any top-level type (a top-level
declaration cannot be `private` in Java), `is_api` is gated on the type being declared surface so a
package-private type in a library module is never marked API, and `EXTRACTOR_EPOCH` goes 1 → 2 so a
plain `sdd index` re-extracts rather than short-circuiting (guard at `IndexService.java:308`).

Full suite after the change: **1782 tests, 0 failures** (core 503, index 251, plan 243, agent 172,
cli 613). The only golden-dump movement was `extractor_epoch: 1 → 2` — the fixture estate contains
no package-private top-level type, so it never exercised this path. Closed with two new unit tests
in `ApiSurfaceExtractorTest`.

## KB after re-index (`sdd index --force`, 39.5s, all six repos OK)

| repo | types before | types after | of which is_api |
|---|---|---|---|
| trading-candles | 18 | **24** | 0 |
| trading-core | 110 | **154** | 0 |
| trading-ops | 17 | 17 | 0 |
| trading-platform-libs | 107 | **109** | 107 |
| trading-product-a | 2 | 2 | 0 |
| trading-product-b | 2 | 2 | 0 |

All three previously-invisible classes are now present, all correctly `is_api = 0`.

## Probe counts in the composed prompt (occurrences, and rank among surviving type lines)

| case / mode | class | before | after |
|---|---|---|---|
| case-6-explored, declared | `TierInvalidationListener` | 4, ABSENT | **8, ranks [41, 81]** |
| case-6-explored, declared | `OrdersConfig` | 1, ABSENT | **3, rank [43]** |
| case-6-explored, declared | `Channels` | 18, rank 1 | 18, rank 1 |
| case-6-explored, declared | `ProviderClient` | 9, rank 42 | 9, rank 44 |
| case-6 **bare**, declared | `TierInvalidationListener` | 0, ABSENT | **5, ranks [1, 56]** |

Every rank shown is a *surviving* line — the instrumentation counts what is in the prompt after
truncation. **The plan's headline metric is met: bare case-6 goes 0 → 5 occurrences, target was ≥3.**
It is met with `anchorTypes: []`, i.e. by the indexing fix alone, with no graph involved.

Unchanged where nothing was seeded: all three deterministic-mode prompts are byte-identical
(905 / 816 / 881), so the change does not perturb specs that reach nothing.

## Displacement audit (case-6-explored, declared)

| repo | type lines | added | dropped |
|---|---|---|---|
| trading-platform-libs | 40 → 40 | none | none |
| trading-candles | 18 → **24** | 6 incl. `TierInvalidationListener` | **none** |
| trading-core | 40 → 40 | **28** | **28** |
| ops / product-a / product-b | unchanged | | |

trading-core is at the `TYPE_BUDGET = 40` ceiling, so 28 types were swapped out to admit 28 others.
For *this* spec the trade is favourable — in came `TierInvalidationListener`, `OrdersConfig`,
`ClientEntitlement`, `AuthController`; out went `PayloadKeySpec` and `ChannelTemplates`, and
`PayloadKeySpec` is the very token-collision false positive the 2026-08-19 round complained about.

**But the trade was not made on relevance.** The only ordering is `is_api DESC, fqcn`, so which 28
survive is alphabetical accident. Evidence bytes moved accordingly: trading-core 10018 → 9511,
trading-candles 7121 → 7824, every other repo unchanged.

## What this means for the graph (V7 / `KbRefGraph` / drafter tiering)

Two conclusions, and they point in opposite directions:

1. **The graph is not needed to hit the headline metric.** Indexing alone did it. Any claim that a
   reference graph was required to surface `TierInvalidationListener` is now false.
2. **The case for the graph is nonetheless stronger than before.** Completing the corpus raised
   trading-core from 110 to 154 types competing for the same 40 slots, and displacement at that
   ceiling is now happening blind. A distance-from-anchor signal is exactly what would make the
   swap deliberate instead of alphabetical. The explored case already carries a real two-element
   anchor set (`Channels`, `ProviderClient`) for such a walk to start from.

Note the remaining hard limit, unchanged by this work: the **bare** case-6 still has
`anchorTypes: []`, because its spec declares no touchpoints. A graph cannot help a spec that
anchors nothing — for those, seeding, not selection, is the constraint.
