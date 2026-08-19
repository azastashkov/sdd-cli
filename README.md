# sdd — Spec-Driven Development pipeline for multi-repo estates

[![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Build: Gradle](https://img.shields.io/badge/build-Gradle-02303A?logo=gradle&logoColor=white)](https://gradle.org)
[![Tests](https://img.shields.io/badge/tests-1%2C599%20passing-brightgreen)](#development)
[![No runtime dependencies added](https://img.shields.io/badge/runtime%20deps-JDK%20only-blue)](#deterministic-first)

> These badges are static, and deliberately so: this repository has no CI, so a
> build-status badge would assert something nothing verifies. The test count is
> from a local `./gradlew clean build` and is refreshed by hand — see
> [Development](#development).

`sdd` turns a written spec into coordinated, human-gated code changes across a
multi-repo estate of Gradle/Spring services and npm/TypeScript packages: a
human writes the spec, `sdd` plans the
change across every affected repo, an agent loop implements it repo by repo,
and a second human gate reviews and decides on the result before anything
ships. Design: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md`.

## Deterministic-first

`sdd` extracts what the estate actually contains — modules, dependencies,
REST endpoints and clients, Kafka roles — with real parsers into a SQLite
knowledge base (`.sdd/index.db`), not with a model. Both ecosystems land in
the same tables, so a question like "who calls `POST /api/orders`" is answered
with the Java services AND the browser SDK that call it, and a change to an
endpoint pulls the front ends that depend on it into its blast radius. Models are used only
where a parser cannot substitute: writing a short repo-card summary, drafting
a plan narrative and its open questions, and doing the per-repo coding during
implementation. The knowledge base — never a model's memory of the estate —
is the source of truth for what exists and what depends on what.

## The two gates

Every other command exists to serve two human checkpoints:

- **Gate 1 — `sdd plan approve`.** A spec can come from a canonical markdown
  file, an exported Confluence page, a live Jira issue, a live Confluence
  page, or free text on the command line (any mix of the last four
  composes into one bundle; a canonical markdown spec must stand alone) —
  see [`docs/commands.md`](docs/commands.md) for the exact combination
  rules. Every one of those sources normalizes into the same canonical
  markdown spec, at which point the two-gate model is unchanged: `sdd plan
  <spec>.md` drafts `plan.md` — the affected repos, the execution order, and
  a narrative. A human reads and edits `plan.md` by hand. `sdd plan approve
  <spec>.plan.md` re-validates it against the current knowledge base and the
  repos' live git state, then freezes it into `plan.json` — the only input
  `sdd implement` will accept. Nothing is built or changed on disk in any
  repo before this gate. The Jira/Confluence path adds one extra human
  checkpoint ahead of that: normalizing a Jira issue or Confluence page
  always stops at a `.spec.md` for review, exactly like the pre-existing
  Confluence-export path — `sdd plan` never runs impact analysis directly
  against a remote source.
- **Gate 2 — `sdd review` + a decision.** `sdd implement <spec>.plan.json`
  runs the agent loop across the estate, leaving every repo on its own run
  branch. `sdd review <spec>.plan.json` rebuilds the estate against those
  checkpoints and writes `report.md` — a release runbook plus per-repo
  findings. A human reads it and decides, per repo:
  `sdd review approve|reject|redo <repo> <spec>.plan.json` (or walk the same
  choices interactively with `sdd review --interactive`). Only an `approve`
  survives `sdd clean`; nothing is squashed into a mergeable commit before a
  human says so.

`sdd index`, `sdd status` and `sdd clean` are support for those two gates,
not gates themselves: `index` builds the knowledge base the plan and every
review rebuild reads from, `status` is a read-only look at any run's state
and decisions, and `clean` discards the branches for work that was never
approved.

## Closed-network estates: Jira, Confluence, Bitbucket

`sdd` can talk to a self-hosted Jira, Confluence and/or Bitbucket Data
Center — each independently optional — for requirement ingestion and source
control, entirely inside a closed corporate network that never reaches the
public internet. Configure it under `sdd.yml`'s `atlassian:` block (see
`sdd.yml.example`):

- **The three sites** — `atlassian.jira`, `atlassian.confluence`,
  `atlassian.bitbucket` — each with a `base_url` and a `token`. Declare only
  the ones this estate actually uses; an absent `atlassian:` block, or an
  absent individual site, changes no other command's behaviour at all.
- **Credentials come from Personal Access Tokens exported as environment
  variables**, referenced as `${VAR}` in `sdd.yml` — never written into the
  file itself. The corporate convention this project targets exports
  `JIRA_API_KEY`, `CONFLUENCE_API_KEY` and `BITBUCKET_API_KEY` from
  `~/.zshrc`, the same place the model credentials in step 2 of the
  Quickstart live (see `.env.example` for the full list of variables);
  `sdd.yml.example` uses those same names.
  The variable name is not special-cased anywhere in `sdd`'s own code — it is
  parsed out of whichever `${VAR}` reference `sdd.yml` actually contains — so
  any name works, these are simply the ones this estate's shell profile uses.
- **A private CA and a forward proxy**, under `atlassian.tls`/
  `atlassian.proxy`, for the common case where self-hosted Jira/Confluence/
  Bitbucket sit behind a certificate the JDK's bundled trust store does not
  recognize, and/or egress is only reachable through a corporate proxy.
- **No new third-party runtime dependency was added to support any of
  this** — a closed network cannot resolve one on demand at build time
  anyway. Jira/Confluence/Bitbucket integration is built entirely on the
  JDK's own `HttpClient` and the JSON/XML libraries already in the tree for
  the pre-existing Confluence-export path.

Run `sdd doctor` first on a network like this — it probes all three
configured sites and reports exactly what's reachable before you write a
spec. See [`docs/commands.md`](docs/commands.md)'s "Atlassian integration:
what's verified, what's assumed" section for how much confidence stands
behind each Jira/Confluence/Bitbucket behaviour, and
[`docs/runbook.md`](docs/runbook.md) for a step-by-step operator runbook —
its "Closed corporate network environment" section covers this network,
including what to do the moment something goes wrong.

## Quickstart: an end-to-end run

This is the shortest path from an empty workspace to a reviewed, decided run.
Every command below is real and exit-coded; see
[`docs/commands.md`](docs/commands.md) for the full flag and exit-code
reference.

1. **Build the CLI once:**

   ```
   ./gradlew :sdd-cli:installDist
   ```

   This installs `sdd` at `sdd-cli/build/install/sdd/bin/sdd`. The examples
   below call it as `sdd`; put that path on your `PATH`, or prefix every
   command with it.

2. **Configure model credentials.** `sdd` reads every credential from the
   **process environment**, never from a file of its own: `sdd.yml` holds
   only a `${VAR}` reference, which `ConfigLoader` expands at load time. So
   export the keys the example configuration references from your shell
   profile —

   ```sh
   export ROUTER_AI_API_KEY=...
   export DEEPSEEK_API_KEY=...
   ```

   — from `~/.zshrc` (recommended: one profile serves every workspace), then
   `source ~/.zshrc` or open a new shell. For a single workspace you can
   instead `cp .env.example .env`, fill it in and `source .env`; that is the
   same mechanism, just scoped narrower. There is no dotenv reader in `sdd` —
   `.env` works only because you sourced it. **Never commit `.env`.**

   `.env.example` is the checklist of which variables exist; the names above
   are what `sdd.yml.example` happens to reference, and nothing in `sdd`'s
   code special-cases them.

   On a closed corporate network the model tiers use **no API key at all** —
   a client certificate is the credential. See
   [`docs/runbook.md`](docs/runbook.md), which covers both environments end
   to end.

   The example configuration uses hosted endpoints for every tier; to keep
   the coding tier on your own machine instead (Apple Silicon, ~40 GB disk),
   run `scripts/serve-qwen.sh` and point `models.coder` at it —
   `sdd.yml.example` carries that variant commented out.

3. **Configure the workspace.** A *workspace* is a directory holding one
   checkout of every repo in the estate, plus one `sdd.yml`:

   ```
   cp sdd.yml.example <workspace>/sdd.yml
   ```

   and adjust it — at minimum, the `models.planner` and `models.coder`
   endpoints. Every command below takes `--workspace <dir>` (default: the
   current directory); the rest of this walkthrough assumes you `cd
   <workspace>` first and omits the flag. Note that `--workspace` only
   locates `sdd.yml` and the estate checkouts — the spec/plan file argument
   every command below takes is a plain path resolved from wherever you run
   `sdd`, so keep your spec and plan files inside `<workspace>` too and this
   stays simple.

4. **Check the environment:**

   ```
   sdd doctor
   ```

   Confirms the Java version, `sdd.yml` loads, `.sdd/index.db` opens, and
   every configured model endpoint answers.

5. **Build the knowledge base:**

   ```
   sdd index
   ```

   Scans every git checkout directly under `<workspace>`, extracts its
   facts, and writes them to `.sdd/index.db`. Re-run this any time the
   estate's code changes — a repo whose fingerprint is unchanged is skipped
   automatically unless you pass `--force`.

6. **Write a spec.** `sdd` reads a strict canonical markdown format — YAML
   front matter (`id`, `title`, `owner`, `status`), then `## ` sections in a
   fixed order, of which `Goal`, `Requirements` and `Acceptance Criteria`
   are required:

   ```markdown
   ---
   id: SPEC-101
   title: Add a health endpoint to order-service
   owner: you
   status: draft
   ---

   ## Goal

   Give order-service a /healthz endpoint consumers can poll.

   ## Requirements

   - R1: order-service exposes GET /healthz returning 200 when ready.

   ## Acceptance Criteria

   - A1: A fresh checkout responds 200 from /healthz after startup.
   ```

   You can also have one generated from free text, a Confluence export, or a
   Jira issue:

   ```
   sdd plan --text "Give order-service a /healthz endpoint" --out SPEC-101.md
   ```

   That path **always stops at the file** — it normalizes the input into the
   same canonical format and writes it, and never runs impact analysis. Read
   what it produced before continuing, in particular its `## Open Questions`
   (below).

7. **Draft a plan (Gate 1, part 1):**

   ```
   sdd plan SPEC-101.md
   ```

   Runs impact analysis over the knowledge base and writes `SPEC-101.plan.md`
   — read and edit it by hand.

8. **Approve the plan (Gate 1, part 2):**

   ```
   sdd plan approve SPEC-101.plan.md
   ```

   Re-validates the edited plan and freezes it into `SPEC-101.plan.json`.
   This is the point of no return for planning — `sdd implement` only
   accepts a `plan.json`.

9. **Implement it:**

   ```
   sdd implement SPEC-101.plan.json
   ```

   Runs the agent loop across every affected repo in dependency order,
   leaving each one on its own run branch. Exits `3` (paused) on an infra
   failure, an unreachable model endpoint, or the run's token budget running
   out — resume with `sdd implement --resume SPEC-101.plan.json` (raise
   `run.token_budget` in `sdd.yml` first if that was the cause; pass
   `--wait-endpoint` to auto-resume once an unreachable endpoint answers
   again).

10. **Review it (Gate 2, part 1):**

    ```
    sdd review SPEC-101.plan.json
    ```

    Rebuilds every succeeded repo against its checkpoint, re-checks
    contracts, and writes `.sdd/runs/SPEC-101-v1/review/report.md`. Add
    `--interactive` to decide right after reading it, instead of running the
    decision commands separately.

11. **Decide, per repo (Gate 2, part 2):**

    ```
    sdd review approve  order-service SPEC-101.plan.json
    sdd review reject   some-other-repo SPEC-101.plan.json --reason "wrong approach"
    sdd review redo     a-third-repo SPEC-101.plan.json --reason "missed an edge case"
    ```

    `approve` squashes the run branch into one commit and records the new
    checkpoint. `reject` and `redo` leave the branch as-is; `redo` also
    re-verifies everything downstream of the repo being redone.

12. **Check where things stand at any point:**

    ```
    sdd status SPEC-101.plan.json
    ```

13. **Clean up once every repo is decided:**

    ```
    sdd clean --force SPEC-101.plan.json
    ```

    Deletes the run branches (and the run dir) for everything that was not
    `approve`d. Without `--force` it only prints what it would delete.

## Open Questions: two sections, two mechanisms

`Open Questions` appears in both a spec and a plan, and they are not the same
thing. Confusing them is easy and costs a gate.

### In a spec (`.spec.md` / `SPEC-101.md`)

When a spec is generated rather than hand-written — `--text`, a Confluence
export, a Jira issue — anything the normalizer could not confidently place
lands here:

```markdown
## Open Questions
- Q1: Which repos own the tier mapping?
```

**Nothing blocks on these.** `SpecValidator` checks only their *shape* — ids
match `Q<number>`, are unique, and are non-blank — so `sdd plan SPEC-101.md`
runs happily with them unanswered and just counts them:

```
spec OK: SPEC-101 — 3 requirements, 2 acceptance, 1 constraints, 2 touchpoints, 2 open questions
```

They are not inert, though. Both model calls render the **whole** spec into
their prompt, Open Questions included, so an unanswered question is read as
context by the planner with nothing marking it undecided — which is exactly how
a guess becomes a plan.

**Resolve each one by folding the answer into the section it belongs to, then
delete the `Q`.** A question is a placeholder for a requirement, constraint or
touchpoint that could not be extracted:

```markdown
## Open Questions
- Q1: Which repos own the tier mapping?     ← delete
```

```markdown
## Touchpoints
- repo: trading-core                         ← and state it as fact here
- class: TierUpdateListener
- config: pricing.tier.refresh-interval
```

### When the task names something with no touchpoint kind

A Redis channel, a database table, a dashboard panel, a business term — the knowledge base has no
concept of these, so no touchpoint can resolve one. Put them in `## Evidence` instead, as prose plus
the file that proves it — or let [`sdd explore`](#sdd-explore-let-the-estate-answer-first) find them
for you:

```markdown
## Evidence
- redis channel `quotes.v1.spread` published here — trading-pricing/src/main/java/com/acme/QuotePublisher.java:88
- table TIER_SPREAD created by — trading-core/src/main/resources/db/migration/V12__tier_spread.sql:1
```

Evidence never seeds a repo — only touchpoints do that. What it does is carry **the code's
vocabulary into the planner's ranking**: the cited type or file is promoted past the evidence row
budget instead of losing on alphabetical order, which is the same mechanism anchors use. That is the
lever for "the plan named the right repos but the steps were vague".

Each bullet should end with a `<repo>/<path>:<line>` citation. A bullet without one still parses —
you can hand-edit freely — but `sdd plan` reports it as a gate problem, because a claim nobody can
check is the thing this section exists to avoid.

### `sdd explore`: let the estate answer first

Filling in touchpoints and evidence by hand means already knowing which repo owns a config key, or
which classes subscribe to a channel. That is the archaeology the tool should do, and it is exactly
what fails when the task is somebody else's subsystem.

```
sdd explore SPEC-101.md          # reads every indexed repo, writes findings into the spec
$EDITOR SPEC-101.md              # review what it proposes — this is a gate, not a handoff
sdd plan SPEC-101.md             # unchanged, deterministic, as always
```

It runs a read-only agent over the whole estate — it can grep and read files, and it has no edit
tool and no build tool at all — and writes back two things: resolvable `## Touchpoints`, and
`## Evidence` bullets for everything the touchpoint grammar cannot express. The spec is rewritten
through the same safe-write-plus-backup path a normalized Confluence spec uses, and re-parsed as a
self-check before you ever see it. `--out` writes to a different file instead of in place.

**Why it is a separate command and not part of `sdd plan`.** `sdd plan approve` SHA-hashes
`plan.md`, so the planner's evidence has to be a deterministic function of the knowledge base and
the spec. A model roaming the estate is not deterministic. Running it before the gate, into a file a
human approves, keeps the deterministic half deterministic — `Closure.expand` still takes no model.

**Two things it cannot do,** both enforced in code rather than asked for in a prompt:

- It cannot propose a touchpoint the knowledge base does not resolve. The proposal is checked at the
  moment it is made, and a miss is refused with the reason.
- It cannot cite a file it did not open. Every finding's citation is re-read from disk and the quoted
  line is copied from the file, never supplied by the model.

Ceilings live under `explore:` in `sdd.yml` (`turns`, `tokens`, `wall_seconds`, `context_soft_cap`).
They exist so the survey terminates and is reproducible, not to save tokens. A run that ends early
still writes everything it found, plus an Open Question saying the survey may be incomplete.

Measured on a six-repo estate (`docs/measurements/2026-08-19-explore/results.md`): a spec naming a
Redis channel produced **zero seeds and a blocking question**, and even a hypothetically perfect
impact analysis never once named the classes that subscribe to it. With explorer output in the spec,
the same case reached every expected repo with no blocking questions, and the subscribing classes
appeared in the planner's prompt for the first time.

### Touchpoint kinds

The kinds are `repo:`, `endpoint:`, `topic:`, `class:`, `artifact:` and `config:`. Each is resolved
against the knowledge base — a hint verified, never trusted — and a miss becomes a blocking
question rather than a guess. `config:` takes a Spring property key, or a prefix of one anchored at
a dot: `pricing.tier` resolves every repo declaring anything beneath it, while `pricing.tie`
resolves nothing, so a typo misses loudly instead of matching something real.

Touchpoints deserve the most attention. Measured on a real estate, a spec with
no resolvable touchpoint yields **zero seeds** and a blocking `no seeds`
question, while the free-text fallback seeds on prose — in one run it matched
the English word *"build"* in a requirement and seeded the wrong repo. One
accurate `class:` or `repo:` touchpoint is the highest-value edit available
here.

There is no machine-readable way to answer a spec question. The spec gate is
you.

### In a plan (`.plan.md`)

Here the mechanism is real. Questions carry a `[blocking]` marker, and a
blocking one stops `sdd plan approve`. You answer in place, with the one
human-only extension the plan parser accepts:

```markdown
- Q1: [blocking] Which repos own the tier mapping?
  - resolution: trading-core owns it; product-a only reads through the SDK.
```

Then `sdd plan revise SPEC-101.plan.md` folds every question and its resolution
back into a fresh version, and `sdd plan approve` re-checks.

`  - resolution:` is recognised **only** in a plan. Writing it into a spec does
nothing at all.

## Progress reporting

`index`, `implement`, `review` and `plan` report how far a slow run has
gotten while it runs, instead of staying silent until everything prints at
once. On a terminal it is a single self-updating line — no ANSI, no colour —
showing which repo (or phase) is in flight and how long it has been there;
piped or in CI it degrades to plain, append-only `<item>  done` lines instead.
It always writes to **stderr**, so `sdd index 2>/dev/null` or `sdd index |
cat` leaves stdout's own report untouched. Turn it off with `--quiet` (works
either before or after the subcommand) or `SDD_PROGRESS=off`; see
[`docs/commands.md`](docs/commands.md)'s "Progress reporting" section for the
full decision ladder (`SDD_PROGRESS` → `TERM` → `CI` → console) and what each
command reports.

## Inspecting the knowledge base

Everything `sdd index` learns lives in one SQLite file, `<workspace>/.sdd/index.db`.
Nothing is written as loose files, so `sqlite3` is how you look at it.

Open it **read-only**. `sdd` applies pending schema migrations on every open,
including read-only commands, so pointing the binary at a database is never a
neutral act:

```
sqlite3 'file:<workspace>/.sdd/index.db?mode=ro' "select * from meta;"
```

**Repo cards.** One row per repo, upserted — no history is kept. `card_md` is
the full markdown, `card_line` the one-line summary, and `input_hash` the cache
key that decides whether the next `sdd index` regenerates or reports `cached`:

```
sqlite3 -header -column <workspace>/.sdd/index.db \
  "select r.name, length(c.card_md) chars, c.model, c.created_at
   from repo_card c join repo r on r.id = c.repo_id order by r.name;"
```

```
sqlite3 <workspace>/.sdd/index.db \
  "select c.card_md from repo_card c join repo r on r.id = c.repo_id
   where r.name = 'my-repo';"
```

An empty `repo_card` is worth fixing before planning rather than pressing on:
`ModelSeeder` feeds every card to the planner when choosing affected repos, and
`WorkOrder` hands one to each coding agent. With none, impact analysis reasons
over an empty inventory and agents start from a `(no repo card)` fallback.

**What was indexed, and whether anything degraded:**

```
sqlite3 -header -column <workspace>/.sdd/index.db \
  "select name, build_system, gradle_status, parse_status, substr(head_commit,1,8) head
   from repo order by name;"
```

**Estate shape** — sizes per repo, which is what governs how much evidence a
repo contributes to the planning prompt:

```
sqlite3 -header -column <workspace>/.sdd/index.db \
  "select r.name, count(t.id) types, sum(t.is_api) api_types
   from repo r
   left join module m on m.repo_id = r.id
   left join java_type t on t.module_id = m.id
   group by r.name order by types desc;"
```

**Who implements what, across repos** — the question `api_usage` cannot answer,
since it records the module a reference came from and not the referring type:

```
sqlite3 -header -column <workspace>/.sdd/index.db \
  "select r.name repo, t.fqcn, s.relation, s.resolution
   from type_supertype s
   join java_type t on t.id = s.type_id
   join module m on m.id = t.module_id
   join repo r on r.id = m.repo_id
   where s.supertype_fqcn = 'com.example.SomeInterface'
   order by r.name, t.fqcn;"
```

`resolution` records how the supertype was resolved (`IMPORT`, `SAME_PACKAGE`,
`WRITTEN`, `UNRESOLVED`) — an unresolved row is still a row, because "no
subtypes" and "subtypes we could not place" are different answers.

## Reference

[`docs/commands.md`](docs/commands.md) documents every command's flags, exit
codes, and what it writes to disk, verified line-by-line against the source.

## Development

- Java 21, Gradle. `./gradlew build` runs all tests.
- Reading TypeScript needs `node` on PATH (or `node_home` in `sdd.yml`). Tests
  that require it are tagged `node-it` and skip themselves when it is absent;
  everything else, including indexing an npm repo's dependency graph, works
  without it.
- Modules: `sdd-core` (config, db, model client, retrieval), `sdd-index`,
  `sdd-plan`, `sdd-agent`, `sdd-cli`.
