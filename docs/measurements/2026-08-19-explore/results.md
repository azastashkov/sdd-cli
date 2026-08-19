# Exploration measurement — does an estate survey buy anything the planner cannot already get?

**Date:** 2026-08-19 · **Estate:** the same six trading repos as the 2026-08-18 corpus, all on
`main` · **Model calls: zero.**

Same method as `../2026-08-18-plan-facts/results.md`: predictions written down first, real repos,
ground truth audited over `src/main` only, and every number reproducible from a harness rather than
argued. Probe workspace `~/projects/github/sdd-measure/probe-6repo` — the live estate KB is never
opened, because `Database.open` migrates on every open including read-only paths.

Three new cases, chosen because their answers are **not Java types** — the category the plan says
the index has no ontology for:

| case | the thing the task names | what it is | ground truth (src/main) |
|---|---|---|---|
| 6 | `tier.update` | a Redis pub/sub channel | core, candles, platform-libs |
| 7 | `sweep-interval` | a Spring config key | core |
| 8 | `refdata.clients` | a Postgres table | core, platform-libs |

Harnesses: `sdd-agent/src/test/java/sdd/agent/tool/EstateReachHarness.java` (new, `@Tag("measure")`,
env-gated) and the existing `PlanFactsHarness`. Neither adds anything to production code.

---

## The plan's premise, as written, is REFUTED on this estate

> "A task naming `tier.lvc.map` or a Postgres table cannot match anything, so it yields no
> candidates."

Measured (`reach.md`), `fts_symbol` returns hits for **every one of these terms, with 100% repo
recall**, and better repo precision than a raw estate-wide grep on 3 of 5 terms:

| term | fts repos | P | R | estate search repos | P | R |
|---|---|---|---|---|---|---|
| `tier.update` | 3 | 100% | 100% | 4 | 75% | 100% |
| `tier.lvc.map` | 2 | 100% | 100% | 3 | 67% | 100% |
| `sweep-interval` | 3 | 33% | 100% | 2 | 50% | 100% |
| `refdata.clients` | 3 | 67% | 100% | 6 | 33% | 100% |
| `TierCacheSweeper` (control) | 1 | 100% | 100% | 2 | 50% | 100% |

Two reasons it works, both visible in the raw output: `FtsRetriever` splits the query on
non-alphanumerics and ORs the tokens, so `tier.update` matches on `tier`; and **javadoc summaries
are in the corpus**, so `refdata.clients` matches `EntitlementService` on prose alone (flagged
`[javadoc only]`). That is the retrieval aid recorded in the retrieval decision, doing its job.

**Caveat, and it is not a small one.** These six repos carry unusually dense javadoc. The 53-repo
corp estate may not, and this table is the first thing to re-measure there.

## What actually fails — and it is worse than the premise, not better

FTS reaching a repo is not the same as the planner reaching a fact. Three measured layers:

**1. Candidates are not seeds.** All three cases produce `seeds: []` and end at the blocking
question `no seeds: add touchpoints to the spec or check the knowledge base`. The FTS candidates
exist but are offered to `ModelSeeder`; the deterministic half never promotes them.

**2. The top candidates are token collisions.** One per requirement, and the wrong one:

| case | term | top FTS candidate per repo | is it the answer? |
|---|---|---|---|
| 6 | `tier.update` | `PayloadKeySpec` (core), `CandleMdRejectListener` (candles) | no — the listeners are `TierInvalidationListener` |
| 7 | `sweep-interval` | `getOutboxSweepInterval` (core) | no — that is order-service's *different* sweep |
| 8 | `refdata.clients` | `EntitlementService`, `RefdataSeeder` | **yes**, both right; `CandleRepository` is noise |

**3. The decisive one: even a PERFECT impact analysis never names the classes the task is about.**
Running case 6 in `declared` mode — a scripted seeder returning exactly the expected repos, i.e. the
best impact analysis that could possibly exist — gives zero blocking questions and every expected
repo. And in that prompt:

- `tier.update` appears **3 times, all of them in the human's own spec prose**
- `TierInvalidationListener` — the three classes that actually subscribe to the channel — appears
  **0 times**
- case 7 is the same shape: `sweep-interval` appears once, in the human's Goal, and the KB
  contributes nothing about it

So the planner is asked to change every listener on a channel while being told nothing about where
any listener is. **Perfect repo-level seeding does not fix this**, which is why it is not the
retrieval defect the 2026-08-18 round fixed.

## The mechanism check: does explorer output actually reach the prompt?

`case-6-redis-channel-explored.md` is case 6 with exactly what `sdd explore` produces — three
resolvable touchpoints and five cited Evidence bullets, each a real `file:line` from these repos.
Run through the same harness, **deterministic mode, still zero model calls**:

| | prompt bytes | seeds | blocking | `TierInvalidationListener` | `Channels` | `ProviderClient` | `OrdersConfig` |
|---|---|---|---|---|---|---|---|
| case 6 as written | 905 | 0 | 1 (`no seeds`) | 0 | 0 | 0 | 0 |
| case 6 + explorer output | 30,334 | 3 | **0** | **2** | **16** | **7** | **1** |

`affected` goes from `[]` to all six repos with 100% recall of the declared set. `OrdersConfig`
reaching the prompt matters as much as the listeners do: it is the class documenting that
order-service deliberately has **no** listener, i.e. the repo the planner must leave alone.

Two distinct mechanisms are visible in that jump. Touchpoints supply seeds, so impact analysis runs
at all. Evidence bullets are spec prose, so they flow through `SpecRenderer` into
`PlanDrafter.salientTerms` and promote those names past the evidence budget — the anchor mechanism
from 2026-08-18, driven by a source that is not the human's prior knowledge.

## No regression

The 2026-08-18 six-case corpus re-run against the same probe workspace is **byte-identical** to its
recorded `results-final` baseline on affected sets, blocking questions and the declared diff.

## What is still unmeasured, and it is the phase's own kill criterion

> "If the explorer's proposed touchpoints are no better than what `ModelSeeder` already picks from
> repo cards, then the problem was never retrieval and this is a large build for nothing."

**That comparison has not been run.** It needs a live model, and everything above is deliberately
model-free. What is measured is the *upper bound*: given correct explorer output, the prompt
improves as shown. Whether a real model produces correct output on a real estate is the open
question, and the corp 53-repo estate is where it has to be answered:

```
sdd explore SPEC-XX.md --model planner     # then review the diff it wrote into the spec
sdd plan SPEC-XX.md                        # compare affected set + repo steps to the baseline
```

The honest summary: the plan's stated premise was wrong about *reach* and right about *outcome*, and
the reason it is right is one layer deeper than it claimed — not that the terms are unfindable, but
that finding a repo is not finding a fact, and nothing in today's deterministic pipeline turns a
term into a `file:line` a planner can act on.
