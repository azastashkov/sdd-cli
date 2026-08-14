# Phase 5C-1: Declared Contract Grammar + Gate-2 Plan-Conformance Axis

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Gate 1's approved interface machine-comparable, so Gate 2 can report when an implementation shipped something other than what the human approved — today it reads `MATCHES`.

**Architecture:** One shared grammar (`DeclaredContract`, in `sdd-core`) is parsed by Gate 1's validator and consumed by Gate 2's re-check, so the two can never disagree about what a declaration means. Gate 1 gains a second fenced block per contract, holding declarations in the actualizer's own output vocabulary; prose stays in the existing block. Gate 2 gains a **second, independent axis** beside drift — `Conformance` — because "changed since we recorded it" and "matches what Gate 1 approved" are different questions and a contract can be both.

**Tech Stack:** Java 21, Jackson (plan.json), JUnit 5 + AssertJ. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — line 51 (plan.md's Interface Contracts), line 66 (Gate-2 contract re-check, "diff vs plan deltas"), and the **Amendment (2026-08-14): declared contract grammar, and the Gate-2 plan-conformance axis**, which is the binding authority for this plan and should be read first.

## Global Constraints

- **You may only declare what Gate 2 can extract.** The declared grammar is `ContractActualizer`'s output vocabulary, normalized. Anything else builds a check that cannot check. Status codes, response types and handler classes are therefore *not* declarable.
- **Containment, not equality.** Every declared member must appear in the freshly extracted surface. Extras are never divergence — adding API does not break a contract.
- **Truncation suppresses the verdict.** A declared member absent only because extraction hit `ContractActualizer.MAX_BODY` (4000) reports `NOT_COMPARABLE`, never `DIVERGED_FROM_PLAN`.
- **A plan with no declared block reports `NOT_DECLARED`, never `MATCHES`.** Plans frozen before this phase have prose-only bodies and must not read as conforming.
- **Divergence is a warning.** It never fails the review and never changes an exit code (spec line 66: "mismatches = report warnings, human adjudicates"). Coupling it to `Decisions.approve` is explicitly out of scope.
- **Backward compatibility is mandatory in both directions:** a pre-5C `plan.json` (no `declared` key) must load, and a 5C `plan.json` must not break `sdd implement --resume` against a run started before it.
- Zero-test-breaking outside files a task explicitly edits; `./gradlew build` green at every task boundary.
- Conventional commits, ending with the `Co-Authored-By:` trailer in the form the branch's existing commits use.

## Context (verified against source at `397b998`)

Signatures below were read from the code; the literals in the task blocks compile as written.

- The contract record travels through **four shapes**, all currently `(id, kind, provider, consumers, body, compat)`: `PlanDrafter.DraftContract` → `PlanDocument.PlanContract` (`sdd-plan/.../approve/PlanDocument.java:54`) → `PlanJson.Contract` (`PlanJson.java:34`) → `PlanModel.PlanContract` (`sdd-cli/.../implement/PlanModel.java:25`). Every one gains a trailing `List<String> declared`.
- `PlanMdRenderer.java:80-95` renders `### <id> (<kind>[, <compat>]) — <provider> -> <consumers>` then ```` ```yaml ```` + `contract.body()` + ```` ``` ````.
- `PlanMdParser` treats `Interface Contracts` as a section (`PlanMdParser.java:15,106`) and suspends section-splitting inside fences (`:75`) — the new fence must ride that same suspension.
- `PlanJson.java:108-111` writes contracts; `PlanJsonReader.java:41-43` reads them (tree-based, `text(c, "…")`).
- `ContractActualizer.actualize(Path, List<PlanContract>)` returns `Map<contractId, body>`; `javaApi(sessions, draftedBody)` selects types by `draftedBody.contains(type.fqcn()) || draftedBody.contains(simple(type.fqcn()))` and **falls back to the entire surface when nothing matches** (`relevant.isEmpty() ? all : relevant`). `cap()` applies `MAX_BODY` and appends `TRUNCATION_MARKER` (`"…(truncated)"`, public).
- Actualizer output grammar, per kind:
  - `java-api`: `<fqcn>\n` then, per member, `  <signature>: <returnType>` — e.g. `  resolveTier(String): ClientTier`
  - `rest`: `<METHOD> <pathTemplate> -> <classFqcn>#<methodName>`
  - `kafka`: `<role> <topic>`
- `ContractRecheck.Finding(contractId, provider, kind, status, detail, extractedFrom)`; `Status { MATCHES, TRUNCATED_MATCH, DRIFTED, MISSING_RECORD, NOT_EXTRACTABLE }`.
- `ReviewReport.render` takes `List<ContractRecheck.Finding> contracts`; `appendContracts(md, contracts, unstaged)` renders the section and `appendContractLine(...)` the Summary line.
- `sdd-plan` declares `api(project(":sdd-core"))` and `sdd-cli` `implementation(project(":sdd-core"))`, so **`sdd-core` is the only module both can share.**

## File Structure

| File | Responsibility |
|---|---|
| `sdd-core/src/main/java/sdd/core/contract/DeclaredContract.java` | **New.** The grammar: parse declared text per kind into canonical members, report grammar problems, and answer "which declared members are missing from this actual body". The single source of truth both gates use. |
| `sdd-core/src/main/java/sdd/core/contract/ContractKinds.java` | **New.** The three kind names and which are declarable, so no string literal is duplicated across modules. |
| `sdd-plan/.../gen/PlanMdRenderer.java` | Emit the second fenced block. |
| `sdd-plan/.../gen/PlanDrafter.java` | Ask the model for declarations; sanitize; degrade to empty rather than inventing. |
| `sdd-plan/.../approve/PlanMdParser.java` | Capture the `contract` fence into `declared`. |
| `sdd-plan/.../approve/PlanDocument.java`, `PlanJson.java` | Carry `declared` through Gate 1 and into `plan.json`. |
| `sdd-plan/.../approve/PlanValidator.java` | Reject a malformed declaration at Gate 1, where a human can fix it. |
| `sdd-cli/.../implement/PlanModel.java`, `PlanJsonReader.java` | Carry `declared` into the run. |
| `sdd-cli/.../implement/ContractActualizer.java` | Use declarations as the selector; suppress the whole-surface fallback when they exist. |
| `sdd-cli/.../review/ContractRecheck.java` | The `Conformance` axis. |
| `sdd-cli/.../review/ReviewReport.java` | Render conformance beside drift; count it in the Summary. |

---

### Task 1: `DeclaredContract` — the shared grammar

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/contract/DeclaredContract.java`, `sdd-core/src/main/java/sdd/core/contract/ContractKinds.java`
- Test: `sdd-core/src/test/java/sdd/core/contract/DeclaredContractTest.java`

**Interfaces:**
- Produces:
  - `ContractKinds.JAVA_API = "java-api"`, `REST = "rest"`, `KAFKA = "kafka"`; `static boolean declarable(String kind)` — true for all three, false otherwise (an unknown kind must never be reported as diverged).
  - `record DeclaredContract(String kind, List<String> members, List<String> problems)` where `members` are **canonical** strings and `problems` are human-readable grammar errors.
  - `static DeclaredContract parse(String kind, String declaredText)` — blank text yields empty members and no problems (that is `NOT_DECLARED`, not an error).
  - `static List<String> canonicalizeActual(String kind, String actualBody)` — the actualizer's output in the same canonical space.
  - `List<String> missingFrom(String actualBody)` — declared members with no match in the actual body, in declaration order.
  - `boolean isEmpty()`.
- Consumes: nothing. Pure; no I/O.

**Canonical forms** (both sides normalize to these, so comparison is string equality):

| kind | declared input | actual input | canonical |
|---|---|---|---|
| `java-api` | `com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier` | `com.trading.pricing.core.JdbcTierResolver` + `  resolveTier(String): ClientTier` | `com.trading.pricing.core.JdbcTierResolver#resolveTier(String):ClientTier` |
| `rest` | `GET /api/admin/tier-spreads` | `GET /api/admin/tier-spreads -> com.x.C#m` | `GET /api/admin/tier-spreads` |
| `kafka` | `produces orders.v1` | `produces orders.v1` | `produces orders.v1` |

**Type normalization (java-api only):** every type token is reduced to its simple name, *including inside generics*, because `ApiSurfaceExtractor` emits simple names — `java.util.Optional<com.trading.model.Tier>` and `Optional<Tier>` must compare equal. Reduce each maximal run of `[A-Za-z0-9_.$]` by keeping the text after its last `.`. Whitespace is collapsed and spaces around `<`, `>`, `,`, `:` removed.

- [ ] **Step 1: Write the failing test.** `DeclaredContractTest`, covering exactly these cases:

```java
@Test
void aJavaApiDeclarationCanonicalizesToTheActualizersOwnShape() {
    DeclaredContract declared = DeclaredContract.parse("java-api",
            "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier");
    assertThat(declared.members())
            .containsExactly("com.trading.pricing.core.JdbcTierResolver#resolveTier(String):ClientTier");
    assertThat(declared.problems()).isEmpty();
    assertThat(declared.missingFrom("""
            # actualized (java-api)
            com.trading.pricing.core.JdbcTierResolver
              loadAll(): void
              resolveTier(String): ClientTier
            """)).isEmpty();
}

@Test
void aWrongReturnTypeIsMissingEvenThoughTheMethodNameMatches() {
    // The real trading-product-a failure: shipped Tier where the contract said Optional<Tier>.
    DeclaredContract declared = DeclaredContract.parse("java-api",
            "com.trading.pricing.core.TierResolver#tierFor(String): Optional<Tier>");
    assertThat(declared.missingFrom("""
            com.trading.pricing.core.TierResolver
              tierFor(String): Tier
            """))
            .containsExactly("com.trading.pricing.core.TierResolver#tierFor(String):Optional<Tier>");
}

@Test
void typesCompareBySimpleNameIncludingInsideGenerics() {
    DeclaredContract declared = DeclaredContract.parse("java-api",
            "com.trading.pricing.core.TierResolver#tierFor(java.lang.String): java.util.Optional<com.trading.model.Tier>");
    assertThat(declared.missingFrom("""
            com.trading.pricing.core.TierResolver
              tierFor(String): Optional<Tier>
            """)).isEmpty();
}

@Test
void extraActualMembersAreNotDivergence() {
    DeclaredContract declared = DeclaredContract.parse("java-api",
            "com.trading.tier.ClientTier#getTier(): Tier");
    assertThat(declared.missingFrom("""
            com.trading.tier.ClientTier
              getClientId(): String
              getTier(): Tier
              equals(Object): boolean
            """)).isEmpty();
}

@Test
void aDeclaredTypeThatDoesNotExistAtAllIsMissing() {
    DeclaredContract declared = DeclaredContract.parse("java-api",
            "com.trading.absent.Nope#gone(): void");
    assertThat(declared.missingFrom("com.trading.tier.ClientTier\n  getTier(): Tier\n"))
            .containsExactly("com.trading.absent.Nope#gone():void");
}

@Test
void restComparesMethodAndPathAndIgnoresTheHandler() {
    DeclaredContract declared = DeclaredContract.parse("rest", "GET /api/admin/tier-spreads");
    assertThat(declared.members()).containsExactly("GET /api/admin/tier-spreads");
    assertThat(declared.missingFrom(
            "GET /api/admin/tier-spreads -> com.trading.admin.TierSpreadsController#tierSpreads\n"))
            .isEmpty();
    assertThat(declared.missingFrom("POST /api/admin/tier-spreads -> com.x.C#m\n"))
            .containsExactly("GET /api/admin/tier-spreads");
}

@Test
void kafkaComparesRoleAndTopic() {
    DeclaredContract declared = DeclaredContract.parse("kafka", "produces orders.v1");
    assertThat(declared.missingFrom("consumes orders.v1\n")).containsExactly("produces orders.v1");
    assertThat(declared.missingFrom("produces orders.v1\n")).isEmpty();
}

@Test
void blankDeclaredTextIsEmptyAndNotAProblem() {
    DeclaredContract declared = DeclaredContract.parse("java-api", "   \n\n  ");
    assertThat(declared.isEmpty()).isTrue();
    assertThat(declared.problems()).isEmpty();
}

@Test
void aMalformedJavaApiLineIsAProblemNotASilentSkip() {
    DeclaredContract declared = DeclaredContract.parse("java-api", "resolveTier(String): ClientTier");
    assertThat(declared.problems()).hasSize(1);
    assertThat(declared.problems().get(0)).contains("resolveTier(String): ClientTier")
            .contains("<fqcn>#<signature>: <returnType>");
    assertThat(declared.members()).isEmpty();
}

@Test
void commentAndBlankLinesAreIgnored() {
    DeclaredContract declared = DeclaredContract.parse("rest", """
            # the admin surface
            GET /api/admin/tier-spreads

            """);
    assertThat(declared.members()).containsExactly("GET /api/admin/tier-spreads");
    assertThat(declared.problems()).isEmpty();
}

@Test
void anUnknownKindIsNeverDeclarable() {
    assertThat(ContractKinds.declarable("grpc")).isFalse();
    assertThat(DeclaredContract.parse("grpc", "whatever").problems())
            .singleElement().asString().contains("grpc");
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.** Run: `./gradlew :sdd-core:test --tests 'sdd.core.contract.DeclaredContractTest'`

- [ ] **Step 3: Implement.** `ContractKinds` is three constants plus `declarable`. `DeclaredContract.parse` splits on newlines, drops blank lines and lines whose first non-space character is `#`, then per kind:
  - `java-api`: require `<fqcn>#<signature>: <returnType>` — an `#` before the first `(`, and a `:` after the closing `)`. Canonical = `normalizeTypes(fqcn) + "#" + normalizeTypes(signature) + ":" + normalizeTypes(returnType)` with the fqcn left fully qualified (it is the type's identity) but *argument and return* types reduced to simple names.
  - `rest`: require `<METHOD> <path>`, METHOD in `GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS`; canonical = `METHOD + " " + path`.
  - `kafka`: require `<role> <topic>`, two tokens; canonical = as written.

  `canonicalizeActual` mirrors the actualizer's shapes: for `java-api` track the current type (an unindented line) and emit `type + "#" + member` for each indented line, skipping the `# actualized (...)` header; for `rest` cut at `" -> "`; for `kafka` take the line as-is. `missingFrom` is `members` minus `canonicalizeActual(kind, actualBody)`.

- [ ] **Step 4: Run — expect PASS.** Run: `./gradlew :sdd-core:test --tests 'sdd.core.contract.DeclaredContractTest' && ./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-core/src
git commit -m "feat: a shared declared-contract grammar in the actualizer's vocabulary"
```

---

### Task 2: Carry `declared` through Gate 1 and into `plan.json`

**Files:**
- Modify: `sdd-plan/src/main/java/sdd/plan/gen/PlanMdRenderer.java`, `sdd-plan/src/main/java/sdd/plan/approve/PlanMdParser.java`, `sdd-plan/src/main/java/sdd/plan/approve/PlanDocument.java`, `sdd-plan/src/main/java/sdd/plan/approve/PlanJson.java`, `sdd-plan/src/main/java/sdd/plan/gen/PlanDrafter.java` (record only), `sdd-cli/src/main/java/sdd/cli/implement/PlanModel.java`, `sdd-cli/src/main/java/sdd/cli/implement/PlanJsonReader.java`
- Test: `sdd-plan/src/test/java/sdd/plan/approve/PlanMdParserTest.java` (append), `sdd-plan/src/test/java/sdd/plan/gen/PlanMdRendererTest.java` (append), `sdd-plan/src/test/java/sdd/plan/approve/PlanJsonTest.java` (append), `sdd-cli/src/test/java/sdd/cli/implement/PlanJsonReaderTest.java` (append)

**Interfaces:**
- Produces: all four contract records gain a trailing `List<String> declared` (defensively copied in the compact constructor, exactly as `consumers` already is). `plan.json` gains an optional `"declared": [...]` per contract. The rendered block is:

````markdown
### tier-resolver-api (java-api) — trading-platform-libs -> trading-product-a

```yaml
Add to existing JdbcTierResolver: ... (prose, unchanged)
```

```contract
com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier
```
````

- Consumes: Task 1's `DeclaredContract` (parser and validator only in Task 3; this task is plumbing).

**Ordering matters:** add the record component to **all four** records in one commit — a partial rename does not compile, and the compiler is your safety net here.

- [ ] **Step 1: Write the failing tests.**

```java
// PlanMdRendererTest
@Test
void aContractWithDeclarationsRendersASecondFencedBlock() {
    String md = PlanMdRenderer.render(draftWithContract(List.of(
            "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier")));
    assertThat(md).contains("```contract\n"
            + "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier\n"
            + "```");
}

@Test
void aContractWithNoDeclarationsRendersNoContractFence() {
    assertThat(PlanMdRenderer.render(draftWithContract(List.of()))).doesNotContain("```contract");
}

// PlanMdParserTest
@Test
void theContractFenceRoundTripsThroughTheParser() {
    PlanDocument doc = PlanMdParser.parse(PlanMdRenderer.render(draftWithContract(List.of(
            "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier"))));
    assertThat(doc.contracts()).singleElement()
            .extracting(PlanDocument.PlanContract::declared, InstanceOfAssertFactories.list(String.class))
            .containsExactly("com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier");
    // the prose block must be untouched by the new fence
    assertThat(doc.contracts().get(0).body()).contains("Add to existing JdbcTierResolver");
}

@Test
void aPreExistingPlanWithNoContractFenceParsesWithEmptyDeclarations() {
    PlanDocument doc = PlanMdParser.parse(LEGACY_PLAN_MD);   // fixture without a ```contract block
    assertThat(doc.contracts().get(0).declared()).isEmpty();
}

// PlanJsonTest + PlanJsonReaderTest
@Test
void declarationsSurviveTheRoundTripThroughPlanJson() { /* write then read, assert equality */ }

@Test
void aPlanJsonWithoutDeclaredLoadsWithAnEmptyList() {
    PlanModel plan = PlanJsonReader.read(PRE_5C_PLAN_JSON);   // no "declared" key anywhere
    assertThat(plan.contracts().get(0).declared()).isEmpty();
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.** Run: `./gradlew :sdd-plan:test :sdd-cli:test --tests '*Plan*'`

- [ ] **Step 3: Implement.** Add the component to `DraftContract`, `PlanDocument.PlanContract`, `PlanJson.Contract`, `PlanModel.PlanContract` (each with `declared = List.copyOf(declared)`). `PlanMdRenderer` appends the second fence only when `declared` is non-empty. `PlanMdParser` captures a ```` ```contract ```` fence inside a contract's section into its lines — reuse the existing fence-suspension logic at `PlanMdParser.java:75` rather than adding a second mechanism. `PlanJsonReader` reads `"declared"` with the existing `text`/array idiom, defaulting to `List.of()` when the key is absent.

- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-plan/src sdd-cli/src
git commit -m "feat: carry declared contract members from plan.md into plan.json"
```

---

### Task 3: Gate 1 validates the grammar, and the drafter emits it

**Files:**
- Modify: `sdd-plan/src/main/java/sdd/plan/approve/PlanValidator.java`, `sdd-plan/src/main/java/sdd/plan/gen/PlanDrafter.java`
- Test: `sdd-plan/src/test/java/sdd/plan/approve/PlanValidatorTest.java` (append), `sdd-plan/src/test/java/sdd/plan/gen/PlanDrafterTest.java` (append)

**Interfaces:**
- Produces: a malformed declaration is a **problem** (blocks `sdd plan approve`), because Gate 1 is exactly where a human can still fix it; a contract of a declarable kind with *no* declarations is a **warning**, not a problem — pre-existing plans must stay approvable and a human may deliberately leave a contract undeclared.
- Consumes: `DeclaredContract.parse` (Task 1), `PlanDocument.PlanContract.declared` (Task 2).

- [ ] **Step 1: Write the failing test.**

```java
// PlanValidatorTest
@Test
void aMalformedDeclarationIsAProblem() {
    PlanValidator.Result result = PlanValidator.validate(planWithDeclared("resolveTier(String): X"), kb);
    assertThat(result.problems()).anySatisfy(p ->
            assertThat(p).contains("tier-resolver-api").contains("<fqcn>#<signature>: <returnType>"));
}

@Test
void anUndeclaredContractIsOnlyAWarning() {
    PlanValidator.Result result = PlanValidator.validate(planWithDeclared(), kb);   // none
    assertThat(result.problems()).noneMatch(p -> p.contains("declares nothing"));
    assertThat(result.warnings()).anySatisfy(w ->
            assertThat(w).contains("tier-resolver-api").contains("declares nothing"));
}

@Test
void aWellFormedDeclarationIsSilent() {
    PlanValidator.Result result = PlanValidator.validate(
            planWithDeclared("com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier"), kb);
    assertThat(result.problems()).isEmpty();
}

// PlanDrafterTest — ScriptedChatModel, the established idiom
@Test
void theDrafterCapturesADeclaredBlockFromTheModel() { /* scripted response carrying declarations */ }

@Test
void aModelThatOmitsDeclarationsDegradesToAnUndeclaredContractRatherThanInventingOne() {
    // Silence must never be filled in: an invented declaration would be checked against reality
    // and reported as divergence the human never approved.
}
```

- [ ] **Step 2: Run — expect RED.** Run: `./gradlew :sdd-plan:test --tests 'sdd.plan.approve.PlanValidatorTest' --tests 'sdd.plan.gen.PlanDrafterTest'`

- [ ] **Step 3: Implement.** In `PlanValidator`, inside the existing contract loop (`PlanValidator.java:97`), parse each contract's `declared` and add every `problems()` entry prefixed `contract '<id>': `; add the `declares nothing` warning when `ContractKinds.declarable(kind)` and `declared` is empty. In `PlanDrafter`, extend the prompt to require a `declarations:` list per contract in the actualizer's vocabulary, with the three grammars spelled out and an explicit instruction to omit the list when unsure; parse it into `DraftContract.declared`, and run the existing anti-forgery sanitizer over it so a model cannot smuggle prose in.

- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: validate declared contract grammar at Gate 1 and draft it"
```

---

### Task 4: The Gate-2 conformance axis

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/ContractActualizer.java`, `sdd-cli/src/main/java/sdd/cli/review/ContractRecheck.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/ContractRecheckTest.java` (append), `sdd-cli/src/test/java/sdd/cli/implement/ContractActualizerTest.java` (append)

**Interfaces:**
- Produces: `ContractRecheck.Conformance { DECLARED_MET, DIVERGED_FROM_PLAN, NOT_DECLARED, NOT_COMPARABLE }` and `Finding` gains trailing `Conformance conformance, List<String> missing` (empty unless `DIVERGED_FROM_PLAN`). The existing `Status` is untouched — the two axes are computed independently and a finding may be `DRIFTED` **and** `DIVERGED_FROM_PLAN`.
- Consumes: `DeclaredContract` (Task 1), `PlanContract.declared` (Task 2).

**The three rules from the amendment, each mapping to one code change:**

1. `ContractActualizer.javaApi` takes the declared members as its selector when non-empty: select types whose fqcn appears in a declared member, and **skip the `relevant.isEmpty() ? all : relevant` fallback entirely** in that case, so "none of the declared types exist" produces a small body that fails containment loudly instead of a whole-surface dump that hides it. With no declarations, behavior is byte-for-byte unchanged.
2. Conformance is computed *before* truncation is ruled out: if `missingFrom` is non-empty **and** the actual body ends with `TRUNCATION_MARKER`, the verdict is `NOT_COMPARABLE`, not `DIVERGED_FROM_PLAN`.
3. Empty `declared` → `NOT_DECLARED`, whatever the drift axis says.

- [ ] **Step 1: Write the failing test.**

```java
@Test
void aContractWhoseImplementationMatchesTheDeclarationIsDeclaredMet() { /* → DECLARED_MET, missing empty */ }

@Test
void aWrongReturnTypeIsDivergedFromPlanEvenWhenItMatchesWhatTheRunRecorded() {
    // The core case: fresh == recorded, so the drift axis says MATCHES — and the conformance
    // axis must still say DIVERGED_FROM_PLAN. This is the bug this phase exists to fix.
    assertThat(finding.status()).isEqualTo(ContractRecheck.Status.MATCHES);
    assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.DIVERGED_FROM_PLAN);
    assertThat(finding.missing())
            .containsExactly("com.trading.pricing.core.TierResolver#tierFor(String):Optional<Tier>");
}

@Test
void aContractWithNoDeclarationsIsNotDeclaredRatherThanMet() { /* pre-5C plan → NOT_DECLARED */ }

@Test
void aMissingMemberBeyondTheTruncationCapIsNotComparableNotDiverged() {
    // Body padded past MAX_BODY so it ends with TRUNCATION_MARKER; the declared member is absent.
    assertThat(finding.conformance()).isEqualTo(ContractRecheck.Conformance.NOT_COMPARABLE);
}

@Test
void aFindingCanBeBothDriftedAndDiverged() { /* both axes independent, both asserted */ }

@Test
void declaredTypesSelectTheExtractionAndSuppressTheWholeSurfaceFallback() {
    // ContractActualizerTest: declaring a type that does not exist must NOT dump every type.
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.ContractRecheckTest' --tests 'sdd.cli.implement.ContractActualizerTest'`

- [ ] **Step 3: Implement**, threading `declared` into `actualize` and computing the axis in `check`.

- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: Gate-2 reports divergence from the contract Gate 1 approved"
```

---

### Task 5: Report the axis

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/review/ReviewReport.java`
- Test: `sdd-cli/src/test/java/sdd/cli/review/ReviewReportTest.java` (append)

**Interfaces:**
- Produces: each contract finding line gains its conformance; a `DIVERGED_FROM_PLAN` finding lists every missing member indented beneath it. The Summary gains `- Plan conformance: <m> met, <d> diverged, <u> undeclared, <n> not comparable` immediately after the existing `- Contract re-check:` line. Exit codes are **unchanged** — divergence is a warning.
- Consumes: Task 4's `Conformance` and `missing`.

- [ ] **Step 1: Write the failing test.**

```java
@Test
void aDivergedContractNamesEveryMissingMemberInTheReport() {
    assertThat(report).contains("DIVERGED_FROM_PLAN")
            .contains("declared but not found: com.trading.pricing.core.TierResolver#tierFor(String):Optional<Tier>");
}

@Test
void theSummaryCountsEveryConformanceState() {
    assertThat(report).contains("- Plan conformance: 1 met, 1 diverged, 1 undeclared, 0 not comparable");
}

@Test
void anUndeclaredContractSaysSoRatherThanClaimingConformance() {
    assertThat(report).contains("NOT_DECLARED").doesNotContain("DECLARED_MET");
}

@Test
void divergenceDoesNotChangeTheExitCode() { /* ReviewCommandTest-level: diverged → still exit 0 */ }
```

- [ ] **Step 2: Run — expect RED.** Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.review.ReviewReportTest'`
- [ ] **Step 3: Implement** in `appendContracts` and `appendContractLine`.
- [ ] **Step 4: Run — expect PASS, then the full build.** Run: `./gradlew build`
- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: render plan conformance in the Gate-2 report"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. **Amendment coverage:** the declared grammar per kind (Task 1); declare-only-what-can-be-extracted, enforced by the grammar's own shape (Task 1) and by validation (Task 3); the second axis with its four values (Task 4); truncation suppresses the verdict (Task 4); `NOT_DECLARED` never reads as `MATCHES` (Tasks 4-5); the selector replacement and fallback suppression (Task 4); warning-only severity (Task 5).
3. **Backward compatibility, both directions:** a pre-5C `plan.json` loads with empty declarations (Task 2) and re-checks as `NOT_DECLARED` (Task 4); a 5C `plan.json` does not break `sdd implement --resume` on a run started before it.
4. **Real-estate readiness:** the frozen `SPEC-101-v1` run re-checks as `NOT_DECLARED` for both contracts, proving rule 2 on real data without a re-plan. A fresh `sdd plan` cycle on the trading estate then exercises the drafter's declared block end to end — and `trading-product-a`'s known `Tier`-vs-`Optional<Tier>` mistake is the natural divergence case to reproduce.

## Known carried items (explicitly NOT in this phase)

- **Decision concurrency safety, the 5B code cleanups, and polish + docs** — Phase 5C-2.
- **The estate rebuild covers only SUCCEEDED repos**, not "all affected repos" (spec line 66). Ratified in 5A and re-ratified for 5C.
- **Report failure codes** are `RepoState` + `run.detail()` free text rather than spec line 141's ladder.
- **`kafka` declarations are untested against a real estate** — the trading estate has no Kafka (Phase-0 spike #4 confirmed a true negative), so that kind's grammar is fixture-proven only.
- **Divergence does not block approval.** `Decisions.approve` is untouched; a human may approve a diverged repo.

## Self-Review (completed at write time)

1. **Spec coverage:** every clause of the 2026-08-14 amendment maps to a task (Verification 2). Line 51's "compilable skeletons … in fenced YAML" is satisfied in spirit by a second fence in a grammar a machine can check; the prose block stays YAML-fenced and unchanged, so no existing plan's rendering breaks.
2. **Placeholder scan:** the only elisions are Task 3's `PlanDrafter` scripted-model fixtures and Task 4/5's assertion bodies, each pinned to an existing test class with its assertions enumerated. No "TBD", no "handle edge cases".
3. **Type consistency:** `DeclaredContract.parse/canonicalizeActual/missingFrom/isEmpty` and `ContractKinds.declarable` (T1) → T3, T4; `declared` as a trailing `List<String>` on all four contract records (T2) → T3, T4; `Conformance` + `Finding`'s two new trailing components (T4) → T5. `ContractActualizer.actualize` gains the declared selector in T4 only.
4. **Judgment calls for reviewers:** a second fence rather than structured YAML inside the existing one (YAML would need quoting for `#` and `: ` and the codebase has already been bitten by YAML-1.1 traps); malformed declaration = problem but undeclared = warning; conformance as a separate field rather than a `Status` constant; divergence never changes an exit code.
