# Phase 5C-2: Decision Safety, Honest Failure Codes, and the Docs Phase 5 Never Wrote

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the three correctness gaps Phase 5 knowingly carried — concurrent decisions can lose a verdict, "unresolved" is reported as "diverged", and the report's failure codes are free text — then pay down the duplication those phases accumulated and write the user-facing docs spec line 94 has never claimed.

**Architecture:** Three independent correctness fixes, each in its own layer: an optimistic-retry write for `decisions.json` (no new lock file, so no new staleness lifecycle); a fifth `Conformance` value fed by resolution signals the extractors already produce; and `StepResult` — which already exists and is already computed per attempt — persisted through `RepoRun` into `state.json` and the report instead of being discarded. Then the cleanups, which are mechanical, and the docs, which are not.

**Tech Stack:** Java 21, Jackson, JUnit 5 + AssertJ. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — line 42 ("unresolved ≠ nonexistent"), line 66 (Gate-2 re-check), line 71 (exit taxonomy), line 94 (`polish + docs`), the **Amendment (2026-08-14): declared contract grammar** and the **Amendment (2026-08-14): unresolved extraction is its own conformance verdict**, which is binding for Task 3 and should be read first.

## Global Constraints

- **Every conformance value is a warning.** Nothing in this phase changes an exit code. `Decisions.approve` stays untouched.
- **`sdd-index` is not modified.** `EndpointInfo` gains no resolution field. Only REST's verbless-`@RequestMapping` shape (verb `ANY`) is recognisable from the extractor's existing output without it; the unresolvable-path shape is not, and is dropped rather than approximated (see Known carried items). A change reaching the indexed knowledge base is out of scope and a signal to stop and report.
- **Backward compatibility both directions:** a `state.json` written before this phase has no `failureCode` and must load with it null; a `state.json` written after must not break `sdd implement --resume` on a run started before it. The estate has two frozen runs (`SPEC-101-v1`, `SPEC-101-v2`) that must both still load.
- **`NOT_RESOLVED` only when the reason a member is missing is a named unresolved shape on the actual side.** A member absent from a fully resolved surface stays `DIVERGED_FROM_PLAN`. Where a contract has both, divergence wins the verdict and unresolved members are named separately.
- **Docs must describe what the code does, not what the plan hoped.** Every command, flag and exit code in the README is verified against the source before it is written. A wrong README is worse than none.
- Zero-test-breaking outside files a task explicitly edits; `./gradlew build` green at every task boundary.
- Conventional commits ending with the `Co-Authored-By:` trailer in the form the branch's existing commits use.

## Context (verified against source at `f1a2f07`)

- `RunStore.writeDecisions(Path, Map)` publishes via `publishAtomically` (`RunStore.java:167-179`), staging at `<target>.<pid>-<counter>.tmp`. Atomic rename prevents a torn read; it does **not** prevent a lost update. `DecisionCommand` reads at `:96` and writes at `:105` — a read-modify-write of the whole map.
- `DecisionCommand` and `InteractiveReview` are the only decision writers. Each invocation applies **exactly one** transition (`approve`/`reject`/`redo`), which is what makes re-applying against fresh state well-defined.
- `Conformance { DECLARED_MET, DIVERGED_FROM_PLAN, NOT_DECLARED, NOT_COMPARABLE }` lives in `ContractRecheck`; `conformanceOf(contract, fresh, extracted)` (`ContractRecheck.java:157`) is a pure function whose signature deliberately cannot see `recorded`.
- `DeclaredContract.missingFrom(actualBody)` returns declared members absent from the canonicalized actual set; `canonicalizeActual(kind, body)` per kind.
- `SpringModel.KafkaUse(topic, role, classFqcn, groupId, payloadType, resolution, rawExpr)` — **has** `resolution()`. `SpringModel.EndpointInfo(classFqcn, methodName, httpMethod, pathTemplate, requestType, responseType)` — **has no** resolution field. `RestEndpointExtractor.resolvePaths:83` substitutes `""` for an unresolved path; `:62` yields the verb `ANY` for a verbless `@RequestMapping`.
- `ContractActualizer.kafka()` emits `<role> <topic>` and drops `resolution()`; `rest()` emits `<METHOD> <path> -> <fqcn>#<method>`.
- `StepResult { SUCCESS, VERIFY_FAILED, BLOCKED, EXHAUSTED, BUDGET, MALFORMED, WEDGED, INFRA }` (`sdd-agent/.../run/StepResult.java:3`) — computed per attempt and **discarded**: `RepoRun(repo, state, branch, checkpointSha, detail)` carries only free text.
- `ReviewReport.render` takes **14 positional parameters** (`ReviewReport.java:34-42`), four of them adjacent same-typed `List<String>`.
- `shortSha` exists in **4** copies; `.replace("```", "'''")` in **4** copies.
- `README.md` is **24 lines** and mentions no pipeline command.

## File Structure

| File | Responsibility |
|---|---|
| `sdd-cli/.../implement/RunStore.java` | Optimistic-retry write for `decisions.json`; `failureCode` in `state.json`. |
| `sdd-cli/.../review/DecisionCommand.java`, `InteractiveReview.java` | Re-apply their single transition on a write conflict. |
| `sdd-cli/.../implement/ContractActualizer.java` | Mark unresolved entries per kind. |
| `sdd-cli/.../review/ContractRecheck.java` | `NOT_RESOLVED`, and the rule that divergence outranks it. |
| `sdd-cli/.../implement/RepoRun.java`, `Orchestrator.java` | Carry `StepResult` into run state. |
| `sdd-cli/.../review/ReviewReport.java` | Render `NOT_RESOLVED` and the failure code; take a `ReportInputs` record. |
| `sdd-core/.../contract/Markdown.java` | **New.** One fence-escape helper. |
| `sdd-cli/.../review/Shas.java` | **New.** One `shortSha`. |
| `README.md` | The user-facing docs spec line 94 has never had. |

---

### Task 1: Optimistic-retry decision writes

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java`, `sdd-cli/src/main/java/sdd/cli/review/DecisionCommand.java`, `sdd-cli/src/main/java/sdd/cli/review/InteractiveReview.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java` (append), `sdd-cli/src/test/java/sdd/cli/ReviewDecisionsCommandTest.java` (append)

**Interfaces:**
- Produces: `record DecisionsSnapshot(Map<String, DecisionRecord> decisions, String fingerprint)`; `RunStore.readDecisionsSnapshot(Path runDir)`; `RunStore.writeDecisions(Path runDir, Map<String, DecisionRecord>, String expectedFingerprint)` → `boolean` (false = the file changed underneath, caller must retry). The existing two-argument `writeDecisions` stays for callers with nothing to conflict against (tests, first write), delegating with a fingerprint read immediately beforehand.
- `fingerprint` is the SHA-256 of the file's bytes, or `""` when the file does not exist — so "created concurrently" is a conflict too, not a silent overwrite.
- Consumes: `Decisions`, `DecisionRecord`.

**The retry loop belongs in the callers, not in `RunStore`.** `RunStore` cannot know which transition to re-apply; the command does. Each command applies exactly one transition, so the loop is: read snapshot → build `Decisions` from it → apply the one transition → write with the fingerprint → on `false`, repeat. Cap at 5 attempts, then fail with an explicit message naming the run — a livelock must not look like a hang.

- [ ] **Step 1: Write the failing test.**

```java
// RunStoreTest
@Test
void aWriteWhoseFingerprintIsStaleIsRefused() {
    store.writeDecisions(runDir, Map.of("lib", new DecisionRecord(Decision.APPROVED, "")));
    RunStore.DecisionsSnapshot stale = store.readDecisionsSnapshot(runDir);
    store.writeDecisions(runDir, Map.of("svc", new DecisionRecord(Decision.REJECTED, "no")));   // someone else
    assertThat(store.writeDecisions(runDir, Map.of("lib", new DecisionRecord(Decision.REDO, "")),
            stale.fingerprint())).isFalse();
    assertThat(store.readDecisions(runDir)).containsKey("svc");   // the other writer's verdict survived
}

@Test
void aWriteWithACurrentFingerprintSucceeds() { /* → true, and the value lands */ }

@Test
void anAbsentFileHasAnEmptyFingerprintAndAConcurrentCreateIsAConflict() {
    RunStore.DecisionsSnapshot before = store.readDecisionsSnapshot(runDir);
    assertThat(before.fingerprint()).isEmpty();
    store.writeDecisions(runDir, Map.of("lib", new DecisionRecord(Decision.APPROVED, "")));
    assertThat(store.writeDecisions(runDir, Map.of("svc", new DecisionRecord(Decision.REDO, "")),
            before.fingerprint())).isFalse();
}

// ReviewDecisionsCommandTest — the property that matters end to end
@Test
void aDecisionWrittenUnderneathIsNotLostWhenThisCommandRetries() {
    // Pre-seed decisions.json with svc REJECTED after the command has read but before it writes,
    // by pointing the command at a store whose first write attempt is forced to conflict once.
    // Assert BOTH verdicts are present afterwards and the command still exits 0.
}
```

- [ ] **Step 2: Run — expect RED.** `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.RunStoreTest' --tests 'sdd.cli.ReviewDecisionsCommandTest'`
- [ ] **Step 3: Implement.** Fingerprint with `MessageDigest.getInstance("SHA-256")` over `Files.readAllBytes`. Compare-then-publish is not atomic against a determined racer — say so in the javadoc rather than implying it is; the guarantee is that a *lost update* becomes a *detected conflict* in every interleaving except a write landing between the compare and the rename, which the retry then catches on the next read.
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `fix: a concurrent decision write is detected and retried, not lost`

---

### Task 2: One fence-escape helper, one `shortSha`, and the small residuals

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/contract/Markdown.java`, `sdd-cli/src/main/java/sdd/cli/review/Shas.java`
- Modify: `sdd-plan/.../gen/PlanMdRenderer.java`, `sdd-plan/.../gen/PlanDrafter.java`, `sdd-cli/.../review/SquashApprove.java`, `DecisionCommand.java`, `CleanCommand.java`, `RunContext.java`, `sdd-core/.../contract/DeclaredContract.java`, `sdd-cli/.../implement/Orchestrator.java`, `sdd-cli/.../review/ReviewReport.java`
- Test: `sdd-core/src/test/java/sdd/core/contract/MarkdownTest.java`, `sdd-cli/src/test/java/sdd/cli/review/ShasTest.java`

**Interfaces:**
- Produces: `Markdown.neutralizeFences(String)` — the single definition of `` ``` `` → `'''`; `Shas.shortSha(String)` — the single definition of the 7-char form, null/blank-safe.
- Consumes: nothing.

Also in this task, each a one-liner the reviews recorded:
- `DeclaredContract`'s kafka error message hardcodes `[produces, consumes]` while the REST path renders `REST_METHODS` from the set — render `KAFKA_ROLES` the same way so the literal cannot drift.
- `DeclaredContract.parseKafkaLine` folds arity and role errors into one message, so `produces a b` is reported as a role-vocabulary problem — split them.
- `Orchestrator`'s "actualized to nothing" event says "matching its declared types" even for `rest`/`kafka` contracts, which ignore declarations entirely — make the declared-types clause conditional on `!contract.declared().isEmpty()`.
- `ReviewReport.java:147`'s arrow-form switch *statement* over `RebuildScope.Kind` becomes a switch *expression*, so a fourth kind fails at compile time rather than runtime.
- The exit-code legend's `2` clause omits the decision-side meanings (refused by invariant, applied-but-squash-failed) although `approve` re-renders the same report.
- `canonicalizeRestActual` does not skip the `# actualized (rest)` header that the java-api and kafka paths skip — make the three consistent.

- [ ] **Step 1: Write the failing tests** — `MarkdownTest` (a fence marker is neutralized; already-neutralized text is unchanged — idempotence, because two call sites compose), `ShasTest` (7 chars; null and blank return `"(none)"` rather than throwing).
- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement**, replacing all four copies of each. Grep for `replace("```"` and `shortSha` afterwards and confirm exactly one definition of each remains.
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.** Every existing test must pass **unmodified**: these are pure extractions with no behavior change.
- [ ] **Step 5: Commit** — `refactor: one fence-escape helper and one shortSha, plus the recorded one-liners`

---

### Task 3: `NOT_RESOLVED` — unresolved extraction stops reading as divergence

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/ContractActualizer.java`, `sdd-core/src/main/java/sdd/core/contract/DeclaredContract.java`, `sdd-cli/src/main/java/sdd/cli/review/ContractRecheck.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/ContractActualizerTest.java` (append), `sdd-core/src/test/java/sdd/core/contract/DeclaredContractTest.java` (append), `sdd-cli/src/test/java/sdd/cli/review/ContractRecheckTest.java` (append)

**Interfaces:**
- Produces: `ContractActualizer.UNRESOLVED_MARKER = " [unresolved]"`, appended to an actual line whose value could not be resolved. `DeclaredContract.unresolvedMembers(String actualBody)` → the canonical members of marked lines. `ContractRecheck.Conformance.NOT_RESOLVED`, and `Finding` gains a trailing `List<String> unresolved`.
- Consumes: `SpringModel.KafkaUse.resolution()`.

**Per kind, exactly as the amendment specifies:**
- `kafka` — mark when `resolution()` is not the resolved value (grep `KafkaExtractor` for the literal it uses; do not guess).
- `rest` — mark when the verb is `ANY` (`RestEndpointExtractor.mappingsOf:62`, a verbless `@RequestMapping`).
  *Correction (implementation, task 3): this bullet originally also said mark an empty path
  template (`resolvePaths:83` substitutes `""`). That shape is not implemented: `RestEndpointExtractor.extract`
  always joins the result through `Routes.join(base, path)`, whose floor guard means the joined
  `pathTemplate` can never literally be `""` — it floors to `"/"` for every combination of empty
  base and path, indistinguishable from a genuine bare-root endpoint. See Known carried items.*
- `java-api` — no unresolved shape; nothing to mark.

**The verdict rule:** compute `missingFrom` as today. Partition the missing members: a member is *unresolved-explained* when the actual side has a marked entry that could plausibly be it — same kind-specific key ignoring the unresolvable part (for `rest`, same path with verb `ANY` — the "same verb with an empty path" branch this rule also describes is not implemented, since that shape is never marked, per the correction above; for `kafka`, same topic with an unresolved role, or same role with an unresolved topic). If **every** missing member is unresolved-explained → `NOT_RESOLVED`. If any is not → `DIVERGED_FROM_PLAN`, with the unresolved ones still listed in `unresolved` so the human sees why the rest could not be judged. Divergence outranks unresolved; both outrank nothing.

- [ ] **Step 1: Write the failing test.**

```java
// ContractRecheckTest — all three through the real actualizer, per the rule adopted in 5C-1:
// for a containment check, no test may hand-write both sides.
@Test
void aDynamicKafkaTopicIsNotResolvedNotDiverged() { /* @Value-driven topic → NOT_RESOLVED */ }

@Test
void anUnresolvableRestPathIsNotResolvedNotDiverged() { /* NOT IMPLEMENTED — dropped, not
    distinguishable from a bare-root endpoint; see Known carried items */ }

@Test
void aVerblessRequestMappingIsNotResolvedNotDiverged() { /* verb ANY → NOT_RESOLVED */ }

@Test
void aRealDivergenceAlongsideAnUnresolvedMemberStillReportsDiverged() {
    assertThat(finding.conformance()).isEqualTo(Conformance.DIVERGED_FROM_PLAN);
    assertThat(finding.missing()).isNotEmpty();
    assertThat(finding.unresolved()).isNotEmpty();   // named separately, not silently folded in
}

@Test
void aFullyResolvedSurfaceMissingAMemberIsStillDiverged() { /* the regression guard */ }
```

- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: unresolved extraction reports NOT_RESOLVED instead of accusing`

---

### Task 4: The failure code the orchestrator already knows

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RepoRun.java`, `RunStore.java`, `Orchestrator.java`, `sdd-cli/src/main/java/sdd/cli/review/ReviewReport.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java` (append), `sdd-cli/src/test/java/sdd/cli/implement/OrchestratorTest.java` (append), `sdd-cli/src/test/java/sdd/cli/review/ReviewReportTest.java` (append)

**Interfaces:**
- Produces: `RepoRun` gains a trailing nullable `String failureCode` carrying the `StepResult` name of the final attempt (null for `SUCCESS` and for any repo that never ran). Serialized into `state.json` as `failure_code`; **absent key reads as null**, so both frozen estate runs still load. `ReviewReport` prints it on a non-SUCCEEDED repo's line before the free-text detail.
- Consumes: `StepResult` (`sdd-agent`).

**Do not invent a taxonomy.** `StepResult` already has the values and the orchestrator already computes them per attempt; this task persists the last one and renders it. If threading it requires changing `StepOutcome` or the attempt loop's shape, stop and report — that is the implement path and the blast radius is larger than this task's.

- [ ] **Step 1: Write the failing test.** `OrchestratorTest`: a repo whose final attempt is `VERIFY_FAILED` lands `failureCode == "VERIFY_FAILED"` in `state.json`; a SUCCEEDED repo lands null. `RunStoreTest`: a `state.json` with no `failure_code` key reads null (use a literal pre-5C-2 fixture, not one built by the current writer). `ReviewReportTest`: a FAILED repo's line contains `VERIFY_FAILED`; a SUCCEEDED repo's line does not contain a failure code at all.
- [ ] **Step 2: Run — expect RED.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run — expect PASS, then `./gradlew build`.**
- [ ] **Step 5: Commit** — `feat: persist and report the failure code the orchestrator already computed`

---

### Task 5: `ReportInputs`

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/review/ReportInputs.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/review/ReviewReport.java`, `RunContext.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/ReviewReportTest.java` (mechanical call-site update only)

**Interfaces:**
- Produces: `record ReportInputs(...)` holding every current `render` parameter; `ReviewReport.render(ReportInputs)` replaces the 14-positional-parameter signature. The four adjacent same-typed `List<String>` parameters — `notLocallyVerified`, `stagingFailures`, `restoreFailures`, `diffFailures` — become named components a transposition cannot silently swap.
- Consumes: everything Tasks 3 and 4 added, so this task runs **after** them and absorbs their parameters rather than being re-done.

- [ ] **Step 1:** Introduce the record and change the signature. This is the one task with no new behavior, so there is no RED to capture — its evidence is that every existing `ReviewReportTest` assertion passes **unmodified**, with only call sites changed.
- [ ] **Step 2: Run — expect PASS unmodified, then `./gradlew build`.**
- [ ] **Step 3: Commit** — `refactor: ReviewReport.render takes a ReportInputs record`

---

### Task 6: The docs spec line 94 has never had

**Files:**
- Modify: `README.md`
- Create: `docs/commands.md`

**Interfaces:**
- Produces: a README that gets a new user from an empty workspace to a reviewed run, and a command reference covering `doctor`, `index`, `plan` (+ `approve`, `revise`), `graph`, `implement`, `review` (+ `approve`/`reject`/`redo`, `--interactive`), `clean`, `status` — each with its real flags, its real exit codes, and what it writes.
- Consumes: the source, which is the only authority for what to document.

**Verify every claim against the code before writing it.** Read each `@Command` and `@Option` rather than describing intent: several commands have flags no document mentions (`--force`, `--no-cards`, `--no-rebuild`, `--retry`, `--wait-endpoint`, `--workspace`), and the exit-code taxonomy (`0/2/3/4`) is load-bearing for anyone scripting this. Document the two-gate model explicitly — Gate 1 is `plan approve`, Gate 2 is `review` plus a decision — because nothing in the repo says so today and it is the thing a new reader most needs.

- [ ] **Step 1:** Enumerate every command and flag from source; note the exit codes each returns.
- [ ] **Step 2:** Write `docs/commands.md` from that enumeration.
- [ ] **Step 3:** Rewrite `README.md`: what sdd is, the deterministic-first principle, the two gates, a worked end-to-end sequence, and a pointer to the reference.
- [ ] **Step 4:** Re-read both against the source, correcting anything aspirational.
- [ ] **Step 5: Commit** — `docs: a README and command reference that match the code`

---

## Verification

1. `./gradlew build` — all modules green.
2. **Amendment coverage:** `NOT_RESOLVED` exists with the per-kind signals the amendment names (Task 3); the divergence-outranks-unresolved rule and the separate listing (Task 3); `sdd-index` untouched — verify with `git diff --stat main..HEAD -- sdd-index` returning empty.
3. **Concurrency:** the property test proves a verdict written underneath is not lost (Task 1).
4. **Backward compatibility:** both frozen estate runs (`SPEC-101-v1`, `SPEC-101-v2`) still load — `sdd status` against each must exit 0 after Task 4.
5. **Real-estate readiness:** `sdd review` on `SPEC-101-v2` must still report `2 met, 0 diverged` — this phase must not disturb a verdict that is currently correct.
6. **Docs:** every flag documented exists in source; every exit code documented is returned somewhere.

## Known carried items (explicitly NOT in this phase)

- **The estate rebuild covers only SUCCEEDED repos**, not "all affected repos" (spec line 66). Ratified in 5A, re-ratified in 5C-1, re-ratified here.
- **Divergence does not block approval.** `Decisions.approve` stays untouched.
- **`kafka` declarations remain fixture-proven only** — the trading estate has no Kafka. Task 3's kafka path inherits that limitation.
- **`EndpointInfo` gains no resolution field**, so REST unresolved detection is inference from degenerate output rather than a first-class signal. A future phase that touches `sdd-index` should make it explicit.
- **REST's unresolvable-path shape is dropped, not implemented.** Only the verb `ANY` (a verbless `@RequestMapping`) is distinguishable from `EndpointInfo` alone; an empty path template is not, because `RestEndpointExtractor.extract` always joins the result through `Routes.join(base, path)`, and `Routes.join`'s own floor guard means the joined `pathTemplate` can never be `""` — it floors to `"/"` for every combination of empty base and empty path, indistinguishable from a genuine bare-root endpoint. An implementation with a genuinely `@Value`-driven/unresolvable REST path still reports `DIVERGED_FROM_PLAN` today. Fixing this needs a resolution field on `SpringModel.EndpointInfo` (mirroring `KafkaUse.resolution()`), which is an `sdd-index` change this phase's global constraint forbids — rejected mid-phase because `EndpointInfo` feeds the indexed knowledge base and `sdd index` writes it, and a schema change would put an estate's existing index out of step with the code for a shape that has never occurred on it. Cost of leaving it: the status quo (over-reporting divergence for this one shape) persists, now documented rather than silently believed fixed.
- **The compare-then-publish window.** Optimistic retry converts a lost update into a detected conflict in every interleaving except a write landing between the compare and the rename; the retry catches that on the next read. True serialization would need a lock, which this phase deliberately does not add.
- **`state.json` has no equivalent of `decisions.json`'s fingerprint, so two concurrent approves lose a checkpoint.** *Trigger:* two `sdd review approve <repo>` commands against different repos of the SAME run, overlapping in time. `RunContext.load` snapshots the whole `RunState` at command start; `DecisionCommand.squashAndRecord` mutates one repo's checkpoint sha on that snapshot and republishes the entire document (`DecisionCommand.java:263-265`), so the second writer's document is built from a pre-first-write snapshot and the first repo's new checkpoint is overwritten with its pre-squash sha. *Symptom:* nothing at approve time — it surfaces later as an exit-4 `Resume.prepare` failure on the next `sdd implement --resume/--retry`, because that repo's recorded checkpoint no longer matches its branch head, which the squash moved. **Ruled documented rather than fixed** in this phase's final review: it fails loudly rather than lying, and fingerprinting a second file (plus a retry loop around a squash that has already rewritten git history) is a design change this wave should not carry. What DID change: `DecisionCommand`'s class javadoc now scopes its concurrency guarantee to `decisions.json` explicitly and names this hazard, and `RunContext.writeReport`'s javadoc points at it, so the code no longer claims a safety it does not have. *Cost if wrong:* a human who approves two repos in parallel gets a run that cannot be resumed until they hand-fix `state.json`.
- **`RunContext.writeReport`'s remaining positional group.** *Trigger:* adding, removing or reordering a `List<String>` parameter on `writeReport`. `ReportInputs` (task 5) named the four adjacent `List<String>` components for every READER inside `ReviewReport`, but `writeReport` still takes `notLocallyVerified`, `stagingFailures` and `restoreFailures` positionally from three call sites (`ReviewCommand.java:144`, `InteractiveReview.java:159`, `DecisionCommand.java:190`), and `ReportInputs`' own canonical constructor takes them positionally too. *Symptom:* a silent transposition — the report renders "these repos could not be checked out at their checkpoint" over a list of repos whose verification tasks were merely excluded, with no compile error anywhere. The hazard moved up one frame; it was not eliminated. `ReportInputs`' javadoc previously claimed it produced "a compile error at every call site" and has been trimmed to what is actually true. Eliminating it needs per-kind wrapper types or a builder.
- **`failureCode` is null on four real failure paths**, so an absent `[code]` bracket in `state.json` or the Gate-2 report does NOT mean the repo succeeded. *Trigger:* a FAILED/PAUSED repo whose reason arose after the agent's own last attempt returned SUCCESS, or before it produced any result: a failed maven-local publish (`Orchestrator.java:275`), binary-incompatible japicmp drift (`:313`), an infrastructural publish failure (`:269`) and `PAUSED_ENDPOINT` (`:230`). *Symptom:* a reader (human or script) that treats "no code" as "no failure" misreads those four transitions. Ruled deliberate — none of those reasons exists in `StepResult`'s vocabulary and inventing one would be worse — and `transitionLocked`'s javadoc now says so instead of claiming the opposite.
- **`ContractRecheck.kafkaExplains`' pattern matching is a heuristic over pattern text**, not Spring's own topic-pattern semantics. *Trigger:* a `@KafkaListener(topicPattern = …)` whose pattern is valid to Spring but means something different to `java.util.regex` (or is not a valid Java regex at all). *Symptom:* an excuse that is slightly too wide or too narrow — a missing declared member either excused when Spring would not have matched it, or (for an uncompilable pattern) reported as `DIVERGED_FROM_PLAN` when the topic really is covered. It is deliberately biased toward the second, safer error. The root cause sits one layer down: `KafkaExtractor` hardcodes `resolution() == "DYNAMIC"` for every `topicPattern` listener whether or not the pattern resolved, so the conformance axis cannot tell an unresolvable expression from a perfectly readable pattern and has to infer it from the text. Fixing it properly means distinguishing the two in `sdd-index`, which this phase's global constraint forbids.

## Self-Review (completed at write time)

1. **Spec coverage:** line 42 → Task 3; line 94 → Task 6; the unresolved amendment → Task 3; the concurrency claim 5B retracted → Task 1; the failure-code carry → Task 4. Line 66's rebuild scope and the approval coupling are named above as deliberately carried.
2. **Placeholder scan:** the only elisions are Task 3's fixture bodies and Task 1's end-to-end conflict harness, each pinned to an existing test class with its assertions enumerated. Task 5 and Task 6 have no code to elide.
3. **Type consistency:** `DecisionsSnapshot`/`writeDecisions(…, fingerprint)` (T1) are used only by T1's callers; `Markdown.neutralizeFences`/`Shas.shortSha` (T2) → the sites T2 lists; `UNRESOLVED_MARKER`/`unresolvedMembers`/`NOT_RESOLVED`/`Finding.unresolved` (T3) → T3 and T5; `RepoRun.failureCode` (T4) → T4 and T5. **T5 must run last** because it absorbs T3's and T4's new parameters.
4. **Judgment calls for reviewers:** optimistic retry over a lock (no new staleness lifecycle); the retry loop in the callers rather than `RunStore` (only the caller knows the transition); divergence outranking unresolved; `StepResult` reused rather than a new taxonomy; REST unresolved inferred rather than signalled, to keep `sdd-index` out of scope.
