# Phase 3A — Spec Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `sdd plan` its front door: the internal R/A/C spec model, a strict markdown parser + byte-identical renderer, and the `SpecSource` seam whose v1 adapter turns an exported Confluence page into a human-gated normalized spec — plus the three Phase-2C operational carry-forward fixes.

**Architecture:** Everything lands in the previously empty `sdd-plan` module (package `sdd.plan.spec` for the model/parser/renderer/seam, `sdd.plan.confluence` for the v1 adapter) except the carry-forward fixes (sdd-core/sdd-index/sdd-cli) and the new `PlanCommand` (sdd-cli). The only model call site is `ConfluenceNormalizer` (planner endpoint, DeepSeek); its output is always rendered to a file for human review — nothing downstream consumes it in this phase. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` Component 2 + the 2026-08-11 Amendment; carry-forwards: bottom of `docs/superpowers/plans/2026-08-11-phase2c-matching-cards-curation.md`.

**Tech Stack:** Java 21, SnakeYAML 2.2 (front matter), Jackson databind 2.17.2 (normalizer JSON), jsoup 1.18.3 (NEW — Confluence XHTML extraction), picocli 4.7.6, JUnit 5 + AssertJ, ScriptedChatModel from sdd-core testFixtures.

## Global Constraints

- Java 21; all dependency versions go through `gradle/libs.versions.toml`; jsoup is the ONLY new third-party library this phase.
- The internal spec model is the design's Component-2 format: front matter `id, title, owner, status` + H2 sections in this canonical order: `Goal` (required), `Background`, `Requirements` (required, `R#` items), `Acceptance Criteria` (required, `A#` items), `Constraints` (`C#`), `Touchpoints`, `Out of Scope`, `Open Questions` (`Q#`), plus `Attachments` — an amendment-driven addition carrying recorded attachment references. Touchpoint kinds EXACTLY: `repo | endpoint | topic | class | artifact`.
- The `SpecSource` seam is the amendment's verbatim shape: `interface SpecSource { NormalizedSpec load(String ref); }`.
- Deterministic-first: the ONLY model call in this phase is `ConfluenceNormalizer.normalize`. Its output is written to a file for the Gate-1 human review — never fed onward automatically. Images are NOT interpreted: they become attachment references.
- `finish_reason == "length"` is an explicit failure, never half-parsed (design System-shape rule).
- Round-trip law: `SpecParser.parse(SpecRenderer.render(spec)).equals(spec)` for every valid spec, and canonical files re-render byte-identically.
- `SpecParseException` messages start `line <N>: ` (1-based). CLI output taxonomy: unexpected failures (exceptions) print `error: <message>` to stderr, exit 1; semantic validation findings print `problem: <text>`, exit 1; normalization gate hints print `  gate: <text>`, exit 0. All `printf` uses `Locale.ROOT`.
- Carry-forwards #3 (card cache key) and #4 (golden third repo) are explicitly DEFERRED — do not touch `RepoCardGenerator`'s hash composition, `GoldenEstateTest`, or `golden/estate.json`. (Task 1 touches the matcher but changes only in-memory `Report` counters, never persisted `rest_call_edge` rows, so the golden estate is unaffected and #4's regeneration trigger does not fire — it stays deferred to the phase that changes matcher edge OUTPUT.)
- Never read or print `.env` or any `api_key` value. Test yaml uses unreachable endpoints (`http://127.0.0.1:1/v1`) with no real keys.
- Never push. Run the FULL `./gradlew build` before each commit that touches more than one module, and before finishing any task.

---

## File Structure

**Task 1 (fix):** `sdd-index/.../store/RestMatcher.java`, `RestMatcherTest.java`
**Task 2 (fix):** `sdd-core/.../llm/HttpChatModel.java` + `HttpChatModelTest.java`; `sdd-index/.../IndexService.java` + `IndexServiceTest.java`; `sdd-cli/.../IndexCommand.java`
**Task 3:** `gradle/libs.versions.toml`, `sdd-plan/build.gradle.kts`, `sdd-plan/src/main/java/sdd/plan/spec/{SpecItem,Touchpoint,NormalizedSpec,SpecValidator}.java` + tests
**Task 4:** `sdd-plan/src/main/java/sdd/plan/spec/{SpecParseException,SpecParser}.java` + `SpecParserTest.java`
**Task 5:** `sdd-plan/src/main/java/sdd/plan/spec/SpecRenderer.java`, `sdd-plan/src/test/resources/spec/canonical.md` + `SpecRendererTest.java`
**Task 6:** `sdd-plan/src/main/java/sdd/plan/confluence/{SpecNormalizationException,ConfluenceExtract}.java` + `ConfluenceExtractTest.java`
**Task 7:** `sdd-plan/src/main/java/sdd/plan/spec/{SpecSource,MarkdownSpecSource,SpecSources}.java`, `sdd-plan/src/main/java/sdd/plan/confluence/{ConfluenceNormalizer,ConfluenceExportSource}.java` + tests
**Task 8:** `sdd-cli/src/main/java/sdd/cli/PlanCommand.java`, `SddCli.java`, `sdd-cli/build.gradle.kts` + `PlanCommandTest.java`

---

### Task 1: RestMatcher counter fix (carry-forward #2)

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/store/RestMatcher.java` (manual pass, ~lines 98-105)
- Test: `sdd-index/src/test/java/sdd/index/store/RestMatcherTest.java`

**Interfaces:**
- Consumes: existing `RestMatcher.match(Jdbi, List<ManualEdge>)` and `Report(int high, int medium, int low, int manual, List<String> warnings)`.
- Produces: unchanged signatures; `Report` counts now always equal the surviving rows per confidence.

Context: the manual pass deletes already-counted rows (`DELETE FROM rest_call_edge WHERE client_id=:c AND endpoint_id=:e`) and replaces them with HIGH/MANUAL, but never decrements the `high`/`medium`/`low` counters, so the CLI `match:` line over-reports.

- [ ] **Step 1: Write the failing test** (append to `RestMatcherTest`, using its existing `client(...)`/`endpoint(...)` helpers and module fields):

```java
@Test
void manualPinDecrementsCountersForReplacedEdges() {
    client(ordersModule, "RESTTEMPLATE", "POST", "/pay/charge", null, "/pay/charge");
    endpoint(billingModule, "POST", "/pay/charge");
    endpoint(inventoryModule, "POST", "/pay/charge");   // ambiguous: LOW × 2 before the pin

    RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of(
            new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service")));

    // the pin deleted the svc-orders→billing LOW row and replaced it with HIGH/MANUAL;
    // the svc-orders→inventory LOW row survives — counters must match surviving rows
    assertThat(report.low()).isEqualTo(1);
    assertThat(report.manual()).isEqualTo(1);
    Integer lowRows = db.jdbi().withHandle(h -> h.createQuery(
            "SELECT count(*) FROM rest_call_edge WHERE confidence='LOW'").mapTo(Integer.class).one());
    assertThat(lowRows).isEqualTo(report.low());
}
```

- [ ] **Step 2: Run it — expect FAIL** with `report.low()` == 2 (over-count).
Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.RestMatcherTest'`

- [ ] **Step 3: Fix the manual pass.** In `RestMatcher.match`, inside the `for (long cid : clientIds) { for (long eid : endpointIds) {` loop, BEFORE the existing DELETE, read the confidences being replaced and decrement:

```java
for (long cid : clientIds) {
    for (long eid : endpointIds) {
        List<String> replaced = h.createQuery(
                        "SELECT confidence FROM rest_call_edge WHERE client_id=:c AND endpoint_id=:e")
                .bind("c", cid).bind("e", eid).mapTo(String.class).list();
        for (String confidence : replaced) {
            switch (confidence) {
                case "HIGH" -> high--;
                case "MEDIUM" -> medium--;
                case "LOW" -> low--;
                default -> { }
            }
        }
        h.createUpdate("DELETE FROM rest_call_edge WHERE client_id=:c AND endpoint_id=:e")
                .bind("c", cid).bind("e", eid).execute();
        insertEdge(h, cid, eid, "HIGH", "MANUAL");
        manual++;
    }
}
```

(`high`/`medium`/`low` are the existing local counters in the same transaction lambda — make them non-final locals as they already are; no structural change.)

- [ ] **Step 4: Run the sdd-index suite — expect PASS** (all existing RestMatcher tests must stay green; `manualEdgePinsAndUnmatchedManualEdgeWarns` already pins over a LOW pair and keeps passing because it asserts `manual()` and rows, not `low()`).
Run: `./gradlew :sdd-index:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "fix: decrement match counters when a manual pin replaces counted edges"
```

---

### Task 2: Card-path operational hardening (carry-forwards #1 and #5)

**Files:**
- Modify: `sdd-core/src/main/java/sdd/core/llm/HttpChatModel.java` (configurable attempt cap)
- Modify: `sdd-index/src/main/java/sdd/index/IndexService.java` (`lastCardError` accessor)
- Modify: `sdd-cli/src/main/java/sdd/cli/IndexCommand.java` (crash-vs-skip output + capped card model)
- Test: `sdd-core/src/test/java/sdd/core/llm/HttpChatModelTest.java`, `sdd-index/src/test/java/sdd/index/IndexServiceTest.java`

**Interfaces:**
- Produces: `public HttpChatModel(ModelEndpoint endpoint, int maxAttempts)` and `public HttpChatModel(ModelEndpoint endpoint, int maxAttempts, HttpClient client, Sleeper sleeper)`; existing 1-arg and 3-arg constructors keep default `MAX_ATTEMPTS = 6`. `public String lastCardError()` on `IndexService` (null unless card generation crashed).
- Consumes: existing retry loop internals (`MAX_ATTEMPTS`, `backoff`, `Sleeper`).

Context (final-review Important finding): a TCP-accepting-but-silent coder endpoint stalls each card call for 6 attempts × `timeout_seconds` (default 600 s) before the 3-consecutive-failure short-circuit — hours of silence. Cards are background enrichment with input-hash caching, so failing fast is correct: cap card-path attempts at 2. Separately, a swallowed card crash currently prints exactly like `--no-cards`.

- [ ] **Step 1: Write the failing HttpChatModel test** (append to `HttpChatModelTest`; it already has the wiremock extension, `request()` helper, and a `model()` helper to mirror):

```java
@Test
void attemptCapBoundsRetries() {
    wm.stubFor(post("/v1/chat/completions").willReturn(serverError()));
    ModelEndpoint ep = new ModelEndpoint(wm.baseUrl() + "/v1", "test-model", "sk-key",
            256, 0.0, Duration.ofSeconds(5));
    HttpChatModel capped = new HttpChatModel(ep, 2, HttpClient.newHttpClient(), millis -> { });

    assertThatThrownBy(() -> capped.complete(request()))
            .isInstanceOf(ModelException.class);
    wm.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
}
```

- [ ] **Step 2: Run it — expect COMPILE FAILURE** (no 4-arg constructor).
Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.HttpChatModelTest'`

- [ ] **Step 3: Add the attempt cap to HttpChatModel.** Add a `private final int maxAttempts;` field. Constructors (keep existing field assignments; `MAX_ATTEMPTS` stays as the default constant):

```java
public HttpChatModel(ModelEndpoint endpoint) {
    this(endpoint, MAX_ATTEMPTS);
}

public HttpChatModel(ModelEndpoint endpoint, int maxAttempts) {
    this(endpoint, maxAttempts, HttpClient.newHttpClient(), Thread::sleep);
}

public HttpChatModel(ModelEndpoint endpoint, HttpClient client, Sleeper sleeper) {
    this(endpoint, MAX_ATTEMPTS, client, sleeper);
}

public HttpChatModel(ModelEndpoint endpoint, int maxAttempts, HttpClient client, Sleeper sleeper) {
    if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    this.maxAttempts = maxAttempts;
    // ... existing endpoint/client/sleeper assignments unchanged ...
}
```

Inside `complete(...)`, replace every use of `MAX_ATTEMPTS` in the retry loop (loop bound and the "no sleep after final attempt" check) with the `maxAttempts` field.

- [ ] **Step 4: Run sdd-core tests — expect PASS** (existing `retriesOn5xxThenSucceeds` proves the default path still retries).
Run: `./gradlew :sdd-core:test`

- [ ] **Step 5: Write the failing IndexService test changes.** In `IndexServiceTest`, extend the two existing card tests:

In `cardGenerationRuntimeExceptionIsSwallowedAndRunSucceeds`, after `assertThat(service.lastCardResult()).isNull();` add:

```java
            assertThat(service.lastCardError()).contains("boom");
```

In `noCardModelLeavesCardResultNull`, after `assertThat(service.lastCardResult()).isNull();` add:

```java
            assertThat(service.lastCardError()).isNull();
```

- [ ] **Step 6: Run — expect COMPILE FAILURE** (no `lastCardError()`).
Run: `./gradlew :sdd-index:test --tests 'sdd.index.IndexServiceTest'`

- [ ] **Step 7: Implement `lastCardError`.** In `IndexService`: add field `private String lastCardError;` next to the other `last*` fields, and change `generateCards` to record the failure:

```java
private RepoCardGenerator.CardResult generateCards(Jdbi jdbi, Path workspace) {
    lastCardError = null;
    if (cardModel == null) {
        return null;
    }
    try {
        return RepoCardGenerator.generate(jdbi, workspace, cardModel, cardModelName);
    } catch (RuntimeException e) {
        lastCardError = String.valueOf(e);
        return null;
    }
}
```

Add the accessor next to `lastCardResult()`:

```java
public String lastCardError() {
    return lastCardError;
}
```

- [ ] **Step 8: Wire the CLI.** In `IndexCommand`:
  - Add constant: `private static final int CARD_MAX_ATTEMPTS = 2;  // cards are cached background enrichment — fail fast, retry on the next index run`
  - Change the card-model construction to `new IndexService(null, new HttpChatModel(coder, CARD_MAX_ATTEMPTS), coder.model())`.
  - Change the cards output branch so a crash is distinguishable from a configured skip:

```java
if (service.lastCardResult() == null) {
    out.println(service.lastCardError() == null
            ? "cards: skipped"
            : "cards: failed (" + firstLine(service.lastCardError()) + ")");
} else {
    // existing printf line unchanged
}
```

(The `cards: failed` CLI branch is a ternary over state fully covered by the `IndexServiceTest` assertions in Step 5; `IndexCommandTest`'s existing `--no-cards` and cards-enabled tests pin both other output paths. Triggering a real card crash through the CLI would require a repo fixture and a real Gradle run — out of proportion for this branch. Sanctioned deviation from carry-forward #5's wording: the failed line goes to STDOUT, not stderr, keeping all `cards:` status lines on one stream — the substance, crash ≠ configured skip, is met.)

- [ ] **Step 9: Full build — expect PASS.**
Run: `./gradlew build`

- [ ] **Step 10: Commit**

```bash
git add sdd-core/src sdd-index/src sdd-cli/src
git commit -m "fix: cap card-path retry attempts and surface swallowed card crashes"
```

---

### Task 3: sdd-plan bootstrap + spec model + validator

**Files:**
- Modify: `gradle/libs.versions.toml` (add jsoup)
- Modify: `sdd-plan/build.gradle.kts`
- Create: `sdd-plan/src/main/java/sdd/plan/spec/SpecItem.java`, `Touchpoint.java`, `NormalizedSpec.java`, `SpecValidator.java`
- Test: `sdd-plan/src/test/java/sdd/plan/spec/SpecValidatorTest.java`

**Interfaces:**
- Produces (later tasks depend on these EXACT shapes):
  - `public record SpecItem(String id, String text)`
  - `public record Touchpoint(Touchpoint.Kind kind, String value)` with `enum Kind { REPO, ENDPOINT, TOPIC, CLASS, ARTIFACT }`, `String key()` (lowercase name), `static Kind fromKey(String)` (null on unknown)
  - `public record NormalizedSpec(String id, String title, String owner, String status, String goal, String background, List<SpecItem> requirements, List<SpecItem> acceptance, List<SpecItem> constraints, List<Touchpoint> touchpoints, List<String> outOfScope, List<SpecItem> openQuestions, List<String> attachments)` — strings never null (`background` is `""` when absent), lists never null and defensively copied
  - `public static List<String> problems(NormalizedSpec)` on `SpecValidator` (empty = valid)

- [ ] **Step 1: Add jsoup to the catalog.** In `gradle/libs.versions.toml`, add under `[versions]`: `jsoup = "1.18.3"` and under `[libraries]`: `jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }`.

- [ ] **Step 2: Extend `sdd-plan/build.gradle.kts`** (snakeyaml/jackson are `implementation`-scoped in sdd-core, so sdd-plan must declare its own):

```kotlin
plugins {
    `java-library`
}

dependencies {
    api(project(":sdd-core"))
    implementation(libs.snakeyaml)
    implementation(libs.jackson)
    implementation(libs.jsoup)
    testImplementation(libs.bundles.test)
    testImplementation(testFixtures(project(":sdd-core")))
    testRuntimeOnly(libs.junit.launcher)
}
```

- [ ] **Step 3: Write the failing validator test** — `sdd-plan/src/test/java/sdd/plan/spec/SpecValidatorTest.java`:

```java
package sdd.plan.spec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecValidatorTest {

    private static NormalizedSpec valid() {
        return new NormalizedSpec("SPEC-1", "Loyalty tiers", "ana", "draft",
                "Add loyalty tiers to pricing.", "",
                List.of(new SpecItem("R1", "Price response includes the customer tier.")),
                List.of(new SpecItem("A1", "GET /price returns tier for gold customers.")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void validSpecHasNoProblems() {
        assertThat(SpecValidator.problems(valid())).isEmpty();
    }

    @Test
    void blankFrontMatterAndEmptyRequiredSectionsAreNamed() {
        NormalizedSpec s = new NormalizedSpec("", "Loyalty tiers", " ", "draft",
                "", "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(SpecValidator.problems(s)).contains(
                "front matter: id is blank",
                "front matter: owner is blank",
                "Goal section is empty",
                "Requirements: at least one R item is required",
                "Acceptance Criteria: at least one A item is required");
    }

    @Test
    void idShapeDuplicatesAndBlankTextAreNamed() {
        NormalizedSpec s = new NormalizedSpec("SPEC-1", "T", "o", "draft", "G", "",
                List.of(new SpecItem("R1", "a"), new SpecItem("R1", "b"), new SpecItem("X2", "c"),
                        new SpecItem("R3", " ")),
                List.of(new SpecItem("A1", "ok")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(SpecValidator.problems(s)).contains(
                "Requirements: duplicate id 'R1'",
                "Requirements: id 'X2' must match R<number>",
                "Requirements: R3 has no text");
    }

    @Test
    void listsAreDefensivelyCopiedAndNullsRejected() {
        java.util.List<SpecItem> mutable = new java.util.ArrayList<>(
                List.of(new SpecItem("R1", "x")));
        NormalizedSpec s = new NormalizedSpec("i", "t", "o", "s", "g", "",
                mutable, List.of(new SpecItem("A1", "y")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        mutable.clear();
        assertThat(s.requirements()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new NormalizedSpec(null, "t", "o", "s", "g", "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 4: Run — expect COMPILE FAILURE** (types missing).
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 5: Implement the records.**

`SpecItem.java`:

```java
package sdd.plan.spec;

import java.util.Objects;

/** One ID-prefixed spec bullet: R1/A1/C1/Q1 plus its text. */
public record SpecItem(String id, String text) {
    public SpecItem {
        Objects.requireNonNull(id);
        Objects.requireNonNull(text);
    }
}
```

`Touchpoint.java`:

```java
package sdd.plan.spec;

import java.util.Locale;
import java.util.Objects;

/** A KB hint from the spec author — verified against the knowledge base in Phase 3B, never trusted. */
public record Touchpoint(Kind kind, String value) {
    public Touchpoint {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(value);
    }

    public enum Kind {
        REPO, ENDPOINT, TOPIC, CLASS, ARTIFACT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromKey(String key) {
            for (Kind kind : values()) {
                if (kind.key().equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
```

`NormalizedSpec.java`:

```java
package sdd.plan.spec;

import java.util.List;
import java.util.Objects;

/**
 * The internal structured spec model — the ONLY shape anything downstream of ingestion
 * consumes, regardless of where the spec came from (canonical markdown, Confluence, ...).
 */
public record NormalizedSpec(String id, String title, String owner, String status,
                             String goal, String background,
                             List<SpecItem> requirements, List<SpecItem> acceptance,
                             List<SpecItem> constraints, List<Touchpoint> touchpoints,
                             List<String> outOfScope, List<SpecItem> openQuestions,
                             List<String> attachments) {
    public NormalizedSpec {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(status);
        Objects.requireNonNull(goal);
        Objects.requireNonNull(background);
        requirements = List.copyOf(requirements);
        acceptance = List.copyOf(acceptance);
        constraints = List.copyOf(constraints);
        touchpoints = List.copyOf(touchpoints);
        outOfScope = List.copyOf(outOfScope);
        openQuestions = List.copyOf(openQuestions);
        attachments = List.copyOf(attachments);
    }
}
```

`SpecValidator.java`:

```java
package sdd.plan.spec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Semantic completeness checks. Structural grammar is SpecParser's job — this judges a parsed spec. */
public final class SpecValidator {
    private SpecValidator() {
    }

    public static List<String> problems(NormalizedSpec spec) {
        List<String> problems = new ArrayList<>();
        requireNonBlank(problems, "id", spec.id());
        requireNonBlank(problems, "title", spec.title());
        requireNonBlank(problems, "owner", spec.owner());
        requireNonBlank(problems, "status", spec.status());
        if (spec.goal().isBlank()) {
            problems.add("Goal section is empty");
        }
        if (spec.requirements().isEmpty()) {
            problems.add("Requirements: at least one R item is required");
        }
        if (spec.acceptance().isEmpty()) {
            problems.add("Acceptance Criteria: at least one A item is required");
        }
        checkItems(problems, "Requirements", "R", spec.requirements());
        checkItems(problems, "Acceptance Criteria", "A", spec.acceptance());
        checkItems(problems, "Constraints", "C", spec.constraints());
        checkItems(problems, "Open Questions", "Q", spec.openQuestions());
        return problems;
    }

    private static void requireNonBlank(List<String> problems, String field, String value) {
        if (value.isBlank()) {
            problems.add("front matter: " + field + " is blank");
        }
    }

    private static void checkItems(List<String> problems, String section, String prefix,
                                   List<SpecItem> items) {
        Set<String> seen = new HashSet<>();
        Pattern shape = Pattern.compile(prefix + "[1-9][0-9]*");
        for (SpecItem item : items) {
            if (!shape.matcher(item.id()).matches()) {
                problems.add(section + ": id '" + item.id() + "' must match " + prefix + "<number>");
            } else if (!seen.add(item.id())) {
                problems.add(section + ": duplicate id '" + item.id() + "'");
            }
            if (item.text().isBlank()) {
                problems.add(section + ": " + item.id() + " has no text");
            }
        }
    }
}
```

- [ ] **Step 6: Run — expect PASS.**
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 7: Full build (catalog + new module deps touched), then commit**

```bash
./gradlew build
git add gradle/libs.versions.toml sdd-plan
git commit -m "feat: internal spec model, validator, and sdd-plan module bootstrap"
```

---

### Task 4: Strict spec parser

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/spec/SpecParseException.java`, `SpecParser.java`
- Test: `sdd-plan/src/test/java/sdd/plan/spec/SpecParserTest.java`

**Interfaces:**
- Consumes: Task 3 records.
- Produces: `public static NormalizedSpec parse(String markdown)` on `SpecParser`; `public class SpecParseException extends RuntimeException` with `SpecParseException(int line, String message)` and `public int line()`, message rendered as `line <N>: <message>`.

- [ ] **Step 1: Write the failing tests** — `SpecParserTest.java`:

```java
package sdd.plan.spec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpecParserTest {

    static final String FULL = """
            ---
            id: SPEC-9
            title: 'Loyalty tiers: phase one'
            owner: ana
            status: draft
            ---

            ## Goal
            Add loyalty tiers to pricing.

            Gold customers get the discounted rate.

            ## Background
            Pricing today is flat per SKU.

            ## Requirements
            - R1: Price response includes the customer tier.
            - R2: Tier rules load from configuration.

            ## Acceptance Criteria
            - A1: GET /price returns tier for gold customers.

            ## Constraints
            - C1: No schema change to the pricing database.

            ## Touchpoints
            - repo: svc-pricing
            - endpoint: GET /price

            ## Out of Scope
            - Loyalty point accrual

            ## Open Questions
            - Q1: Which service owns tier configuration?

            ## Attachments
            - tier-diagram.png
            """;

    @Test
    void parsesEverySectionOfTheCanonicalFormat() {
        NormalizedSpec spec = SpecParser.parse(FULL);

        assertThat(spec.id()).isEqualTo("SPEC-9");
        assertThat(spec.title()).isEqualTo("Loyalty tiers: phase one");
        assertThat(spec.owner()).isEqualTo("ana");
        assertThat(spec.status()).isEqualTo("draft");
        assertThat(spec.goal()).isEqualTo(
                "Add loyalty tiers to pricing.\n\nGold customers get the discounted rate.");
        assertThat(spec.background()).isEqualTo("Pricing today is flat per SKU.");
        assertThat(spec.requirements()).containsExactly(
                new SpecItem("R1", "Price response includes the customer tier."),
                new SpecItem("R2", "Tier rules load from configuration."));
        assertThat(spec.acceptance()).containsExactly(
                new SpecItem("A1", "GET /price returns tier for gold customers."));
        assertThat(spec.constraints()).containsExactly(
                new SpecItem("C1", "No schema change to the pricing database."));
        assertThat(spec.touchpoints()).containsExactly(
                new Touchpoint(Touchpoint.Kind.REPO, "svc-pricing"),
                new Touchpoint(Touchpoint.Kind.ENDPOINT, "GET /price"));
        assertThat(spec.outOfScope()).containsExactly("Loyalty point accrual");
        assertThat(spec.openQuestions()).containsExactly(
                new SpecItem("Q1", "Which service owns tier configuration?"));
        assertThat(spec.attachments()).containsExactly("tier-diagram.png");
    }

    @Test
    void minimalSpecParsesWithEmptyOptionals() {
        NormalizedSpec spec = SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc
                """);
        assertThat(spec.background()).isEmpty();
        assertThat(spec.constraints()).isEmpty();
        assertThat(spec.touchpoints()).isEmpty();
        assertThat(spec.attachments()).isEmpty();
    }

    @Test
    void missingFrontMatterFailsAtLineOne() {
        assertThatThrownBy(() -> SpecParser.parse("## Goal\nG\n"))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 1: spec must start with '---'");
    }

    @Test
    void unknownFrontMatterKeyAndMissingKeyFail() {
        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                priority: high
                ---
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageContaining("unknown front matter key 'priority'");
        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                ---
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageContaining("front matter is missing 'status'");
    }

    @Test
    void unknownAndOutOfOrderSectionsFailWithLineNumbers() {
        String unknown = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Storyline
                x
                """;
        assertThatThrownBy(() -> SpecParser.parse(unknown))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 8: unknown section '## Storyline'");

        String outOfOrder = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Requirements
                - R1: req

                ## Goal
                G.
                """;
        assertThatThrownBy(() -> SpecParser.parse(outOfOrder))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 11: section 'Goal' is duplicated or out of canonical order");
    }

    @Test
    void malformedBulletsFailWithTheExpectedShape() {
        String badItem = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - X1: wrong prefix
                """;
        assertThatThrownBy(() -> SpecParser.parse(badItem))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 12: Requirements items must look like '- R1: <text>'");

        String badTouchpoint = """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc

                ## Touchpoints
                - service: svc-pricing
                """;
        assertThatThrownBy(() -> SpecParser.parse(badTouchpoint))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 18: Touchpoints items must look like '- repo: <value>'")
                .hasMessageContaining("repo, endpoint, topic, class, artifact");
    }

    @Test
    void crlfInputParsesIdenticallyToLf() {
        // regression pin, not a TDD RED step — String.lines() already handles \r\n; this
        // keeps a future refactor to split("\n") from breaking Windows-edited gate files
        assertThat(SpecParser.parse(FULL.replace("\n", "\r\n"))).isEqualTo(SpecParser.parse(FULL));
    }

    @Test
    void contentBeforeFirstSectionAndMissingRequiredSectionFail() {
        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---
                stray prose
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageStartingWith("line 7: content before the first '## ' section heading");

        assertThatThrownBy(() -> SpecParser.parse("""
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req
                """))
                .isInstanceOf(SpecParseException.class)
                .hasMessageContaining("missing required section '## Acceptance Criteria'");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-plan:test --tests 'sdd.plan.spec.SpecParserTest'`

- [ ] **Step 3: Implement.**

`SpecParseException.java`:

```java
package sdd.plan.spec;

/** Structural spec error, pinned to a 1-based line number. */
public class SpecParseException extends RuntimeException {
    private final int line;

    public SpecParseException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
```

`SpecParser.java`:

```java
package sdd.plan.spec;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict line-oriented parser for the canonical spec format (design Component 2): YAML front
 * matter with exactly id/title/owner/status, then '## ' sections in canonical order. Structural
 * violations throw SpecParseException with a line number; semantic completeness is
 * SpecValidator's job (an empty Requirements section parses fine and fails validation).
 */
public final class SpecParser {
    static final List<String> ORDER = List.of("Goal", "Background", "Requirements",
            "Acceptance Criteria", "Constraints", "Touchpoints", "Out of Scope",
            "Open Questions", "Attachments");
    private static final List<String> REQUIRED = List.of("Goal", "Requirements", "Acceptance Criteria");
    private static final List<String> FRONT_KEYS = List.of("id", "title", "owner", "status");
    private static final Map<String, Pattern> ITEM_PATTERNS = Map.of(
            "Requirements", Pattern.compile("- (R[1-9][0-9]*): (.+)"),
            "Acceptance Criteria", Pattern.compile("- (A[1-9][0-9]*): (.+)"),
            "Constraints", Pattern.compile("- (C[1-9][0-9]*): (.+)"),
            "Open Questions", Pattern.compile("- (Q[1-9][0-9]*): (.+)"));
    private static final Map<String, String> ITEM_HINTS = Map.of(
            "Requirements", "R1", "Acceptance Criteria", "A1", "Constraints", "C1", "Open Questions", "Q1");
    private static final Pattern TOUCHPOINT = Pattern.compile("- ([a-z]+): (.+)");
    private static final Pattern PLAIN = Pattern.compile("- (.+)");

    private SpecParser() {
    }

    public static NormalizedSpec parse(String markdown) {
        List<String> lines = markdown.lines().toList();
        if (lines.isEmpty() || !lines.get(0).equals("---")) {
            throw new SpecParseException(1, "spec must start with '---' front matter");
        }
        int close = lines.subList(1, lines.size()).indexOf("---");
        if (close < 0) {
            throw new SpecParseException(lines.size(), "front matter is never closed with '---'");
        }
        close += 1;   // index of the closing --- in lines
        Map<String, String> front = frontMatter(lines.subList(1, close), close + 1);

        Builder b = new Builder();
        String section = null;
        List<String> prose = new ArrayList<>();
        for (int i = close + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (line.startsWith("## ")) {
                closeSection(b, section, prose);
                section = heading(line.substring(3), section, lineNo);
                b.seen.add(section);
                prose = new ArrayList<>();
            } else if (line.startsWith("#")) {
                throw new SpecParseException(lineNo, "only '## ' section headings are allowed");
            } else if (section == null) {
                if (!line.isBlank()) {
                    throw new SpecParseException(lineNo, "content before the first '## ' section heading");
                }
            } else if (section.equals("Goal") || section.equals("Background")) {
                prose.add(line);   // prose keeps lines verbatim, including internal blanks
            } else if (!line.isBlank()) {
                bullet(b, section, line, lineNo);
            }
        }
        closeSection(b, section, prose);
        for (String required : REQUIRED) {
            if (!b.seen.contains(required)) {
                throw new SpecParseException(lines.size(), "missing required section '## " + required + "'");
            }
        }
        return new NormalizedSpec(front.get("id"), front.get("title"), front.get("owner"),
                front.get("status"), b.goal, b.background, b.requirements, b.acceptance,
                b.constraints, b.touchpoints, b.outOfScope, b.openQuestions, b.attachments);
    }

    private static Map<String, String> frontMatter(List<String> yamlLines, int closingLine) {
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(String.join("\n", yamlLines));
        } catch (RuntimeException e) {
            throw new SpecParseException(2, "front matter is not valid YAML: " + e.getMessage());
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SpecParseException(2, "front matter must be a YAML mapping");
        }
        Map<String, String> front = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!FRONT_KEYS.contains(key)) {
                throw new SpecParseException(2,
                        "unknown front matter key '" + key + "' (allowed: id, title, owner, status)");
            }
            front.put(key, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        for (String key : FRONT_KEYS) {
            if (!front.containsKey(key)) {
                throw new SpecParseException(closingLine, "front matter is missing '" + key + "'");
            }
        }
        return front;
    }

    private static String heading(String name, String current, int lineNo) {
        int idx = ORDER.indexOf(name);
        if (idx < 0) {
            throw new SpecParseException(lineNo,
                    "unknown section '## " + name + "' (known: " + String.join(", ", ORDER) + ")");
        }
        int currentIdx = current == null ? -1 : ORDER.indexOf(current);
        if (idx <= currentIdx) {
            throw new SpecParseException(lineNo,
                    "section '" + name + "' is duplicated or out of canonical order");
        }
        return name;
    }

    private static void bullet(Builder b, String section, String line, int lineNo) {
        Pattern itemPattern = ITEM_PATTERNS.get(section);
        if (itemPattern != null) {
            Matcher m = itemPattern.matcher(line);
            if (!m.matches()) {
                throw new SpecParseException(lineNo, section + " items must look like '- "
                        + ITEM_HINTS.get(section) + ": <text>'");
            }
            SpecItem item = new SpecItem(m.group(1), m.group(2));
            switch (section) {
                case "Requirements" -> b.requirements.add(item);
                case "Acceptance Criteria" -> b.acceptance.add(item);
                case "Constraints" -> b.constraints.add(item);
                default -> b.openQuestions.add(item);
            }
            return;
        }
        if (section.equals("Touchpoints")) {
            Matcher m = TOUCHPOINT.matcher(line);
            Touchpoint.Kind kind = m.matches() ? Touchpoint.Kind.fromKey(m.group(1)) : null;
            if (kind == null) {
                throw new SpecParseException(lineNo, "Touchpoints items must look like "
                        + "'- repo: <value>' (kinds: repo, endpoint, topic, class, artifact)");
            }
            b.touchpoints.add(new Touchpoint(kind, m.group(2)));
            return;
        }
        Matcher m = PLAIN.matcher(line);
        if (!m.matches()) {
            throw new SpecParseException(lineNo, section + " items must look like '- <text>'");
        }
        (section.equals("Out of Scope") ? b.outOfScope : b.attachments).add(m.group(1));
    }

    private static void closeSection(Builder b, String section, List<String> prose) {
        if ("Goal".equals(section)) {
            b.goal = String.join("\n", prose).strip();
        } else if ("Background".equals(section)) {
            b.background = String.join("\n", prose).strip();
        }
    }

    private static final class Builder {
        final Set<String> seen = new HashSet<>();
        String goal = "";
        String background = "";
        final List<SpecItem> requirements = new ArrayList<>();
        final List<SpecItem> acceptance = new ArrayList<>();
        final List<SpecItem> constraints = new ArrayList<>();
        final List<Touchpoint> touchpoints = new ArrayList<>();
        final List<String> outOfScope = new ArrayList<>();
        final List<SpecItem> openQuestions = new ArrayList<>();
        final List<String> attachments = new ArrayList<>();
    }
}
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: strict state-machine parser for the canonical spec format"
```

---

### Task 5: Renderer + round-trip law

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/spec/SpecRenderer.java`
- Create: `sdd-plan/src/test/resources/spec/canonical.md`
- Test: `sdd-plan/src/test/java/sdd/plan/spec/SpecRendererTest.java`

**Interfaces:**
- Consumes: Task 3 records, Task 4 `SpecParser.parse`.
- Produces: `public static String render(NormalizedSpec)` on `SpecRenderer`. Canonical form: required sections always emitted (even empty); optional sections omitted when empty; every section preceded by one blank line; file ends with a newline.

- [ ] **Step 1: Create the canonical golden resource** `sdd-plan/src/test/resources/spec/canonical.md` — byte-exact content (no trailing spaces; final newline after the last line):

```markdown
---
id: SPEC-9
title: 'Loyalty tiers: phase one'
owner: ana
status: draft
---

## Goal
Add loyalty tiers to pricing.

Gold customers get the discounted rate.

## Background
Pricing today is flat per SKU.

## Requirements
- R1: Price response includes the customer tier.
- R2: Tier rules load from configuration.

## Acceptance Criteria
- A1: GET /price returns tier for gold customers.

## Constraints
- C1: No schema change to the pricing database.

## Touchpoints
- repo: svc-pricing
- endpoint: GET /price

## Out of Scope
- Loyalty point accrual

## Open Questions
- Q1: Which service owns tier configuration?

## Attachments
- tier-diagram.png
```

- [ ] **Step 2: Write the failing tests** — `SpecRendererTest.java`:

```java
package sdd.plan.spec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpecRendererTest {

    @Test
    void canonicalFileReRendersByteIdentically() throws Exception {
        String canonical = new String(SpecRendererTest.class
                .getResourceAsStream("/spec/canonical.md").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(SpecRenderer.render(SpecParser.parse(canonical))).isEqualTo(canonical);
    }

    @Test
    void parseOfRenderIsIdentityForMinimalSpec() {
        NormalizedSpec spec = new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(SpecParser.parse(SpecRenderer.render(spec))).isEqualTo(spec);
    }

    @Test
    void parseOfRenderIsIdentityForTrickyScalars() {
        NormalizedSpec spec = new NormalizedSpec("S-2", "Bob's launch: v2 #final", "o", "draft",
                "Line one.\n\nLine two.", "Some\ncontext.",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(new SpecItem("C1", "constraint")),
                List.of(new Touchpoint(Touchpoint.Kind.TOPIC, "orders.events")),
                List.of("out"), List.of(new SpecItem("Q1", "q?")), List.of("a.png"));
        assertThat(SpecParser.parse(SpecRenderer.render(spec))).isEqualTo(spec);
    }

    @Test
    void yamlTrapScalarsAreQuotedAndRoundTrip() {
        // bare 'no'/'123'/'2026-08-11' would be resolved by SnakeYAML to Boolean/Integer/Date —
        // the renderer must quote anything that does not read back as the identical string
        for (String trap : List.of("no", "yes", "on", "true", "null", "123", "1.10", "0x1A", "2026-08-11")) {
            NormalizedSpec spec = new NormalizedSpec(trap, trap, trap, trap, "G.", "",
                    List.of(new SpecItem("R1", "r")), List.of(new SpecItem("A1", "a")),
                    List.of(), List.of(), List.of(), List.of(), List.of());
            assertThat(SpecParser.parse(SpecRenderer.render(spec))).as(trap).isEqualTo(spec);
        }
    }

    @Test
    void requiredSectionsRenderEvenWhenEmptySoIncompleteSpecsRoundTrip() {
        NormalizedSpec incomplete = new NormalizedSpec("S-3", "T", "unknown", "draft", "", "",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        String rendered = SpecRenderer.render(incomplete);
        assertThat(rendered).contains("## Goal").contains("## Requirements")
                .contains("## Acceptance Criteria")
                .doesNotContain("## Background").doesNotContain("## Touchpoints");
        assertThat(SpecParser.parse(rendered)).isEqualTo(incomplete);
    }
}
```

- [ ] **Step 3: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-plan:test --tests 'sdd.plan.spec.SpecRendererTest'`

- [ ] **Step 4: Implement** `SpecRenderer.java`:

```java
package sdd.plan.spec;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders a NormalizedSpec to the canonical markdown form. Law: parse(render(spec)) == spec
 * for every valid spec, and files already in canonical form re-render byte-identically.
 * Required sections (Goal/Requirements/Acceptance Criteria) are always emitted — an
 * incomplete normalized spec must still round-trip so the Gate-1 reviewer can complete it.
 */
public final class SpecRenderer {
    private static final Pattern SAFE_SCALAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._/-]*");

    private SpecRenderer() {
    }

    public static String render(NormalizedSpec spec) {
        StringBuilder md = new StringBuilder("---\n");
        scalar(md, "id", spec.id());
        scalar(md, "title", spec.title());
        scalar(md, "owner", spec.owner());
        scalar(md, "status", spec.status());
        md.append("---\n");
        prose(md, "Goal", spec.goal(), true);
        prose(md, "Background", spec.background(), false);
        items(md, "Requirements", spec.requirements(), true);
        items(md, "Acceptance Criteria", spec.acceptance(), true);
        items(md, "Constraints", spec.constraints(), false);
        touchpoints(md, spec.touchpoints());
        plain(md, "Out of Scope", spec.outOfScope());
        items(md, "Open Questions", spec.openQuestions(), false);
        plain(md, "Attachments", spec.attachments());
        return md.toString();
    }

    private static void scalar(StringBuilder md, String key, String value) {
        md.append(key).append(": ");
        if (bareSafe(value)) {
            md.append(value);
        } else {
            md.append('\'').append(value.replace("'", "''")).append('\'');
        }
        md.append('\n');
    }

    /**
     * A value may render unquoted only when YAML reads the bare scalar back as the identical
     * string — 'no'/'123'/'1.10'/'2026-08-11' resolve to Boolean/Integer/Double/Date under
     * YAML 1.1 and would corrupt the round trip.
     */
    private static boolean bareSafe(String value) {
        if (!SAFE_SCALAR.matcher(value).matches() || value.endsWith(" ")) {
            return false;
        }
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(value);
        return value.equals(parsed);
    }

    private static void prose(StringBuilder md, String section, String body, boolean required) {
        if (!required && body.isBlank()) {
            return;
        }
        md.append("\n## ").append(section).append('\n');
        if (!body.isBlank()) {
            md.append(body.strip()).append('\n');
        }
    }

    private static void items(StringBuilder md, String section, List<SpecItem> items, boolean required) {
        if (!required && items.isEmpty()) {
            return;
        }
        md.append("\n## ").append(section).append('\n');
        for (SpecItem item : items) {
            md.append("- ").append(item.id()).append(": ").append(item.text()).append('\n');
        }
    }

    private static void touchpoints(StringBuilder md, List<Touchpoint> touchpoints) {
        if (touchpoints.isEmpty()) {
            return;
        }
        md.append("\n## Touchpoints\n");
        for (Touchpoint t : touchpoints) {
            md.append("- ").append(t.kind().key()).append(": ").append(t.value()).append('\n');
        }
    }

    private static void plain(StringBuilder md, String section, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        md.append("\n## ").append(section).append('\n');
        for (String value : values) {
            md.append("- ").append(value).append('\n');
        }
    }
}
```

- [ ] **Step 5: Run — expect PASS.** If the byte-identity test fails, diff the two strings — the fixture must have NO trailing whitespace and exactly one final newline.
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 6: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: canonical spec renderer with parse/render round-trip law"
```

---

### Task 6: Deterministic Confluence extraction

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/confluence/SpecNormalizationException.java`, `ConfluenceExtract.java`
- Test: `sdd-plan/src/test/java/sdd/plan/confluence/ConfluenceExtractTest.java`

**Interfaces:**
- Produces: `public static Extracted extract(String html)` on `ConfluenceExtract` with nested `public record Extracted(String text, List<String> attachments)`; `public class SpecNormalizationException extends RuntimeException` with `(String)` and `(String, Throwable)` ctors. `static final int MAX_TEXT_CHARS = 300_000` guard.
- Consumes: jsoup (Task 3 dependency).

Handles BOTH input flavors per the amendment: Confluence storage-format XHTML (`<ac:image><ri:attachment ri:filename=…>`, `<ac:structured-macro>` code blocks — jsoup selects namespaced tags via `ac|image` / `ri|attachment`) and plain exported HTML (`<img src=…>`). Images are NEVER interpreted — they become `[attachment: <name>]` markers plus entries in `attachments`.

- [ ] **Step 1: Write the failing tests** — `ConfluenceExtractTest.java`:

```java
package sdd.plan.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceExtractTest {

    @Test
    void storageFormatHeadingsListsTablesImagesAndCodeBecomeMarkdownishText() {
        String storage = """
                <h1>Loyalty tiers</h1>
                <p>We want tiered pricing. <ac:image><ri:attachment ri:filename="tiers.png"/></ac:image></p>
                <ul><li>gold</li><li>silver</li></ul>
                <table>
                  <tr><th>Tier</th><th>Discount</th></tr>
                  <tr><td>gold</td><td>10%</td></tr>
                  <tr><td>si|lver</td><td>5%</td></tr>
                </table>
                <ac:structured-macro ac:name="code"><ac:plain-text-body>GET /price</ac:plain-text-body></ac:structured-macro>
                """;

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(storage);

        assertThat(extracted.text()).contains("# Loyalty tiers");
        assertThat(extracted.text()).contains("We want tiered pricing.").contains("[attachment: tiers.png]");
        assertThat(extracted.text()).contains("- gold").contains("- silver");
        assertThat(extracted.text()).contains("| Tier | Discount |")
                .contains("| --- | --- |")
                .contains("| gold | 10% |")
                .contains("| si\\|lver | 5% |");
        assertThat(extracted.text()).contains("```\nGET /price\n```");
        assertThat(extracted.attachments()).containsExactly("tiers.png");
    }

    @Test
    void exportedHtmlImagesAreCollectedByFileNameOnceEach() {
        String html = """
                <html><body>
                <h2>Design</h2>
                <p><img src="attachments/123/diagram.png"></p>
                <p><img src="attachments/123/diagram.png"></p>
                <div><p>Nested prose survives.</p></div>
                </body></html>
                """;

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(html);

        assertThat(extracted.text()).contains("## Design").contains("[attachment: diagram.png]")
                .contains("Nested prose survives.");
        assertThat(extracted.attachments()).containsExactly("diagram.png");
    }

    @Test
    void cdataCodeBodiesSurvive() {
        // real storage-format exports wrap code in CDATA; jsoup's HTML parser turns the CDATA
        // section into a bogus Comment node — the extractor must recover it
        String storage = "<ac:structured-macro ac:name=\"code\">"
                + "<ac:plain-text-body><![CDATA[GET /price?tier=gold]]></ac:plain-text-body>"
                + "</ac:structured-macro>";

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(storage);

        assertThat(extracted.text()).contains("```\nGET /price?tier=gold\n```");
    }

    @Test
    void bareTextPreBlocksAndInlineWrappersAreNotLost() {
        String html = """
                <div>Intro prose outside any paragraph.<p>Para.</p><span>Trailing <b>note</b>.</span></div>
                <pre>curl -s /price</pre>
                """;

        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(html);

        assertThat(extracted.text()).contains("Intro prose outside any paragraph.")
                .contains("Para.")
                .contains("Trailing note.")
                .contains("```\ncurl -s /price\n```");
    }

    @Test
    void oversizeExtractionFailsLoudly() {
        String huge = "<p>" + "x".repeat(ConfluenceExtract.MAX_TEXT_CHARS + 100) + "</p>";
        assertThatThrownBy(() -> ConfluenceExtract.extract(huge))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("too large");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-plan:test --tests 'sdd.plan.confluence.ConfluenceExtractTest'`

- [ ] **Step 3: Implement.**

`SpecNormalizationException.java`:

```java
package sdd.plan.confluence;

/** Spec ingestion failed before a normalized spec could be produced — rerun after fixing the cause. */
public class SpecNormalizationException extends RuntimeException {
    public SpecNormalizationException(String message) {
        super(message);
    }

    public SpecNormalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ConfluenceExtract.java`:

```java
package sdd.plan.confluence;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic text extraction from a Confluence export — storage-format XHTML or exported
 * HTML. No model involvement: headings/paragraphs/lists/tables/code become markdown-ish text;
 * images are NOT interpreted (design amendment) — they become [attachment: name] markers and
 * entries in Extracted.attachments so the Gate-1 reviewer knows visual context exists.
 * Walks child NODES (not just elements): bare text directly inside div/body wrappers must
 * survive — silent prose loss would starve the normalizer without anyone noticing.
 */
public final class ConfluenceExtract {
    static final int MAX_TEXT_CHARS = 300_000;   // ~75k tokens — leaves DeepSeek headroom
    private static final Pattern CDATA = Pattern.compile("\\[CDATA\\[(.*)]]", Pattern.DOTALL);

    public record Extracted(String text, List<String> attachments) {
    }

    private ConfluenceExtract() {
    }

    public static Extracted extract(String html) {
        Document doc = Jsoup.parse(html);
        List<String> attachments = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        walk(doc.body(), text, attachments);
        String result = text.toString().strip();
        if (result.length() > MAX_TEXT_CHARS) {
            throw new SpecNormalizationException("Confluence export too large: extracted "
                    + result.length() + " chars (limit " + MAX_TEXT_CHARS + ")");
        }
        return new Extracted(result, List.copyOf(attachments));
    }

    private static void walk(Element parent, StringBuilder text, List<String> attachments) {
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode textNode) {
                String bare = textNode.text().strip();
                if (!bare.isEmpty()) {
                    text.append(bare).append("\n\n");
                }
            } else if (node instanceof Element el) {
                element(el, text, attachments);
            }
        }
    }

    private static void element(Element el, StringBuilder text, List<String> attachments) {
        switch (el.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = el.tagName().charAt(1) - '0';
                text.append("#".repeat(level)).append(' ').append(el.text()).append("\n\n");
            }
            case "p" -> {
                String paragraph = paragraph(el, attachments);
                if (!paragraph.isBlank()) {
                    text.append(paragraph).append("\n\n");
                }
            }
            case "ul", "ol" -> {
                for (Element li : el.children()) {
                    if (li.tagName().equals("li")) {
                        text.append("- ").append(li.text()).append('\n');
                    }
                }
                text.append('\n');
            }
            case "table" -> {
                table(el, text);
                text.append('\n');
            }
            case "pre" -> text.append("```\n").append(el.wholeText().strip()).append("\n```\n\n");
            case "ac:image" -> text.append(storageImageRef(el, attachments)).append("\n\n");
            case "img" -> text.append(htmlImageRef(el, attachments)).append("\n\n");
            case "ac:structured-macro" -> {
                Element body = el.selectFirst("ac|plain-text-body");
                if (body != null) {
                    String code = plainText(body);
                    if (!code.isEmpty()) {
                        text.append("```\n").append(code).append("\n```\n\n");
                    }
                } else {
                    walk(el, text, attachments);
                }
            }
            default -> {
                // block-free wrappers (span/a/strong/...) render as one paragraph; anything
                // containing block structure dives deeper
                if (el.select("p, ul, ol, table, pre, h1, h2, h3, h4, h5, h6").isEmpty()) {
                    String paragraph = paragraph(el, attachments);
                    if (!paragraph.isBlank()) {
                        text.append(paragraph).append("\n\n");
                    }
                } else {
                    walk(el, text, attachments);
                }
            }
        }
    }

    /** jsoup's HTML parser turns CDATA sections into bogus Comment nodes — recover the payload. */
    private static String plainText(Element body) {
        String direct = body.wholeText().strip();
        if (!direct.isEmpty()) {
            return direct;
        }
        for (Node node : body.childNodes()) {
            if (node instanceof Comment comment) {
                Matcher m = CDATA.matcher(comment.getData());
                if (m.matches()) {
                    return m.group(1).strip();
                }
            }
        }
        return "";
    }

    private static String paragraph(Element p, List<String> attachments) {
        StringBuilder sb = new StringBuilder(p.text());
        for (Element image : p.select("ac|image, img")) {
            String ref = image.tagName().equals("img")
                    ? htmlImageRef(image, attachments)
                    : storageImageRef(image, attachments);
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(ref);
        }
        return sb.toString().strip();
    }

    private static void table(Element tableEl, StringBuilder text) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : tableEl.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.select("th, td")) {
                cells.add(cell.text().replace("|", "\\|"));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        text.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n");
        text.append("|").append(" --- |".repeat(rows.get(0).size())).append('\n');
        for (List<String> row : rows.subList(1, rows.size())) {
            text.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
    }

    private static String storageImageRef(Element acImage, List<String> attachments) {
        Element attachment = acImage.selectFirst("ri|attachment");
        String name = attachment == null ? "unknown-image" : attachment.attr("ri:filename");
        return remember(name.isBlank() ? "unknown-image" : name, attachments);
    }

    private static String htmlImageRef(Element img, List<String> attachments) {
        String src = img.attr("src");
        int slash = src.lastIndexOf('/');
        String name = slash >= 0 ? src.substring(slash + 1) : src;
        return remember(name.isBlank() ? "unknown-image" : name, attachments);
    }

    private static String remember(String name, List<String> attachments) {
        if (!attachments.contains(name)) {
            attachments.add(name);
        }
        return "[attachment: " + name + "]";
    }
}
```

Known v1 simplifications (fine to keep; note for reviewers): nested tables flatten (`tr.select` is descendant-scoped), list items render flat. Both degrade to readable text and the human gate sees the result.

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: deterministic Confluence export extraction with attachment refs"
```

---

### Task 7: Normalizer + SpecSource seam

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/spec/SpecSource.java`, `MarkdownSpecSource.java`, `SpecSources.java`
- Create: `sdd-plan/src/main/java/sdd/plan/confluence/ConfluenceNormalizer.java`, `ConfluenceExportSource.java`
- Test: `sdd-plan/src/test/java/sdd/plan/confluence/ConfluenceNormalizerTest.java`, `sdd-plan/src/test/java/sdd/plan/spec/MarkdownSpecSourceTest.java`

**Interfaces:**
- Produces:
  - `public interface SpecSource { NormalizedSpec load(String ref); }` (amendment verbatim)
  - `public final class MarkdownSpecSource implements SpecSource` (reads the file, `SpecParser.parse`)
  - `public static boolean isConfluenceExport(String ref)` on `SpecSources` (`.html`/`.htm`/`.xhtml`, case-insensitive)
  - `public static NormalizedSpec normalize(ConfluenceExtract.Extracted extracted, ChatModel planner, String modelName, int maxTokens, String fallbackId)` on `ConfluenceNormalizer`
  - `public ConfluenceExportSource(ChatModel planner, String modelName, int maxTokens)` implementing `SpecSource`; `static String specId(Path file)` → `spec-<slug-of-basename>`
- Consumes: `ChatModel`/`ChatRequest`/`ChatMessage`/`ChatResponse` from `sdd.core.llm`; Task 3/4/6 types; `ScriptedChatModel` from `sdd.core.testing` (testFixtures).

Model-call rules (mirrors `RepoCardGenerator`, adjusted for the planner): temperature 0.15; `maxTokens` comes from the planner endpoint config (NOT hardcoded — normalization output is a whole spec); `finish_reason == "length"` → `SpecNormalizationException`; malformed JSON → `SpecNormalizationException` with a ≤200-char snippet; ``` fences stripped before parsing. IDs are assigned by CODE (`R1..Rn` in array order), never trusted from the model. Model-supplied `unmapped` items and invalid touchpoint kinds become Open Questions prefixed `[unmapped] ` / `[unmapped touchpoint] `. `ModelException` propagates to the caller (CLI prints it). SANITIZATION (the rendered gate file must re-parse): every list item and scalar field collapses internal whitespace to single spaces (`replaceAll("\\s+", " ")` — bullets are one-line by grammar); goal/background keep newlines but strip leading heading markers (`(?m)^\s*#+\s*` → `""`) so prose can never collide with the section grammar.

- [ ] **Step 1: Write the failing normalizer tests** — `ConfluenceNormalizerTest.java`:

```java
package sdd.plan.confluence;

import org.junit.jupiter.api.Test;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceNormalizerTest {

    private static final ConfluenceExtract.Extracted EXTRACTED =
            new ConfluenceExtract.Extracted("# Loyalty\nWe want tiers.", List.of("tiers.png"));

    private static ChatResponse response(String content, String finishReason) {
        return new ChatResponse(ChatMessage.assistant(content), finishReason, new Usage(10, 10));
    }

    private static final String GOOD_JSON = """
            {"title": "Loyalty tiers", "owner": "", "status": "", "goal": "Add tiers.",
             "background": "Flat pricing today.",
             "requirements": ["Price response includes tier", "Tier rules configurable"],
             "acceptance": ["GET /price returns tier"],
             "constraints": ["No pricing schema change"],
             "touchpoints": [{"kind": "repo", "value": "svc-pricing"},
                             {"kind": "service", "value": "bogus"}],
             "out_of_scope": ["Point accrual"],
             "open_questions": ["Who owns tier config?"],
             "unmapped": ["Rollout percentage table"]}""";

    @Test
    void assignsIdsCarriesAttachmentsAndDemotesUnmappedToOpenQuestions() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "deepseek-v4-flash",
                16384, "spec-loyalty-page");

        assertThat(spec.id()).isEqualTo("spec-loyalty-page");
        assertThat(spec.owner()).isEqualTo("unknown");
        assertThat(spec.status()).isEqualTo("draft");
        assertThat(spec.requirements()).containsExactly(
                new SpecItem("R1", "Price response includes tier"),
                new SpecItem("R2", "Tier rules configurable"));
        assertThat(spec.acceptance()).containsExactly(new SpecItem("A1", "GET /price returns tier"));
        assertThat(spec.constraints()).containsExactly(new SpecItem("C1", "No pricing schema change"));
        assertThat(spec.touchpoints()).containsExactly(
                new Touchpoint(Touchpoint.Kind.REPO, "svc-pricing"));
        assertThat(spec.openQuestions()).containsExactly(
                new SpecItem("Q1", "Who owns tier config?"),
                new SpecItem("Q2", "[unmapped] Rollout percentage table"),
                new SpecItem("Q3", "[unmapped touchpoint] service: bogus"));
        assertThat(spec.attachments()).containsExactly("tiers.png");

        // prompt shape: system prompt + the extracted text; planner maxTokens passed through
        assertThat(planner.requests()).hasSize(1);
        assertThat(planner.requests().get(0).messages().get(0).content())
                .isEqualTo(ConfluenceNormalizer.SYSTEM_PROMPT);
        assertThat(planner.requests().get(0).messages().get(1).content())
                .contains("We want tiers.");
        assertThat(planner.requests().get(0).maxTokens()).isEqualTo(16384);
    }

    @Test
    void fencedJsonIsAccepted() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(
                response("```json\n" + GOOD_JSON + "\n```", "stop")));
        NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id");
        assertThat(spec.requirements()).hasSize(2);
    }

    @Test
    void truncatedResponseFailsExplicitly() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("{", "length")));
        assertThatThrownBy(() -> ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id"))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("finish_reason=length");
    }

    @Test
    void malformedJsonFailsWithSnippet() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("not json at all", "stop")));
        assertThatThrownBy(() -> ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id"))
                .isInstanceOf(SpecNormalizationException.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageContaining("not json at all");
    }

    @Test
    void modelWhitespaceAndHeadingMarkersAreSanitizedSoTheSpecReparses() {
        // a multi-line requirement or a goal quoting a markdown heading must not produce a
        // gate file that SpecParser later rejects
        String json = """
                {"title": "T", "owner": "", "status": "", "goal": "# Big plan\\nAdd tiers.",
                 "background": "", "requirements": ["line one\\nline two"],
                 "acceptance": ["ok"], "constraints": [], "touchpoints": [],
                 "out_of_scope": [], "open_questions": [], "unmapped": []}""";
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(json, "stop")));

        NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id");

        assertThat(spec.goal()).isEqualTo("Big plan\nAdd tiers.");
        assertThat(spec.requirements()).containsExactly(new SpecItem("R1", "line one line two"));
        assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec)))
                .isEqualTo(spec);
    }
}
```

And `MarkdownSpecSourceTest.java`:

```java
package sdd.plan.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownSpecSourceTest {
    @TempDir Path dir;

    @Test
    void loadsAndParsesAFileRef() throws Exception {
        Path file = dir.resolve("s.md");
        Files.writeString(file, """
                ---
                id: S-1
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                - A1: acc
                """);
        assertThat(new MarkdownSpecSource().load(file.toString()).id()).isEqualTo("S-1");
    }

    @Test
    void missingFileSurfacesAsUncheckedIo() {
        assertThatThrownBy(() -> new MarkdownSpecSource().load(dir.resolve("nope.md").toString()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void refShapeSelection() {
        assertThat(SpecSources.isConfluenceExport("page.HTML")).isTrue();
        assertThat(SpecSources.isConfluenceExport("page.xhtml")).isTrue();
        assertThat(SpecSources.isConfluenceExport("page.htm")).isTrue();
        assertThat(SpecSources.isConfluenceExport("spec.md")).isFalse();
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 3: Implement.**

`SpecSource.java`:

```java
package sdd.plan.spec;

/**
 * The spec-ingestion seam (design amendment 2026-08-11): implementations are selected by ref
 * shape. v1: MarkdownSpecSource (canonical passthrough) and ConfluenceExportSource. Future
 * adapters (canonical SDD format, Confluence REST API) plug in here.
 */
public interface SpecSource {
    NormalizedSpec load(String ref);
}
```

`MarkdownSpecSource.java`:

```java
package sdd.plan.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Passthrough adapter: the ref is a canonical spec markdown file. */
public final class MarkdownSpecSource implements SpecSource {
    @Override
    public NormalizedSpec load(String ref) {
        try {
            return SpecParser.parse(Files.readString(Path.of(ref)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`SpecSources.java`:

```java
package sdd.plan.spec;

import java.util.Locale;

/** Ref-shape dispatch for the SpecSource seam. */
public final class SpecSources {
    private SpecSources() {
    }

    public static boolean isConfluenceExport(String ref) {
        String lower = ref.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml");
    }
}
```

`ConfluenceNormalizer.java`:

```java
package sdd.plan.confluence;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.ArrayList;
import java.util.List;

/**
 * The single model call site of Phase 3A: maps extracted Confluence text into the internal
 * spec model. IDs are assigned here, never trusted from the model; anything the model could
 * not place confidently lands in Open Questions. The result is ALWAYS human-gated — the
 * caller renders it to a file for Gate-1 review; nothing consumes it directly.
 */
public final class ConfluenceNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String SYSTEM_PROMPT = """
            You convert one raw feature-specification document into strict JSON for a \
            spec-driven development pipeline. Return exactly ONE JSON object - no markdown \
            fences, no commentary - with exactly these fields:
            {"title": string, "owner": string, "status": string, "goal": string,
             "background": string, "requirements": [string, ...], "acceptance": [string, ...],
             "constraints": [string, ...],
             "touchpoints": [{"kind": "repo"|"endpoint"|"topic"|"class"|"artifact", "value": string}, ...],
             "out_of_scope": [string, ...], "open_questions": [string, ...], "unmapped": [string, ...]}
            Rules:
            - Use only information present in the document. Never invent requirements.
            - "requirements" are behaviours to build; "acceptance" are checks that prove them.
            - "goal" is 1-3 sentences; longer context belongs in "background".
            - Use "" for owner/status when the document does not state them.
            - Anything you cannot confidently place goes into "unmapped" verbatim.
            """;

    private ConfluenceNormalizer() {
    }

    public static NormalizedSpec normalize(ConfluenceExtract.Extracted extracted, ChatModel planner,
                                           String modelName, int maxTokens, String fallbackId) {
        ChatResponse response = planner.complete(new ChatRequest(modelName,
                List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(extracted.text())),
                List.of(), maxTokens, 0.15));
        if ("length".equals(response.finishReason())) {
            throw new SpecNormalizationException(
                    "planner response truncated (finish_reason=length) — raise models.planner.max_tokens");
        }
        JsonNode root = parseJson(response.message().content());

        List<String> questionTexts = new ArrayList<>(strings(root, "open_questions"));
        for (String unmapped : strings(root, "unmapped")) {
            questionTexts.add("[unmapped] " + unmapped);
        }
        List<Touchpoint> touchpoints = new ArrayList<>();
        for (JsonNode node : root.path("touchpoints")) {
            Touchpoint.Kind kind = Touchpoint.Kind.fromKey(node.path("kind").asText());
            String value = oneLine(node.path("value").asText());
            if (kind == null || value.isBlank()) {
                questionTexts.add("[unmapped touchpoint] " + node.path("kind").asText() + ": " + value);
            } else {
                touchpoints.add(new Touchpoint(kind, value));
            }
        }
        return new NormalizedSpec(
                fallbackId,
                text(root, "title", fallbackId),
                text(root, "owner", "unknown"),
                text(root, "status", "draft"),
                prose(root, "goal"),
                prose(root, "background"),
                numbered("R", strings(root, "requirements")),
                numbered("A", strings(root, "acceptance")),
                numbered("C", strings(root, "constraints")),
                touchpoints,
                strings(root, "out_of_scope"),
                numbered("Q", questionTexts),
                extracted.attachments());
    }

    private static JsonNode parseJson(String content) {
        if (content == null) {
            throw new SpecNormalizationException("planner returned no content");
        }
        String stripped = content.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                stripped = stripped.substring(firstNewline + 1, lastFence).strip();
            }
        }
        try {
            JsonNode root = JSON.readTree(stripped);
            if (!root.isObject()) {
                throw new SpecNormalizationException("planner output is not a JSON object");
            }
            return root;
        } catch (JacksonException e) {
            String snippet = stripped.length() > 200 ? stripped.substring(0, 200) + "..." : stripped;
            throw new SpecNormalizationException("planner output is not valid JSON: " + snippet, e);
        }
    }

    private static List<String> strings(JsonNode root, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : root.path(field)) {
            String value = oneLine(node.asText());
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String text(JsonNode root, String field, String fallback) {
        String value = oneLine(root.path(field).asText());
        return value.isBlank() ? fallback : value;
    }

    /** Bullets and front-matter scalars are one-line by the spec grammar. */
    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    /** Prose keeps newlines but may never collide with the '#' section grammar. */
    private static String prose(JsonNode root, String field) {
        return root.path(field).asText().replaceAll("(?m)^\\s*#+\\s*", "").strip();
    }

    private static List<SpecItem> numbered(String prefix, List<String> texts) {
        List<SpecItem> items = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            items.add(new SpecItem(prefix + (i + 1), texts.get(i)));
        }
        return items;
    }
}
```

`ConfluenceExportSource.java`:

```java
package sdd.plan.confluence;

import sdd.core.llm.ChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** v1 Confluence adapter: exported page file -> deterministic extract -> model normalization. */
public final class ConfluenceExportSource implements SpecSource {
    private final ChatModel planner;
    private final String modelName;
    private final int maxTokens;

    public ConfluenceExportSource(ChatModel planner, String modelName, int maxTokens) {
        this.planner = planner;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
    }

    @Override
    public NormalizedSpec load(String ref) {
        Path file = Path.of(ref);
        String html;
        try {
            html = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ConfluenceExtract.Extracted extracted = ConfluenceExtract.extract(html);
        return ConfluenceNormalizer.normalize(extracted, planner, modelName, maxTokens, specId(file));
    }

    static String specId(Path file) {
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return "spec-" + (slug.isBlank() ? "confluence" : slug);
    }
}
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: SpecSource seam with Confluence normalization via the planner model"
```

---

### Task 8: `sdd plan` CLI (ingest-only stage) + e2e

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/PlanCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/SddCli.java` (register subcommand)
- Modify: `sdd-cli/build.gradle.kts` (depend on sdd-plan)
- Test: `sdd-cli/src/test/java/sdd/cli/PlanCommandTest.java`

**Interfaces:**
- Consumes: everything from Tasks 3-7; `ConfigLoader.load(Path)`, `ModelEndpoint` (`model()`, `maxTokens()`), `HttpChatModel(ModelEndpoint)`; `ScriptedChatModel` (already on sdd-cli's test classpath via testFixtures).
- Produces: `sdd plan <ref>` — canonical `.md`: parse + validate + summary, exit 0/1; Confluence export: normalize, write `<ref>.spec.md` (or `--out`), print gate guidance, exit 0. Package-private field `ChatModel plannerForTest` as the test seam.

- [ ] **Step 1: Add the module dependency.** In `sdd-cli/build.gradle.kts` `dependencies` block, after the `:sdd-index` line, add:

```kotlin
    implementation(project(":sdd-plan"))
```

- [ ] **Step 2: Write the failing tests** — `PlanCommandTest.java` (mirrors `IndexCommandTest` conventions; note `plan(...)` builds the CommandLine around a caller-supplied `PlanCommand` instance so the seam field can be set):

```java
package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCommandTest {
    @TempDir Path ws;

    private record Run(int exitCode, String out) {}

    private Run plan(PlanCommand cmd, String... args) {
        StringWriter sw = new StringWriter();
        CommandLine cl = new CommandLine(cmd);
        cl.setOut(new PrintWriter(sw, true));
        cl.setErr(new PrintWriter(sw, true));
        return new Run(cl.execute(args), sw.toString());
    }

    private String yaml() {
        return """
                models:
                  planner:
                    base_url: http://127.0.0.1:1/v1
                    model: deepseek-v4-flash
                    max_tokens: 16384
                  coder:
                    base_url: http://127.0.0.1:1/v1
                    model: qwen
                """;
    }

    private static final String VALID_SPEC = """
            ---
            id: SPEC-7
            title: Loyalty tiers
            owner: ana
            status: draft
            ---

            ## Goal
            Add loyalty tiers to pricing.

            ## Requirements
            - R1: Price response includes the customer tier.

            ## Acceptance Criteria
            - A1: GET /price returns tier for gold customers.
            """;

    @Test
    void validCanonicalSpecPrintsSummary() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("spec OK: SPEC-7")
                .contains("1 requirements").contains("Phase 3B");
        assertThat(run.exitCode()).isZero();
    }

    @Test
    void semanticProblemsFailNamingEachOne() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("incomplete.md");
        Files.writeString(spec, """
                ---
                id: SPEC-8
                title: T
                owner: o
                status: draft
                ---

                ## Goal
                G.

                ## Requirements
                - R1: req

                ## Acceptance Criteria
                """);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("problem: Acceptance Criteria: at least one A item is required");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void parseErrorsSurfaceWithLineNumber() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("broken.md");
        Files.writeString(spec, VALID_SPEC.replace("- R1: Price", "* R1: Price"));

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("error: line 12: Requirements items must look like");
        assertThat(run.exitCode()).isEqualTo(1);
    }

    @Test
    void confluenceExportNormalizesWritesGateFileAndReparses() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path export = ws.resolve("loyalty-page.html");
        Files.writeString(export,
                "<h1>Loyalty tiers</h1><p>We want tiers.</p><p><img src=\"images/diagram.png\"></p>");
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "Loyalty tiers", "owner": "", "status": "", "goal": "Add tiers.",
                         "background": "", "requirements": ["Price response includes tier"],
                         "acceptance": ["GET /price returns tier"], "constraints": [],
                         "touchpoints": [{"kind": "repo", "value": "svc-pricing"}],
                         "out_of_scope": [], "open_questions": [], "unmapped": ["Rollout table"]}"""),
                "stop", new Usage(10, 10))));

        Run run = plan(cmd, "--workspace", ws.toString(), export.toString());

        assertThat(run.exitCode()).isZero();
        Path written = ws.resolve("loyalty-page.html.spec.md");
        assertThat(run.out()).contains("normalized spec written: " + written)
                .contains("review and edit the spec, then run: sdd plan " + written);
        String content = Files.readString(written);
        assertThat(content).contains("- Q1: [unmapped] Rollout table")
                .contains("## Attachments").contains("- diagram.png");

        // Gate round-trip: the written file is a valid canonical spec
        Run second = plan(new PlanCommand(), "--workspace", ws.toString(), written.toString());
        assertThat(second.out()).contains("spec OK: spec-loyalty-page");
        assertThat(second.exitCode()).isZero();
    }

    @Test
    void outOptionRedirectsTheGateFile() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path export = ws.resolve("page.html");
        Files.writeString(export, "<h1>T</h1><p>Prose.</p>");
        PlanCommand cmd = new PlanCommand();
        cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"title": "T", "owner": "", "status": "", "goal": "G.",
                         "background": "", "requirements": ["r"], "acceptance": ["a"],
                         "constraints": [], "touchpoints": [], "out_of_scope": [],
                         "open_questions": [], "unmapped": []}"""),
                "stop", new Usage(10, 10))));
        Path target = ws.resolve("gate.md");

        Run run = plan(cmd, "--workspace", ws.toString(), "--out", target.toString(), export.toString());

        assertThat(run.exitCode()).isZero();
        assertThat(run.out()).contains("normalized spec written: " + target);
        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.exists(ws.resolve("page.html.spec.md"))).isFalse();
    }

    @Test
    void planIsRegisteredOnTheRootCommand() throws Exception {
        Files.writeString(ws.resolve("sdd.yml"), yaml());
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);
        StringWriter sw = new StringWriter();
        CommandLine cmd = new CommandLine(new SddCli());
        cmd.setOut(new PrintWriter(sw, true));
        cmd.setErr(new PrintWriter(sw, true));

        int code = cmd.execute("plan", "--workspace", ws.toString(), spec.toString());

        assertThat(sw.toString()).contains("spec OK: SPEC-7");
        assertThat(code).isZero();
    }

    @Test
    void missingConfigFailsCleanly() throws Exception {
        Path spec = ws.resolve("loyalty.md");
        Files.writeString(spec, VALID_SPEC);

        Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

        assertThat(run.out()).contains("error: sdd.yml not found");
        assertThat(run.exitCode()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run — expect COMPILE FAILURE.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.PlanCommandTest'`

- [ ] **Step 4: Implement `PlanCommand.java`:**

```java
package sdd.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.plan.confluence.ConfluenceExportSource;
import sdd.plan.confluence.SpecNormalizationException;
import sdd.plan.spec.MarkdownSpecSource;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParseException;
import sdd.plan.spec.SpecParser;
import sdd.plan.spec.SpecRenderer;
import sdd.plan.spec.SpecSources;
import sdd.plan.spec.SpecValidator;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "plan",
        description = "Ingest a spec (canonical markdown or Confluence export); impact analysis lands in Phase 3B")
public final class PlanCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Option(names = "--out",
            description = "Where to write the normalized spec (Confluence refs only; default: <ref>.spec.md)")
    Path out;

    @Parameters(index = "0", description = "Spec ref: canonical .md, or exported Confluence .html/.htm/.xhtml")
    String ref;

    @Spec CommandSpec spec;

    ChatModel plannerForTest;   // test seam — mirrors IndexService's injectable ChatModel

    @Override
    public Integer call() {
        PrintWriter outWriter = spec.commandLine().getOut();
        PrintWriter errWriter = spec.commandLine().getErr();
        SddConfig config;
        try {
            config = ConfigLoader.load(workspace);
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
        try {
            return SpecSources.isConfluenceExport(ref)
                    ? normalize(config, outWriter)
                    : validate(outWriter, errWriter);
        } catch (RuntimeException e) {
            errWriter.println("error: " + e.getMessage());
            return 1;
        }
    }

    private Integer normalize(SddConfig config, PrintWriter outWriter) {
        ModelEndpoint planner = config.models().get("planner");
        ChatModel model = plannerForTest != null ? plannerForTest : new HttpChatModel(planner);
        NormalizedSpec normalized =
                new ConfluenceExportSource(model, planner.model(), planner.maxTokens()).load(ref);
        String rendered = SpecRenderer.render(normalized);
        try {
            SpecParser.parse(rendered);   // self-check: never hand the human a gate file that cannot re-parse
        } catch (SpecParseException e) {
            throw new SpecNormalizationException(
                    "normalized spec failed self-check (" + e.getMessage() + ") — rerun normalization", e);
        }
        Path target = out != null ? out : Path.of(ref + ".spec.md");
        try {
            Files.writeString(target, rendered);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        outWriter.println("normalized spec written: " + target);
        for (String problem : SpecValidator.problems(normalized)) {
            outWriter.println("  gate: " + problem);
        }
        outWriter.println("review and edit the spec, then run: sdd plan " + target);
        return 0;
    }

    private Integer validate(PrintWriter outWriter, PrintWriter errWriter) {
        NormalizedSpec parsed = new MarkdownSpecSource().load(ref);
        List<String> problems = SpecValidator.problems(parsed);
        if (!problems.isEmpty()) {
            for (String problem : problems) {
                errWriter.println("problem: " + problem);
            }
            return 1;
        }
        outWriter.printf(Locale.ROOT,
                "spec OK: %s — %d requirements, %d acceptance, %d constraints, %d touchpoints, %d open questions%n",
                parsed.id(), parsed.requirements().size(), parsed.acceptance().size(),
                parsed.constraints().size(), parsed.touchpoints().size(), parsed.openQuestions().size());
        outWriter.println("impact analysis is not implemented yet (Phase 3B)");
        return 0;
    }
}
```

In `SddCli.java`, extend the annotation's subcommand list: `subcommands = {DoctorCommand.class, IndexCommand.class, PlanCommand.class}`.

- [ ] **Step 5: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 6: Full build, then commit**

```bash
./gradlew build
git add sdd-cli
git commit -m "feat: sdd plan ingest stage with human-gated Confluence normalization"
```

---

## Verification

1. `./gradlew build` — every module green (includes the `gradle-it` tagged ITs).
2. Round-trip law pinned by `SpecRendererTest.canonicalFileReRendersByteIdentically` + the identity tests.
3. e2e: `PlanCommandTest.confluenceExportNormalizesWritesGateFileAndReparses` proves export → normalize → gate file → re-parse → `spec OK`.
4. Manual smoke (optional, needs a real DeepSeek key in `.env`): `sdd plan <some-export.html> --workspace <ws>` writes a reviewable `.spec.md`.

## Self-Review (completed at write time)

1. **Spec coverage:** Component-2 spec format (front matter + fixed H2s + ID-prefixed bullets + strict state machine + touchpoint kinds) → Tasks 3-4; byte-identical re-render (design Testing section) → Task 5; amendment (SpecSource seam, ConfluenceExportSource, tables→markdown, images→attachment refs, DeepSeek normalization, auto-assigned IDs, unmappable→Open Questions, human gate before impact analysis, extensible adapters) → Tasks 6-8; carry-forward #1 → Task 2 (attempt cap; `timeout_seconds` already exists in config); #2 → Task 1; #5 → Task 2; #3/#4 explicitly deferred (Global Constraints). Touchpoint KB verification is Phase 3B by design ("verified against the KB" happens at impact analysis; the parser only enforces kind grammar).
2. **Placeholder scan:** no TBD/TODO; every code step carries complete code; Task 2 Step 3 references existing constructor field assignments by name rather than reproducing them — deliberate, the implementer edits that file with the existing body in view.
3. **Type consistency:** `NormalizedSpec` 13 components in the same order everywhere (Tasks 3, 4, 5, 7, 8); `SpecItem(String id, String text)`; `Touchpoint.Kind.fromKey` null-on-unknown used by parser (hard error) and normalizer (demote to open question) — intentionally different policies, both stated; `ConfluenceExtract.Extracted(String text, List<String> attachments)` across 6, 7; `normalize(Extracted, ChatModel, String, int, String)` across 7, 8; `plannerForTest` package-private field name across 8's code and tests; `HttpChatModel(ModelEndpoint, int)` across Task 2 and Task 8 (default 6 attempts for the planner call — only the card path caps at 2).
4. **Adversarial critique pass (3 independent critics vs the real codebase, findings folded in):** every proposed signature, helper name, and line-number assertion verified against the repo; YAML-1.1 trap scalars now force quoting via a parse-back check (`bareSafe`) with a pinning test; Confluence CDATA code bodies recovered from jsoup's bogus-comment representation, `<pre>` handled, and bare text nodes inside wrappers preserved (walk over child NODES), each with tests; normalizer output sanitized (one-line bullets/scalars, heading markers stripped from prose) plus a `PlanCommand` render→parse self-check so a gate file can never be written unreadable; `--out` and CRLF pinned by tests; carry-forward #4's non-trigger by Task 1 justified in Global Constraints.
