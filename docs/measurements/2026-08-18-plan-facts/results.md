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

## Case 3 — the change-set half: `--since` is justified, and the mapping is exact

Ground truth is a real commit: `8e54df6` in trading-platform-libs, *"live re-tier via
unsub-then-resub + tier.update consumer"* — the commit that introduced `TierUpdateListener` and the
`pricing.tier.updates.received` counter. 7 files changed, 3 of them main source.

**The gating risk is clear.** `java_type.file_path` is repo-root-relative and forward-slashed, and
**0** rows estate-wide contain `../`, `/private/var`, or an absolute path. It joins directly with
JGit `DiffEntry` paths, so the change-set mapping needs no normalization on this estate.

**Today, case 3a blocks.** The spec names the commit sha in prose; the pipeline cannot use it:

```
seeds:      []
blocking:   ["no seeds: add touchpoints to the spec or check the knowledge base"]
candidates: [trading-core(fts: R1 hit: GenericKey),
             trading-platform-libs(fts: R1 hit: FeedStatusPublisher),
             trading-ops(fts: R2 hit: build), …]
```

Prose seeding is noise again — `hit: build` from "must continue to build", `hit: GenericKey`.

**The prototype reproduces ground truth exactly** (`ChangeSetProbe`, JGit tree-to-tree over
`8e54df6^..8e54df6`, mapped onto `java_type.file_path`):

| changed path | mapped |
|---|---|
| `…/PricingCoreAutoConfiguration.java` (M) | `com.trading.pricing.core.PricingCoreAutoConfiguration` `[api]` |
| `…/SubscriptionReconciler.java` (M) | `com.trading.pricing.core.SubscriptionReconciler` `[api]` |
| `…/TierUpdateListener.java` (A) | `com.trading.pricing.core.TierUpdateListener` `[api]` |
| 4 × `src/test/…` | *(no indexed type)* — reported, not silently dropped |

3/3 main files map to exactly one type each; `Closure.expand` from that single git seed reaches the
same five dependents the declared run reaches. **Prediction P1 is met on both arms — build it.**

**And it beats anchoring on completeness.** Case 3b (the same spec plus
`class: TierUpdateListener` / `class: SubscriptionReconciler`) plans cleanly — but writing those
touchpoints *is* the git archaeology the developer wanted the tool to do. The change set also names
`PricingCoreAutoConfiguration`, which a human hand-writing touchpoints would plausibly miss.

**The same truncation mechanism as case 2, confirmed independently.** All three changed types rank
**56, 59 and 62** in platform-libs against `TYPE_BUDGET = 25`:

| type in prompt | 3a (sha in prose) | 3b (anchored) |
|---|---|---|
| `TierUpdateListener` | **0** | 4 |
| `SubscriptionReconciler` | **0** | 6 |
| `PricingCoreAutoConfiguration` | 1 *(incidental member line, not a type line)* | 2 |

---

## Case 4 — rebuild-only annotation accuracy: the cheap rule wins outright

`Closure.usesApiOf` asks *"does this repo use anything at all from that repo"*, never *"does it use
the thing that changed"*. Measured against two real changes, with every non-listed consumer being
ground-truth rebuild-only. Ground truth audited over `src/main` only, and two apparent
counter-examples were chased down and dismissed — product-a's `FixSessionListener` reference is in
`src/test` (not indexed), and ops's `SubscriptionReconciler` appears only in a javadoc comment and
in design markdown. The KB is right in both cases.

**Change A — `FixSessionListener`** (truth: candles + core need code):

| rule | correct | false CODE_CHANGE | false REBUILD_ONLY |
|---|---|---|---|
| TODAY (unfiltered) | **3/5** | 2 (product-a, product-b) | 0 |
| TYPE_FILTERED | **5/5** | 0 | 0 |
| KIND_AWARE (reads `ref_kind`) | **5/5** | 0 | 0 |

**Change B — commit `8e54df6`** (truth: *nobody* needs code; none of the three changed types is
referenced outside platform-libs):

| rule | correct | false CODE_CHANGE | false REBUILD_ONLY |
|---|---|---|---|
| TODAY (unfiltered) | **1/5** | 4 (candles, core, product-a, product-b) | 0 |
| TYPE_FILTERED | **5/5** | 0 | 0 |
| KIND_AWARE | **5/5** | 0 | 0 |

**Member-level usage is killed.** Restricting the existing query to the changed types fixes 100% of
the measured errors — a one-query change with no schema change. Member granularity buys nothing on
either case, and it was the expensive candidate.

**`ref_kind` reading is *not* justified either — it ties.** KIND_AWARE scores identically to
TYPE_FILTERED on both changes, so on this evidence it is unmotivated. It stays an orphan fact, and
that is now a measured judgement rather than an oversight.

**Honest limits of this result.** Every error today is in the *safe* direction (false
`CODE_CHANGE_LIKELY`), and neither candidate produced a false `BUMP_REBUILD_ONLY` — but neither case
was constructed to provoke one. The way type-filtering could produce the dangerous direction is a
consumer that breaks *transitively*: platform-libs changes type X, the consumer references only Y
which extends X, so the consumer never names X and would be scored rebuild-only. That is exactly
where hierarchy facts would be load-bearing, and it is untested here.

---

## The unified diagnosis

Cases 2 and 3 fail by one mechanism:

> **Everything the planner needs is already in the KB. It ranks past the evidence budget, and the
> only thing that promotes it is the spec naming it — which fails exactly when the developer does
> not yet know what to name.**

That reframes both remaining fact candidates. A subtype table and a `--since` change set are **not
discovery mechanisms** — they are *sources of names* feeding `PlanDrafter.ranked()`'s `terms` set,
so the facts already present get promoted past the budget. Which is a far smaller change than a
fact layer, and it explains why anchoring rescues every case: anchoring is the human supplying the
names by hand.

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

**Git change-set seeding — BUILT AND JUSTIFIED.** See case 3 above: it blocks today, and the
prototype maps 3/3 changed main files onto exactly the right types. Its value is as a *source of
names* for the ranking, not as a discovery mechanism.

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

## Determinism

The whole report is **byte-identical across two runs** — checked by diff, not asserted. That matters
because `sdd plan approve` SHA-hashes the `plan.md` this evidence produces.

## Not yet measured

No Layer-2 run against the real CLI yet, so blocking-question *rendering* and `plan.json`'s
`repo_steps[].files` are unmeasured. The change-set prototype is measured only against a
single-commit range on one repo; multi-commit ranges, renames and deletions are untested, as is any
repo whose modules sit outside the repo directory (an included build), where the path join could
still break. The annotation rules are measured on two changes in one provider, neither constructed
to provoke a false `BUMP_REBUILD_ONLY`.


---

# After the fixes (re-measured 2026-08-18, same probe, still zero model calls)

Four of the five surviving candidates are implemented. Re-running the same corpus:

| | baseline | after |
|---|---|---|
| **Case 3a** seeds | none → **blocked** (`no seeds`) | seeded from git, **0 blocking questions** |
| **Case 3a** annotations | 1/5 correct (4 false CODE_CHANGE) | **5/5** |
| **Case 3a** changed types in prompt | 0 of 3 | **3 of 3** (4, 11, 15 mentions) |
| **Case 2** annotations | 3/5 (product-a/b false CODE_CHANGE) | **5/5** |
| **Case 2** implementors in prompt | 2 of 5 | **3 of 5** |
| Members reaching service repos | 0 | 60 per repo |
| Drafter prompt (6 repos) | ~14.9k chars | ~35k chars |

**What is fixed, and by what.** Evidence budgets split per section and the `is_api` member filter
removed; the annotation anchored on the changed types; `--since` turned into git seeds whose changed
types join the anchor set. The anchor set is the common thread — it is a *source of names* feeding
`PlanDrafter.ranked()`, which is what promotes facts that were always in the KB but ranked past the
budget.

**And with the fifth survivor — the subtype table (V6) — case 2 closes too.** All five
`FixSessionListener` implementors are now named in the KB across three repos, and **5 of 5 reach the
model** (from 2 of 5):

| implementor | baseline | after |
|---|---|---|
| `QfjExecutionAdapter` | 1 | 6 |
| `CandleMdRejectListener` | 1 | 2 |
| `QfjMarketDataAdapter` | 0 | 8 |
| `ExchangeSimulator` | **0** | **3** |
| `VenueMarketDataSimulator` | **0** | **3** |

The last two are the ones ranked 48 and 65 — no budget increase reaches them, and the spec cannot
name them. They arrive because `type_supertype` supplies their names as anchors, which is the whole
claim of the unified diagnosis, now demonstrated rather than argued.

The `extractor_epoch` guard was verified live: a plain `sdd index` — no `--force` — re-extracted all
six repos after the migration, which is the trap V2 and V3 both fell into.

The prompt is 2.3× bigger. That is one call per plan against a 384k-context planner, and it buys
every service repo's method signatures, which previously did not exist in the prompt at all.
