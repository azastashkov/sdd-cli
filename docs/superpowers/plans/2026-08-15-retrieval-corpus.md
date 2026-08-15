# Retrieval corpus: close the `retrieval` lie, then fix retrieval where it was actually broken

## Context

Phase 6 shipped with this carried item (`docs/superpowers/plans/2026-08-14-phase6-sdd-explain.md:272`):

> **`SddConfig.retrieval` is dead config.** `ConfigLoader` validates `fts`|`embeddings` and demands a
> `models.embeddings` endpoint for the latter, but nothing reads it and no `EmbeddingsRetriever`
> exists — a user configuring `embeddings` silently gets FTS. Fixing it means a
> `Retrievers.of(config, jdbi)` factory and an actual embeddings backend.

The claim was exactly true. `ConfigLoader` validated the enum and demanded a `models.embeddings`
endpoint for `embeddings`; the value reached the `SddConfig` record; and there were **zero production
reads of `SddConfig.retrieval()`** — the only readers were two assertions in `ConfigLoaderTest`. All
three retriever construction sites (`ExplainCommand`, `PlanCommand`, `ReviseCommand`) hardcoded
`new FtsRetriever(db.jdbi())`.

**The carried item's prescribed remedy — build the embeddings backend — did not survive contact with
the estate.** This branch did what the evidence supported instead: it closed the dishonesty, which
was the actual defect, then fixed the two *measured* causes of poor retrieval, and deferred the
embeddings decision until there was a corpus that could plausibly benefit from one.

### The diagnosis that redirected the work

Five natural-language questions were run against the estate KB before any change. The failures were
not ranking failures:

1. **No stemming.** `TierResolver`, `MfeProbe` and `GroupDirectory` were all indexed and all missed.
   Re-running the same queries with singular tokens ranked `TierResolver` first. The failures were
   `tiers`≠`tier`, `resolved`≠`resolver`, `probes`≠`probe`. `V1__init.sql:154` declared
   `tokenize = "unicode61 tokenchars '_$'"`; SQLite ships a `porter` wrapper that stems.
2. **The corpus was impoverished.** `fts_symbol(identifier, fqcn, words, module_id)` held identifiers
   and nothing else. The questions porter could not fix were answered, in prose, by javadoc that
   existed in the source and was absent from the KB — `GroupDirectory`'s type comment opens
   *"Write-through state directory closing the ordering gap between a successful admin PUT and this
   service's OWN GroupToggleWatcher…"*, which is close to a restatement of the question that missed
   it. `ApiSurfaceExtractor.toTypeInfo` never called `getJavadoc()` and `java_type` had no javadoc
   column.

Neither failure mode is a ranking problem, and embeddings only ever improve a ranking. Embedding an
identifiers-only corpus asks a text embedder to do semantic matching over two- and three-word symbol
names, which is where embeddings are weakest and BM25 strongest: it would buy a better *sort* of the
wrong *list*. It also cuts against a lesson this project has already paid for three times, recorded
in the `sdd explain` notes as *"the recurring bug class here is fuzzy/lossy machinery reused for
exact work"*.

**So the branch shipped three changes and one measurement.**

| Commit | Change |
|---|---|
| `c5b0fcc`, `54e0a3e` | `retrieval: embeddings` is rejected at config load naming the remedy, rather than silently downgraded to FTS. The `SddConfig.retrieval` component is deleted; the YAML key is still validated, because snakeyaml would otherwise swallow the value in silence. |
| `6de05cc` | `V2__fts_porter.sql` recreates `fts_symbol` with `tokenize = "porter unicode61 tokenchars '_$'"` and an unused `doc` column, repopulated by `FtsSymbolWriter.rebuildFrom`. First in-place migration this codebase has ever run. |
| `141f3ac`, `c6b9ec9`, `4be4625` | `V3` adds `java_type.javadoc`; the extractor fills it; the indexer writes the type's javadoc summary into `fts_symbol.doc` at the lowest bm25 weight, and `Hit.docOnly` marks a hit whose javadoc was the only column that matched. |

This document is the fourth: the measurement, and the embeddings decision it supports.

---

## The measurement

### Why it did not run against the user's estate

The live KB at `~/projects/github/trading-estate/.sdd/index.db` is at `schema_version = 1` and two
frozen production runs depend on it. Any command that opens a database applies pending migrations —
including read-only `sdd status`, `sdd review` and `sdd graph` — so pointing this branch's binary at
it would migrate it v1→v3 as a side effect of measuring. It was therefore never opened by the new
binary; it was read with `sqlite3` only.

The measurement ran instead in a throwaway probe workspace: the same six repo symlinks the estate
uses, a copy of its `sdd.yml`, indexed with `--no-cards`. Repo cards need model credentials and cost
tokens, and `repo_card` has no bearing on an `fts_symbol` measurement.

### Why it is an A/B and not a before/after

The "before" numbers in the diagnosis above were taken from the live v1 database. That database was
indexed when every repo in the estate was checked out on an **agent-written commit from a frozen
`sdd implement` run**, and none of those commits is reachable from its repo's current `main`:

| repo | `repo.head_commit` in the v1 DB | current `main` | ancestor of `main`? |
|---|---|---|---|
| trading-candles | `7cf9712` `sdd: SPEC-101-v2 trading-candles` | `ba26a68` | no |
| trading-core | `a73acbd` `sdd: SPEC-101-v2 trading-core` | `636a538` | no |
| trading-ops | `c1e5ce7` `sdd: SPEC-101-v2 trading-ops` | `6ad6bd6` | no |
| trading-platform-libs | `33e9c96` `sdd: SPEC-101-v2 trading-platform-libs` | `a94e747` | no |
| trading-product-a | `bc87e11` `sdd: SPEC-101-v2 trading-product-a` | `8c418eb` | no |
| trading-product-b | `08a3cbd` `sdd: SPEC-101-v2 trading-product-b` | `683748b` | no |

The estate has since absorbed a `feature/frontend-repo-split` merge in five of the six repos. Thirteen
Java files differ. **One of them matters directly: `TierSpreadService` is gone.** It existed only on
`bc87e11`, the agent-written commit the v1 DB indexed; it is not in current `main`, and neither are
the `getTierSpreadBps`/`setTierSpreadBps` accessors that `TiersProperties` used to carry. In the
estate as it stands today the only tier-spread code anywhere is the mock venue's `VenueProperties`.

Comparing a fresh index against the plan's original numbers would therefore have measured this
branch's change *plus* the estate's drift, and reported the sum as the former. So both arms were run
inside the probe database against byte-identical corpus content, differing only in the thing under
test.

### The harness

Not a test. "Is this ranking good?" has no green/red answer, and encoding one would be its own lie.
The whole harness is this, run against the probe database:

```sql
-- The old schema, reproduced from the new rows: identical content, old tokenizer, no doc column.
CREATE VIRTUAL TABLE fts_baseline USING fts5(
  identifier, fqcn, words, module_id UNINDEXED,
  tokenize = "unicode61 tokenchars '_$'");
INSERT INTO fts_baseline(identifier, fqcn, words, module_id)
  SELECT identifier, fqcn, words, module_id FROM fts_symbol;
```

```python
# Tokenize exactly as FtsRetriever.search does — the real query path, not a hand-tuned one.
match = " OR ".join('"%s"' % t for t in re.split(r"[^A-Za-z0-9_$]+", question) if t.strip())

OLD = ("SELECT identifier, bm25(fts_baseline) s FROM fts_baseline WHERE fts_baseline MATCH ?"
       " ORDER BY s, identifier, module_id LIMIT 5")
NEW = ("SELECT identifier, bm25(fts_symbol,10.0,3.0,8.0,2.0,0.0) s,"
       " highlight(fts_symbol,3,char(2),char(3)) hl_doc, …"
       " FROM fts_symbol WHERE fts_symbol MATCH ? ORDER BY s, identifier, module_id, fqcn LIMIT 5")
```

`OLD` is byte-for-byte the retriever at `1e74933`, including its shorter `ORDER BY` — the added
`fqcn` tiebreak was checked and changes no result for any of the five questions. Doc-only provenance
is read per hit with the same `highlight(…, char(2), char(3))` technique `FtsRetriever` uses.

### The corpus that was measured

| | probe (`schema_version = 3`) | live v1 DB |
|---|---|---|
| `fts_symbol` rows | 1214 | 1227 |
| distinct identifiers | 858 | 867 |
| `java_type` rows | 256 | 258 |
| `java_type` with javadoc | **219 (85.5%)** | n/a — no such column |
| `fts_symbol` rows with non-empty `doc` | 219 | n/a |

**How much javadoc actually reached the KB: 219 of 256 types.** The 37 without were checked rather
than assumed. They are nine Spring `*Application` boot classes and twenty-eight nested records,
nested enums and bare enums — `Tier`, `Side`, `AdminServiceApplication` were read at source and carry
no comment at all. This is source coverage, not an extraction gap. At file level, 241 of 263 main
sources carry a type-level javadoc block and **none carries javadoc only below the type level**,
which is what makes the member-level gap (carried item 5) cheap to defer *for this estate* and says
nothing about any other.

### Results

Top 5 per arm. `[IFWD]` shows which columns matched — identifier, fqcn, words, doc.

**Q1 — "what broadcasts product status over the websocket"** (already correct before)

| # | old: `unicode61`, bare `bm25()` | new: `porter` + `doc`, weighted |
|---|---|---|
| 1 | `productStatus` −7.97 | `broadcastProductStatus` −25.93 `[W]` |
| 2 | `broadcastProductStatus` −7.55 | `productStatus` −13.98 `[W]` |
| 3 | `status` −5.87 | `ProductGate` −11.82 `[WD]` |
| 4 | `status` −5.87 | `LoadWsClient` −11.70 `[D]` **doc-only** |
| 5 | `status` −5.87 | `ProductRef` −10.90 `[WD]` |

The verb moves to first and three near-duplicate `status` rows leave the window.

**Q2 — "how are client tiers resolved"** (all wrong before)

| # | old | new |
|---|---|---|
| 1 | `ProviderClient` −6.88 | `TierResolver` −16.08 `[WD]` |
| 2 | `ResolvedField` −5.78 | `JdbcTierResolver` −14.40 `[WD]` |
| 3 | `resolvedFields` −5.78 | `tierResolver` −12.74 `[W]` |
| 4 | `ResolvedMigration` −5.48 | `FieldResolvers` −11.45 `[WD]` |
| 5 | `migrateResolvedFields` −5.48 | `Activation` −10.57 `[D]` **doc-only** |

**Q3 — "which component probes whether a microfrontend is reachable"** (missed `MfeProbe` before)

| # | old | new |
|---|---|---|
| 1 | `reachable` −9.79 | `MfeProbe` −17.05 `[WD]` |
| 2 | `Component` −9.09 | `Action` −15.13 `[D]` **doc-only** |
| 3 | `PricingAApplication` −7.64 | `PayloadKeySpec` −13.51 `[D]` **doc-only** |
| 4 | `main` −6.04 | `reachable` −13.01 `[IW]` |
| 5 | `name` −5.72 | `RejectDecider` −11.32 `[D]` **doc-only** |

**Q4 — "where is the ordering gap between an admin write and the watcher"** (missed `GroupDirectory`)

| # | old | new |
|---|---|---|
| 1 | `write` −9.04 | `GroupDirectory` −29.33 `[FD]` |
| 2 | `GapStats` −6.05 | `GroupToggleWatcher` −21.72 `[WD]` |
| 3 | `SeqGapTracker` −6.05 | `InterestWatcher` −17.20 `[WD]` |
| 4 | `InterestWatcher` −6.04 | `SessionRegistry` −14.18 `[D]` **doc-only** |
| 5 | `writeTierCacheMeta` −5.75 | `AdminProperties` −14.12 `[FWD]` |

`GroupDirectory` moved from rank **31 to rank 1**.

**Q5 — "what handles tier spreads"** (generic tokens crowded out the answer)

| # | old | new |
|---|---|---|
| 1 | `Tier` −5.27 | `getTierSpreadBps` −16.25 `[W]` |
| 2 | `tier` −4.68 | `setTierSpreadBps` −16.25 `[W]` |
| 3 | `tier` −4.68 | `handle` −10.64 `[IW]` |
| 4 | `tier` −4.50 | `handleBadMessage` −9.93 `[W]` |
| 5 | `tier` −4.50 | `handleJwtAuth` −9.93 `[W]` |

Read this one with the drift caveat in hand. `TierSpreadService`, which the pre-branch analysis
expected to surface, **does not exist in the estate any more**; `VenueProperties.getTierSpreadBps` is
now the only tier-spread symbol there is, so the new top hit is the best available answer rather than
a wrong one. Positions 3–5 are the visible cost of porter: `handles` stems to `handl` and now matches
every `handle*` method in the estate.

### Which of the three changes did the work

The same five questions, run across four arms over the one corpus. `D` isolates the weights, `C` adds
the tokenizer, `B` adds the javadoc — so each row's delta is attributable.

| arm | table | tokenizer | doc column | weights |
|---|---|---|---|---|
| **A** old | `fts_baseline` | `unicode61` | no | bare (all 1.0) |
| **D** | `fts_baseline` | `unicode61` | no | 10, 3, 8 |
| **C** | `fts_porter_nodoc` | `porter` | no | 10, 3, 8 |
| **B** new | `fts_symbol` | `porter` | yes | 10, 3, 8, 2 |

Rank of the known-good answer in each arm:

| question | target | A | D | C | B |
|---|---|---|---|---|---|
| Q1 | `broadcastProductStatus` | 2 | 2 | **1** | **1** |
| Q2 | `TierResolver` | absent | absent | **1** | **1** |
| Q3 | `MfeProbe` | absent | absent | 5 | **1** |
| Q4 | `GroupDirectory` | 31 | 38 | 42 | **1** |
| Q5 | `getTierSpreadBps` | 46 | 46 | **1** | **1** |

Three findings, and only the first was expected:

1. **The tokenizer did most of the work.** Porter alone (C vs D) fixed Q2, Q5 and Q1, and moved Q3's
   answer from absent into the window.
2. **The javadoc did the rest, and only the rest.** Prose (B vs C) is the entire reason Q3 reached
   first and the entire reason Q4 did — `GroupDirectory` was at rank 42 without it. These are the two
   questions the diagnosis predicted prose would be needed for, and they are exactly the two it
   fixed. No other question moved.
3. **The bm25 weights changed nothing.** Arm D is identical to arm A on four of five questions, and
   on the fifth it pushed the right answer *down*, 31 → 38. On a code-only corpus the discriminating
   matches all land in the same column, so the ratio between columns never gets to matter. The four
   code weights are not vindicated by this measurement; they are simply unexercised by it. The `doc`
   weight of 2.0 is the only one shown to be load-bearing.

### Caveats, stated rather than buried

- **Five questions is not a benchmark.** It is the same five the diagnosis was built from, so they
  are the questions the changes were designed against. They can show a fix landed; they cannot show
  retrieval is good in general, and nothing here is a defence against overfitting to them.
- **The estate drifted, and Q5 drifted out from under its own answer.** The A/B is internally
  controlled and unaffected, but the old numbers in the Context section above were taken over a
  different tree and must not be subtracted from the new ones.
- **Recall rose sharply and that is a cost, not only a benefit.** Q4 went from 58 matching rows to
  367. Everything past the top few is now more crowded, not less.
- **This measured the retrieval layer, not `sdd explain` end to end.** No model calls were made:
  cards were skipped and no question was put through the two-call pipeline. The A/B reproduces
  `QuestionInterpreter`'s call path exactly (raw question, `SYMBOL_CANDIDATES = 20`) and
  `SeedFinder`'s closely (requirement prose, `FTS_LIMIT = 8`). It does **not** reproduce
  `SearchFacts`, which searches the model-extracted `searchTerms` rather than the raw question and so
  runs a narrower query than anything measured here.
- **`docOnly` did not fire for either prose-dependent answer** — see the carried items. The split the
  plan predicted as "visible proof" did not appear, and the reason is worth reading.

---

## The embeddings decision

**Not built. `retrieval: embeddings` fails config load.** The measurement supports this, and the
support is narrower than it looks, so here is exactly what it does and does not say.

What it says: after this branch, all five questions return a defensible top hit, and **not one of the
four fixes was a ranking fix**. Two were tokenization (Q2, Q5), two were corpus (Q3, Q4), and Q1 was
never broken. An embeddings backend addresses neither cause. There is no measured failure left among
these five for vectors to solve, so building them now would be building against a hypothesis rather
than a symptom — and the cost is a `/v1/embeddings` client, a new provider dependency for `sdd index`
(neither RouterAI nor DeepSeek obviously serves embeddings), a content-addressed vector cache, plan
output that stops being byte-reproducible, and an explicit reversal of a Phase-6 invariant. Roughly
thirteen tasks.

What it does **not** say is that embeddings would never help, and the counter-argument is measurable
rather than rhetorical. Q3 asks about a *microfrontend*; the type is called `MfeProbe`. Nothing
stems "microfrontend" to "mfe" — they share no root, and no tokenizer will ever connect them. Q3
still works because it carries a *second* content word that does stem-match: `probes` → `probe`
matches `MfeProbe`'s `words` column, which is what pulled it into the window at rank 5 before the
javadoc took it to 1.

Take that second word away and the whole apparatus fails. Asking the same question with ordinary
synonyms, against the full v3 corpus with javadoc in place:

| question | matching rows | rank of `MfeProbe` |
|---|---|---|
| "which component **probes** whether a microfrontend is **reachable**" | 118 | **1** |
| "which component **checks** whether a microfrontend is **up**" | 113 | **absent** |
| "is the microfrontend **up**" | 137 | **127** |

Same estate, same corpus, same answer sitting in the index — and a rephrasing that any user might
type puts it out of reach entirely. **This is a real, reproducible synonym gap that neither of this
branch's fixes closes**, and closing gaps like it is exactly what embeddings are for.

**So the decision is parked, not settled.** It is parked because the failures actually observed on
this estate were lexical and are now fixed, and because a synonym gap is cheap to live with while it
shows up in rephrasings rather than in questions people actually ask. **The trigger for revisiting is
specific and now demonstrated to be reachable:** questions whose answers are demonstrably in the
corpus and which the porter-stemmed index cannot reach because question and corpus share no stem —
with the javadoc already in place, so the cheap fix has been tried. The table above is one such case,
constructed. Two or three arriving *unconstructed*, from real use, would settle it the other way.
Until then the design work is costed and recorded, not lost, and reversing this decision is cheap
(see carried item 1).

---

## Known carried items

### 1. No embeddings backend, and `retrieval: embeddings` now fails config load

*Trigger:* wanting semantic rather than lexical retrieval.
*Symptom:* config load fails naming the remedy. `sdd explain` still exits 0 because
`ExplainCommand.buildModel` catches every `RuntimeException` and degrades to a fallback reason
(`sdd-cli/src/main/java/sdd/cli/ExplainCommand.java:174-176`); every other command exits 1.
*Ruling:* rejected rather than silently downgraded — accepting a setting is a promise to act on it.
*Cost if wrong:* small and bounded. One validator branch, one record component, four test call sites.
No schema, no data and no public API to undo.

### 2. Embeddings would require overturning a Phase-6 invariant

*Trigger:* any embeddings phase, at its first task.
*Symptom:* `EmbeddingsRetriever.search` must embed the query — network I/O inside a seam that
`docs/superpowers/plans/2026-08-14-phase6-sdd-explain.md:24` declares model-free, and whose stated
guarantee is *a test asserting exactly two model calls per `explain` invocation*. Embedding the query
makes three.
*Ruling:* unresolved, deliberately. Recorded so an embeddings phase **opens** with this decision
rather than discovering it in review.
*Cost if wrong:* an invariant is reversed inside a task rather than as a declared design change, and
the test that encoded it is edited to fit the code instead of the other way round.

### 3. Spec line 32 promises a `--retrieval` override that does not exist

*Trigger:* the day a second backend lands, or a user reading the spec and trying the flag.
*Symptom:* `--retrieval` is documented and unimplemented.
*Ruling:* not added. A flag with one legal value is noise, and it would create a second place to
reject `embeddings` that could drift from the first.
*Cost if wrong:* a spec sentence stays untrue until a second backend exists.

### 4. Spec line 32's "un-anchored file ranking" has nothing to switch

*Trigger:* reading the spec as a description of what was built.
*Symptom:* the sentence implies a second consumer of the retrieval flag. `WorkOrder.manifest` ranks
on `step.files()`, `is_api` and `file_ref` and never takes free text, so the flag only ever governed
`SeedFinder` and explain's SEARCH intent.
*Ruling:* recorded, not implemented — the clause describes a component that was never built.
*Cost if wrong:* an embeddings phase scopes work for a consumer that does not exist.

### 5. A V2-upgraded workspace carries no javadoc until re-indexed, and nothing says so

This is the sharpest of these items, and it is a **determinism** issue rather than only a
retrieval-quality one.

*Trigger:* running any command against a workspace migrated from v1/v2 without re-running
`sdd index`.
*Symptom:* `FtsSymbolWriter.rebuildFrom` writes `doc` as empty at migration time because no javadoc
exists yet, and `java_type.javadoc` is NULL for every pre-existing row. So a migrated workspace and a
freshly-indexed one **over the same estate** now hold different FTS content, therefore produce
different bm25 rankings, therefore produce different `plan.md` seed lists — under a command that
SHA-pins `plan.md` at approve time. Nothing in the output distinguishes the two states.
*Ruling:* documented, not fixed. Detecting it needs an index-time marker in `meta`, because
`javadoc IS NULL` cannot distinguish "never re-indexed" from "this estate has no javadoc" — and 37
of this estate's 256 types legitimately have none.
*Cost if wrong:* two users over one estate get different approved plans and no signal that they
should not have.

The tension worth recording alongside it: the same commit spends an entire `ORDER BY` key
(`FtsRetriever`'s `fqcn` tiebreak) eliminating a *tie-order* divergence between migrated and fresh
workspaces, while shipping a *content* divergence between them unannounced. The smaller problem got
the fix.

### 6. Member-level javadoc is not extracted

*Trigger:* a question answered by a method's doc comment but not by its enclosing type's.
*Symptom:* the type does not surface, and nothing indicates that the answer was in the source.
*Ruling:* deferred. Measured this time: no file in this estate carries javadoc *only* below the type
level, so type-level extraction misses no file entirely here. That is a fact about this estate, not
about the design.
*Cost if wrong:* one more `api_member` column and a larger FTS corpus — cheap, but the corpus growth
is the same recall-versus-noise trade the doc column already made once.

### 7. Stale javadoc is indexed as-is and nothing detects the drift

*Trigger:* a doc comment describing behaviour the code no longer has.
*Symptom:* the type is surfaced as a candidate for a question it no longer answers.
*Ruling:* mitigated, not fixed. The weight floor keeps prose from dominating, the `docOnly` marker
labels it in explain's output, and the fact firewall stops it becoming a claim — so the cost is a
wasted candidate, not a wrong answer.
*Cheapest real detection if it ever matters:* flag javadoc whose `{@link}`/`{@code}` targets no
longer appear in the type's `api_member` rows. That is provable staleness and needs no model.
*Cost if wrong:* a human reads a confident comment about code that has moved on.

### 8. `docOnly` is deliberately not rendered into `plan.md`

*Trigger:* a doc-only hit becoming a Gate-1 seed.
*Symptom:* `SeedFinder.java:63`'s detail string is SHA-pinned and feeds `ModelSeeder`'s prompt, so a
doc-only seed reads identically to an identifier seed in the artifact a human approves.
*Ruling:* deferred pending this measurement, which was to show whether doc-only seeds occur at all.
**They occur, and often.** Within `SeedFinder`'s `FTS_LIMIT = 8` window: 3/8 doc-only on Q1, 2/8 on
Q2, 4/8 on Q3, 4/8 on Q4, 0/8 on Q5. Half the seed candidates for a prose-heavy requirement can be
doc-only. This item is now supported by evidence rather than speculation, and the deferral should be
re-taken on it.
*Cost if wrong:* a human approves an affected repo whose only evidence was an unverified comment.

### 9. Doc-only hits are deliberately not marked in `QuestionInterpreter`'s call-1 candidate list

*Trigger:* wanting the same provenance labelling explain's SEARCH section already has.
*Symptom:* the call-1 candidate vocabulary mixes doc-only and code-derived names with no distinction.
Measured at `SYMBOL_CANDIDATES = 20`: 6, 2, 10, 11 and 2 doc-only respectively — on Q4 more than half
the list.
*Ruling:* not marked, deliberately. Marking some candidates implies the untagged ones are
*confirmed*, which revives exactly the anchoring the existing "may be irrelevant" caveat exists to
prevent. A partial guarantee is worse than none here.
*Cost if wrong:* the interpreter anchors on prose-derived names it cannot tell apart from verified
ones.

### 10. Javadoc deliberately does not reach `sdd implement`'s work orders

*Trigger:* work orders proving too thin on intent.
*Symptom:* `WorkOrder.build` reads structural KB tables and `repo_card`; `java_type.javadoc` is
available to it and not wired in.
*Ruling:* an agent editing code should read the code. Unverified prose in a coding prompt is a way to
have an agent faithfully implement a stale comment.
*Cost if wrong:* work orders stay structural when a sentence of intent would have helped.

### 11. The bm25 weights were never tuned against real questions, and this measurement barely tests them

*Trigger:* anyone treating `(10.0, 3.0, 8.0, 2.0, 0.0)` as tuned.
*Symptom:* they are the plan's starting values. This measurement is the first evidence about them,
and it is thin: arm D shows the four code weights change **no answer** relative to bare bm25 on this
corpus and these questions, and move Q4's answer marginally further down. Only the `doc` weight of
2.0 is shown to matter — it is the whole of the Q3 and Q4 improvement.
*Ruling:* **no code change.** Retuning on five self-selected questions would be overfitting dressed
as evidence, and it is a separate decision from this one.
*What the numbers do suggest, on the record:* the `doc` weight may be slightly high relative to the
design intent — see item 12 — but lowering it is not obviously safe, because `GroupDirectory` climbed
from 42 to 1 on the strength of it. Any retune must re-run this A/B against a **larger and
independently chosen** question set.

### 12. `FtsRetriever`'s class javadoc claims a guarantee the measurement falsifies

*Trigger:* reading `FtsRetriever`'s class comment as a specification.
*Symptom:* it states that prose in the `doc` column *"breaks ties and surfaces types no identifier
could reach, but it can never win against a code-derived match."* **It can.** bm25 weights scale a
column's contribution; they do not cap it, so a long javadoc matching many query terms outscores a
short identifier matching one even at 2.0 against 10.0. Two instances in five questions: on Q3
`Action` (−15.13, doc-only) and `PayloadKeySpec` (−13.51, doc-only) both outrank `reachable` (−13.01,
identifier and words matched); on Q4 `SessionRegistry` (−14.18, doc-only) outranks `AdminProperties`
(−14.12, three columns matched).
*Ruling:* recorded, not fixed — this task made no code changes. But it should be taken up, because a
comment stating a false guarantee is this project's own governing rule violated one level down, and
the same call was already made once on this branch (Task 3, minor #3). The fix is a choice between
correcting the sentence and lowering the weight until it becomes true, and those have different
consequences: the second would likely undo the Q3/Q4 wins.
*Cost if wrong:* a future reader relies on a floor that does not exist.

### 13. `docOnly` reports *presence* provenance, not *rank* provenance — and the predicted proof did not appear

*Trigger:* reading `[matched on javadoc]` as "javadoc is why this is here".
*Symptom:* the plan predicted that Q3 and Q4 would surface `MfeProbe` and `GroupDirectory` **with**
the marker, as visible proof that the weighting and the labelling both worked. **Neither is marked.**
`MfeProbe` matched `words` as well, because porter stems `probes` to `probe` — legitimate. But
`GroupDirectory` is marked as a code match only because the query's word "admin" appears in the
package fragment of `com.trading.admin.GroupDirectory`. Its rank of 1 is entirely javadoc-driven; it
sat at 42 without the doc column; and the user sees no indication that unverified prose put it top.
*Ruling:* the flag is behaving exactly as `FtsRetriever` documents it — `docOnly` means the javadoc
was the *only* column that matched, which is a statement about why the row is in the result set, not
about why it ranks where it does. That is a defensible definition and the stricter one. It is
recorded here because it is not the definition the plan was reasoning with, and because of what that
means for where the marker actually fires. Across all five questions, **not one doc-only hit in any
top 5 was the answer to its question** — some are topically adjacent (`LoadWsClient`,
*"Load-harness WS client"*, on the websocket question) and some are unrelated (`RejectDecider`,
*"Decides whether an order is rejected upfront"*, on the microfrontend question) — while both of the
answers javadoc genuinely rescued went unlabelled. On this sample the label attaches to non-answers
and detaches from the answers, which is the opposite of what it was introduced to show.
*Cost if wrong:* the provenance labelling reassures rather than informs, which is worse than absent —
it is the anchoring failure of item 9, arriving through the door item 9 held shut.

### 14. `Database.migrate` reports the code's migration count, not the database's recorded version

`Database.java:66` returns `MIGRATIONS.size()` unconditionally, so an older binary opening a newer
database reports the wrong version. Pre-existing, but newly reachable now that more than one
migration exists.

### 15. The two backend labels stay hardcoded and are load-bearing

`SearchFacts.java:58`'s `"fts_symbol (bm25)"` and `SeedFinder.java:63`'s `"fts"`. The second flows
into `ModelSeeder`'s **prompt**, `ImpactAnalysis`'s reason lines and `PlanMdRenderer`'s `plan.md`.
*Ruling:* left alone with a comment saying why they must not be reworded. Derive them from the
retriever only once a second backend exists, and then define that backend to emit these exact strings
for FTS.

---

## A note for whoever next touches `fts_symbol`

**bm25's weights are positional over *all* declared columns, and `UNINDEXED` columns still occupy a
slot.** For `fts_symbol(identifier, fqcn, words, doc, module_id UNINDEXED)` the call
`bm25(fts_symbol, 10.0, 3.0, 8.0, 2.0, 0.0)` therefore has a trailing placeholder that exists only to
keep the four real weights aligned.

This was verified rather than assumed, and the dangerous half is the error mode: **supplying too many
weights is silently ignored and supplying too few defaults the rest to 1.0 — neither raises.** An
off-by-one would mis-weight every column in the table without any signal at all. The order is fixed
by `V2__fts_porter.sql:9` and **must be re-checked against it if that table is ever recreated.**

---

## Self-review

1. **Does the document claim anything the measurement does not support?** The strongest claim made is
   "no measured failure remains among these five that embeddings would address", and it is scoped in
   the text to five questions on one estate. The counter-evidence — the microfrontend/mfe synonym gap
   — is given equal space, and it is *measured* rather than argued: two rephrasings of Q3 were run
   against the same corpus and the answer is absent from one and at rank 127 in the other. It is
   labelled as constructed, because it is. The weights are explicitly *not* claimed to be validated;
   the four code weights are called unexercised, which is what arm D shows.
2. **Are the numbers honestly caveated?** The estate drift is stated with a table, an ancestry check
   and the one concrete consequence (`TierSpreadService` is gone, so Q5's expected answer cannot
   appear). The recall cost is given as a number, not a hedge. The parts of production not covered —
   `SearchFacts`' narrower query, and the whole two-call `explain` pipeline — are named.
3. **Are the two negative findings recorded as prominently as the positive ones?** Items 12 and 13
   are both failures of things this branch shipped, found by this measurement, and both are in the
   carried-items list with the same Trigger/Symptom/Ruling/Cost treatment as the rest. Item 13
   contradicts a prediction the plan made in writing, and says so.
4. **Judgment calls for reviewers:** running the A/B in a probe workspace rather than the user's
   estate; using the raw question as the query, which matches two of the three production call sites
   but not `SearchFacts`; adding the four-arm decomposition, which was not asked for but is the only
   thing that attributes the improvement to a cause; and declining to retune the weights on the
   evidence in hand.
