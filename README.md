# sdd — Spec-Driven Development pipeline for multi-repo estates

`sdd` turns a written spec into coordinated, human-gated code changes across a
multi-repo Gradle/Spring estate: a human writes the spec, `sdd` plans the
change across every affected repo, an agent loop implements it repo by repo,
and a second human gate reviews and decides on the result before anything
ships. Design: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md`.

## Deterministic-first

`sdd` extracts what the estate actually contains — modules, dependencies,
REST endpoints and clients, Kafka roles — with real parsers into a SQLite
knowledge base (`.sdd/index.db`), not with a model. Models are used only
where a parser cannot substitute: writing a short repo-card summary, drafting
a plan narrative and its open questions, and doing the per-repo coding during
implementation. The knowledge base — never a model's memory of the estate —
is the source of truth for what exists and what depends on what.

## The two gates

Every other command exists to serve two human checkpoints:

- **Gate 1 — `sdd plan approve`.** `sdd plan <spec>.md` drafts `plan.md`: the
  affected repos, the execution order, and a narrative. A human reads and
  edits `plan.md` by hand. `sdd plan approve <spec>.plan.md` re-validates it
  against the current knowledge base and the repos' live git state, then
  freezes it into `plan.json` — the only input `sdd implement` will accept.
  Nothing is built or changed on disk in any repo before this gate.
- **Gate 2 — `sdd review` + a decision.** `sdd implement <spec>.plan.json`
  runs the agent loop across the estate, leaving every repo on its own run
  branch. `sdd review <spec>.plan.json` rebuilds the estate against those
  checkpoints and writes `report.md` — a release runbook plus per-repo
  findings. A human reads it and decides, per repo:
  `sdd review approve|reject|redo <repo> <spec>.plan.json` (or walk the same
  choices interactively with `sdd review --interactive`). Only an `approve`
  survives `sdd clean`; nothing is squashed into a mergeable commit before a
  human says so.

`sdd index`, `sdd graph`, `sdd status` and `sdd clean` are support for those
two gates, not gates themselves: `index` builds the knowledge base the plan
and every review rebuild reads from, `graph` visualizes it, `status` is a
read-only look at any run's state and decisions, and `clean` discards the
branches for work that was never approved.

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

2. **Configure model credentials.** `cp .env.example .env`, paste the real
   keys, `source .env`. Never commit `.env`. The example configuration uses
   hosted endpoints for every tier; to keep the coding tier on your own
   machine instead (Apple Silicon, ~40 GB disk), run `scripts/serve-qwen.sh`
   and point `models.coder` at it — `sdd.yml.example` carries that variant
   commented out.

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

## Reference

[`docs/commands.md`](docs/commands.md) documents every command's flags, exit
codes, and what it writes to disk, verified line-by-line against the source.

## Development

- Java 21, Gradle. `./gradlew build` runs all tests.
- Modules: `sdd-core` (config, db, model client, retrieval), `sdd-index`,
  `sdd-plan`, `sdd-agent`, `sdd-cli`.
