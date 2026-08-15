# sdd command reference

This is the exhaustive reference for every `sdd` subcommand: what it does, its
real flags, its real exit codes, and what it writes to disk. Every claim below
was checked against the source at the file:line noted in parentheses; where a
detail could not be verified, it is left out rather than guessed.

For the two-gate model this reference serves (`plan approve` = Gate 1,
`review` + a decision = Gate 2) and a worked end-to-end sequence, see
[`README.md`](../README.md).

Run any command as `sdd-cli/build/install/sdd/bin/sdd <command> ...` after
`./gradlew :sdd-cli:installDist` (`sdd-cli/build.gradle.kts`: `application`
plugin, `mainClass = "sdd.cli.SddCli"`, `applicationName = "sdd"`). Every
command defaults `--workspace` to the current directory
(`Path workspace = Path.of(".")`).

## Exit codes, in general

`sdd` does not use one exit-code convention everywhere — the commands split
into two families:

- **`doctor`, `index`, `plan`, `plan approve`, `plan revise`, `graph`** use a
  plain success/failure split: **`0`** on success, **`1`** on an application
  error (bad config, validation problems, an empty knowledge base, an
  unhandled exception). None of these set `exitCodeOnInvalidInput`, so a
  malformed invocation picocli itself rejects (an unknown option, for
  example) falls through to picocli's own default, **`2`** — the
  `CommandLine.ExitCode.USAGE` constant. Verified live:
  `sdd plan --help` prints `Unknown option: '--help'` and exits `2`, because
  none of these commands declare `-h`/`--help` themselves — only the top-level
  `sdd` does (`SddCli.java:8`, `mixinStandardHelpOptions = true`, not
  inherited by subcommands).
- **`implement`, `review` (and its `approve`/`reject`/`redo` subcommands),
  `clean`, `status`** all declare `exitCodeOnInvalidInput = 4` and use a
  4-way taxonomy: **`0`** clean success, **`2`** a real finding (a repo not
  `SUCCEEDED`, a failed rebuild/checkout/restore, checkpoint drift, a
  refused decision), **`3`** paused (only `implement`), **`4`** unusable
  input or a live run lock. `status` never returns `2` or `3` — it is
  read-only and never judges (`StatusCommand.java:92-93`).

## `sdd doctor`

**What it does:** checks the local environment is ready to run everything
else — Java major version, `sdd.yml` loads, `.sdd/index.db` opens (creating it
if absent), and every configured model endpoint answers a probe.
(`DoctorCommand.java:17-53`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `DoctorCommand.java:19-20` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | every check passed |
| `1` | at least one check failed (`DoctorCommand.java:52`, `allOk ? 0 : 1`) |

**Writes to disk:** nothing beyond `Database.open`'s side effect of creating
`.sdd/index.db` (and the `.sdd/` directory) if it does not already exist —
same as every other command that opens the database.

## `sdd index`

**What it does:** scans every git repo directly under the workspace
(`WorkspaceScanner.scan`: a directory with a `.git`, not excluded by
`sdd.yml`'s `excludes:`), extracts its Gradle/Spring facts into
`.sdd/index.db`, links internal dependencies, matches REST/Kafka edges, and
(unless skipped) generates a repo-card summary per repo with the `coder`
model. (`IndexCommand.java:23-116`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `IndexCommand.java:28-29` |
| `--no-cards` | off | Skip model-generated repo card summaries | `IndexCommand.java:31-32` |
| `--force` | off | Re-index every repo even when its fingerprint is unchanged, instead of skipping it as "(unchanged, skipped)". Composable with `--no-cards`; does not itself force card regeneration (cards are cached independently by content hash) | `IndexCommand.java:34-39` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | no repo was scanned, or at least one repo did not fail — `allFailed` requires a non-empty result list whose every entry is `FAILED`, so an empty workspace exits 0 too (`IndexCommand.java:109-111`) |
| `1` | config failed to load, `--no-cards` is absent and the `coder` endpoint's API key is unresolved, every scanned repo's status was `FAILED`, or an unhandled exception (`IndexCommand.java:50-52, 61-64, 109-115`) |

**Writes:** `.sdd/index.db` (schema tables for repos, modules, deps, REST
endpoints/clients, Kafka roles, repo cards) and a curation report path printed
at the end (`service.lastReportPath()`, `IndexCommand.java:108`).

## `sdd plan <ref>`

**What it does:** has two modes selected by the shape of `<ref>`
(`SpecSources.isConfluenceExport`, `PlanCommand.java:79-81`):

- **Confluence export** (`.html`/`.htm`/`.xhtml`): normalizes it into
  canonical markdown via the `planner` model, self-checks that the result
  re-parses, and writes `<ref>.spec.md` (or `--out`). This is spec
  *normalization*, not impact analysis — it prints
  `review and edit the spec, then run: sdd plan <path>` and stops.
  (`PlanCommand.java:88-114`)
- **Canonical markdown spec** (anything else, typically `.md`): validates the
  spec, requires a non-empty `.sdd/index.db` (i.e. `sdd index` already run),
  runs impact analysis (which repos are affected and why), computes an
  execution order, drafts open questions and a plan narrative with the
  `planner` model, and writes `<spec-base>.plan.md`.
  (`PlanCommand.java:116-161`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `PlanCommand.java:46-47` |
| `--out <path>` | `<ref>.spec.md` | Where to write the normalized spec (Confluence refs only; rejects a non-markdown target) | `PlanCommand.java:49-51, 89-91` |
| `<ref>` (positional, arity 0..1) | — | Spec ref: canonical `.md`, or an exported Confluence `.html`/`.htm`/`.xhtml` | `PlanCommand.java:53-55` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | normalization or plan-drafting succeeded |
| `1` | missing `<ref>`, config load failed, spec validation problems, empty/missing knowledge base, or an unhandled exception (`PlanCommand.java:67-70, 74-77, 82-85, 119-124, 129-139`) |

**Writes:** `<ref>.spec.md` (normalize mode) or `<spec-base>.plan.md` (validate
mode), each via `SafeWrite.writeWithBackup` — an existing file at that path is
backed up first, and the backup path is printed if one was made
(`PlanCommand.java:104-108, 153-157`).

### `sdd plan approve <spec>.plan.md` — Gate 1

**What it does:** the human-in-the-loop gate that freezes a reviewed
`plan.md` into `plan.json`. Parses `plan.md`, re-validates the sibling
`<spec>.md`, checks every affected repo's live git state against what the
plan expects, runs `PlanValidator` (plan-vs-knowledge-base consistency),
probes each cross-repo edge with a Gradle include-build smoke test (a failed
probe only warns and falls back to `MAVEN_LOCAL` — it never fails the
command; `PlanJson.java:92-104`), and on success compiles and writes
`<spec-base>.plan.json`. (`ApproveCommand.java:31-123`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `ApproveCommand.java:33-34` |
| `<planPath>` (positional, required) | — | The reviewed `<spec>.plan.md` | `ApproveCommand.java:36-37` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | plan approved, `plan.json` written |
| `1` | wrong file extension, spec validation problems, empty/missing knowledge base, git-state/plan-validator problems, or an unhandled exception (`ApproveCommand.java:48-52, 60-65, 66-69, 94-101, 118-121`) |

**Writes:** `<spec-base>.plan.json` (`ApproveCommand.java:107-109`). Prints
the spec and plan SHA-256 hashes that get embedded in the plan JSON.

### `sdd plan revise <spec>.plan.md`

**What it does:** regenerates a plan with the prior round's Q&A folded in,
bumping `plan_version`. Re-runs impact analysis and drafting against the
current knowledge base, using the old plan's questions/resolutions as extra
context for the `planner` model. (`ReviseCommand.java:35-111`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `ReviseCommand.java:39-40` |
| `<planPath>` (positional, required) | — | The existing `<spec>.plan.md` to revise | `ReviseCommand.java:42-43` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | plan revised |
| `1` | wrong file extension, spec validation problems, empty/missing knowledge base, or an unhandled exception (`ReviseCommand.java:55-58, 64-70, 71-74, 106-109`) |

**Writes:** overwrites `<planPath>` in place (version bumped), via
`SafeWrite.writeWithBackup` — the previous version is backed up first
(`ReviseCommand.java:98-101`).

## `sdd graph`

**What it does:** renders the knowledge base's estate dependency graph as
Mermaid, either to stdout or to a file. (`GraphCommand.java:17-62`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `GraphCommand.java:19-20` |
| `--out <path>` | stdout | Write the graph to a file instead of stdout | `GraphCommand.java:22-23` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | rendered successfully |
| `1` | knowledge base missing/empty, or an unhandled exception (`GraphCommand.java:32-35, 39-42, 57-60`) |

**Writes:** `--out`'s target file, if given; otherwise nothing (prints to
stdout).

## `sdd explain <question>`

**What it does:** answers a plain-English question about the estate from
`.sdd/index.db` in three steps — interpret, deterministic fetch, narrate. A
`planner` model call (`MODEL_KEY = "planner"`, `ExplainCommand.java:71`) turns
the question into a validated retrieval request (an intent plus entities,
each checked against the KB); plain SQL then fetches the matching facts with
no model involved; a second `planner` call narrates prose over exactly those
facts. Read-only like `graph`/`plan`'s validate path — it never writes
anything unless `--out` is given, and the `.sdd/index.db` existence check
runs before `Database.open` so opening the database is never itself the
thing that creates a KB this command is about to report as missing.
(`ExplainCommand.java:36-55, 73-144`)

The string the narrator is shown and the printed `## Evidence` section are
the same value: `EvidenceRenderer.render(evidence)` is called once to build
the call-2 user message (`AnswerNarrator.java:52`) and again, unmodified, to
print the report (`ExplainReport.java:80`) — an answer can only be grounded
in facts its reader can also see.

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `ExplainCommand.java:58-59` |
| `--out <path>` | stdout | Write the explanation to a file instead of stdout | `ExplainCommand.java:61-62` |
| `<question>` (positional, arity `0..*`) | — | The question; words are joined with single spaces | `ExplainCommand.java:64-65, 78` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | an answer was produced and printed — including a thin answer, a "no facts in the knowledge base match this question" report, or an "answer unavailable" report when the model couldn't be reached; `explain` reports, it never judges, so it never returns anything but `0`/`1` |
| `1` | the question is blank/missing, the knowledge base is missing or has zero repos (`.sdd/index.db` absent, or `SELECT count(*) FROM repo` is `0`), or an unhandled exception (`ExplainCommand.java:78-82, 84-94, 140-143`) |

**Writes:** nothing, unless `--out <path>` is given, in which case the
rendered report is written there instead of printed
(`ExplainCommand.java:128-138`).

**The two model calls, and what runs between them:**

- **Call 1 — interpret** (`QuestionInterpreter.interpret`,
  `ExplainCommand.java:98-101`): the model is shown the question plus the
  KB's known repo and topic names (`QuestionInterpreter.java:93-97`) and
  returns one JSON object naming an intent (`describe`, `consumers`,
  `dependency_path`, `impact`, or `search`) and the entities the question
  refers to. Every named entity is resolved against the KB and dropped —
  with a reason appended to the request's `notes()` — if it does not exist
  (`QuestionInterpreter.java:144-153`); the model is never trusted to have
  named something real just because it said so. If no model is configured
  (`sdd.yml` missing, `models.planner` absent, or an `api_key` env var
  unresolved) or the call itself fails, interpretation falls back to literal
  whole-word matching of known repo/topic names plus regex-shaped class and
  endpoint candidates in the question text — never inference from keywords
  (`QuestionInterpreter.java:291-352`, `ExplainCommand.java:159-171`).
- **Deterministic fetch, between the two calls**
  (`EvidenceCollector.collect`, `ExplainCommand.java:103-104`): plain SQL
  against `.sdd/index.db`, dispatched on the interpreted intent — repo,
  module, endpoint, Kafka-role and dependency facts for `describe`, a
  full-text search over `fts_symbol` for `search`, and so on. No model call
  happens anywhere in this step (`EvidenceCollector.java:26-50`).
- **Call 2 — narrate** (`AnswerNarrator.narrate`, `ExplainCommand.java:112-113`):
  the model is shown the rendered evidence string — and only that string —
  and told to answer from it alone, never naming a repo, topic, endpoint or
  class absent from it (`AnswerNarrator.java:26-46`). Skipped entirely when
  the fetch found zero facts: a narrator handed nothing is exactly where
  invention happens, so there is no call 2 to make in that case
  (`ExplainCommand.java:108-125`).

**Grounding check, and what it cannot catch:** after a narrated answer,
`AnswerAudit.check` loads every `repo.name` and `kafka_topic.name` from the
KB and flags any that appear, whole-word, in the answer but not in the
evidence it was shown (`AnswerAudit.java:49-58`). **This is a hallucination
smoke alarm, not a correctness check: it can only ever catch an invented
name, never an invented relationship.** An answer asserting a false
dependency between two repos that are both individually, legitimately
present in the evidence — e.g. "svc-billing calls svc-notify" when the
evidence never states that edge, but both names appear elsewhere in
unrelated sections — passes the audit silently, because neither name is
itself absent from the evidence (`AnswerAudit.java:18-24`). A clean audit is
not proof the answer is correct.

**Absence is never asserted as fact:** `consumers` and `impact` answers
always carry a caveat counting unresolved REST clients and dynamically-named
Kafka topics among the repos in play, because the KB cannot prove a negative
— an unresolved caller is invisible to these queries, not absent from the
estate (`AbsenceGuard.java:9-26`; the narrator is told the same rule,
`AnswerNarrator.java:39-43`).

**Staleness is not checked:** nothing compares `repo.head_commit` to the
working tree at read time, so an answer can be arbitrarily behind the real
estate while reading as current. Every answer states
`Provenance: N repos indexed; indexed <earliest> to <latest>`
(`KbStatus.provenance`, `EvidenceRenderer.java:166-178`,
`EvidenceCollector.java:49`); an `impact` answer additionally surfaces
index-status warnings for degraded/failed/stale repos in its closure, via
`Closure.expand`'s own status check (`ImpactFacts.java:79-82`,
`KbStatus.java:19-39`).

**FTS is the only retrieval backend:** `explain` always constructs an
`FtsRetriever` (`ExplainCommand.java:101`) — no `EmbeddingsRetriever` exists.
`sdd.yml`'s `retrieval` key accepts only `fts` (the default) or an absent
key; `ConfigLoader` rejects `retrieval: embeddings` at load time, before it
could be declared and then silently ignored (`ConfigLoader.java:38,
rejectUnimplementedRetrieval`). The search section's `[fts_symbol (bm25)]`
label states what actually answered (`SearchFacts.java:14-17, 58`).

**Javadoc can make a type findable, never a fact:** the indexer stores the
first sentence of each type's javadoc (whitespace-collapsed, inline tags
flattened, capped at 400 characters) in `java_type.javadoc` and in
`fts_symbol`'s `doc` column (`ApiSurfaceExtractor.javadocSummary`,
`SourcePersistence.insertType`), so a question whose wording only appears in
prose — "what closes the ordering gap?" — can still find the type that
answers it. That text is unverified: nothing here checks a doc comment
against the code it sits above. So it is weighted at the floor, well below
every identifier column (`bm25(fts_symbol, 10.0, 3.0, 8.0, 2.0, 0.0)`,
`FtsRetriever.java:70`), it never reaches any other section — `describe`,
`consumers`, `dependency_path` and `impact` are pure SQL over structural
tables — and a hit found *only* through prose is labelled
`[matched on javadoc]` on its own fact line (`SearchFacts.java:42, 56`), so
a stale comment can never reach a reader looking like code-derived evidence.
That weighting is per-term, not a ranking guarantee: bm25 scores a whole row
across term frequency, document frequency and field length, so a type whose
javadoc matches most of the question does rank above one whose name matches a
single word of it — measured, not hypothetical. The label, not the ordering,
is what tells a reader where a hit came from.
The narrator is given a rule for that label beside its `repo_card` one —
offer such a hit as a candidate whose documentation matches, not as evidence
of behaviour (`AnswerNarrator.java:46-49`).
Member-level javadoc is not indexed, and doc-only hits are deliberately not
marked in `plan.md`'s seed list.

## `sdd implement <spec>.plan.json`

**What it does:** executes an approved `plan.json` across the estate,
repo-by-repo in dependency order, with the escalation-ladder coding models
(`sdd.yml`'s `run.escalation_ladder`, default `[coder, planner]`,
`RunSettings.java:16`). This is the work that happens *between* the two
gates. A run is identified by `<specId>-v<planVersion>` and persisted under
`.sdd/runs/<runId>/`. (`ImplementCommand.java:65-423`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `ImplementCommand.java:69-70` |
| `--resume` | off | Resume a paused or crashed run of this plan from its checkpoints | `ImplementCommand.java:72-73` |
| `--retry <repo>[,<repo>...]` | none | Re-run these already-settled (`SUCCEEDED` or `FAILED`) repos on resume; repeatable or comma-separated; implies `--resume`; retrying a `SUCCEEDED` repo discards its checkpoint and resets the branch to the plan base | `ImplementCommand.java:75-78, 232-237` |
| `--wait-endpoint` | off | After a pause caused by an unreachable model endpoint, poll the ladder's endpoints every 30s and auto-resume once they all answer | `ImplementCommand.java:80-82, 93, 104-132` |
| `<planJsonPath>` (positional, required) | — | The approved `<spec>.plan.json` | `ImplementCommand.java:84` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | every repo `SUCCEEDED` ("COMPLETE") (`Orchestrator.java:139`) |
| `2` | the run finished but at least one repo did not succeed ("PARTIAL") (`Orchestrator.java:139`) |
| `3` | the run paused ("PAUSED") — an infra failure, an unreachable model endpoint, or the run's token budget exhausted (`Orchestrator.java:137-155, 230, 269, 331`) — resume with `sdd implement --resume <planJsonPath>` (or `--wait-endpoint`, for the endpoint case) (`ImplementCommand.java:373-382`) |
| `4` | unusable input: wrong file extension, no run to resume, unknown `--retry` repo, preflight/resume-prep problems, the run's lock is held by another process, or an unhandled exception (`ImplementCommand.java:153-156, 168-171, 207-210, 219-229, 239-247, 267-271, 385-388`, `exitCodeOnInvalidInput = 4` at `ImplementCommand.java:67`) |

**Writes:** under `.sdd/runs/<runId>/` — `plan.json` and `spec.md`
(snapshots taken at run start, `RunStore.java:44-51`), `lock` (held for the
duration; `RunStore.java:57-77`), atomically-published `state.json`
(`RunStore.java:106-195`), append-only `events.jsonl` (per-repo state
transitions; `RunStore.java:201-215`), `propagation.json`
(cross-repo publish plan; `RunStore.java:295-320`), and, per repo touched, a
`<repo>/` subdirectory with `agent-events.jsonl`, `transcript.jsonl` and
`edits.jsonl` (`RunStore.java:239-278`). When any plan edge needs a
`mavenLocal` fallback, also writes the Maven-local init script under the run
dir (`MavenLocalInit`, referenced at `ImplementCommand.java:305-313`).

## `sdd review <spec>.plan.json` — Gate 2 (read-only half)

**What it does:** the review half of Gate 2 (design line 66-67): checks the
whole estate out to its run checkpoints, rebuilds/verifies each `SUCCEEDED`
repo, re-checks actualized contracts against fresh extraction, computes
checkpoint drift, and renders `report.md` — the release runbook plus
per-section findings. Every checked-out repo is restored to its original
branch/commit in a `finally`, even on failure. No lock is taken, but the
command refuses (exit `4`) on every path — not only the mutating ones — while
`sdd implement`'s run lock is held, because racing it would report on an
estate that no longer exists; a *stale* lock only warns and reviews anyway,
since the crashed run is exactly the one a human needs to see.
(`ReviewCommand.java:29-181`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory (`scope = INHERIT`, so it also applies to the `approve`/`reject`/`redo` subcommands) | `ReviewCommand.java:54-56` |
| `--no-rebuild` | off | Skip the estate rebuild verification pass — the report is built from whatever branch the working trees happen to be on | `ReviewCommand.java:58-59, 123-131` |
| `--interactive` | off | After the report is written, walk every `PENDING` repo in order and prompt `[a]pprove / [r]eject / re[d]o / [v]iew diff / [s]kip / [q]uit` | `ReviewCommand.java:61-63, 147-155` |
| `<planJsonPath>` (positional, arity 0..1) | — | The approved `<spec>.plan.json` | `ReviewCommand.java:75-76` |

**Known scope limitation, carried from earlier phases:** the rebuild pass
covers only repos in state `SUCCEEDED`, not "every affected repo"
(`RebuildPass.java:24-38`) — a repo that never ran, or that `FAILED`, is not
rebuilt.

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | every repo `SUCCEEDED`, no rebuild failure, no restore/staging failure, no checkpoint drift, and (if `--interactive`) no follow-up finding |
| `2` | any of the above conditions failed, OR (if `--interactive`) a follow-up (a refused decision, a squash refusal, a failed re-verify) demanded it — whichever is worse wins (`ReviewCommand.java:157-174`) |
| `4` | missing `<planJsonPath>`, no run found for it, the run's lock is held by `sdd implement`, or an unhandled exception (`ReviewCommand.java:91-98, 103-107, 175-178`, `exitCodeOnInvalidInput = 4` at `ReviewCommand.java:47`) |

**Writes:** `.sdd/runs/<runId>/review/report.md` and one
`review/<repo>.diff` per `SUCCEEDED` repo with a resolvable checkpoint
(`RunContext.java:84-103, 111-127`). `report.md`'s sections, in the order they
appear in the document: Summary, Staging failures, Checkpoint drift, Repos,
Rebuild failures, Contract re-check, Branch restore failures, Diff failures,
Propagation, Release runbook (`ReviewReport.java:93, 370, 386, 233, 291, 324,
399, 411, 425, 76`). Summary, Repos and Release runbook always render; the
other six are omitted when they have nothing to report. The order is
load-bearing rather than
incidental: staging failures and checkpoint drift precede Repos deliberately,
because both invalidate what Repos says and a reader who meets "rebuild: OK"
first has already formed a verdict by the time the caveat arrives
(`ReviewReport.java:64-65`). If `--interactive` records any decision, it also writes
whatever `approve`/`reject`/`redo` write (below) for each decided repo, and
re-renders `report.md` once at the end of the walk
(`InteractiveReview.java:158-162`).

### `sdd review approve <repo> <spec>.plan.json` — Gate 2 (decision)

**What it does:** approves one repo's run branch — squashes it into the one
reviewed commit and records the new checkpoint. Refuses (does not apply) if
the repo's own state or its plan-graph invariants disallow it (e.g. an
unresolved upstream); a refusal is reported, not thrown.
(`DecisionCommand.java:194-207`)

**Flags**

| Flag | Description | Verified |
|---|---|---|
| `<repo>` (positional, required) | The repo to decide on | `DecisionCommand.java:45-46` |
| `<planJsonPath>` (positional, arity 0..1) | The approved `<spec>.plan.json` | `DecisionCommand.java:51-52` |
| `--workspace` | inherited from `sdd review` | `ReviewCommand.java:54-56` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | approved and squashed cleanly (or squash was a no-op because there was nothing to squash) |
| `2` | the decision itself was refused (`DecisionCommand.java:160-163`), the squash was refused (dirty tree or branch moved off checkpoint; `DecisionCommand.java:233-237`), or the post-squash branch restore failed (`DecisionCommand.java:274-283`) |
| `4` | missing `<planJsonPath>`, no run found, repo not in the plan, the run's lock is held, or an unhandled exception (`DecisionCommand.java:134-141, 144-152, 188-191`, `exitCodeOnInvalidInput = 4` at `DecisionCommand.java:196`) |

**Writes:** `.sdd/runs/<runId>/review/decisions.json` (the new verdict, via
optimistic-retry write with up to 5 attempts on a concurrent-write conflict;
`DecisionCommand.java:81-127`), an entry appended to the run's top-level
`events.jsonl` (same file `implement` appends repo-state transitions to;
`RunStore.java:201-215`), a rewrite of `state.json` with the new checkpoint
sha on a real squash (`DecisionCommand.java:252-254`), and a re-render of
`review/report.md`.

### `sdd review reject <repo> <spec>.plan.json [--reason <text>]`

**What it does:** rejects a repo's run branch — no squash, no downstream
re-verify. (`DecisionCommand.java:286-295`)

**Flags:** same `<repo>`/`<planJsonPath>` as `approve`, plus:

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--reason <text>` | `""` | Why the work was rejected | `DecisionCommand.java:288-289` |

**Exit codes:** `0` applied, `2` decision refused, `4` input/lock error — same
mechanics as `approve` (no squash step, so no squash-specific `2` case).

**Writes:** `decisions.json`, `events.jsonl`, re-rendered `report.md` — same
as `approve`, minus the `state.json` checkpoint rewrite.

### `sdd review redo <repo> <spec>.plan.json [--reason <text>] [--no-reverify]`

**What it does:** marks a repo for re-implementation and, unless
`--no-reverify`, re-verifies its transitive downstream subtree against its
current checkpoints (design line 67's redo includes re-verify by definition,
not as an optional extra). (`DecisionCommand.java:297-359`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--reason <text>` | `""` | Why the work must be redone | `DecisionCommand.java:300-301` |
| `--no-reverify` | off | Skip re-verifying the downstream subtree | `DecisionCommand.java:303-304` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | redo recorded; downstream re-verify (if run) found nothing wrong |
| `2` | decision refused, the downstream staging failed, or a downstream repo's branch restore failed (`DecisionCommand.java:342-358`) |
| `4` | input/lock error, same as `approve` |

**Writes:** `decisions.json`, `events.jsonl`, re-rendered `report.md`; prints
`then run: sdd implement --retry <repo> <planJsonPath>` as the next step
(`DecisionCommand.java:327-328`).

## `sdd clean [<spec>.plan.json]`

**What it does:** deletes the run branches for repos that never got
`APPROVED` (design line 21/94) — everything `sdd implement` leaves sitting on
its run branch once a human is done deciding — and the run dir alongside
them, once every non-approved repo in it was cleanly deleted. `APPROVED`
repos and their branches are never touched. A decision token that cannot be
parsed, or a `state.json` branch name outside this run's own `sdd/<runId>/`
namespace, blocks the delete for that repo (and the run dir) rather than
guessing. Without `--force` this only prints what it would do.
(`CleanCommand.java:49-311`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `CleanCommand.java:53-54` |
| `--force` | off | Actually delete; without it, only prints what would happen | `CleanCommand.java:56-57` |
| `<planJsonPath>` (positional, arity 0..1) | every run dir in the workspace | A specific `<spec>.plan.json` to clean | `CleanCommand.java:59-61` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | nothing needed cleaning, or every applicable delete succeeded |
| `2` | at least one per-repo or per-run failure (branch delete failed, corrupted decision token, foreign branch name, unreadable run dir) — reported, other runs/repos still processed (`CleanCommand.java:111-120`) |
| `4` | named plan has no run dir, OR any targeted run's lock is held by `sdd implement` (checked per run; one locked run does not stop others from being reported), or an unhandled exception (`CleanCommand.java:79-80, 97-102, 117-119, 121-124`, `exitCodeOnInvalidInput = 4` at `CleanCommand.java:51`) |

**Writes (only with `--force`):** deletes the qualifying `sdd/<runId>/…` git
branches (checking each out to the plan's base sha first if it happened to be
the currently checked-out branch) and, once every repo in that run is fully
handled, deletes the run dir itself — `state.json` last, so a crash mid-delete
still leaves something `sdd status`/`sdd clean` can find on a later pass
(`CleanCommand.java:280-302`).

## `sdd status [<spec>.plan.json]`

**What it does:** a read-only view of one or every run — run state and
Gate-2 decisions per repo, plus the lock's live/idle status. Never checks a
repo out, never touches the run lock, never writes anything.
(`StatusCommand.java:29-148`)

**Flags**

| Flag | Default | Description | Verified |
|---|---|---|---|
| `--workspace <dir>` | `.` | Workspace directory | `StatusCommand.java:45-46` |
| `<planJsonPath>` (positional, arity 0..1) | every run dir, newest-first | A specific `<spec>.plan.json` | `StatusCommand.java:48-50` |

**Exit codes**

| Code | Meaning |
|---|---|
| `0` | always, on any successful invocation — including "no runs found" and a per-run read failure (that run is reported and skipped, not fatal; `StatusCommand.java:92-93, 106-109`) |
| `4` | named plan has no run dir, or an unhandled exception at the top level (`StatusCommand.java:65-66, 84-87`, `exitCodeOnInvalidInput = 4` at `StatusCommand.java:43`) |

**Writes:** nothing.
