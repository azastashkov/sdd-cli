# The generated export, checked against the real OpenSpec CLI

Run 2026-08-21 against **`@fission-ai/openspec@1.10.0`**, Node v25.6.1, via
`OpenSpecValidateHarness` (`@Tag("measure")`, gated on `SDD_OPENSPEC_VALIDATE`, never in the default
test task — sdd itself must never require Node).

## Result

```
$ npx --yes @fission-ai/openspec@1.10.0 validate --changes --strict
- Validating...
✓ change/spec-tier-invalidation-v1
Totals: 1 passed, 0 failed (1 items)
```

Both golden repos — the provider (`pricing-core`, one ADDED requirement, two allocated scenarios, a
`binary-compatible` java-api contract) and the consumer (`svc-orders`) — pass `--strict`, which
promotes every warning to an error. That covers the `SHALL`-in-the-body rule and the Purpose brevity
warning, not just the hard errors.

## What the unit tests could not have told us

`OpenSpecChangeTest` asserts 17 rules, but they are rules **we transcribed** from reading the
project's source. They prove conformance to our belief about the format. This harness is the only
thing that proves conformance to the tool.

## Two things the harness found

**1. A bare `validate` is not a validation.** In a non-interactive shell it exits 1 with:

```
Nothing to validate. Try one of:
  openspec validate --all
  openspec validate --changes
  openspec validate --specs
```

The first version of this harness used bare `validate` and read that exit code as a rejection — so
its negative control "passed" while proving nothing whatsoever, and its positive case failed for a
reason that had nothing to do with the files. The target is now explicit (`--changes`).

**2. That is exactly why the negative control exists, and it had to be strengthened.** A control
that only asserts a non-zero exit cannot tell "the change was rejected" from "the CLI was invoked
wrongly" — and in this case those were the same exit code. It now asserts on the reported totals:

```
✗ change/spec-tier-invalidation-v1
Totals: 0 passed, 1 failed (1 items)
```

produced by replacing the delta with an ADDED requirement carrying no scenario.

## Re-run trigger

Whenever `OpenSpecChange.TARGET_VERSION` changes. The format's delta grammar has been stable for
about a year, but the surrounding layout has not — `openspec/project.md` became
`openspec/config.yaml`, `openspec/AGENTS.md` was deleted, and `/openspec:*` became `/opsx:*`, all
within recent releases. A transcription cannot notice any of that; this harness can.

```
SDD_OPENSPEC_VALIDATE=1 ./gradlew :sdd-plan:test --tests '*OpenSpecValidateHarness' --rerun-tasks
```
