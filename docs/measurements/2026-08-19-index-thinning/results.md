# Index thinning — measured, and CLOSED as not worth doing

**Date:** 2026-08-19 · **Estate:** the six trading repos · **Model calls: zero.**

Phase 4 of the exploration plan proposed removing the source-derived tables — `java_type`,
`api_member`, `file_ref`, `fts_symbol`, `api_usage`, `type_supertype` — keeping only the Gradle
Tooling API graph, on two stated rationales:

> "That would cut most of the 40 s indexing cost, since source parsing is the bulk of it, and remove
> the evidence-budget problem at its root."

Both are now measured. **Neither holds.** The phase is closed rather than deferred; what would
reopen it is stated at the end.

---

## 1. The cost claim is refuted: source parsing is 5–8%, not the bulk

`sdd-index/src/test/java/sdd/index/IndexCostHarness.java` times the same two stages
`IndexService.indexRepo` runs, through the same entry points, per repo.

| | Gradle Tooling API | source parsing | source share |
|---|---|---|---|
| cold daemon (`cold.md`) | 39.7 s | 2.0 s | **5%** |
| warm daemon (`warm.md`) | 21.8 s | 2.0 s | **8%** |

The plan's own "40 s for 6 repos" total is confirmed almost exactly (42 s cold). Its *attribution*
is what was wrong: **95% of that time is the Gradle Tooling API**, which the same plan says must be
kept because resolved dependency versions, `mode=COMPOSITE`, the classpath jar list and the
artifact→module map are unobtainable any other way.

So the whole prize is ~2 s on six repos — call it 18 s on 53 — against a re-run cost that is already
near zero thanks to the `head_commit || ':' || dirty_hash` fingerprint short-circuit. That is not a
budget worth taking any risk for.

## 2. The evidence-budget claim points the wrong way

Removing tables removes *evidence*; it does not remove the budget, which exists because a prompt has
a size limit. And the 2026-08-19 exploration measurement found the opposite direction to be the
useful one: the case-6 prompt went from 905 bytes (blocked, no seeds) to 30,334 bytes once the right
facts were in it, and that is what put the subscribing classes in front of the planner. Thinning
would move against the only thing that has been measured to help.

## 3. The consumer map has changed since the plan was written

Audited 2026-08-19 over `sdd-*/src/main`. Two of the plan's specific claims are now false, both
because of work that landed after it was written:

| table | plan said | measured now |
|---|---|---|
| `config_property` | "has no reader anywhere and can be deleted immediately, independently of all of this" | **False.** Phase 0 gave it `KbEntities.resolveConfig` — it is what makes `config:` touchpoints resolve. Deleting it now breaks a shipped feature. |
| `fts_symbol` | "its only job is free-text candidates, which the explorer replaces by construction" | **False.** Measured 100% repo recall on four non-Java terms, beating a raw estate grep on precision for three of them, and it is now also a live explorer tool (`search_symbols`). Removing it would lose measured capability, not shed dead weight. |
| `file_ref` | "one consumer (`WorkOrder`'s manifest); safe to drop as soon as the explorer supplies the agent's file list" | Consumer confirmed (`WorkOrder.java:119`), gate **unmet** — the explorer feeds the spec, not `WorkOrder`. |
| `api_member` | drafter evidence only | Also `FtsSymbolWriter.rebuildFrom`. Gate unmet: explorer citations do not carry signatures. |
| `api_usage` | drafter annotation | Also `RepoCardGenerator:336` and `UsageLinker`. Gate unmet. |
| `type_supertype` | anchor closure | Confirmed, single reader `KbHierarchy`. Gate unmet. |
| `java_type` | "last, and possibly never" | ~12 readers, including `KbEntities.resolveClass` — the substrate that verifies the explorer's own touchpoints. Unchanged: never. |

Every candidate fails the plan's own sequencing rule ("remove a table only after the explorer
measurably covers its consumers"), and the live-model comparison that rule depends on has still not
been run.

## Decision

**Do not thin the index.** The cost saving is 5–8% of a step that is already short-circuited on
re-runs; the risk is deleting the deterministic substrate that verifies model claims, which is the
one property separating this design from "ask a model and hope".

**What would reopen it:** a measurement showing the Gradle Tooling API stage is no longer 95% of the
cost (e.g. a much larger estate where source parsing scales worse than build model extraction), AND
the live-model explorer comparison passing on the corp estate. Both, not either.
