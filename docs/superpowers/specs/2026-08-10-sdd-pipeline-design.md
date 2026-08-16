# sdd — Multi-repo Spec-Driven Development pipeline

## Context

A 5-year-old estate of 40+ Git repos (Java Spring services + shared Java libraries, Gradle everywhere, mixed library-consumption styles) is moving to Spec-Driven Development: a structured feature spec goes in, coordinated code changes across many repos come out. The available models are context-limited — Qwen3.6-35B (262k), run locally from the HuggingFace model `Qwen3.6-35B-A3B-8bit`, and DeepSeek-V4-Flash-0731-384 (384k) via its remote API — so no model can ever see the whole estate. The tool to build, `sdd`, closes that gap **deterministically**: parsers extract estate-wide facts once; models only ever do local, precisely-scoped work.

All design decisions below were brainstormed and approved section-by-section with the user (2026-08-10). A 4-agent design workflow (3 subsystem designers + adversarial critic) produced the raw design; the critic's 3 blockers and 8 major findings are folded in and marked where load-bearing.

**Fixed decisions:** Java 21; CLI (no server); full pipeline v1 (index → plan → implement → review); own agent loop (no external harness); operates on a directory of existing local checkouts; no git pushes, no Nexus publishing in v1; own markdown spec format; human gates at plan approval and final diff review; deterministic-first architecture; switchable retrieval backend (FTS default, embeddings optional).

## System shape

One Gradle project, modules `sdd-index`, `sdd-plan`, `sdd-agent`, `sdd-cli` (picocli). Subcommands:

```
sdd index                  # build/refresh knowledge base
sdd plan <spec.md>         # impact analysis → plan.md
sdd plan approve <plan.md> # validate + compile plan.json   ← GATE 1
sdd implement <runId>      # unattended topological agent runs
sdd review <runId>         # per-repo diff review            ← GATE 2
sdd status / sdd clean
```

Workspace-local state: `.sdd/index.db` (SQLite, WAL), `.sdd/runs/<runId>/` (file-based run state), `sdd.yml` (single config: repo excludes, Gradle-version→JDK map, artifact/edge overrides, model endpoints, retrieval backend, per-repo test exclusions).

**Model routing:** DeepSeek (384k) = whole-estate synthesis (impact seeding, plan drafting, attempt-2 escalation). Qwen (262k) = local work (repo cards, coding agent). Per-model `max_tokens` (planner ≥16k); `finish_reason=length` is an explicit failure, never half-parsed.

**Model runtime:**
- **Qwen (local):** HuggingFace model `Qwen3.6-35B-A3B-8bit` (8-bit MLX quant, MoE ~3B active — fits this macOS machine), downloaded via `huggingface-cli download` and served with `mlx_lm.server` exposing OpenAI-compatible `/v1/chat/completions` on localhost. A `scripts/serve-qwen.sh` setup script installs `mlx-lm`, fetches the model, and starts the server; `sdd.yml` defaults point at it. mlx-lm's native tool-call support is exactly what Spike 3 benchmarks — the fenced-text `tool_protocol` fallback likely becomes the default on this stack.
- **DeepSeek (remote API):** OpenAI-compatible endpoint (`https://api.deepseek.com/v1`), model `deepseek-v4-flash`. Authentication via `DEEPSEEK_API_KEY` environment variable, interpolated into `sdd.yml` (`api_key: ${DEEPSEEK_API_KEY}`) — the key the user provided is set in the local shell/`.env` at setup time and is never written to any committed file.

**Retrieval (user-requested flag):** `Retriever` interface; `sdd.yml retrieval: fts | embeddings` + `--retrieval` override. `fts` = SQLite FTS5 over type/member identifiers. `embeddings` = OpenAI-compatible `/v1/embeddings` endpoint; type-level chunks (FQCN + public signatures + javadoc) and repo cards embedded at index time; vectors in `sqlite-vec` inside the same `index.db`. The flag switches only the fuzzy layer (free-text seed resolution, un-anchored file ranking). Graph-exact lookups are SQL always.

**Key libraries:** `gradle-tooling-api`, `javaparser-symbol-solver-core`, `sqlite-jdbc` + `jdbi3`, `jgit`, `snakeyaml`, `tomlj`, `commonmark` (+ yaml-front-matter ext), `jackson` (core/yaml, strict), `japicmp`, `jtokkit` (×1.15 safety margin, calibrated against endpoint `usage`), `picocli`, `jqwik` + `wiremock` (tests). LLM client is hand-rolled on `java.net.http` (~400 lines) — no framework.

## Component 1 — Knowledge layer (`sdd-index`)

- **Scan:** first-level children of workspace containing `.git`. Classification per Gradle *module* (Boot plugin/config ⇒ SERVICE; maven-publish ⇒ LIBRARY; both ⇒ SERVICE; neither ⇒ UNKNOWN); repo kind is a display rollup only.
- **Gradle extraction:** Tooling API drives each repo's own wrapper + injected init script (Groovy, Gradle 5.6-safe APIs) dumping JSON: modules, plugins, publication coords, declared + resolved deps (lenient resolution), `includedBuilds`. Daemon JDK from wrapper-version→JDK map in `sdd.yml` (old Gradle dies on JDK 21). Always `--no-configuration-cache` (version-gated). 10-min/repo timeout. **Fallback:** static parse of `build.gradle(.kts)` + `libs.versions.toml` → declared-only edges, status `DEGRADED`.
- **Consumption mode per internal edge** (critical enum, shared by ALL layers — critique M2): `PINNED | SNAPSHOT | DYNAMIC | COMPOSITE | BOM_MANAGED`, plus `declared_via: DIRECT | CATALOG | BOM` recording the actual declaration site (may be a different repo).
- **Source extraction:** JavaParser + symbol solver backed by the resolved compile classpath jars from the Gradle step. Lombok members synthesized from annotations (signatures suffice; ignore-list for non-member-generating annotations like `@Slf4j`). Extracted: library public API surface (+ signature hashes), REST endpoints (`@RestController` etc., context-path aware), REST clients (Feign / RestTemplate / WebClient / RestClient) with a **resolution ladder** (literal → constant folding → `@Value`/config property → `DYNAMIC` with raw expression), Kafka `@KafkaListener` consumers + `KafkaTemplate` producers (same ladder), flattened config properties per profile, **file-level reference graph `file_ref`** and identifier FTS (critique B1 — these power context-pack selection), `api_usage` (which module touches which internal library types).
- **Global link passes:** artifact→module mapping (publication coords preferred; `UNIQUE(grp,name)` with loud conflicts; `sdd.yml artifactOverrides`), REST client→endpoint matching (Feign name+path HIGH / unique path MEDIUM / ambiguous LOW / `manualEdges` MANUAL), Kafka topic linking. Curation report lists every `DYNAMIC` client/topic. Planner rule: **unresolved ≠ nonexistent**.
- **Repo cards** (Qwen): `card_line` ≤30 tokens + `card_md` ≤450 tokens from a deterministic ≤12k-token selection (README head, module list, endpoint/topic lists, deps, ≤3 key files with ~3k/file cap); cached by input hash.
- **Incremental:** fingerprint = HEAD sha + dirty hash; per-repo transactions, keep-last-good (`STALE_OK`); statuses per phase (`OK/DEGRADED/STALE_OK/FAILED`) that the planner must downgrade confidence on. Global link passes rerun after any repo change.
- **Schema:** `repo, module, artifact, dep_edge(mode, declared_via, is_internal, to_module_id), java_type, api_member, api_usage, file_ref, rest_endpoint, rest_client, rest_call_edge(confidence), kafka_topic, kafka_role, config_property, repo_card, meta` + FTS5 table (+ sqlite-vec table when embeddings enabled) + view `v_repo_dep_edge` for repo-granularity closure. Indexer owns the schema; planner queries it (critique B1).

## Component 2 — Planning (`sdd-plan`)

- **Spec format:** YAML front matter (`id, title, owner, status`) + fixed H2s — required: Goal, Requirements, Acceptance Criteria; optional: Background, Constraints, Touchpoints, Out of Scope, Open Questions. ID-prefixed bullets (`R1:`/`A1:`/`C1:`/`Q1:`) parsed by a strict state machine; Touchpoints (`repo|endpoint|topic|class|artifact: value`) are hints verified against the KB, never trusted.
- **Impact analysis:** (A) deterministic pre-seed — touchpoints resolved via KB, free text via Retriever, provenance recorded; (B) model seeding — one DeepSeek call (spec + all card_lines/cards + seeds) → `{repo, role, covers, reason}` JSON; model/graph discrepancies recorded and surfaced, never silently resolved; (C) deterministic closure — **full transitive closure over internal Gradle edges** (api/implementation annotates "code change likely" vs "bump/rebuild only", never limits propagation — critique M1); REST/Kafka edges add 1-hop contract blast radius marked `pending-contract`; `BOM_MANAGED` pulls the declaration-site repo into the affected set (M2). **Repo-level SCC detection** at plan time: cycles co-scheduled as one unit or hard-fail naming the cycle (M3).
- **plan.md** (human-editable Gate-1 artifact): Summary; Open Questions (deterministic detectors — unresolvable touchpoint, disconnected seeds, contract-vs-constraint conflict, uncovered requirement, unresolved-caller warnings — plus model-emitted questions; `[blocking]` items need written resolutions); Affected Repos (role/why/mode); **Excluded Candidates with reasons**; Execution Order; Interface Contracts (java-api with compilable skeletons, rest deltas, kafka deltas) in fenced YAML; Repo Steps (sub-spec naming real files/classes from the KB, `covers` requirement IDs, `provides/consumes_contracts`, `version_action`, verification tasks).
- **`plan approve`** (critique M5): strict validation (YAML schemas w/ line numbers; execution order is a legal topo order naming any violated edge; contract provider/consumer closure; every requirement covered; blocking questions resolved) → **compile** plan.md + KB → `plan.json` (affected subgraph edges, modes, per-edge propagation mechanism chosen by live `--include-build` smoke test, base SHAs) → pin SHA-256 of both files. Human-edit paths: direct edits re-validated; substantive changes (`sdd plan revise`) re-run generation with Q&A appended, bumping `plan_version`.

## Component 3 — Agent loop + orchestration (`sdd-agent`)

- **Work order** ≤ ~25k tokens (critique B2 — lean prompt beats context dump for 35B): sub-spec + referenced spec bullets and **actualized** upstream contracts (never-trimmed floors), repo card, ranked file manifest with one-line reasons (KB seeds → 1–2 hops over `file_ref` → matching tests). Agent reads files itself.
- **Loop:** `ChatModel` interface (`HttpChatModel` / `RecordingChatModel` / `ReplayChatModel` / `ScriptedChatModel` — the testing seam). Native OpenAI tool-calls; config `tool_protocol: native | fenced` fallback. Six tools: `read_file` (400-line/16KB cap), `list_files`, `search` (pure-Java regex walk), `apply_edit` (search/replace blocks; exact match with one whitespace-lenient fallback pass; Java edits syntax-checked via JavaParser and auto-reverted on failure; creation = empty search block), `run_gradle` (allowlisted tasks; orchestrator appends substitution flags invisibly; no generic shell), `done(success|blocked, summary)`.
- **Guardrails:** path jail (`toRealPath` prefix check), `.git/**` denied, agent has zero git verbs (orchestrator owns git via JGit: branch/checkout/add/commit/diff/reset-to-recorded-SHA only, never push/remote), env-scrubbed subprocess with **per-repo `JAVA_HOME`** from the same JDK map (critique M6), 15-min Gradle timeout with process-tree kill.
- **Context management:** endpoint `usage`-based accounting; 80k soft cap; deterministic oldest-first eviction of tool results only (stubs left in place); preserved always: system prompt, work order, last Gradle output, 2 latest reads, all edit turns. No model summarization. Exhaustion → `CONTEXT_EXHAUSTED` fresh restart with machine-built digest (edits persist on disk). Budgets: 40 turns / 45 min / 1.5M cumulative tokens per attempt (30M per run, configurable); 3-strike malformed-call rule; identical-action and identical-build-signature wedge detection; 2 done→verify-fail cycles = attempt failure. Max 2 attempts; attempt 2 = hard reset to base + attempt-1 digest + **escalation to DeepSeek**.
- **Orchestration:** Kahn topo sort over Gradle edges only (SCC units co-scheduled; REST/Kafka provider-first as tie-break); virtual-thread workers, separate `gradle_workers` and `model_concurrency` semaphores; run state in `runs/<runId>/` (`plan.json` immutable, `state.json` atomic temp+rename, `events.jsonl`, per-repo `transcript.jsonl`/`edits.jsonl`, `m2/`, `review/`); resumable (`--resume`: verify branch HEADs = checkpoints, reset any `IN_PROGRESS` to last checkpoint, fresh attempt). Checkpoint commits on `sdd/<runId>/<slug>` branches. Pre-flight: clean trees, base-SHA check with **staleness recovery** (M8: moved HEAD → re-index → diff plan-relevant surfaces → auto-advance or demand per-repo re-plan), `./gradlew --version` warm-up, disk check, workspace lock.
- **Library propagation without publishing** (critique B3): primary = injected `--include-build <lib-checkout>` on every dependent Gradle call (version-agnostic substitution; works for PINNED and SNAPSHOT); fallback = run-scoped `mavenLocal` (`publishToMavenLocal -Pversion=<planned> -Dmaven.repo.local=<runDir>/m2` + init-script repo injection); mechanism per edge chosen at plan time by smoke test, recorded in plan.json and the Gate-2 report. PINNED/BOM edges also get the version-bump edit at the real declaration site. COMPOSITE injects nothing.
- **Contract actualization between topo levels, orchestrator-owned** (critique M4): after an upstream goes green — re-extract real signatures/endpoints/topics from its tree into downstream work orders; when a contract declares `compat: binary-compatible`, japicmp against the baseline jar resolved from Nexus; breaking drift ⇒ upstream FAILED before any consumer starts. Actual contracts live in `runs/<runId>/` files (KB stays read-only during implement).
- **Verification:** agent inner loop targeted; on `done`, orchestrator independently runs the plan step's verification tasks (per-repo exclusions from `sdd.yml`, surfaced as "not locally verified" — M7). Output compacted deterministically ≤4k tokens (javac patterns; JUnit XML, never console-scraped; project-package stack filtering; "N more errors omitted"). INFRA-classified failures (resolution/network/daemon/Docker) never reach the agent — retry once, then `PAUSED_INFRA` or scoped skip.

## Component 4 — Review + consistency (`sdd review`)

Deterministic consistency pass first: estate rebuild of all affected repos in topo order with final substitution flags; contract re-check (re-run extractors, diff vs plan deltas; mismatches = report warnings, human adjudicates); **release runbook** emission (ordered: release lib X from commit Y → merge pinned dependents → snapshot dependents pick up on republish). Gate 2: `review/report.md` (statuses, diffstats, verification/contract results, token spend, failure codes, runbook) + per-repo `.diff` files; terminal approve/reject/redo/view flow, decisions persisted, resumable. Approve = squash to one templated commit (`Sdd-Run:` trailer), restore original branch. Invariants: dependent unapprovable while consumed upstream rejected; library redo auto-re-verifies downstream subtree. `sdd clean` removes unapproved branches + run dir.

## Error handling (cross-cutting)

Endpoint outage: backoff w/ jitter (6 tries, `Retry-After` honored) → `PAUSED_ENDPOINT` + resume command (optional `--wait-endpoint`). HTTP 400 → one eviction retry → `CONTEXT_EXHAUSTED`. Index failures degrade per repo/phase, never sink the run. Every transition → `events.jsonl` + one structured console line; exit codes 0/2/3/4 = COMPLETE/PARTIAL/PAUSED/ABORTED.

## Testing

- **Fixture estate** in test resources: ~6 tiny real Gradle git repos (Gradle 6.9/7.6/8.10 wrappers; pinned/SNAPSHOT/composite/BOM consumers; Feign+RestTemplate pair; Kafka pair; Lombok; `@Value` route; one broken build). Integration tests (`@Tag("gradle-it")`) run real indexing + implement.
- Golden-file index tests (canonical sorted JSON dumps); incrementality mutation tests.
- Unit: mode classification, route normalization/matching, constant-folding ladder, Lombok synthesis, static fallback parser, spec/plan parser round-trip (byte-identical re-render), token budgeter floors/ceiling, edit applier edge cases, path jail w/ symlinks, Gradle output parser vs captured logs, crash-resume idempotency.
- `jqwik` property tests: topo validator + scheduler on random DAGs (order, eligibility, failure cascade to `SKIPPED_UPSTREAM_FAILED`).
- Agent loop: `ScriptedChatModel` for mechanics (compaction, strikes, wedges, budget exhaustion); `Replay` with request-hash verification for golden transcripts; wiremock for HTTP-level; fault injection for PAUSED/resume.
- E2E dry run: fixture estate + fixture spec → plan → implement (replayed models) → review report assertions.

## Implementation order

**Phase 0 — de-risking spikes (against the real estate; each can force design revision):**
1. Index prototype (connector + init script + JSON dump) over all 40 repos → OK/DEGRADED/FAILED split. >20% DEGRADED ⇒ rework quality assumptions.
2. `--include-build` smoke test over every internal edge → fallback rate; validate `-Pversion=` vs release plugins (nebula/axion).
3. ~400-line ChatModel client + 10-task scripted benchmark: Qwen native tool-calls + search/replace edits at 25–80k prompts on the local mlx-lm server (`Qwen3.6-35B-A3B-8bit`) → decides `tool_protocol` default (native vs fenced) and the 80k cap; also measures local tokens/sec to size agent-loop wall-clock budgets.
4. REST/Kafka extraction recall sample → % DYNAMIC ⇒ curation burden.

**Phase 1 — skeleton:** Gradle multi-module project, picocli CLI, `sdd.yml` + SQLite schema + migrations, `ChatModel` + Retriever interfaces, fixture-estate test harness. Model runtime setup: `scripts/serve-qwen.sh` (install `mlx-lm`, download `Qwen3.6-35B-A3B-8bit` from HuggingFace, start OpenAI-compatible server), `DEEPSEEK_API_KEY` env wiring (user-provided key, set locally, never committed), and an `sdd doctor` check that both endpoints answer. Commit design spec to `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md`.
**Phase 2 — `sdd index`:** scan → gradle-extract (+fallback) → source-extract → link passes → cards → curation report; golden tests green.
**Phase 3 — `sdd plan` + `approve`:** spec parser, 3-phase impact analysis, plan.md render, validators, plan.json compiler + smoke tests.
**Phase 4 — `sdd implement`:** agent loop + tools + guardrails; orchestrator, state machine, propagation mechanisms, contract actualization, verification.
**Phase 5 — `sdd review` + consistency pass + runbook; `status`/`clean`; polish + docs.**

Each phase lands with its tests; TDD per superpowers:test-driven-development during implementation.

## Verification (end-to-end)

1. Unit + property suites per phase (`./gradlew test`).
2. `gradle-it` fixture-estate integration suite: full `index → plan → approve → implement (replayed models) → review` producing asserted branches, diffs, state.json, report.md.
3. Phase-0 spike reports checked against thresholds above (the real-estate numbers are the acceptance criteria for the design's assumptions).
4. First live pilot: one small real feature spec through the whole pipeline with a human at both gates; measure seed recall/precision and token spend per repo.

## Open items deliberately deferred (post-v1)

Git-hosting API integration (auto-PRs), central server/CI mode, embeddings-quality eval harness, spring-cloud-stream extraction, shared-DB edges, weekly live eval set.

---

## Amendment (2026-08-11): Spec ingestion — Confluence-first, format-extensible

Requirement change: SDD specs currently exist ONLY as Confluence pages (plain text + pictures + table data); no canonical SDD format is defined yet. The original "own markdown format" decision is retained as the INTERNAL model, not the input format.

**Design:** `sdd-plan` ingests specs through a `SpecSource` seam:
- `interface SpecSource { NormalizedSpec load(String ref); }` — implementations are selected by ref shape/config.
- **v1 adapter — ConfluenceExportSource:** reads an exported Confluence page file (storage-format XHTML or exported HTML). Text is extracted; tables are converted to markdown tables; images are NOT interpreted in v1 — they are recorded as attachment references in the normalized spec so the Gate-1 reviewer knows visual context exists.
- **Normalization step (planner model, DeepSeek):** maps the free-form content into the internal structured spec model — unchanged from the original design: front matter (id/title/owner/status) + Goal + Requirements `R#` + Acceptance Criteria `A#` + Constraints `C#` + optional Touchpoints/Out of Scope/Open Questions. IDs are auto-assigned; anything the model cannot confidently map becomes an Open Question. The normalized spec is written to a file the human edits/approves exactly like a hand-written spec BEFORE impact analysis runs — Gate 1 covers normalization errors.
- **Extensibility:** when the canonical SDD format is specified, it becomes another `SpecSource` adapter (the already-designed structured-markdown parser is the passthrough case). A Confluence REST API source (config: `confluence: {base_url, api_token: ${CONFLUENCE_API_TOKEN}}`) is a planned extension of the same seam — v1 uses exported files to avoid new infrastructure.

**Unchanged:** everything downstream of ingestion (impact analysis, plan.md, contracts, agents) consumes the internal structured model only; the deterministic-first principle holds because normalization output is human-gated before anything acts on it.

## Amendment (2026-08-12): closure annotation source

The M1 "code change likely" vs "bump/rebuild only" annotation derives from `api_usage`
evidence (consumer modules referencing provider-repo types), not from api/implementation
declaration scope: `dep_edge.configuration` records resolved classpath names
(compileClasspath/runtimeClasspath), so declaration scope is not available in the KB. The
annotation still never limits propagation.

## Amendment (2026-08-12): sdd graph command

User requirement: a small standalone phase (3C-3, after 3C-2) adds `sdd graph [--workspace <ws>] [--out <file>]` — a read-only, model-free renderer of the knowledge base's estate graph as Mermaid: repo-level nodes styled by kind (SERVICE/LIBRARY/UNKNOWN), internal Gradle edges labeled with their consumption mode, REST call edges (client repo → provider repo, labeled by confidence) and Kafka topic edges (producer → consumer, labeled by topic) as visually distinct link types. Output to stdout by default, `--out <file>` to write a file. Deterministic ordering (same KB → byte-identical output); empty-KB handling mirrors `sdd plan`'s error. Detailed scope (module-level drill-down, affected-subgraph filtering by spec) is decided when the phase is planned.

## Amendment (2026-08-12): sdd explain command

User requirement: add `sdd explain <free-text question>` — a **read-only** natural-language query interface over the knowledge base. The user asks a plain-English question about the estate (e.g. "why does trading-ops depend on platform-libs?", "what consumes the tier-resolver API?", "what would change if I modify the admin spreads endpoint?"); a model INTERPRETS the question, the tool retrieves the relevant DETERMINISTIC facts from the KB (dependency graph, `api_usage`, REST/Kafka edges, repo cards, and the existing impact/retrieval machinery), and the model synthesizes a grounded answer citing those facts. Deterministic-first holds: the model interprets the question and narrates the answer, but the facts it reasons over come from SQLite, not the model's memory — so answers are grounded and auditable. No code changes, no writes; runs against the current KB (mirrors `sdd graph`/`sdd plan` empty-KB handling). Model routing: the planner (DeepSeek, whole-estate synthesis) is the natural interpreter/synthesizer; a retrieval step selects the fact set. **This is a standalone feature to be brainstormed → spec'd → planned as its own phase** — the exact retrieval scope (which KB relations feed the answer, how the question maps to queries: pure FTS/retriever seeding vs a small tool-calling loop over KB query tools vs a fixed fact bundle), the output format (prose answer + cited facts / a structured "evidence" section), and whether it reuses the 4A agent-tool loop with read-only KB tools are decided when the phase is planned. Sequencing relative to the Phase-4C sub-phases is the user's call.

## Amendment (2026-08-13): configurable N-tier model escalation ladder

Line 59's "Max 2 attempts; attempt 2 = hard reset to base + attempt-1 digest + escalation to DeepSeek" is generalized: `sdd.yml`'s `run:` section gains an optional `escalation_ladder` (a list of `models:` keys, in attempt order), defaulting to `[coder, planner]` — exactly the original two-tier shape. The orchestrator walks the ladder tier by tier; after any attempt whose result needs escalation (VERIFY_FAILED/EXHAUSTED/BUDGET/MALFORMED/WEDGED), it hard-resets to base, re-applies bump edits, and runs the next tier — as long as one exists and the run token budget isn't exhausted — otherwise it stops with the last outcome. Every tier after the first sees a digest naming every prior attempt (one-line summary each) plus the most recent one's full verification output, capped at 4000 chars. A 1-entry ladder means no escalation ever happens; an N-entry ladder (N > 2) escalates up to N-1 times. Default configuration reproduces today's behavior byte-for-byte.

## Amendment (2026-08-14): declared contract grammar, and the Gate-2 plan-conformance axis

Line 51 already requires Interface Contracts to hold "java-api with compilable skeletons, rest deltas,
kafka deltas" in fenced YAML, and `PlanMdRenderer` already emits the fenced block — but `PlanDrafter`
fills it with prose, so line 66's "diff vs plan deltas" has never had a machine-comparable baseline to
diff against. `ContractActualizer.javaApi` uses `contract.body()` only as a *selector* for which types
to re-extract, never as a comparison baseline, so an implementation that shipped a different interface
than Gate 1 approved re-checks as `MATCHES`. This amendment closes that.

**The declared block's grammar is the actualizer's own output vocabulary**, normalized. That constraint
is the design: a contract may declare only what Gate 2 can extract, because anything else builds a
check that cannot check. Per kind:

- `java-api` — one member per line, `<fqcn>#<signature>: <returnType>`, e.g.
  `com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier`. Types are compared by
  simple name, matching what `ApiSurfaceExtractor` emits.
- `rest` — `<METHOD> <pathTemplate>`, e.g. `GET /api/admin/tier-spreads`. The handler class behind the
  extractor's `-> <fqcn>#<method>` is an implementation detail no one approves at Gate 1, so it is
  excluded from both the declaration and the comparison. Status codes and response types are not
  extracted today and therefore stay in prose.
- `kafka` — `<role> <topic>` with role `produces` or `consumes`, e.g. `produces orders.v1`. This is the
  one kind where the *normalized* half of the rule does real work: `KafkaExtractor` writes the role as
  the literal `PRODUCER`/`CONSUMER` and the actualizer emits that field verbatim, so both sides are
  canonicalized onto the human spelling (`PRODUCER` → `produces`, `CONSUMER` → `consumes`) and a
  declaration may be written either way. Those two are the only role values the extractor ever
  produces, so any other role is a grammar error at Gate 1 rather than a member that can never match.

Prose keeps its place alongside the block: it carries intent and the constraints no extractor sees.

**Gate 2 gains a second, independent axis.** The existing `Status` answers "did the implementation
change since the run recorded it". Plan conformance answers "does it match what Gate 1 approved" —
a different question, so it is a separate field rather than another `Status` constant, which avoids a
precedence fight when a contract both drifted and diverged. Values: `DECLARED_MET`,
`DIVERGED_FROM_PLAN`, `NOT_DECLARED`, `NOT_COMPARABLE`. The check is **containment**: every declared
member must appear in the freshly extracted surface; extras are not divergence, because adding API is
not breaking a contract.

Three rules keep the axis honest:

1. **Truncation suppresses the verdict.** Bodies are capped at `MAX_BODY`; a declared member absent
   only because extraction was cut off reports `NOT_COMPARABLE`, never `DIVERGED_FROM_PLAN` — the same
   reasoning that `TRUNCATED_MATCH` already encodes for the drift axis.
2. **A plan with no declared block reports `NOT_DECLARED`, never `MATCHES`.** Plans frozen before this
   amendment have prose-only bodies; they must not read as conforming.
3. **When a declared block is present it replaces the prose selector, and `javaApi`'s
   `relevant.isEmpty() ? all : relevant` fallback does not apply.** Dumping the entire API surface when
   nothing matches would mask the strongest divergence signal there is — that none of the declared
   types exist at all.

Divergence is a **warning** that never fails the review, consistent with line 66's "mismatches = report
warnings, human adjudicates". It renders in its own report section and is counted in the Summary, so
the human deciding approve/reject sees it. Coupling it to `Decisions.approve` as a refusal is
deliberately left out of scope.

## Amendment (2026-08-14): unresolved extraction is its own conformance verdict

The 2026-08-14 declared-contract amendment gave `Conformance` four values. It needs a fifth. Line 42's
planner rule is **unresolved ≠ nonexistent**, but the conformance axis as shipped cannot say
"unresolved", so it says `DIVERGED_FROM_PLAN` — the same verdict a genuinely wrong implementation
gets. A `@Value`-driven Kafka topic and a verbless `@RequestMapping` are both reachable on a real
estate, and both would be reported as an implementation defect when the truth is that extraction
could not see far enough.

*Correction (phase 5C-2, task 3):* this amendment originally also named an unresolvable REST path
expression as a third reachable shape. Implementation proved that claim false — see the `rest`
bullet below, which now states what is actually distinguishable instead of what was assumed to be.

`Conformance` gains **`NOT_RESOLVED`**: a declared member whose actual counterpart exists but could not
be resolved by extraction. It ranks with `NOT_COMPARABLE` rather than `DIVERGED_FROM_PLAN` — the report
states what is not known rather than accusing — and like every other conformance value it is a warning
that never changes an exit code.

The signal travels differently per kind, because the extractors differ and this amendment deliberately
does not change `sdd-index`:

- `kafka` — `SpringModel.KafkaUse` already carries `resolution()`, and `ContractActualizer` marks an
  entry whose `resolution()` is `DYNAMIC` rather than emitting it as if it were a literal topic.
  *Correction (phase 5C-2, final review):* this bullet originally justified the marker as
  "`KafkaExtractor` falls back to the raw expression as the topic when `ValueResolver` cannot resolve
  it". That is only one of the two shapes the marker actually covers. `KafkaExtractor` also hardcodes
  `resolution()` to `DYNAMIC` for **every** `@KafkaListener(topicPattern = …)`, whether or not the
  pattern text itself resolved — so a plain literal pattern arrives at the conformance axis marked
  unresolved even though extraction read it perfectly. The marker therefore means "this entry is not
  a literal topic to compare for equality", not "extraction could not see this".
  **Consequence for the partition rule:** an unresolved kafka entry excuses a missing declared member
  only when it could plausibly *be* that member — the same topic, or a genuinely unresolvable
  `${…}`/`#{…}` expression (which could have been anything), or a pattern that actually matches the
  missing topic. Matching on the role alone would let one resolved `topicPattern` listener excuse
  every missing declared `consumes` member on the contract, reporting `NOT_RESOLVED` about a surface
  extraction read exactly — the identical false-negative shape the `rest` bullet below rejects the
  `pathTemplate == "/"` heuristic for. The pattern step is a heuristic over the pattern text (Java
  regex semantics, not Spring's own topic-pattern matching); an invalid pattern excuses nothing.
  Fixing the root cause means giving `KafkaExtractor` a way to distinguish a resolved pattern from an
  unresolved one, which is an `sdd-index` change 5C-2's global constraint forbids.
- `rest` — `SpringModel.EndpointInfo` carries no resolution field, and adding one would reach into the
  indexed knowledge base. Only one degenerate shape is recognisable from the extractor's own output
  without that field: the verb `ANY` (a verbless `@RequestMapping`), which `DeclaredContract`'s
  method vocabulary cannot legally declare in the first place. An empty path template is **not**
  distinguishable this way, despite `RestEndpointExtractor.resolvePaths` substituting `""` on a
  failed resolution: `RestEndpointExtractor.extract` always joins that result through
  `Routes.join(base, path)` (`sdd-core/src/main/java/sdd/core/route/Routes.java`), whose own floor
  guard — `if (joined.isEmpty()) return "/";` — means the joined `pathTemplate` field can never
  literally be `""`. It floors to `"/"` for every combination of empty base and empty path,
  regardless of *why* either was empty, so a genuinely unresolvable path expression produces exactly
  the same `pathTemplate` as a plain bare-root endpoint (e.g. a `@GetMapping` with no path attribute
  at all, which reaches `"/"` through the same floor without `resolvePaths`'s substitution branch
  ever firing). `EndpointInfo` carries no field that tells the two apart, so this shape is dropped
  rather than approximated by a heuristic that would misclassify real root endpoints — see the
  plan's Known carried items.
- `java-api` — no unresolved shape exists; type extraction either sees a member or does not.

The rule that keeps this honest is the same one the axis already follows: **a member is only
`NOT_RESOLVED` when the reason it is missing is a named unresolved shape on the actual side.** A
declared member absent from a fully resolved surface stays `DIVERGED_FROM_PLAN`. Where a contract has
both, divergence wins the verdict — an implementation known to be wrong is the more actionable fact —
and the report names the unresolved members separately so the human can see why the rest could not be
judged.

## Amendment (2026-08-14): sdd explain — the three deferred decisions, resolved

The 2026-08-12 `sdd explain` amendment deliberately left three choices to the implementing phase:
how a question maps to queries, what the output looks like, and whether it reuses the 4A agent-tool
loop. Phase 6 resolves them. Everything that amendment made binding is unchanged: read-only with no
writes, empty-KB handling mirroring `sdd graph`/`sdd plan`, and the planner endpoint as interpreter
and synthesizer.

**Retrieval is interpret → deterministic fetch → narrate, in two model calls.** Call 1 turns the
question into a *validated retrieval request* — an intent plus entities, each resolved against the KB
and dropped with a stated reason when it does not exist. Deterministic SQL then runs; **no model
participates in retrieval**. Call 2 narrates over the retrieved evidence and nothing else.

Reusing the 4A agent-tool loop was rejected on a mechanical ground, not a stylistic one: `AgentLoop`
takes the concrete `Toolbox`, not an interface, so a read-only KB toolbox would require extracting a
tool-provider seam from code the implement path depends on — and the alternative, extending
`Toolbox`'s switch, would hand a read-only command `apply_edit` and `run_gradle`. A one-shot fixed
bundle was rejected because it cannot target the question: "what consumes X" and "why does A depend
on B" would receive the same facts.

**The output is a prose answer followed by an `## Evidence` section**, to stdout by default and to a
file with `--out`. The rendered evidence string is *both* the call-2 prompt and the printed section —
one string, so an answer can never be grounded in facts its reader cannot see. That identity is the
auditability property this command exists for, and it is enforced by test rather than convention.

**Impact questions are answered by `Closure.expand` alone** — the deterministic reverse closure over
the dependency graph. The model-driven `ImpactAnalysis`/`ModelSeeder` pipeline is deliberately not
reused: it would add a third model call and let a model contribute repos to what must be a
deterministic closure. `Closure.expand(Jdbi, Set<String>)` takes no model, and that signature is the
guarantee.

Three rules keep the command honest, each deterministic rather than left to a prompt:

1. **Never narrate over an empty evidence bundle.** When retrieval yields no facts, call 2 is skipped
   entirely — a narrator handed nothing is exactly where invention happens.
2. **Absence is never asserted as fact.** `rest_client.resolution` is not always `LITERAL` and
   `kafka_topic.resolution` can be `DYNAMIC`, so the KB cannot support "nothing consumes X". Every
   consumer and impact answer carries a caveat counting the unresolved callers and dynamic topics in
   the repos in play.
3. **`repo_card` text is model-generated and labelled as such** wherever it appears, and may never be
   the sole basis for a structural claim — it is a model's earlier summary stored in SQL, not an
   extracted fact.

Deliberately out of scope: conversational follow-up (each invocation is independent), any git
freshness check against the working tree, and fixing `SddConfig.retrieval` being dead config — the
search section is instead labelled with the backend that actually answered.

## Amendment (2026-08-16): npm/TypeScript repos, and the cross-language REST join

The Context above says "Java Spring services + shared Java libraries, Gradle
everywhere". That is no longer true of the estate: the micro-frontends moved
into their own TypeScript/npm/Vite repos, and half the estate now builds with
npm. This amendment records what changed and, more importantly, what was
refused.

**Build systems are detected, not assumed.** `sdd.index.extract.BuildExtractor`
reads a repo into a neutral `BuildModel`; `GradleModel` stays exactly as it was,
because it is the deserialization shape of the `sdd-init.gradle` JSON contract.
Gradle is offered every repo first, so a Spring service shipping a
`package.json` for frontend assets stays a Gradle repo. A repo no extractor
claims is `UNSUPPORTED`, which is a fact about the workspace, rather than
`FAILED`, which is a problem to investigate.

**npm coordinates are `("npm", <full package name>)`.** Splitting `@scope/pkg`
into group and name was the obvious alternative and is wrong: an unscoped
package like `react` would carry an empty group, and `ArtifactRef.parse`
requires both halves of a `grp:name` reference to be non-empty, so those
packages would be unaddressable in specs and in `sdd explain`.

**Version grammar is per-ecosystem.** `ModeClassifier` encodes Maven rules,
under which `^0.2.1` reads as PINNED — the exact opposite of what it means.
Every internal specifier in this estate is a caret range, so sharing one
classifier would have mislabelled every npm edge and reported nothing. Both
classification sites dispatch on the consuming module's language.

**TypeScript is read by the TypeScript compiler**, run under `node`, with the
compiler shipped as an ordinary jar dependency. Two rules govern it: only real
syntax counts, and anything unresolvable is marked rather than guessed. The
first is not hypothetical — the SDK documents `/api/streams` in a JSDoc block
and does not call it, so a text scraper invents a caller that does not exist.

**TypeScript call sites go into `rest_client`**, the table the Spring extractor
already fills. `RestMatcher`, `ContractEdges`, the impact closure and every
explain fact read that table and none of them mention a language, so a
TypeScript caller became visible everywhere a Java one is without new query
code.

**No cross-language edge is HIGH confidence.** A browser talks to one origin
and an ingress fans it out, so which service serves a path is genuinely absent
from TypeScript source. MEDIUM — exactly one endpoint in the estate matches
this verb and path — is the strongest claim the evidence supports. HIGH
requires a human, via `manual_edges`.

**A bug this exposed, fixed independently:** `Closure.expand` drained its
build-edge BFS and only then applied contract edges, without re-enqueueing what
they added — so a repo reached through a REST or Kafka contract never had its
own consumers expanded. Pre-existing and equally wrong for Java; its regression
test is a Java-only estate for that reason.

**A TypeScript symbol is recorded under the name a CONSUMER writes** —
`@azastashkov/web-sdk.Tick`, resolved through the package's `exports` map — not
under the file it is declared in and not under the `dist` path it is published
as. Those are three different names for one thing, and only the first joins two
repos: `UsageLinker` pairs a reference to its provider by string equality on
`java_type.fqcn`, so recording either of the others leaves every consumer of the
package pointing at nothing.

**`EntityKind.SYMBOL` is separate from `CLASS`.** `CLASS` resolves a dotless
name by suffix, which is a Java package convention rather than a fact about
names. Sharing one kind would make the bare name `Tick` resolve to both a Java
type and a TypeScript export inside a single citation — a cross-language
conflation in the one place a reader has no way to notice it. `SYMBOL` is exact
match only.

**Three contract kinds were added, and the estate decided their grammar.**
`ts-api` (`<moduleSpecifier>#<Export>[.<member>]: <type>`) is `java-api`'s npm
counterpart, addressed by specifier for the reason above. `rest-client` reuses
`rest`'s `<METHOD> <path>` grammar with the CALLER as provider — chosen over
adding a consumer axis to `rest` specifically because `ContractRecheck`
actualizes `contract.provider()` and nothing else, so this needed no structural
change. `stream-descriptor` is the only kind either toolchain can provide,
because the md/candle/order shapes are built twice — from Java builders in the
registering service and from an object literal in the browser SDK — with no
compiler between them. It is scoped to two axes, `key` and `channels`, because
those are the two BOTH actualizers can see; the wider surface (lvc templates,
conflation, interest) is derivable in Java and absent from the TypeScript
built-ins, so declaring it would declare what one end could never check. The
two-owner problem needed no change to the plan model: the contract is declared
twice, once per provider, and both extractors produce the same body — verified
byte-identical on the real `trading-web-sdk` and `trading-platform-libs`.

**Gate 1 pairs compat with kind, and kind with toolchain.** Each compat value
names a comparison that exists for exactly one kind (japicmp reads bytecode, the
TypeScript check reads declarations), and a `ts-api` contract whose provider is
a Gradle repo actualizes to nothing — reported at Gate 1, where the message
names the typo, rather than at Gate 2 as a wholly missing surface. A repo whose
`build_system` is still NULL blocks nothing: a plan must not be refused because
the knowledge base predates a migration.

**A ts-api declaration selects what is actualized, and on a real package it has to.**
`java-api` has treated its declared block as a selector since 5C-1, but `ts-api`
shipped without one and emitted every export of every entry point in a single
body. A Java contract names a handful of classes out of a module; a TypeScript
package's whole surface is one body, and on the real `trading-web-sdk` that is
~53kB against `ContractActualizer.MAX_BODY`'s 4000 — so the declared members sat
past the cut and every `ts-api` contract could only report `NOT_COMPARABLE`. The
axis could not reach a verdict on the packages it was added for. Selection is by
`<specifier>.<Export>` rather than by the part before the `#`, because there that
prefix is the module specifier and keying on it would re-select the whole
package. Granularity is the export, matching `java-api`'s whole-type selection:
the reader gets the declared export's full shape, and containment only requires
the declared members to be present. Truncation is now proportional to what a plan
declares instead of to how large the provider happens to be.

**The evidence a plan is drafted from must be in the grammar the plan must emit.**
`PlanDrafter` rendered every knowledge-base row with the java-api template,
`<fqcn>#<member>`. For TypeScript that transposes both separators: the knowledge
base records `<specifier>.<Export>` and a declaration addresses
`<specifier>#<Export>`, so the one place the prompt tells a model to copy from
disagreed with the grammar it is asked to produce. Since the prompt also tells it
to omit rather than guess, `ts-api` contracts were drafted with no declarations at
all — and after the selector above, an undeclared contract is precisely the one
Gate 2 cannot check. The trap ran both ways: a transposed line VALIDATES, because
the specifier still starts with `@`, and then selects nothing, so a model that had
copied the evidence would have produced a contract that passed Gate 1 and
actualized to nothing. The naming now lives in `sdd.core.contract.TsNames`, used
by both the evidence renderer and the actualizer, because two hand-mirrored copies
of it is what drifted in the first place.

**`type-compatible` is an assignability probe, not a diff.** A textual diff of
two `.d.ts` files flags legal widening — an added optional member, a widened
parameter — as breaking, and compat drift FAILS the repo, so a false positive
there fails work that was correct. The probe is compiled by the pinned compiler
with `strict` on: without `strictFunctionTypes` parameters are bivariant and
narrowing `(id: string | number)` to `(id: string)` reads as compatible. It is
the one mode that cannot run `noLib` — without `lib.es5`, `Promise` and `Date`
are unknown and every member compares as broken.

**A specific path is no longer ambiguous against a wildcard it merely overlaps.**
`templatesMatch` treats `{}` as a wildcard on BOTH sides, so a call to
`/api/candles/{}/symbols` matched `/api/candles/{}/{}` as well and landed
LOW/AMBIGUOUS against an endpoint it plainly is not — and `manual_edges` could
not correct it, because its own endpoint filter used the same `templatesMatch`
and so selected both endpoints too. `RestMatcher` now narrows a fuzzy candidate
set to exact template matches when any exist, exactly as
`KbEntities.resolveEndpoint` already did after the same symptom. Confidence
stays MEDIUM: narrowing says WHICH endpoint, not that the client reaches that
service. Purely a narrowing — when nothing matches exactly the original set is
returned untouched, so a genuinely templated client is unaffected. On the real
estate this turned four LOW/AMBIGUOUS edges into the two correct MEDIUM ones,
and every call site in the estate now resolves to exactly one endpoint.

**A compatibility gate that did not run now fails the review.** A repo that
DECLARED `binary-compatible` or `type-compatible` and whose gate never reached a
verdict was indistinguishable from one that passed: SUCCEEDED, unmentioned in
the report, exit 0. The outcome is recorded structurally per repo rather than
scraped back out of the prose agent events, and `sdd review` exits 2 with a
`## Compatibility gates that did not run` section. Deliberately narrow: a gate
that BROKE already failed the repo, a repo that never succeeded is not listed,
and a plan declaring no guarantee stays silent — a review that warns about every
run teaches a reader to skip the warning. Writing the record exposed a second
instance of the same bug, that `compatDrift` returned the same empty report
whether the jars matched or every pair had been skipped; zero comparisons is now
SKIPPED, not PASSED.

Both of the above change existing Gradle behaviour, which is why they were
sequenced after everything else and revert alone.
