# sdd — Spec-Driven Development pipeline for multi-repo estates

Turns a structured feature spec into coordinated, human-gated code changes
across a 40+-repo Gradle/Spring estate, using context-limited local models.
Design: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md`.

## Quickstart

1. **Serve the local coder model** (Apple Silicon, ~40 GB disk):
   `scripts/serve-qwen.sh`
2. **Configure the DeepSeek key**: `cp .env.example .env`, paste the real key,
   `source .env`. Never commit `.env`.
3. **Configure the workspace**: copy `sdd.yml.example` to `<workspace>/sdd.yml`
   (the directory containing all estate checkouts) and adjust.
4. **Check the environment**: `./gradlew :sdd-cli:installDist` then
   `sdd-cli/build/install/sdd/bin/sdd doctor --workspace <workspace>`
5. Pipeline commands (`index`, `plan`, `implement`, `review`) arrive in
   Phases 2–5.

## Development

- Java 21, Gradle. `./gradlew build` runs all tests.
- Modules: `sdd-core` (config, db, model client, retrieval), `sdd-index`,
  `sdd-plan`, `sdd-agent`, `sdd-cli`.
