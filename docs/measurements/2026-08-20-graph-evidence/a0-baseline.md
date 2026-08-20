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

---

# A4/A6 — the reference graph, and the traversal rule the measurement corrected

## The approved traversal rule was wrong

The plan specified: hop 1 = inbound ∪ outbound ∪ hierarchy, hops 2..N = **inbound only**, on the
reasoning that outbound-from-outbound leads into the JDK-adjacent world. Measured against the real
estate, that rule provably misses the case the graph exists for. The path is:

```
Channels  <--inbound--  AuthWebConfig  --outbound-->  TierInvalidationListener
```

Neither listener references `Channels` at all — a configuration class wires the two together
(`AuthWebConfig` and `CandlesConfig` each import `Channels` and instantiate their listener). An
inbound-only walk past hop 1 sees the wiring and never the components, which in a Spring estate is
the normal shape rather than an exception.

The concern that motivated inbound-only is answered by a **different filter than direction**:
expansion only crosses an edge whose far end is itself an indexed type. On the probe estate that is
1566 of 3155 edges, and the two most-referenced things in the entire corpus —
`org.slf4j.Logger` (36 inbound) and `io.micrometer.core.instrument.Counter` (19) — have no
`java_type` row, so they are leaves that no amount of outbound walking can traverse.

## Depth: 2, not 3

Bidirectional expansion from the two real anchors, restricted to indexed types:

| depth | new | cumulative | % of the 308 indexed types |
|---|---|---|---|
| 1 | 12 | 14 | 4% |
| 2 | 112 | 126 | 40% |
| 3 | 133 | 259 | **84%** |
| 4 | 18 | 277 | 89% |

A signal that selects 84% of the estate is not a signal, so `MAX_DEPTH = 2`. Both
`TierInvalidationListener`s **and** `com.trading.orders.OrdersConfig` — the class documenting the
service the spec says must be left alone — sit at distance exactly 2. Two is where useful reach and
the last useful discrimination coincide.

`MAX_FRONTIER = 200` is not exercised here: the estate's highest inbound degree is 53
(`com.trading.model.SecurityType`). It exists for the 53-repo case and names every truncation.

## Graph shape on the probe estate

3155 rows; 1566 between two indexed types; 558 distinct targets.
By kind: TYPE 1176, IMPORT 1087, CALL 853, EXTENDS 39.

## Effect on the composed prompt

Every spec that anchors nothing composes **byte-identically** — case-6 bare, case-7 and case-8, in
both modes, all `cmp`-identical to the pre-graph run. Only the two anchored prompts moved.

Row counts held exactly (platform-libs 40→40, core 40→40, candles 24→24, ops 8→8), so this is pure
re-selection rather than budget drift. What was re-selected, for a spec about tier-update fan-out:

| repo | dropped | admitted |
|---|---|---|
| trading-platform-libs | the whole FIX surface — `FixTags`, `ExecutionFixListener`, `QfjExecutionAdapter`, `SessionSettingsFactory`, `ExecutionReportParser`, … | `TierResolver`, `TierUpdateListener`, `TierCacheMeta`, `JdbcTierResolver`, `SubscriptionReconciler`, `PricingCoreAutoConfiguration` |
| trading-core | admin/auth CRUD — `LoginRequest`, `LoginResponse`, `GroupsResponse`, `RemotesResponse`, `UserRepository`, `MfeView`, … | `TiersConfig`, `TiersProperties`, `TierMappingStore`, `TierProviderProperties`, `TierFeedServer`, `GatewayConfig`, `MappingStore` |

Prompt size 30710 → 32302 bytes (+5%), from longer names and paths in the newly selected rows;
per-section caps unchanged and still enforced.

**This is the honest statement of the win.** The probe classes were already fixed by the indexing
change and sit at tier 0 (position 1 in trading-core and trading-candles either way). What the graph
buys is *the other 39 slots*: before it, the swap at the `TYPE_BUDGET = 40` ceiling was decided by
`is_api DESC, fqcn` — alphabetical accident. Now it is decided by distance from what the task is
anchored on.

## A bug the tests caught

`TIERS` was first sized `MAX_DEPTH + 2`, which left the outermost graph distance sharing a tier with
the types no anchor reaches at all — so the last hop bought nothing. `PlanDrafterTest`'s
promote-a-type-at-exactly-MAX_DEPTH case failed on it. Now `MAX_DEPTH + 3`, and that test pins it.

---

# A5 — HierarchyLinker: not built, deliberately

The plan's task A5 was to build a `HierarchyLinker` filling `type_supertype.supertype_module_id`,
on two arguments: it would bound the graph traversal, and it would make
`SupertypeResolver.java:64`'s comment — which describes a `HierarchyLinker` that has never
existed — true.

Both arguments failed on inspection.

**It would not bound anything.** `type_ref` carries `EXTENDS` edges, so an undirected walk already
traverses the hierarchy; `KbRefGraph` needs no separate hierarchy hop and does not have one.

**It would add a second unread column rather than remove the first.** `supertype_module_id` appears
exactly once in the entire tree — its own `CREATE TABLE` in `V6__type_hierarchy.sql`. It is written
by nobody and read by nobody. Filling it with no consumer is precisely the failure this codebase
names repeatedly (`api_usage.ref_kind`, written by one site and read by none since V1) and that this
same session corrected in V7's comment.

So the other half of the plan's own instruction was taken: **the false sentence was removed.** The
comment now records what actually happens — the `SAME_PACKAGE` guess is unchecked, which is safe in
the direction that matters, because `KbHierarchy` joins `supertype_fqcn` by name and a wrong guess
names a type that exists nowhere. It costs a missed subtype, never an invented one. Re-resolving the
guess and rewriting `supertype_fqcn` is what would risk asserting a hierarchy edge the source does
not have, and would make `resolution` a lie about how the row was arrived at.

**Carried, with its trigger:** `supertype_module_id` remains dead schema. Remove it, or give it a
reader, the next time `type_supertype` is touched — the trigger is any change that needs to know
whether a supertype is inside the estate.
