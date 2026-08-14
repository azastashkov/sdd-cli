# Phase 6: `sdd explain` — grounded natural-language answers over the knowledge base

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Context

`sdd` knows an enormous amount about the estate — every module, dependency edge, API member, REST endpoint, Kafka role and cross-repo call sits in `.sdd/index.db` after `sdd index`. Today that knowledge is reachable only by running a whole pipeline: you can get it into a plan, or into a Gate-2 report, but you cannot simply *ask*. Questions a human actually has — "why does trading-ops depend on platform-libs?", "what consumes the tier-resolver API?", "what would change if I modify the admin spreads endpoint?" — currently require reading SQL or inferring from a `sdd graph` render.

The design spec has carried an amendment for this since 2026-08-12 (`Amendment (2026-08-12): sdd explain command`) and deliberately left three decisions to the implementing phase: how a question maps to queries, what the output looks like, and whether it reuses the 4A agent-tool loop. Those are now decided (below). What is binding and unchanged: **read-only, no writes**, empty-KB handling mirroring `sdd graph`/`sdd plan`, and the planner endpoint as interpreter/synthesizer.

The intended outcome is a command that answers in prose *and shows its work* — every claim traceable to a KB row a human can check. The failure this phase exists to prevent is a fluent, confident, wrong answer.

**Goal:** `sdd explain <question>` answers a plain-English question about the estate from deterministic KB facts, printing the answer followed by the evidence it was grounded in.

**Architecture:** Two model calls around deterministic SQL. Call 1 turns the question into a *validated retrieval request* — intent plus entities, every entity resolved against the KB and dropped with a reason if unknown. Deterministic queries then run; no model participates in retrieval. Call 2 narrates over the retrieved evidence and nothing else. The rendered evidence string is *both* the call-2 prompt and the printed `## Evidence` section — one string, so the answer can never be grounded in facts the reader cannot see. Shared KB queries move to a new `sdd.core.kb` package so `sdd explain` and `sdd plan` resolve entities through one implementation rather than two that drift.

**Tech Stack:** Java 21, Jdbi, Jackson, picocli, JUnit 5 + AssertJ. No new dependencies, no `build.gradle.kts` changes in any module.

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — the **Amendment (2026-08-12): sdd explain command** (`:135-137`) is binding and must be read first. Step 0 records this phase's resolution of the three decisions it left open.

## Global Constraints

- **Read-only, and provably so.** `sdd explain` writes nothing except an explicit `--out` file. A test snapshots the workspace file listing before and after and asserts no change when `--out` is absent. `Database.open` **creates** `.sdd/` and the db file, so the `Files.exists` guard must precede it.
- **No model participates in retrieval.** Call 1 chooses *what to look up*; it never chooses how, and it never supplies a fact. The `impact` intent uses `Closure.expand(Jdbi, Set<String>)`, whose signature takes no model — that signature is the guarantee, and a test asserts exactly two model calls per invocation.
- **Never narrate over an empty evidence bundle.** If retrieval produces zero facts, call 2 is skipped entirely. A narrator handed nothing is precisely where invention happens.
- **The prompt and the printed evidence are the same string.** Enforced by a test comparing the call-2 request body against rendered output.
- **Absence is never asserted as fact.** The KB's REST/Kafka extraction is admittedly incomplete (`rest_client.resolution` is not always `LITERAL`; `kafka_topic.resolution` can be `DYNAMIC`). Every `consumers` and `impact` answer carries a deterministic caveat counting unresolved callers and dynamic topics in the repos in play.
- **`repo_card` is model-generated text stored in SQL.** It is labelled as such wherever it appears and may never be the sole basis for a structural claim.
- **Exit codes are the `graph`/`plan` family: 0 success, 1 application error.** No `exitCodeOnInvalidInput`. `explain` reports; it never judges, so it never returns 2.
- **Existing Gate-1 tests must pass unmodified** — `SeedFinderTest`, `ImpactAnalysisTest`, `ClosureTest`, `ExecutionOrderTest`, `OpenQuestionsTest`. They are the regression gate for every extraction in Tasks 1–2.
- Zero-test-breaking outside files a task explicitly edits; `./gradlew build` green at every task boundary.
- Conventional commits ending with the `Co-Authored-By:` trailer in the form the branch's existing commits use.

## Context (verified against source at `2bd7774`)

Facts below were read from the code. **Four contradict what a casual reading suggests and change the design** — they are marked ⚠.

- ⚠ **`SeedFinder.resolve`, `missReason`, `endpointRepos`, `artifactRepos`, `repoOfModule` are all `private static`** (`SeedFinder.java:68, 94, 122, 137, 147`). The only public entry is `find(Jdbi, Retriever, NormalizedSpec)`. Constructing a synthetic `Touchpoint` **does not compile** — extraction is mechanically required, not a style choice.
- ⚠ **`Closure.usesApiOf` (`:91`), `Closure.contracts` (`:127`), `Closure.statusWarnings` (`:269`) are `private static`.** Only `Closure.expand` is public.
- ⚠ **`Closure.expand` does not include the root repos in `Expansion.added`** (`Closure.java:60-83`) — `affected` is seeded with roots but `added` receives only newly-discovered consumers. An impact answer built from `added` omits the repo the user asked about.
- ⚠ **`RepoCardGenerator.composeInput` is package-visible in `sdd.index.cards` and reads `<workspace>/<repo>/README.md` off the working tree** (`:224-241`) — non-KB, unversioned text. Unreachable from `sdd-cli` and unsuitable as "KB fact" regardless.
- `SeedFinder`'s `artifactRepos` (`:130-137`) has **no `ORDER BY`** — the one non-deterministic resolution branch.
- The REST/Kafka cross-repo contract queries exist in **three** near-identical copies: `Closure.contracts` (`:133-166`), `ExecutionOrder.edges` (`:107-134`), `OpenQuestions.disconnectedSeeds` (`:57-79`).
- `Touchpoint` is `record Touchpoint(Kind kind, String value)` (`sdd-plan/.../spec/Touchpoint.java:7`); `resolve` uses only those two accessors.
- View **`v_repo_dep_edge(from_repo_id, to_repo_id, mode, declared_via)`** — `from_repo_id` = **consumer**, `to_repo_id` = **provider**.
- FTS: `fts_symbol(identifier, fqcn, words, module_id UNINDEXED)`; `FtsRetriever(Jdbi).search(q, limit)` → `List<Hit(identifier, fqcn, moduleId, score)>`, **lower score is better**, deterministic tie-break on identifier then module_id. `FtsSymbolWriter.insert` is public so tests can seed it.
- ⚠ `SddConfig.retrieval` (`fts`|`embeddings`) is validated by `ConfigLoader` but **read nowhere** — both call sites hardcode `new FtsRetriever(jdbi)` and no `EmbeddingsRetriever` exists.
- `GraphCommand.java:17-62` is the read-only command template: `@Command(name, description)` with no `exitCodeOnInvalidInput`; `@Option --workspace`; `@Spec CommandSpec`; `implements Callable<Integer>`; `catch (RuntimeException e) { err.println("error: " + e.getMessage()); return 1; }`. Its stdout-or-`--out` branch flushes explicitly (`:44-54`).
- Empty-KB guard, byte-identical in `GraphCommand:32-42` and `PlanCommand:129-139`: `error: knowledge base is empty — run sdd index first` on **stderr**, exit **1**.
- `HttpChatModel(endpoint, maxAttempts)` throws `ConfigException(endpoint.apiKeyError())` in its constructor when the credential is missing — so it must be built inside the catch-all. `PlanCommand` uses `SEED_MAX_ATTEMPTS = 2` for assistive calls.
- Test seam idiom: package-private `ChatModel plannerForTest` on the command (`PlanCommand.java:59`), set directly by tests.
- Structured-call shape (`PlanDrafter`, `ModelSeeder`, `RepoCardGenerator`): `SYSTEM_PROMPT` text block → *"Return exactly ONE JSON object, no markdown fences:"* → literal JSON skeleton → `Rules:` including an anti-invention rule. `complete(new ChatRequest(model, List.of(system, user), List.of(), maxTokens, 0.15))`. Parse: fence sanitizer → `JSON.readTree` → **field-by-field validation, every drop noted, never silent**. `finishReason == "length"` is unavailability, never a partial answer.
- `ScriptedChatModel` is in `sdd-core` **testFixtures** (`sdd/core/testing/ScriptedChatModel.java`); `requests()` exposes what was sent. `ClosureTest.java:19-57` builds a complete 6-repo estate in ~40 lines of raw `h.execute("INSERT …")` — copy that fixture.
- `sdd-cli` already depends on core, index, plan and agent. `docs/commands.md` (430 lines) documents every command with a `file:line` citation per claim.

## File Structure

| File | Responsibility |
|---|---|
| `sdd-core/.../core/kb/EntityKind.java`, `Resolution.java`, `EntityMatch.java` | **New.** The entity vocabulary shared by Gate 1 and explain. |
| `sdd-core/.../core/kb/KbEntities.java` | **New.** The five resolution queries, moved from `SeedFinder`, now returning per-match provenance. |
| `sdd-core/.../core/kb/KbStatus.java`, `Provenance.java` | **New.** Index-quality warnings (moved from `Closure`) and the KB snapshot header. |
| `sdd-core/.../core/kb/ContractEdges.java` | **New.** One definition of the REST/Kafka cross-repo edge queries. |
| `sdd-plan/.../impact/SeedFinder.java`, `Closure.java`, `ExecutionOrder.java` | Delegate to the extracted queries; behavior byte-identical. |
| `sdd-cli/.../cli/explain/` | **New package.** `Intent`, `EntityRef`, `RetrievalRequest`, `QuestionInterpreter`, `Evidence`/`Section`/`Fact`, `EvidenceCollector` + per-intent fact classes, `EvidenceRenderer`, `AnswerNarrator`, `AnswerAudit`, `ExplainReport`. |
| `sdd-cli/.../cli/ExplainCommand.java` | **New.** The command; registered in `SddCli`. |
| `docs/commands.md`, `README.md`, the design spec | The `explain` section, and the amendment recording this phase's resolved decisions. |

---

### Step 0: Record the resolved decisions in the spec — DONE (60de009)

The 2026-08-12 amendment explicitly defers three choices to this phase. Before Task 1, append a short amendment (house style: names the lines it modifies, states the decision and *why*, states what is deliberately out of scope) recording: **(a)** retrieval is interpret → deterministic fetch → narrate, two model calls, chosen over the 4A tool loop because reusing `AgentLoop` requires extracting a tool-provider seam from `Toolbox` — code the implement path depends on — and would hand a read-only command `apply_edit` and `run_gradle`; **(b)** output is prose + an `## Evidence` section, stdout or `--out`; **(c)** `impact` uses `Closure.expand` only — no `ModelSeeder`, no third model call. Commit as `docs:` before the plan commit.

---

### Task 1: `sdd.core.kb` — entity resolution, status, provenance

**Files:** Create `sdd-core/src/main/java/sdd/core/kb/{EntityKind,EntityMatch,Resolution,KbEntities,KbStatus,Provenance}.java`; Modify `sdd-plan/.../impact/SeedFinder.java`, `Closure.java`; Test `sdd-core/src/test/java/sdd/core/kb/{KbEntitiesTest,KbStatusTest}.java`.

**Interfaces:**
- Produces: `enum EntityKind { REPO, ENDPOINT, TOPIC, CLASS, ARTIFACT }`; `record EntityMatch(String repo, String detail, String source)`; `record Resolution(EntityKind kind, String value, List<EntityMatch> matches)` with `repos()` / `isEmpty()`; `KbEntities.resolve(Jdbi, EntityKind, String)`, `.missReason(EntityKind)`, `.repoOfModule(Jdbi, long)`, `.repoNames(Jdbi)`, `.topicNames(Jdbi)`; `KbStatus.warnings(Jdbi, Set<String>)`, `.provenance(Jdbi)` → `record Provenance(int repoCount, String earliestIndexedAt, String latestIndexedAt)`.
- Consumes: `Jdbi`, `sdd.core.route.Routes`.

This is a **move**, not a rewrite. `SeedFinder` keeps its `Touchpoint.Kind → EntityKind` mapping and delegates; `Closure.statusWarnings` delegates to `KbStatus.warnings`. `Resolution` returns more than `SeedFinder` needs (explain wants *which* endpoint matched, not just which repos) — that is additive, and `repos()` must be byte-identical to the old return.

- [ ] **Step 1: Write the failing tests.** All five kinds resolving exactly as before: repo by exact name; endpoint with and without a leading verb, through `Routes.normalize`/`templatesMatch`/`verbsCompatible`; topic via `kafka_role`; class by **both** simple name and FQCN (the `:dotless` branch); artifact by `grp:name`, and `List.of()` for a value with no colon. Each miss returns an empty `Resolution` and the exact existing `missReason` sentence. `matches()` carries the specific matched row. `KbStatus.warnings` reproduces `Closure`'s string byte-for-byte (`OpenQuestions` matches on `warning.contains("indexed with status")` — that coupling is why it must not change).
- [ ] **Step 2: Run — expect RED.** `./gradlew :sdd-core:test --tests 'sdd.core.kb.*'`
- [ ] **Step 3: Implement**, adding the missing `ORDER BY r.name` to the artifact branch — call it out at review as an intentional determinism fix, not an accidental behavior change.
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.** `SeedFinderTest`, `ImpactAnalysisTest`, `ClosureTest`, `OpenQuestionsTest` must pass **unmodified**.
- [ ] **Step 5: Commit** — `refactor: one definition of KB entity resolution and index status`

---

### Task 2: `ContractEdges` — one definition of the cross-repo contract queries

**Files:** Create `sdd-core/src/main/java/sdd/core/kb/ContractEdges.java`; Modify `sdd-plan/.../impact/Closure.java`, `ExecutionOrder.java`; Test `sdd-core/src/test/java/sdd/core/kb/ContractEdgesTest.java`.

**Interfaces:**
- Produces: `record RestEdge(String consumerRepo, String providerRepo, String verb, String normPath, String confidence, String matchedBy)`; `record KafkaEdge(String producerRepo, String consumerRepo, String topic)`; `ContractEdges.rest(Jdbi)`, `.kafka(Jdbi)`.
- Consumes: `Jdbi`.

**Ruling (do not re-litigate):** delegate rather than adding a fourth copy. Three near-identical copies already exist, and this project spent Phase 5C-2 paying down exactly this kind of duplication — a fourth copy written by an author who *knows about the other three* is indefensible. `OpenQuestions.disconnectedSeeds` may stay as-is; its projection is unfiltered by repo inequality, so note the difference rather than forcing it. **Cost if wrong:** two Gate-1 files churn before any user-visible feature exists; the regression gate is their existing tests passing unmodified.

- [ ] **Step 1: Write the failing test** — same edges, same ordering as the existing queries, plus the detail columns (`verb`, `normPath`, `confidence`, `matchedBy`) the callers previously projected away.
- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement**; `Closure.contracts` and `ExecutionOrder.edges` project from `ContractEdges`.
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.** `ClosureTest` and `ExecutionOrderTest` pass **unmodified**.
- [ ] **Step 5: Commit** — `refactor: one definition of the cross-repo contract edges`

---

### Task 3: The interpretation contract and call 1

**Files:** Create `sdd-cli/.../cli/explain/{Intent,EntityRef,RetrievalRequest,QuestionInterpreter}.java`; Test `sdd-cli/src/test/java/sdd/cli/explain/{QuestionInterpreterTest,ExplainFixture}.java`.

**Interfaces:**
- Produces: `enum Intent { DESCRIBE, CONSUMERS, DEPENDENCY_PATH, IMPACT, SEARCH }`; `record EntityRef(EntityKind kind, String value, boolean object)`; `record RetrievalRequest(Intent intent, List<EntityRef> entities, List<String> searchTerms, String restatement, List<String> notes, boolean modelUnavailable)`; `QuestionInterpreter.interpret(...)` and package-private `fallback(Jdbi, question, reason)`. Plus `ExplainFixture` — the `ClosureTest` 6-repo estate + `repo_card` rows + `FtsSymbolWriter.insert` symbols, shared by Tasks 3–8.
- Consumes: Task 1's `KbEntities`, `ChatModel`/`ChatRequest`/`ChatResponse`/`ModelException`.

The JSON contract, deliberately small enough that every field is validatable:

```
{"intent": "describe"|"consumers"|"dependency_path"|"impact"|"search",
 "restatement": string,
 "entities": [{"kind":"repo"|"endpoint"|"topic"|"class"|"artifact",
               "value": string, "role": "subject"|"object"}],   // role optional, default subject
 "search_terms": [string, ...]}
```

`role` exists solely to orient `dependency_path` ("why does A depend on B"); every other intent ignores it. `restatement` is printed verbatim as `Interpreted as: …` — the cheapest defence against a silently misread question, since a misreading becomes visible instead of becoming a wrong answer. No SQL, no table names, no limits in the contract.

Validation, mirroring `ModelSeeder.parse` exactly — every drop noted, never silent: unknown intent → coerce to `search`; unknown kind → drop; entity resolving to zero repos → drop **with `KbEntities.missReason`**; `dependency_path` missing a surviving subject or object → downgrade to `consumers` or `search`; `describe`/`consumers`/`impact` with zero entities → downgrade to `search`; >4 entities or >8 terms → truncate.

**The deterministic fallback must not guess an intent.** Inferring "this sounds like an impact question" from keywords is inference presented as interpretation. It does only literal matching — exact KB repo names as whole words, exact topic names, dotted identifiers resolving as `CLASS`, `VERB /path` shapes resolving as `ENDPOINT` — and emits `DESCRIBE` per named entity plus `SEARCH`. Header: `interpreter unavailable: <reason> — showing the facts about the entities named in your question`.

- [ ] **Step 1: Write the failing tests** with `ScriptedChatModel`: each intent parses; every validation row above; fenced ` ```json ` stripped; `ModelException` / `finishReason=="length"` / null content / non-JSON / JSON-array each produce the fallback with a distinct reason and `modelUnavailable == true`; the fallback finds a literal repo, topic, FQCN and `GET /path` — and **given a question containing the word "impact", still does not choose `IMPACT`**; the sent request contains the question and system prompt (via `requests()`).
- [ ] **Step 2: Run — expect RED.** `./gradlew :sdd-cli:test --tests 'sdd.cli.explain.QuestionInterpreterTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: interpret an explain question into a validated retrieval request`

---

### Task 4: Evidence model and collector — `describe`, `dependency_path`, `search`

**Files:** Create `sdd-cli/.../cli/explain/{Fact,Section,Evidence,EvidenceCollector,RepoFacts,DependencyFacts,SearchFacts}.java`; Test `sdd-cli/src/test/java/sdd/cli/explain/EvidenceCollectorTest.java`.

**Interfaces:**
- Produces: `record Fact(String text)`; `record Section(String title, String source, List<Fact> facts, int totalCount)`; `record Evidence(Provenance provenance, RetrievalRequest request, List<Section> sections, List<String> caveats)` with `isEmpty()`; `EvidenceCollector.collect(Jdbi, Retriever, RetrievalRequest)`.
- Consumes: Tasks 1 and 3.

Per intent: **describe** → repo row, `repo_card.card_line`/capped `card_md` *labelled model-generated*, modules, endpoints, kafka roles, deps both directions via `v_repo_dep_edge`, top `java_type` `ORDER BY is_api DESC`. **dependency_path** → BFS shortest path over `v_repo_dep_edge`, per-hop `dep_edge` detail (`to_grp:to_name`, `configuration`, `declared_version`, `declared_via`, `mode`), `api_usage` fqcns between the two repos' modules, contract edges. **search** → `FtsRetriever.search`, hits mapped module→repo, section labelled `[fts_symbol (bm25)]` so the output is honest about which backend answered.

`RepoFacts` re-expresses `RepoCardGenerator`'s SQL **without** the README read — that file is unversioned working-tree text and must not be presented as a KB fact.

- [ ] **Step 1: Write the failing tests** on `ExplainFixture`: `describe` yields all sections with the card labelled; `dependency_path` on the `svc-orders → lib-api → lib-core` chain returns the hop sequence with concrete `dep_edge` detail and `api_usage` fqcns; **`dependency_path` with no path emits an explicit "no internal Gradle dependency path from A to B in the knowledge base" fact and falls back to contract edges — never a fabricated reason**; `search` preserves best-first ordering and is stable across two identical calls; a section over its limit renders `+N more (showing 25 of M)`; same KB + same request ⇒ identical `Evidence`.
- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: collect deterministic evidence for describe, dependency-path and search`

---

### Task 5: Collector — `consumers`, `impact`, and the absence guard

**Files:** Create `sdd-cli/.../cli/explain/{ConsumerFacts,ImpactFacts}.java`; Modify `EvidenceCollector`; Test `EvidenceCollectorTest` (append).

**Interfaces:**
- Produces: `ConsumerFacts.of(Jdbi, Resolution)`, `ImpactFacts.of(Jdbi, Set<String> roots)` — note `ImpactFacts` takes **no model**, by signature.
- Consumes: Tasks 1, 2, 4; `Closure.expand`.

**consumers** dispatches on the resolved kind: repo → inbound `v_repo_dep_edge` + inbound `api_usage` grouped by consumer + `ContractEdges`; endpoint → the `rest_call_edge` rows for the *resolved endpoint ids* with verb, path, confidence, `matched_by`; class → `api_usage WHERE target_fqcn` grouped by consumer with `ref_kind`; topic → both roles with `group_id`/`payload_type`; artifact → `dep_edge` by `to_grp`/`to_name`.

**impact** → roots from the resolution → `Closure.expand` → render **the roots explicitly first**, then `added`, `cycles`, `warnings`. (`expand` omits roots from `added`; an impact answer that leaves out the repo you asked about is the defect this guards.)

**The absence guard is mandatory, deterministic, and not left to the prompt.** Whenever `consumers` or `impact` runs, count `rest_client` rows with `resolution <> 'LITERAL'` and `kafka_topic` rows with `resolution = 'DYNAMIC'` scoped to the repos in play, and emit a caveat. "Nothing consumes X" is the single most dangerous sentence this command can produce, and the KB cannot support it.

- [ ] **Step 1: Write the failing tests**: each of the five kinds hits the right relation; an endpoint matching more than one repo yields **every** match plus an explicit ambiguity fact, never a silently chosen one; `impact` output **contains the subject repo** (the roots regression); the absence caveat fires with its counts; and a call-count test — run the impact intent with a `ScriptedChatModel` and assert `requests().size() == 2`, proving no third model call.
- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: collect consumer and impact evidence, with the absence caveat`

---

### Task 6: `EvidenceRenderer` (pure)

**Files:** Create `sdd-cli/.../cli/explain/EvidenceRenderer.java`; Test `EvidenceRendererTest.java`. Can run in parallel with Task 5 once Task 4 lands.

**Interfaces:** Produces `EvidenceRenderer.render(Evidence) → String` and `EVIDENCE_CAP`. Consumes Task 4's records only — no `Jdbi`, no I/O.

Rules: every section titled with the KB table or view it came from in `[brackets]`; zero-fact sections omitted entirely (the `ReviewReport`/`CurationReport` idiom); `Interpretation` always rendered, including dropped entities with their `missReason`; `Caveats` rendered when non-empty; truncation always *stated* via `+N more (showing 25 of M)`; `Markdown.neutralizeFences` applied to `card_md` and to any model prose before it enters markdown.

**`EVIDENCE_CAP = 12000`, a deliberate deviation from `PlanDrafter`'s 4000** — that cap governs a per-repo bundle inside a prompt no human reads, whereas this string *is* the human-facing audit trail, so truncating it hides facts from the user rather than just the model. With per-section limits in place the cap is rarely reached. Do **not** "solve" this by capping the prompt copy and printing the full one: that breaks prompt == printed, which is the property the whole design rests on.

- [ ] **Step 1: Write the failing tests** — empty sections omitted; provenance tags present; dropped entities rendered with their reason; a `card_md` containing a literal ``` neutralized; the cap applied with a visible `…(truncated)` marker; byte-identical output for identical input.
- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: render evidence as the audit trail the answer is grounded in`

---

### Task 7: `AnswerNarrator`, `AnswerAudit`, `ExplainReport`

**Files:** Create `sdd-cli/.../cli/explain/{AnswerNarrator,AnswerAudit,ExplainReport}.java`; Test `{AnswerNarratorTest,AnswerAuditTest,ExplainReportTest}.java`.

**Interfaces:** Produces `AnswerNarrator.narrate(...) → record Answer(String prose, List<String> notes, boolean unavailable)`; `AnswerAudit.check(String answer, String evidence, Jdbi) → List<String>`; `ExplainReport.render(...) → String`. Consumes Tasks 3 and 6.

The narrator prompt returns **prose, not JSON**, and carries these rules: answer only from the evidence below; name no repo, topic, endpoint or class absent from it; when the evidence is thin, say so; **never conclude "nothing consumes X" — say "the knowledge base records no consumer of X"** and repeat any caveat; treat `repo_card` text as a summary, not a structural fact.

`AnswerAudit` is a cheap deterministic backstop: load `repo.name` and `kafka_topic.name` (both `UNIQUE` and distinctive), and note any that appear in the answer but not in the evidence. **Document honestly what it cannot catch** — an invented *relationship* between two repos both legitimately present is undetectable. It is a hallucination smoke alarm, not a correctness check, and the docs must not overclaim it.

- [ ] **Step 1: Write the failing tests** — the call-2 prompt contains the evidence string **exactly** as printed (the auditability invariant, asserted by comparing `requests()` against rendered output); `ModelException`/`length`/empty content ⇒ `unavailable` with reason and `ExplainReport` still renders full Evidence; `AnswerAudit` flags a repo and a topic present in the answer but absent from evidence, and does not flag ones present in both; `ExplainReport` renders `Interpreted as:`, prose, audit notes, then `## Evidence`, plus each degraded shape.
- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: narrate the answer over the evidence, and audit it against the KB`

---

### Task 8: `ExplainCommand` and registration

**Files:** Create `sdd-cli/src/main/java/sdd/cli/ExplainCommand.java`; Modify `SddCli.java`; Test `sdd-cli/src/test/java/sdd/cli/ExplainCommandTest.java`.

```java
@Command(name = "explain", description = "Answer a question about the estate from the knowledge base")
// no exitCodeOnInvalidInput — the graph/plan 0/1 family
@Option(names = "--workspace") Path workspace = Path.of(".");
@Option(names = "--out")       Path out;
@Parameters(index = "0", arity = "0..*") List<String> question;   // joined with spaces
@Spec CommandSpec spec;
ChatModel plannerForTest;                                          // PlanCommand's seam
```

`arity = "0..*"` plus an explicit empty check (`error: missing required parameter: <question>`, exit 1) rather than `1..*`, so a bare `sdd explain` gets the house-style message instead of picocli's exit 2.

Degradation ladder, every rung exiting **0** and still printing Evidence: missing credential (`HttpChatModel` throws in its constructor) → deterministic fallback; call 1 fails → fallback with reason; no entity resolves → `search` with every drop listed; **zero facts → skip call 2 entirely** and print `no facts in the knowledge base match this question`; call 2 fails → `answer unavailable: <reason> — the facts below are complete`.

- [ ] **Step 1: Write the failing tests** — missing KB and empty KB both print the exact shared message to stderr, exit 1, and **do not create `.sdd/index.db`**; happy path with two scripted responses exits 0 with prose and `## Evidence` on stdout; `--out` writes markdown and prints `explanation written: <path>`; every degradation rung exits 0 and still prints Evidence (use an exception-throwing model, not `ScriptedChatModel` exhaustion); `explain` is registered on `SddCli`; **nothing is written to the workspace without `--out`** (snapshot the file listing before and after).
- [ ] **Step 2: Run — expect RED.** `./gradlew :sdd-cli:test --tests 'sdd.cli.ExplainCommandTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: sdd explain answers a question from the knowledge base`

---

### Task 9: Documentation

**Files:** Modify `docs/commands.md` (an `## sdd explain <question>` section between `graph` and `implement`), `README.md` (the command list and the quickstart), and the design spec if Step 0's amendment needs correcting against what shipped.

Every claim carries a `file:line` citation — the file's own standard. Document the flags, the 0/1 exit codes, what it writes (nothing, unless `--out`), the two-model-call shape, and — plainly — that the answer is grounded in the printed evidence and that `AnswerAudit` catches invented *names*, not invented *relationships*. Must land after Task 8 so citations are real.

- [ ] **Step 1:** Enumerate the command's flags and exit codes from source.
- [ ] **Step 2:** Write the `docs/commands.md` section; update `README.md`.
- [ ] **Step 3:** Re-read both against source, correcting anything aspirational.
- [ ] **Step 4: Commit** — `docs: sdd explain in the command reference and README`

**Ordering:** Step 0 → T1 → T3 → T4 → {T5 (needs T2), T6} → T7 → T8 → T9. T2 any time before T5.

## Verification

1. `./gradlew build` — all modules green.
2. **Amendment coverage:** read-only (T8's no-writes test), planner as interpreter/synthesizer (T3, T7), deterministic facts from SQLite (T4, T5), empty-KB mirroring `graph`/`plan` (T8), and the three previously-open decisions recorded in Step 0's amendment.
3. **Gate-1 regression:** `SeedFinderTest`, `ImpactAnalysisTest`, `ClosureTest`, `ExecutionOrderTest`, `OpenQuestionsTest` all pass **unmodified** after Tasks 1–2.
4. **No third model call:** the impact-intent test asserts exactly two requests.
5. **Real-estate readiness:** against `~/projects/github/trading-estate` (6 repos, indexed), run each spec example question — "why does trading-ops depend on platform-libs?", "what consumes the tier-resolver API?", "what would change if I modify the admin spreads endpoint?" — and confirm each answer's claims appear in its own `## Evidence` section. Then run one question about something that does not exist and confirm it says so rather than inventing.

## Known carried items (explicitly NOT in this phase)

- **`SddConfig.retrieval` is dead config.** `ConfigLoader` validates `fts`|`embeddings` and demands a `models.embeddings` endpoint for the latter, but nothing reads it and no `EmbeddingsRetriever` exists — a user configuring `embeddings` silently gets FTS. *Trigger:* `retrieval: embeddings` in `sdd.yml`. *Symptom:* FTS results with no indication. Explain does not fix it, but labels its search section `[fts_symbol (bm25)]` so its own output is honest. Fixing it means a `Retrievers.of(config, jdbi)` factory and an actual embeddings backend.
- **`AnswerAudit` cannot detect invented relationships.** It flags names absent from the evidence; an answer asserting a false dependency between two repos both legitimately present passes. *Cost if wrong:* over-trust in a smoke alarm. Documented in T9.
- **KB staleness is invisible.** Nothing compares `repo.head_commit` to the working tree at read time, so answers can be arbitrarily behind reality while sounding current. Mitigated by printing `indexed <max(indexed_at)>` and `KbStatus` warnings; a git freshness check is a different command's job.
- **`OpenQuestions.disconnectedSeeds` keeps its own copy of the contract queries** (its projection is unfiltered by repo inequality). Noted rather than forced into `ContractEdges`.
- **No conversational follow-up.** Each invocation is independent; there is no session or history.

### Deferred by the final review (2026-08-15)

Both were found by the whole-branch review, judged real, and deliberately not fixed in this phase.

- **Small helpers duplicated inside `sdd.cli.explain`.** `kindLabel` is defined identically in `EvidenceCollector` and `EvidenceRenderer`; `mentionsWholeWord` identically in `AnswerAudit` and `QuestionInterpreter`; the `grp:name` split is duplicated between `KbEntities.resolveArtifact` and `ConsumerFacts.artifactConsumers`. *Trigger:* editing one copy. *Symptom:* the copies drift silently — neither has a test that would notice. *Ruling:* deferred; this phase's fix wave was already eight items on an approved branch. *Cost if wrong:* a little duplication to pay down, in a package that just finished paying down exactly this kind of debt.
- **Two `rest_endpoint` rows in one repo with the same verb and path emit each caller fact twice.** `ConsumerFacts.endpointConsumers` calls `callersOf` once per `EntityMatch`; two rows differing only in `class_fqcn`/`method_name` produce identical `detail` strings, and the ambiguity check counts distinct *repos*, so no ambiguity section fires. *Trigger:* the same endpoint declared on two controller methods in one repo. *Symptom:* a duplicated caller line, with no explanation of why. *Cost if wrong:* the answer is redundant, never wrong — the facts are all true, one is just stated twice.

## Self-Review (completed at write time)

1. **Spec coverage:** every clause of the 2026-08-12 amendment maps to a task (Verification 2); the three decisions it defers are resolved in Step 0 and implemented in T3 (retrieval), T6/T7 (output), T5 (impact without a model).
2. **Placeholder scan:** the only elisions are per-task test bodies, each pinned to a named test class with its assertions enumerated. No "TBD", no "handle edge cases".
3. **Type consistency:** `EntityKind`/`Resolution`/`KbStatus`/`Provenance` (T1) → T3, T4, T5, T6; `ContractEdges` (T2) → T4, T5; `RetrievalRequest`/`Intent` (T3) → T4, T5, T6, T7; `Evidence`/`Section`/`Fact` (T4) → T5, T6; `EvidenceRenderer.render` (T6) → T7, T8. **T2 must land before T5**; **T6 must land before T7**.
4. **Judgment calls for reviewers:** extracting to `sdd-core` rather than duplicating queries in explain (drift between what `plan` and `explain` think an entity is would be user-visible); delegating the third contract-query copy rather than adding a fourth; `EVIDENCE_CAP = 12000` deviating from `PlanDrafter`'s 4000 with a stated reason; the deterministic fallback refusing to guess an intent; skipping call 2 on empty evidence; and `AnswerAudit` shipping as an explicitly partial check rather than not shipping.
