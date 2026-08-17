# Plan-generation fact diagnosis — first pass

**Date:** 2026-08-18 · **Estate:** the 6 trading repos, all on `main` · **Model calls: zero.**

Method borrowed from `docs/superpowers/plans/2026-08-15-retrieval-corpus.md`: reproduce the real
failure on the real estate, write the predictions down first, let the measured failure modes pick
the fix.

## Setup

Probe workspace `~/projects/github/sdd-measure/probe-6repo` — symlinks to the six repos plus a copy
of the estate `sdd.yml`, indexed once with `sdd index --force` (with cards, because `ModelSeeder`
feeds `repo_card` into the stage-B call being measured).

**The live estate KB was deliberately not touched.** `Database.open` applies pending migrations on
every open including read-only paths; `~/projects/github/trading-estate/.sdd/index.db` is still at
`schema_version = 1` and would have been migrated 1→5 as a side effect of measuring it. It is also
stale — its `head_commit` values are the abandoned 2026-08-14 `implement` commits.

Harness: `sdd-plan/src/test/java/sdd/plan/gen/PlanFactsHarness.java`, `@Tag("measure")`, gated on
`SDD_MEASURE_WS`. It calls `PlanDrafter.composeInput` directly — package-private, and the way this
project already inspects a composed prompt (`PlanDrafterTest:85,117,…`). No production code was
added or changed for this measurement.

Each spec runs twice: **deterministic** (seeding model unavailable) and **declared** (a scripted
model returns exactly the repos the author declared in a sidecar `.expect` file — a hypothetically
perfect impact analysis). The second isolates *evidence starvation* from *seeding failure*.

Indexed baseline: 6 repos OK, 41 internal edges, 342 internal type refs, 15 endpoints, 0 clients,
0 kafka roles, 6 cards.

---

## P0 — baseline predictions, all scored

| # | Prediction | Result |
|---|---|---|
| **P0.1** | Only trading-platform-libs has `is_api = 1`, so no service repo contributes a single method signature to any prompt | **CONFIRMED** |
| **P0.2** | candles/ops/product-* fit under `TYPE_BUDGET = 25`; core and platform-libs truncate | **CONFIRMED** |
| **P0.3** | platform-libs exceeds `EVIDENCE_CAP = 4000` and truncates | **CONFIRMED** |
| **P0.4** | `- metric: …` is a parse error, not a blocking question | **CONFIRMED** |

**P0.1** — `PlanDrafter.java:387` filters member evidence with `AND jt.is_api = 1`; `is_api` is set
at `ApiSurfaceExtractor.java:70` as `libraryModule && !fqcn.contains(".internal.")`, and only
platform-libs has LIBRARY modules.

| repo | types | `is_api=1` | members reaching the prompt |
|---|---|---|---|
| trading-platform-libs | 107 | 107 | **395** |
| trading-core | 110 | 0 | **0** |
| trading-candles | 18 | 0 | **0** |
| trading-ops | 17 | 0 | **0** |
| trading-product-a | 2 | 0 | **0** |
| trading-product-b | 2 | 0 | **0** |

**P0.3** — platform-libs: 25 type lines = 3200 chars, 40 member lines = 2469 chars, total 5669 vs a
4000 cap. Measured section length 4039 chars with a `…(truncated)` marker. All four cases truncate.

Measured evidence per repo (identical across cases — it is repo-scoped, not spec-scoped):
platform-libs 4039 · core ~4000 · candles 2744 · ops 1791 · **product-a 292 · product-b 292**.

---

## Case 1a — metric named in prose only: the blocking symptom, reproduced

```
seeds:      []
candidates: [trading-platform-libs(fts: R1 hit: TierUpdateListener),
             trading-core(fts: R1 hit: tierUpdated),
             trading-ops(fts: R2 hit: build),
             trading-candles(fts: R2 hit: AggregatorControl)]
blocking:   ["no seeds: add touchpoints to the spec or check the knowledge base"]
```

The affected set is **empty** and the plan blocks. Two things about the candidates:

- `trading-ops(fts: R2 hit: build)` matched the **English word "build"** in R2's prose
  ("must continue to build"). Free-text seeding fired on prose, not on the metric.
- `trading-platform-libs(fts: R1 hit: TierUpdateListener)` looks right and **is a coincidence**:
  the class name happens to share tokens with the metric name. Scored as a miss.

**Metric literals are not facts anywhere.** The exact string `pricing.tier.updates.received`
returns **0** rows from `fts_symbol` and 0 from `config_property`. In the composed prompt it appears
only on lines 11 / 17 / 21 — the echoed Goal, R1 and A1 — never as evidence.

For metrics whose names do *not* coincide with their owning class, FTS is worse than useless:

| metric (real, in the estate) | owner | FTS top-4 |
|---|---|---|
| `ws.slow.client.closes` | `GatewayWsHandler` | KeyedSerialExecutor, ClientConn, SessionRegistry, LoadWsClient — owner absent (72 hits) |
| `orders.rejected.product_disabled` | `OrderController` (trading-core) | two **trading-candles** aggregator classes first — **wrong repo** (258 hits) |
| `ws.mailbox.overflow` | `GatewayWsHandler` | 2 hits, both `GatewayProperties` getters — owner absent |

The middle row is a direct mechanism for "impact analysis picks wrong repos".

**Case 1b** (same spec + `class: TierUpdateListener`) resolves cleanly, zero blocking questions —
so the seeding gap is closed by *anchoring*, not by the metric being findable.

---

## Case 2 — derived class in another repo: measured, and the result is not what I predicted

Ground truth (audited with `grep -rn "implements .*FixSessionListener" --include=*.java` over
`src/main`): **five** implementors across three repos. With a **perfect** impact analysis:

| implementor | repo | in `java_type`? | rank | budget | in prompt |
|---|---|---|---|---|---|
| `QfjExecutionAdapter` | platform-libs | yes (`is_api=1`) | 22 | 25 | ✅ |
| `CandleMdRejectListener` | candles | yes | ≤18 | 25 | ✅ |
| `QfjMarketDataAdapter` | platform-libs | yes (`is_api=1`) | **26** | 25 | ❌ *by one position* |
| `ExchangeSimulator` | core | yes | **48** | 25 | ❌ |
| `VenueMarketDataSimulator` | core | yes | **65** | 25 | ❌ |

**3 of 5 implementors never reach the model** — and the system prompt tells it to name only what is
in the evidence and to omit rather than guess.

**Every miss is `truncated`, not `absent`.** All three are in `java_type`; they lose the
`ORDER BY is_api DESC, fqcn` window at `TYPE_BUDGET = 25`.

---

## What this does to the candidate fixes

**Subtype identity table — repositioned, not killed, and now much cheaper.** My prediction said
"kill it if the implementors already appear". They do not appear — but they *exist*, so the fix is
not "record the subtype so the class can be found". `PlanDrafter.ranked()` promotes rows whose
simple name is **named in the spec**, and `ExchangeSimulator` is never named in the spec — finding
it is the whole point of the exercise. The subtype fact is the only thing that could promote it
past rank 48. **So it is a ranking input, not a discovery mechanism.** That is a smaller claim and
a smaller change than the schema-first framing assumed.

**Evidence budgets — confirmed, and cheap.** Truncation in 4/4 cases; one implementor lost by a
single position. A per-section budget split is a constant change, not a schema change, and it must
land before any new evidence section is added or the new facts will simply eat the old ones.

**The `is_api` member blackout — confirmed, and independent.** It did *not* cause the case-2 misses
(those are type-level). It is a separate, additive defect: five of six repos contribute zero method
signatures, so no consumer-side member can ever be named or declared.

**Wider FTS corpus — killed on the one-number test.** In every case the closure reached all six
repos regardless, so no FTS candidate ever changed the affected set. The candidates that did fire
were prose noise (`hit: build`). Improving the ranking of a list that changes nothing buys nothing.

**Metric extraction — survives, narrowed.** Case 1b shows anchoring fixes seeding, so the metric
table's value is specifically: let an author name a metric without first knowing which class emits
it. Real, and clearly narrower than "the plan can't be made".

**Git change-set seeding — untested so far.** Case 3 is not yet built.

---

## Affected-set degeneracy — a fact about this estate, not the design

Every case reaches all six repos, because every repo depends on trading-platform-libs. Repo-level
recall is therefore always 1.0 and precision is 1/6–3/6 by construction. **Repo-level
precision/recall is not an informative metric on this estate.** The informative levels are the
annotation and the file/type level.

Related: in case 1 the closure annotates trading-product-a and trading-product-b
`CODE_CHANGE_LIKELY` — while their evidence blocks are **292 characters** (2 types, 0 members). The
plan would ask an agent to make a code change in a repo about which the prompt says almost nothing.

## The declaration-vs-closure diff works

The A0 idea produces a real signal with no new facts required. Case 1a, declared mode:

```
expected-but-not-reached: []
reached-but-not-expected: [trading-candles, trading-core, trading-ops,
                           trading-product-a, trading-product-b]
```

Computed by comparison only — nothing feeds back into `affected`, so M1 holds.

## Incidental finding

`ModelSeeder.seed` (`ModelSeeder.java:69`) catches only `ModelException`. Any other runtime
exception from a model client propagates and aborts `sdd plan` instead of degrading to the
documented deterministic-only path.

## Not yet measured

Case 3 (`--since` / change-set) and case 4 (rebuild-only annotation accuracy) are not built. No
Layer-2 run against the real CLI yet, so blocking-question *rendering*, `plan.json`'s
`repo_steps[].files`, and determinism of the hashed artifact are unmeasured.
