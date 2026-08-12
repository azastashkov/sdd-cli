# Phase 3B — Impact Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn `sdd plan <spec.md>` from a validator into an impact analyzer: deterministic touchpoint/Retriever pre-seed with provenance, one DeepSeek seeding call over repo cards, deterministic closure over internal Gradle edges (REST/Kafka 1-hop blast radius, BOM declaration-site pull-in, repo-level SCC detection), and model/graph discrepancy recording — plus the Phase-3A hardening bundle.

**Architecture:** New package `sdd.plan.impact` (records + `SeedFinder` + `ModelSeeder` + `Closure` + `ImpactAnalysis` orchestrator) reading the KB via Jdbi; route-matching primitives move from sdd-index to sdd-core (`sdd.core.route.Routes`) because sdd-plan must never depend on sdd-index. The model call is assistive: its failure degrades to warnings, never aborts analysis (deterministic-first). `PlanCommand.validate()` runs the analysis after a spec validates and prints the result; plan.md rendering stays in Phase 3C. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` Component 2 "Impact analysis"; carry-forwards: bottom of `docs/superpowers/plans/2026-08-11-phase3a-spec-ingestion.md`.

**Tech Stack:** Java 21, Jdbi 3 (sdd-core), SnakeYAML/Jackson already present, ScriptedChatModel testFixtures. NO new third-party dependencies.

## Global Constraints

- Java 21; NO new third-party dependencies; module direction is one-way — sdd-plan depends only on sdd-core (NEVER on sdd-index); shared route semantics therefore live in sdd-core.
- Deterministic-first (design): the ONLY model call in this phase is `ModelSeeder.seed`; `ModelException`, `finish_reason=length`, or malformed JSON degrade to a recorded warning and the analysis continues deterministic-only — the model can add seeds, never veto or abort.
- Closure law (design critique M1): the api-usage annotation (`CODE_CHANGE_LIKELY` vs `BUMP_REBUILD_ONLY`) NEVER limits propagation — full transitive closure over internal Gradle edges always. Design adaptation (recorded): `dep_edge.configuration` stores resolved-classpath names, not api/implementation scope, so the annotation derives from `api_usage` evidence (consumer modules referencing provider-repo types) — a stronger deterministic signal than declaration scope.
- Discrepancy law (design): model/graph discrepancies are recorded and surfaced, never silently resolved — model-only repos stay in the affected set flagged `model-only`; Retriever candidates the model did not select and the graph did not require go to `excluded` with reasons.
- SCC (design critique M3): cycles among affected repos are detected at plan time and reported co-scheduled as one unit (warning naming the cycle) — no hard fail in v1.
- BOM (design critique M2): a `BOM_MANAGED` pulling edge also pulls the declaration-site repo (internal dep of the same consumer whose artifact name matches `(?i)bom|platform`); when none is identifiable, a warning names the edge — surfaced, never silent.
- Contract blast radius flows provider→consumer only, 1-hop, marked `PENDING_CONTRACT`: affected endpoint-owner → its `rest_call_edge` callers (confidence carried into the reason); affected topic-PRODUCER → the topic's CONSUMER repos. Pending-contract repos do not root further Gradle closure in v1 (actual propagation is decided at planning/actualization).
- Planner-confidence downgrades (design): affected repos whose `gradle_status`/`parse_status` is DEGRADED/FAILED/STALE_OK produce warnings.
- CLI taxonomy unchanged: exceptions → `error: <msg>` stderr exit 1; spec-validation findings → `problem: <text>` exit 1; impact findings (unresolved touchpoints, uncovered requirements, discrepancies) are Gate-1 CONTENT printed on stdout with exit 0 — enforcement is Phase 3C's `plan approve`. Empty knowledge base → `error: knowledge base is empty — run sdd index first` exit 1. All printf `Locale.ROOT`.
- Do not touch `GoldenEstateTest`, `golden/estate.json`, or `RepoCardGenerator`'s hash composition.
- Never read or print `.env` or any `api_key` value; test yaml uses unreachable endpoints.
- Never push. Run full `./gradlew build` before any commit touching more than one module.

---

## File Structure

**Task 1 (hardening A):** `sdd-index/.../store/RestMatcher.java` + test; `sdd-core/.../llm/HttpChatModel.java` + test
**Task 2 (hardening B):** `sdd-plan/.../confluence/ConfluenceNormalizer.java` + test; `sdd-cli/.../PlanCommand.java` + test
**Task 3 (routes move):** Create `sdd-core/src/main/java/sdd/core/route/Routes.java` + `RoutesTest`; sdd-index call sites updated, `RouteNormalizer` deleted, `RestMatcher` helpers delegate
**Task 4:** `sdd-plan/src/main/java/sdd/plan/impact/{Seed,AffectedRepo,ImpactResult}.java` + test
**Task 5:** `sdd-plan/src/main/java/sdd/plan/impact/SeedFinder.java` + test
**Task 6:** `sdd-plan/src/main/java/sdd/plan/impact/Closure.java` + test
**Task 7:** `sdd-plan/src/main/java/sdd/plan/impact/ModelSeeder.java` + test
**Task 8:** `sdd-plan/src/main/java/sdd/plan/impact/ImpactAnalysis.java` + test
**Task 9:** `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (extend validate()) + `PlanCommandTest` e2e

---

### Task 1: Hardening A — MANUAL-aware counter decrement + transport-error message

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/store/RestMatcher.java:100-110`
- Modify: `sdd-core/src/main/java/sdd/core/llm/HttpChatModel.java:75-77`
- Test: `sdd-index/src/test/java/sdd/index/store/RestMatcherTest.java`, `sdd-core/src/test/java/sdd/core/llm/HttpChatModelTest.java`

**Interfaces:**
- Consumes: existing `RestMatcher.match`, `Report`, `insertEdge`; `HttpChatModel.complete` retry loop.
- Produces: unchanged signatures; counters correct under duplicate pins; transport errors never print `null`.

Context (3A final review, minors 1 and T8-b): the manual-pass decrement reads `confidence` only, so a second pin of the same (client, endpoint) pair sees the first pin's HIGH/MANUAL row and decrements `high` (which was never incremented for it — `match:` can print `high: -1`). And `IOException.getMessage()` is null for connection-refused, printing `error: transport error: null`.

- [ ] **Step 1: Write the failing RestMatcher test** (append to `RestMatcherTest`, reusing its helpers):

```java
@Test
void duplicateManualPinsDoNotDistortCounters() {
    long cl = client(ordersModule, "RESTTEMPLATE", "POST", "/pay/charge", null, "/pay/charge");
    long ep = endpoint(billingModule, "POST", "/pay/charge");

    // two overlapping manual edges resolve to the same (client, endpoint) pair
    RestMatcher.Report report = RestMatcher.match(db.jdbi(), List.of(
            new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service"),
            new ManualEdge("svc-orders", "POST", "/pay/charge", "billing-service")));

    // second pin replaces the first pin's MANUAL row: manual must be net 1, high must be
    // untouched (the pair was MEDIUM before the first pin: unique candidate)
    assertThat(report.manual()).isEqualTo(1);
    assertThat(report.high()).isZero();
    assertThat(report.medium()).isZero();
    assertThat(edges()).singleElement().satisfies(e -> {
        assertThat(e).containsEntry("client_id", (int) cl).containsEntry("endpoint_id", (int) ep)
                .containsEntry("confidence", "HIGH").containsEntry("matched_by", "MANUAL");
    });
}
```

- [ ] **Step 2: Run it — expect FAIL** (`manual()` == 2 and/or `high()` == -1 under current code).
Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.RestMatcherTest'`

- [ ] **Step 3: Fix the decrement to read matched_by.** Add `import java.util.Map;` to RestMatcher.java (it currently imports only ArrayList/List/Pattern), then replace the replaced-row query + switch (RestMatcher.java:100-110) with:

```java
List<Map<String, Object>> replaced = h.createQuery(
                "SELECT confidence, matched_by FROM rest_call_edge WHERE client_id=:c AND endpoint_id=:e")
        .bind("c", cid).bind("e", eid).mapToMap().list();
for (Map<String, Object> row : replaced) {
    if ("MANUAL".equals(row.get("matched_by"))) {
        manual--;
        continue;
    }
    switch (String.valueOf(row.get("confidence"))) {
        case "HIGH" -> high--;
        case "MEDIUM" -> medium--;
        case "LOW" -> low--;
        default -> { }
    }
}
```

- [ ] **Step 4: Run the sdd-index suite — expect PASS** (both existing pin tests must stay green).
Run: `./gradlew :sdd-index:test`

- [ ] **Step 5: Write the failing HttpChatModel test** (append to `HttpChatModelTest`; `java.net.http.HttpClient` is abstract — a throwing stub is the cleanest way to force a null-message IOException):

```java
@Test
void transportErrorWithNullMessageFallsBackToExceptionClassName() {
    HttpClient refusing = new HttpClient() {
        @Override public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpClient.Redirect followRedirects() { return Redirect.NEVER; }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpClient.Version version() { return Version.HTTP_1_1; }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
        @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest req,
                java.net.http.HttpResponse.BodyHandler<T> h2) throws java.io.IOException {
            throw new java.net.ConnectException();   // getMessage() == null
        }
        @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h2) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest req, java.net.http.HttpResponse.BodyHandler<T> h2,
                java.net.http.HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }
    };
    ModelEndpoint ep = new ModelEndpoint("http://127.0.0.1:1/v1", "m", null, 16, 0.0, Duration.ofSeconds(1));
    HttpChatModel model = new HttpChatModel(ep, 2, refusing, millis -> { });

    assertThatThrownBy(() -> model.complete(request()))
            .isInstanceOf(ModelException.class)
            .hasMessageContaining("ConnectException")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("null"));
}
```

- [ ] **Step 6: Run it — expect FAIL** (message is "transport error: null").
Run: `./gradlew :sdd-core:test --tests 'sdd.core.llm.HttpChatModelTest'`

- [ ] **Step 7: Fix the IOException catch** (HttpChatModel.java:75-77):

```java
} catch (IOException e) {
    String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    last = new ModelException("transport error: " + detail, e);
    backoff(attempt, null);
}
```

- [ ] **Step 8: Full build, then commit**

```bash
./gradlew build
git add sdd-index/src sdd-core/src
git commit -m "fix: manual-aware match counters and non-null transport error detail"
```

---

### Task 2: Hardening B — Unicode sanitization, --out guard, workspace-aware hint

**Files:**
- Modify: `sdd-plan/src/main/java/sdd/plan/confluence/ConfluenceNormalizer.java` (`oneLine`, attachments carry-through)
- Modify: `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (`--out` guard, hint lines)
- Test: `sdd-plan/src/test/java/sdd/plan/confluence/ConfluenceNormalizerTest.java`, `sdd-cli/src/test/java/sdd/cli/PlanCommandTest.java`

**Interfaces:**
- Consumes: `ConfluenceNormalizer.normalize` (5-arg), `oneLine(String)`, `PlanCommand` fields `out`/`workspace`/`ref`.
- Produces: unchanged signatures. `oneLine` collapses Unicode separators; attachments are `oneLine`d before entering `NormalizedSpec`; `--out` with an `.html/.htm/.xhtml` target fails with `error: --out target must be a markdown file (got <target>)`; both follow-up hints include `--workspace <ws> ` when workspace differs from `Path.of(".")`.

Context (3A final review minors 2-3 + T8-a): `\s+` misses U+2028/U+2029/U+0085; attachment file names bypass sanitization entirely; `--out review.html` writes markdown that the hinted command then re-normalizes; the hint run from another cwd fails config load.

- [ ] **Step 1: Write the failing normalizer tests** (append to `ConfluenceNormalizerTest`):

```java
@Test
void unicodeLineSeparatorsAreCollapsedEverywhere() {
    // U+2028 LINE SEPARATOR, U+2029 PARAGRAPH SEPARATOR, U+0085 NEXT LINE — written in the
    // JSON below as Java unicode escapes (translated to the real code points at compile time)
    // so the test cannot be silently neutered by copy/paste normalization of invisible
    // characters. Spell them U+xxxx in comments — the compiler translates escape forms there too.
    String json = """
            {"title": "T\u2028sub", "owner": "", "status": "", "goal": "G.",
             "background": "", "requirements": ["a\u2029b", "c\u0085d"],
             "acceptance": ["ok"], "constraints": [], "touchpoints": [],
             "out_of_scope": [], "open_questions": [], "unmapped": []}""";
    ScriptedChatModel planner = new ScriptedChatModel(List.of(response(json, "stop")));

    NormalizedSpec spec = ConfluenceNormalizer.normalize(EXTRACTED, planner, "m", 100, "id");

    assertThat(spec.title()).isEqualTo("T sub");
    assertThat(spec.requirements()).containsExactly(
            new SpecItem("R1", "a b"), new SpecItem("R2", "c d"));
    assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec))).isEqualTo(spec);
}

@Test
void attachmentNamesAreSanitizedBeforeEnteringTheSpec() {
    ConfluenceExtract.Extracted withBadName = new ConfluenceExtract.Extracted(
            "text", List.of("dia\ngram.png", "ok.png"));
    ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

    NormalizedSpec spec = ConfluenceNormalizer.normalize(withBadName, planner, "m", 100, "id");

    assertThat(spec.attachments()).containsExactly("dia gram.png", "ok.png");
    assertThat(sdd.plan.spec.SpecParser.parse(sdd.plan.spec.SpecRenderer.render(spec)).attachments())
            .containsExactly("dia gram.png", "ok.png");
}
```

- [ ] **Step 2: Run — expect FAIL** (U+2028 survives `\s+`; attachment name carries the newline).
Run: `./gradlew :sdd-plan:test --tests 'sdd.plan.confluence.ConfluenceNormalizerTest'`

- [ ] **Step 3: Fix the normalizer.** In `ConfluenceNormalizer`:
  - `oneLine` becomes Unicode-aware: `return value.replaceAll("(?U)\\s+", " ").strip();` — `(?U)` makes `\s` match U+2028/U+2029/U+0085.
  - In `normalize(...)`, replace the final `extracted.attachments()` argument with a sanitized copy:

```java
                extracted.attachments().stream().map(ConfluenceNormalizer::oneLine).toList());
```

- [ ] **Step 4: Run sdd-plan tests — expect PASS.**
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 5: Write the failing CLI tests** (append to `PlanCommandTest`; reuse its `plan()`/`yaml()` helpers and the scripted-planner pattern from `outOptionRedirectsTheGateFile`):

```java
@Test
void outOptionRejectsHtmlTargets() throws Exception {
    Files.writeString(ws.resolve("sdd.yml"), yaml());
    Path export = ws.resolve("page.html");
    Files.writeString(export, "<h1>T</h1>");

    Run run = plan(new PlanCommand(), "--workspace", ws.toString(),
            "--out", ws.resolve("review.html").toString(), export.toString());

    assertThat(run.out()).contains("error: --out target must be a markdown file");
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(Files.exists(ws.resolve("review.html"))).isFalse();
}

@Test
void followUpHintCarriesNonDefaultWorkspace() throws Exception {
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

    Run run = plan(cmd, "--workspace", ws.toString(), export.toString());

    assertThat(run.out()).contains(
            "review and edit the spec, then run: sdd plan --workspace " + ws + " "
                    + ws.resolve("page.html.spec.md"));
}
```

- [ ] **Step 6: Run — expect FAIL.** Then fix `PlanCommand`:
  - At the top of `normalize(...)`, before any model work:

```java
if (out != null && SpecSources.isConfluenceExport(out.toString())) {
    throw new IllegalArgumentException("--out target must be a markdown file (got " + out + ")");
}
```

  - Add a small helper and use it in the hint line:

```java
private String workspacePrefix() {
    return workspace.equals(Path.of(".")) ? "" : "--workspace " + workspace + " ";
}
```

    Hint becomes: `outWriter.println("review and edit the spec, then run: sdd plan " + workspacePrefix() + target);`

(The `IllegalArgumentException` is caught by `call()`'s existing `catch (RuntimeException e)` → `error: <msg>`, exit 1 — no new plumbing. Note the default-workspace tests keep their existing expectations because `workspacePrefix()` is empty for `.`; the tests above pass `--workspace <tempdir>` so the prefix appears.)

Wait — existing tests DO pass `--workspace ws`: `confluenceExportNormalizesWritesGateFileAndReparses` asserts `contains("review and edit the spec, then run: sdd plan " + written)`. That assertion still passes (contains-check: the line now reads `... sdd plan --workspace <ws> <written>` and no longer *contains* the exact old substring — it DOES break). **Update that existing assertion** in the same commit to the new full string:

```java
        assertThat(run.out()).contains("normalized spec written: " + written)
                .contains("review and edit the spec, then run: sdd plan --workspace " + ws + " " + written);
```

- [ ] **Step 7: Run sdd-cli tests — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 8: Full build, then commit**

```bash
./gradlew build
git add sdd-plan/src sdd-cli/src
git commit -m "fix: unicode-aware sanitization, --out markdown guard, workspace-aware hint"
```

---

### Task 3: Move route primitives to sdd-core (`Routes`)

**Files:**
- Create: `sdd-core/src/main/java/sdd/core/route/Routes.java`
- Create: `sdd-core/src/test/java/sdd/core/route/RoutesTest.java`
- Delete: `sdd-index/src/main/java/sdd/index/spring/RouteNormalizer.java` (and its test class if one exists — check `sdd-index/src/test/java/sdd/index/spring/`)
- Modify: every sdd-index reference to `RouteNormalizer` (find them all: `grep -rln 'RouteNormalizer' sdd-index/src`) and `RestMatcher.templatesMatch`/`verbsCompatible` bodies

**Interfaces:**
- Produces (Tasks 5-6 depend on these EXACT shapes): `public final class Routes` in `sdd.core.route` with
  - `public static String join(String basePath, String methodPath)`
  - `public static String normalize(String template)`
  - `public static boolean templatesMatch(String a, String b)`
  - `public static boolean verbsCompatible(String a, String b)`
- Consumes: the current bodies of `RouteNormalizer.join/normalize` (sdd-index/spring/RouteNormalizer.java:6-19) and `RestMatcher.templatesMatch/verbsCompatible` (RestMatcher.java:131-148) — MOVED VERBATIM, not rewritten.

Rationale (Global Constraints): sdd-plan resolves endpoint touchpoints against `rest_endpoint.norm_path` and must use the indexer's exact matching semantics, but may not depend on sdd-index. Semantics must stay byte-identical — this task is a move, not a redesign.

- [ ] **Step 1: Create `Routes` by MOVING code.** New file `sdd-core/src/main/java/sdd/core/route/Routes.java`:

```java
package sdd.core.route;

/**
 * Shared route semantics: the indexer writes rest_endpoint.norm_path with normalize(); the
 * planner matches touchpoints against it with templatesMatch()/verbsCompatible(). Moved from
 * sdd-index (RouteNormalizer + RestMatcher helpers) verbatim so both modules share one truth.
 */
public final class Routes {
    private Routes() {
    }

    // --- body of RouteNormalizer.join, verbatim ---
    // --- body of RouteNormalizer.normalize (and its private strip helper), verbatim ---
    // --- body of RestMatcher.templatesMatch, verbatim, made public ---
    // --- body of RestMatcher.verbsCompatible, verbatim, made public ---
}
```

Copy the four method bodies (plus `RouteNormalizer`'s private helper) EXACTLY from the current sources; only the class name, package, and `public` modifiers change.

- [ ] **Step 2: Move the tests.** Create `RoutesTest` in sdd-core containing: any existing `RouteNormalizerTest` methods (moved, references renamed to `Routes`), plus the `templateAndVerbHelpers` assertions currently in `RestMatcherTest.java:127-134` rewritten against `Routes`:

```java
package sdd.core.route;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutesTest {

    @Test
    void normalizePrependsSlashCollapsesVarsAndSlashes() {
        assertThat(Routes.normalize("stock/{id}/")).isEqualTo("/stock/{}");
        assertThat(Routes.normalize("//a//b")).isEqualTo("/a/b");
        assertThat(Routes.normalize(null)).isEqualTo("/");
    }

    @Test
    void joinConcatenatesWithSingleSlashes() {
        assertThat(Routes.join("/api/", "/orders")).isEqualTo("/api/orders");
        assertThat(Routes.join("", "")).isEqualTo("/");
    }

    @Test
    void templateAndVerbHelpers() {
        assertThat(Routes.templatesMatch("/a/{}/c", "/a/{}/c")).isTrue();
        assertThat(Routes.templatesMatch("/a/42/c", "/a/{}/c")).isTrue();
        assertThat(Routes.templatesMatch("/a/{}/c", "/a/b")).isFalse();
        assertThat(Routes.verbsCompatible("ANY", "GET")).isTrue();
        assertThat(Routes.verbsCompatible("GET", "ANY")).isTrue();
        assertThat(Routes.verbsCompatible("GET", "POST")).isFalse();
    }
}
```

(If the moved `RouteNormalizer` tests assert different exact values than the two normalize/join methods above, keep the MOVED originals and drop these two sample methods — the moved tests are the authority. `templateAndVerbHelpers` moves here and is DELETED from `RestMatcherTest`.)

- [ ] **Step 3: Update sdd-index.** `grep -rln 'RouteNormalizer' sdd-index/src` — for each hit, replace the import with `sdd.core.route.Routes` and the call sites `RouteNormalizer.x(...)` → `Routes.x(...)`. Delete `RouteNormalizer.java` (and its test if it exists — its methods now live in `RoutesTest`). In `RestMatcher`, replace the `templatesMatch`/`verbsCompatible` bodies with one-line delegates (kept package-private so existing callers compile unchanged):

```java
static boolean templatesMatch(String clientNorm, String endpointNorm) {
    return Routes.templatesMatch(clientNorm, endpointNorm);
}

static boolean verbsCompatible(String clientVerb, String endpointVerb) {
    return Routes.verbsCompatible(clientVerb, endpointVerb);
}
```

- [ ] **Step 4: Full build — expect PASS** (this is the whole point: the compiler finds every missed call site; the moved tests pin identical semantics).
Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-core sdd-index
git commit -m "refactor: move route semantics to sdd-core Routes for planner reuse"
```

---

### Task 4: Impact model records

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/impact/Seed.java`, `AffectedRepo.java`, `ImpactResult.java`
- Test: `sdd-plan/src/test/java/sdd/plan/impact/ImpactResultTest.java`

**Interfaces:**
- Produces (Tasks 5-9 depend on these EXACT shapes):
  - `public record Seed(String repo, String source, String detail)` — source is one of `"touchpoint" | "fts" | "model"`; detail is human-readable provenance (`"repo:svc-pricing"`, `"R1 hit: LoyaltyTier"`, `"covers R1; <reason>"`).
  - `public record AffectedRepo(String repo, String role, String annotation, List<String> covers, List<String> reasons)` — role `"seed" | "dependent" | "contract" | "bom-site"`; annotation `"SEED" | "CODE_CHANGE_LIKELY" | "BUMP_REBUILD_ONLY" | "PENDING_CONTRACT" | "BOM_DECLARATION_SITE"`.
  - `public record ImpactResult(List<Seed> seeds, List<AffectedRepo> affected, List<Seed> excluded, List<String> cycles, List<String> discrepancies, List<String> problems, List<String> warnings)`.
  - All records: `Objects.requireNonNull` on every component, `List.copyOf` on every list.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.impact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImpactResultTest {

    @Test
    void recordsAreDefensiveAndNullHostile() {
        java.util.List<Seed> mutable = new java.util.ArrayList<>(
                List.of(new Seed("svc-pricing", "touchpoint", "repo:svc-pricing")));
        ImpactResult result = new ImpactResult(mutable, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        mutable.clear();
        assertThat(result.seeds()).hasSize(1);

        assertThatThrownBy(() -> new Seed(null, "fts", "d"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AffectedRepo("r", "seed", null, List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement:

`Seed.java`:

```java
package sdd.plan.impact;

import java.util.Objects;

/** A repo proposed for the affected set, with its provenance ("touchpoint" | "fts" | "model"). */
public record Seed(String repo, String source, String detail) {
    public Seed {
        Objects.requireNonNull(repo);
        Objects.requireNonNull(source);
        Objects.requireNonNull(detail);
    }
}
```

`AffectedRepo.java`:

```java
package sdd.plan.impact;

import java.util.List;
import java.util.Objects;

/**
 * One repo in the affected set. role: seed | dependent | contract | bom-site.
 * annotation: SEED | CODE_CHANGE_LIKELY | BUMP_REBUILD_ONLY | PENDING_CONTRACT |
 * BOM_DECLARATION_SITE — annotations describe, they never limit propagation (design M1).
 */
public record AffectedRepo(String repo, String role, String annotation,
                           List<String> covers, List<String> reasons) {
    public AffectedRepo {
        Objects.requireNonNull(repo);
        Objects.requireNonNull(role);
        Objects.requireNonNull(annotation);
        covers = List.copyOf(covers);
        reasons = List.copyOf(reasons);
    }
}
```

`ImpactResult.java`:

```java
package sdd.plan.impact;

import java.util.List;

/**
 * The full impact-analysis outcome. excluded = candidate seeds that neither the model selected
 * nor the graph required (recorded with reasons — never silently dropped). discrepancies =
 * model/graph disagreements, surfaced per the design ("never silently resolved").
 */
public record ImpactResult(List<Seed> seeds, List<AffectedRepo> affected, List<Seed> excluded,
                           List<String> cycles, List<String> discrepancies,
                           List<String> problems, List<String> warnings) {
    public ImpactResult {
        seeds = List.copyOf(seeds);
        affected = List.copyOf(affected);
        excluded = List.copyOf(excluded);
        cycles = List.copyOf(cycles);
        discrepancies = List.copyOf(discrepancies);
        problems = List.copyOf(problems);
        warnings = List.copyOf(warnings);
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: impact-analysis result model"
```

---

### Task 5: SeedFinder — touchpoint resolution + Retriever candidates

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/impact/SeedFinder.java`
- Test: `sdd-plan/src/test/java/sdd/plan/impact/SeedFinderTest.java`

**Interfaces:**
- Produces: `public final class SeedFinder` with nested `public record SeedScan(List<Seed> seeds, List<Seed> candidates, List<String> problems)` and `public static SeedScan find(Jdbi jdbi, Retriever retriever, NormalizedSpec spec)`.
- Consumes: `Routes.normalize/templatesMatch/verbsCompatible` (Task 3), `Seed` (Task 4), `sdd.core.retrieve.Retriever`/`Hit`, `NormalizedSpec`/`Touchpoint` (3A).

Resolution rules (touchpoints are hints verified against the KB, never trusted — design):
- `repo: <name>` → exact `repo.name`; miss → problem `touchpoint repo:<name>: no such repo in the knowledge base`.
- `endpoint: [VERB ]<path>` → first token is a verb iff it matches `[A-Z]+`; otherwise verb `ANY`. Path through `Routes.normalize`; match every `rest_endpoint` row via `Routes.templatesMatch(touchNorm, e.norm_path)` and `Routes.verbsCompatible(verb, e.http_method == null ? "ANY" : e.http_method)`; owning repos (via module) become seeds (one per distinct repo); miss → problem.
- `topic: <name>` → exact `kafka_topic.name`; seeds = repos of ALL `kafka_role` rows on it (producers and consumers — the touchpoint author named the topic, both sides are implicated); a topic with zero roles or no such topic → problem.
- `class: <value>` → `java_type.fqcn = value` OR (when value has no dot) `fqcn LIKE '%.' || value`; owning repos become seeds; miss → problem.
- `artifact: <grp>:<name>` → `artifact` row → `module_id` → repo; a NULL `module_id` or missing row → problem (`touchpoint artifact:<v>: not linked to any indexed module`).
- Free text: for each requirement `SpecItem`, `retriever.search(item.text(), 8)`; each hit's `moduleId` joins to a repo; produces CANDIDATES (not seeds), detail `"<id> hit: <identifier>"`, de-duplicated by repo (first provenance wins), and repos already seeded by touchpoints are skipped.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class SeedFinderTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/p','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/o','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/l','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (1,':','SERVICE','pricing')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind, spring_app_name) VALUES (2,':','SERVICE','orders')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3,':','LIBRARY')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (1,'PriceController','get','GET','/price/{sku}','/price/{}')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (1,1,'CONSUMER')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (3,'com.acme.pricing.LoyaltyTier','CLASS')");
            h.execute("INSERT INTO artifact(grp, name, module_id) VALUES ('com.acme','lib-core',3)");
            FtsSymbolWriter.insert(h, 3L, "LoyaltyTier", "com.acme.pricing.LoyaltyTier");
        });
    }

    private static NormalizedSpec spec(List<Touchpoint> touchpoints, List<SpecItem> requirements) {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                requirements, List.of(new SpecItem("A1", "acc")),
                List.of(), touchpoints, List.of(), List.of(), List.of());
    }

    @Test
    void resolvesEveryTouchpointKindWithProvenance() {
        NormalizedSpec s = spec(List.of(
                        new Touchpoint(Touchpoint.Kind.REPO, "svc-pricing"),
                        new Touchpoint(Touchpoint.Kind.ENDPOINT, "GET /price/1"),
                        new Touchpoint(Touchpoint.Kind.TOPIC, "orders.events"),
                        new Touchpoint(Touchpoint.Kind.CLASS, "LoyaltyTier"),
                        new Touchpoint(Touchpoint.Kind.ARTIFACT, "com.acme:lib-core")),
                List.of(new SpecItem("R1", "tier pricing")));

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.problems()).isEmpty();
        assertThat(scan.seeds()).extracting(Seed::repo, Seed::source, Seed::detail).contains(
                tuple("svc-pricing", "touchpoint", "repo:svc-pricing"),
                tuple("svc-pricing", "touchpoint", "endpoint:GET /price/1"),
                tuple("svc-orders", "touchpoint", "topic:orders.events"),
                tuple("svc-pricing", "touchpoint", "topic:orders.events"),
                tuple("lib-core", "touchpoint", "class:LoyaltyTier"),
                tuple("lib-core", "touchpoint", "artifact:com.acme:lib-core"));
        // lib-core is already a touchpoint seed, so the R1 FTS hit must NOT re-appear as a candidate
        assertThat(scan.candidates()).isEmpty();
    }

    @Test
    void verblessEndpointTouchpointMatchesAnyVerb() {
        NormalizedSpec s = spec(List.of(new Touchpoint(Touchpoint.Kind.ENDPOINT, "/price/1")),
                List.of());

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.problems()).isEmpty();
        assertThat(scan.seeds()).singleElement().satisfies(x -> {
            assertThat(x.repo()).isEqualTo("svc-pricing");
            assertThat(x.detail()).isEqualTo("endpoint:/price/1");
        });
    }

    @Test
    void ftsCandidatesCarryRequirementProvenanceAndDedupe() {
        NormalizedSpec s = spec(List.of(),
                List.of(new SpecItem("R1", "loyalty tier pricing"), new SpecItem("R2", "loyalty tier")));

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.seeds()).isEmpty();
        assertThat(scan.candidates()).singleElement().satisfies(c -> {
            assertThat(c.repo()).isEqualTo("lib-core");
            assertThat(c.source()).isEqualTo("fts");
            assertThat(c.detail()).isEqualTo("R1 hit: LoyaltyTier");
        });
    }

    @Test
    void unresolvableTouchpointsBecomeProblems() {
        NormalizedSpec s = spec(List.of(
                        new Touchpoint(Touchpoint.Kind.REPO, "ghost"),
                        new Touchpoint(Touchpoint.Kind.ENDPOINT, "DELETE /nope"),
                        new Touchpoint(Touchpoint.Kind.TOPIC, "no.topic"),
                        new Touchpoint(Touchpoint.Kind.CLASS, "Ghost"),
                        new Touchpoint(Touchpoint.Kind.ARTIFACT, "com.acme:ghost")),
                List.of(new SpecItem("R1", "req")));

        SeedFinder.SeedScan scan = SeedFinder.find(db.jdbi(), new FtsRetriever(db.jdbi()), s);

        assertThat(scan.seeds()).isEmpty();
        assertThat(scan.problems()).hasSize(5).allSatisfy(p -> assertThat(p).contains("touchpoint"));
        assertThat(scan.problems().get(0)).isEqualTo("touchpoint repo:ghost: no such repo in the knowledge base");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `SeedFinder.java`:

```java
package sdd.plan.impact;

import org.jdbi.v3.core.Jdbi;
import sdd.core.retrieve.Hit;
import sdd.core.retrieve.Retriever;
import sdd.core.route.Routes;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Impact stage A (design): deterministic pre-seed. Touchpoints resolve against the KB — hints
 * verified, never trusted; misses become problems. Requirement free text goes through the
 * Retriever and yields CANDIDATES (not seeds): candidates only enter the affected set if the
 * model confirms them or the graph requires them; the rest surface as excluded.
 */
public final class SeedFinder {
    private static final Pattern VERB = Pattern.compile("[A-Z]+");
    private static final int FTS_LIMIT = 8;

    public record SeedScan(List<Seed> seeds, List<Seed> candidates, List<String> problems) {
        public SeedScan {
            seeds = List.copyOf(seeds);
            candidates = List.copyOf(candidates);
            problems = List.copyOf(problems);
        }
    }

    private SeedFinder() {
    }

    public static SeedScan find(Jdbi jdbi, Retriever retriever, NormalizedSpec spec) {
        List<Seed> seeds = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Touchpoint touchpoint : spec.touchpoints()) {
            List<String> repos = resolve(jdbi, touchpoint);
            String label = touchpoint.kind().key() + ":" + touchpoint.value();
            if (repos.isEmpty()) {
                problems.add("touchpoint " + label + ": " + missReason(touchpoint));
            } else {
                for (String repo : repos) {
                    seeds.add(new Seed(repo, "touchpoint", label));
                }
            }
        }
        Set<String> seededRepos = new LinkedHashSet<>(seeds.stream().map(Seed::repo).toList());
        List<Seed> candidates = new ArrayList<>();
        Set<String> candidateRepos = new LinkedHashSet<>();
        for (SpecItem requirement : spec.requirements()) {
            for (Hit hit : retriever.search(requirement.text(), FTS_LIMIT)) {
                String repo = repoOfModule(jdbi, hit.moduleId());
                if (repo == null || seededRepos.contains(repo) || !candidateRepos.add(repo)) {
                    continue;
                }
                candidates.add(new Seed(repo, "fts", requirement.id() + " hit: " + hit.identifier()));
            }
        }
        return new SeedScan(seeds, candidates, problems);
    }

    private static List<String> resolve(Jdbi jdbi, Touchpoint touchpoint) {
        return switch (touchpoint.kind()) {
            case REPO -> jdbi.withHandle(h -> h.createQuery(
                            "SELECT name FROM repo WHERE name = :n")
                    .bind("n", touchpoint.value()).mapTo(String.class).list());
            case ENDPOINT -> endpointRepos(jdbi, touchpoint.value());
            case TOPIC -> jdbi.withHandle(h -> h.createQuery("""
                            SELECT DISTINCT r.name FROM kafka_role kr
                            JOIN kafka_topic t ON t.id = kr.topic_id
                            JOIN module m ON m.id = kr.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE t.name = :n ORDER BY r.name""")
                    .bind("n", touchpoint.value()).mapTo(String.class).list());
            case CLASS -> jdbi.withHandle(h -> h.createQuery("""
                            SELECT DISTINCT r.name FROM java_type t
                            JOIN module m ON m.id = t.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE t.fqcn = :v OR (:dotless AND t.fqcn LIKE '%.' || :v)
                            ORDER BY r.name""")
                    .bind("v", touchpoint.value())
                    .bind("dotless", !touchpoint.value().contains("."))
                    .mapTo(String.class).list());
            case ARTIFACT -> artifactRepos(jdbi, touchpoint.value());
        };
    }

    private static List<String> endpointRepos(Jdbi jdbi, String value) {
        String verb = "ANY";
        String path = value.strip();
        int space = path.indexOf(' ');
        if (space > 0 && VERB.matcher(path.substring(0, space)).matches()) {
            verb = path.substring(0, space);
            path = path.substring(space + 1).strip();
        }
        String touchNorm = Routes.normalize(path);
        String finalVerb = verb;
        List<Map<String, Object>> endpoints = jdbi.withHandle(h -> h.createQuery("""
                        SELECT e.http_method AS verb, e.norm_path AS norm, r.name AS repo
                        FROM rest_endpoint e
                        JOIN module m ON m.id = e.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE e.norm_path IS NOT NULL ORDER BY r.name""")
                .mapToMap().list());
        Set<String> repos = new LinkedHashSet<>();
        for (Map<String, Object> e : endpoints) {
            String endpointVerb = e.get("verb") == null ? "ANY" : String.valueOf(e.get("verb"));
            if (Routes.templatesMatch(touchNorm, String.valueOf(e.get("norm")))
                    && Routes.verbsCompatible(finalVerb, endpointVerb)) {
                repos.add(String.valueOf(e.get("repo")));
            }
        }
        return List.copyOf(repos);
    }

    private static List<String> artifactRepos(Jdbi jdbi, String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            return List.of();
        }
        String grp = value.substring(0, colon);
        String name = value.substring(colon + 1);
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT r.name FROM artifact a
                        JOIN module m ON m.id = a.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE a.grp = :g AND a.name = :n""")
                .bind("g", grp).bind("n", name).mapTo(String.class).list());
    }

    private static String missReason(Touchpoint touchpoint) {
        return switch (touchpoint.kind()) {
            case REPO -> "no such repo in the knowledge base";
            case ENDPOINT -> "no endpoint matches";
            case TOPIC -> "no known topic with roles";
            case CLASS -> "no such type in the knowledge base";
            case ARTIFACT -> "not linked to any indexed module";
        };
    }

    private static String repoOfModule(Jdbi jdbi, long moduleId) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT r.name FROM module m JOIN repo r ON r.id = m.repo_id WHERE m.id = :m")
                .bind("m", moduleId).mapTo(String.class).findOne().orElse(null));
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: deterministic touchpoint and retriever pre-seed with provenance"
```

---

### Task 6: Closure — transitive Gradle expansion, contracts, BOM sites, SCC

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/impact/Closure.java`
- Test: `sdd-plan/src/test/java/sdd/plan/impact/ClosureTest.java`

**Interfaces:**
- Produces: `public final class Closure` with nested `public record Expansion(List<AffectedRepo> added, List<String> cycles, List<String> warnings)` and `public static Expansion expand(Jdbi jdbi, Set<String> rootRepos)`. `added` contains ONLY repos beyond the roots, in deterministic order: BFS queue order (roots processed in sorted order), each consumer registered before the BOM sites it pulls, contract repos appended last.
- Consumes: `AffectedRepo` (Task 4). Reads `v_repo_dep_edge`, `dep_edge`, `module`, `repo`, `api_usage`, `rest_endpoint`, `rest_client`, `rest_call_edge`, `kafka_role`, `kafka_topic`.

Semantics (Global Constraints): full transitive closure over `v_repo_dep_edge` REVERSED (a root is a provider; its consumers are affected; their consumers too). Annotation per added dependent: `CODE_CHANGE_LIKELY` iff any `api_usage` row links a consumer-repo module to a module of the provider repo that pulled it in, else `BUMP_REBUILD_ONLY` — the annotation never limits traversal. A `BOM_MANAGED` pulling edge additionally pulls the consumer's internal BOM-artifact providers (`(?i)bom|platform` name match) as `bom-site`; none found → warning. After Gradle closure: REST/Kafka 1-hop provider→consumer contracts (`PENDING_CONTRACT`), no further recursion. SCC over the induced internal-edge graph of ALL affected repos (roots + added), cycles reported as `a -> b -> a` strings. Status warnings for any affected repo with `gradle_status`/`parse_status` in DEGRADED/FAILED/STALE_OK.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ClosureTest {
    @TempDir Path ws;
    private Database db;

    // Estate: lib-core <- lib-api <- svc-orders (chain), svc-billing calls svc-orders' endpoint,
    // svc-notify consumes topic produced by svc-orders, platform-bom manages svc-orders' lib-api pin.
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");      // repo 1, module 1
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-api','/w/2','LIBRARY')");        // repo 2, module 2
            h.execute("INSERT INTO repo(name, path, kind, gradle_status) VALUES ('svc-orders','/w/3','SERVICE','DEGRADED')"); // repo 3, module 3
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-billing','/w/4','SERVICE')");    // repo 4, module 4
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-notify','/w/5','SERVICE')");     // repo 5, module 5
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('platform','/w/6','LIBRARY')");       // repo 6, module 6
            for (int i = 1; i <= 6; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            // internal dep edges: consumer module -> provider module
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");        // lib-api -> lib-core
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (3,'com.acme','lib-api','compileClasspath',NULL,'BOM','BOM_MANAGED',1,2)");        // svc-orders -> lib-api (BOM managed)
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (3,'com.acme','acme-platform-bom','compileClasspath','2.0','DIRECT','PINNED',1,6)"); // svc-orders -> platform (the BOM)
            // api_usage: svc-orders code references lib-api types (code change likely); lib-api does NOT reference lib-core types (bump only)
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (2,'com.acme.api.PriceApi','CLASS')");
            h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, target_module_id, ref_kind) VALUES (3,'com.acme.api.PriceApi',2,'IMPORT')");
            // REST: svc-billing's client calls svc-orders' endpoint
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (3,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (4,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
            // Kafka: svc-orders produces orders.events, svc-notify consumes it
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (5,1,'CONSUMER')");
        });
    }

    @Test
    void expandsTransitivelyWithAnnotationsContractsAndBomSites() {
        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo, AffectedRepo::role, AffectedRepo::annotation)
                .containsExactly(
                        tuple("lib-api", "dependent", "BUMP_REBUILD_ONLY"),
                        tuple("svc-orders", "dependent", "CODE_CHANGE_LIKELY"),
                        tuple("platform", "bom-site", "BOM_DECLARATION_SITE"),
                        tuple("svc-billing", "contract", "PENDING_CONTRACT"),
                        tuple("svc-notify", "contract", "PENDING_CONTRACT"));
        AffectedRepo billing = expansion.added().stream()
                .filter(a -> a.repo().equals("svc-billing")).findFirst().orElseThrow();
        assertThat(billing.reasons()).singleElement().asString()
                .contains("calls GET /orders/{}").contains("svc-orders").contains("HIGH");
        assertThat(expansion.warnings()).anySatisfy(w ->
                assertThat(w).contains("svc-orders").contains("DEGRADED"));
        assertThat(expansion.cycles()).isEmpty();
    }

    @Test
    void cyclesAmongAffectedReposAreReportedAsOneUnit() {
        db.jdbi().useHandle(h -> {
            // make lib-core depend back on lib-api: a 2-repo cycle
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (1,'com.acme','lib-api','compileClasspath','1.0','DIRECT','PINNED',1,2)");
        });

        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.cycles()).singleElement().asString()
                .contains("lib-api").contains("lib-core");
        assertThat(expansion.warnings()).anySatisfy(w -> assertThat(w).contains("co-scheduled"));
    }

    @Test
    void multipleProvidersMergeReasonsOnOneDependent() {
        // svc-orders consumes BOTH roots: lib-api (BOM_MANAGED) and platform (PINNED) — one
        // AffectedRepo, two reasons, no duplicate row
        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-api", "platform"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo).containsOnlyOnce("svc-orders");
        AffectedRepo orders = expansion.added().stream()
                .filter(a -> a.repo().equals("svc-orders")).findFirst().orElseThrow();
        assertThat(orders.reasons()).containsExactlyInAnyOrder(
                "depends on lib-api (BOM_MANAGED)", "depends on platform (PINNED)");
    }

    @Test
    void contractReasonAppendsToAnAlreadyAffectedDependent() {
        // give svc-billing a direct Gradle dependency on lib-core: it becomes a dependent AND
        // a REST contract consumer of svc-orders — one row, role stays dependent, both reasons
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (4,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)"));

        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo).containsOnlyOnce("svc-billing");
        AffectedRepo billing = expansion.added().stream()
                .filter(a -> a.repo().equals("svc-billing")).findFirst().orElseThrow();
        assertThat(billing.role()).isEqualTo("dependent");
        assertThat(billing.reasons()).hasSize(2)
                .anySatisfy(r -> assertThat(r).contains("depends on lib-core"))
                .anySatisfy(r -> assertThat(r).contains("calls GET /orders/{}"));
    }

    @Test
    void missingBomSiteProducesAWarningNotSilence() {
        db.jdbi().useHandle(h -> h.execute("DELETE FROM dep_edge WHERE to_name = 'acme-platform-bom'"));

        Closure.Expansion expansion = Closure.expand(db.jdbi(), Set.of("lib-core"));

        assertThat(expansion.added()).extracting(AffectedRepo::repo).doesNotContain("platform");
        assertThat(expansion.warnings()).anySatisfy(w ->
                assertThat(w).contains("BOM_MANAGED").contains("svc-orders").contains("declaration site not identifiable"));
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `Closure.java`:

```java
package sdd.plan.impact;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Impact stage C (design): deterministic closure. Full transitive expansion over internal
 * Gradle edges reversed (provider -> consumers); api_usage evidence annotates code-change-likely
 * vs bump/rebuild-only but NEVER limits traversal (M1). BOM_MANAGED pulling edges also pull the
 * declaration-site repo (M2, heuristic + loud warning). REST/Kafka contracts add one hop,
 * provider -> consumer, marked PENDING_CONTRACT, no further recursion. SCCs among affected
 * repos are reported co-scheduled (M3).
 */
public final class Closure {

    public record Expansion(List<AffectedRepo> added, List<String> cycles, List<String> warnings) {
        public Expansion {
            added = List.copyOf(added);
            cycles = List.copyOf(cycles);
            warnings = List.copyOf(warnings);
        }
    }

    private record RepoEdge(String consumer, String provider, String mode) {
    }

    private Closure() {
    }

    public static Expansion expand(Jdbi jdbi, Set<String> rootRepos) {
        List<RepoEdge> edges = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rf.name AS consumer, rt.name AS provider, v.mode AS mode
                        FROM v_repo_dep_edge v
                        JOIN repo rf ON rf.id = v.from_repo_id
                        JOIN repo rt ON rt.id = v.to_repo_id
                        ORDER BY rf.name, rt.name""")
                .map((rs, ctx) -> new RepoEdge(rs.getString("consumer"), rs.getString("provider"),
                        rs.getString("mode"))).list());
        Map<String, List<RepoEdge>> byProvider = new HashMap<>();
        for (RepoEdge edge : edges) {
            byProvider.computeIfAbsent(edge.provider(), k -> new ArrayList<>()).add(edge);
        }

        List<String> warnings = new ArrayList<>();
        Map<String, AffectedRepo> added = new LinkedHashMap<>();
        Set<String> affected = new LinkedHashSet<>(rootRepos);
        Set<String> bomConsumersSeen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(new TreeSet<>(rootRepos));
        while (!queue.isEmpty()) {
            String provider = queue.removeFirst();
            for (RepoEdge edge : byProvider.getOrDefault(provider, List.of())) {
                String reason = "depends on " + provider + " (" + edge.mode() + ")";
                if (affected.add(edge.consumer())) {
                    String annotation = usesApiOf(jdbi, edge.consumer(), provider)
                            ? "CODE_CHANGE_LIKELY" : "BUMP_REBUILD_ONLY";
                    added.put(edge.consumer(), new AffectedRepo(edge.consumer(), "dependent",
                            annotation, List.of(), List.of(reason)));
                    queue.addLast(edge.consumer());
                } else if (added.containsKey(edge.consumer())) {
                    AffectedRepo existing = added.get(edge.consumer());
                    List<String> reasons = new ArrayList<>(existing.reasons());
                    if (!reasons.contains(reason)) {
                        reasons.add(reason);
                        added.put(edge.consumer(), new AffectedRepo(existing.repo(), existing.role(),
                                existing.annotation(), existing.covers(), reasons));
                    }
                }
                if ("BOM_MANAGED".equals(edge.mode()) && bomConsumersSeen.add(edge.consumer())) {
                    pullBomSites(jdbi, edge.consumer(), added, affected, warnings);
                }
            }
        }

        contracts(jdbi, affected, added, warnings);
        List<String> cycles = cycles(edges, affected, warnings);
        statusWarnings(jdbi, affected, warnings);
        return new Expansion(new ArrayList<>(added.values()), cycles, warnings);
    }

    private static boolean usesApiOf(Jdbi jdbi, String consumerRepo, String providerRepo) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM api_usage u
                        JOIN module mc ON mc.id = u.from_module_id
                        JOIN repo rc ON rc.id = mc.repo_id
                        JOIN module mp ON mp.id = u.target_module_id
                        JOIN repo rp ON rp.id = mp.repo_id
                        WHERE rc.name = :c AND rp.name = :p""")
                .bind("c", consumerRepo).bind("p", providerRepo).mapTo(Integer.class).one()) > 0;
    }

    private static void pullBomSites(Jdbi jdbi, String consumerRepo, Map<String, AffectedRepo> added,
                                     Set<String> affected, List<String> warnings) {
        List<String> sites = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rt.name FROM dep_edge e
                        JOIN module mf ON mf.id = e.from_module_id
                        JOIN repo rf ON rf.id = mf.repo_id
                        JOIN module mt ON mt.id = e.to_module_id
                        JOIN repo rt ON rt.id = mt.repo_id
                        WHERE rf.name = :c AND e.is_internal = 1
                          AND (lower(e.to_name) LIKE '%bom%' OR lower(e.to_name) LIKE '%platform%')
                        ORDER BY rt.name""")
                .bind("c", consumerRepo).mapTo(String.class).list());
        if (sites.isEmpty()) {
            warnings.add("BOM_MANAGED edge from " + consumerRepo
                    + ": declaration site not identifiable — verify the managing BOM manually");
            return;
        }
        for (String site : sites) {
            if (affected.add(site)) {
                added.put(site, new AffectedRepo(site, "bom-site", "BOM_DECLARATION_SITE",
                        List.of(), List.of("manages versions consumed by " + consumerRepo)));
            }
        }
    }

    private static void contracts(Jdbi jdbi, Set<String> affected, Map<String, AffectedRepo> added,
                                  List<String> warnings) {
        record Contract(String consumerRepo, String reason) {
        }
        List<Contract> contracts = new ArrayList<>();
        List<Map<String, Object>> restRows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rc.name AS client_repo, rp.name AS provider_repo,
                               e.http_method AS verb, e.norm_path AS norm, ce.confidence AS confidence
                        FROM rest_call_edge ce
                        JOIN rest_client c ON c.id = ce.client_id
                        JOIN module mc ON mc.id = c.module_id
                        JOIN repo rc ON rc.id = mc.repo_id
                        JOIN rest_endpoint e ON e.id = ce.endpoint_id
                        JOIN module mp ON mp.id = e.module_id
                        JOIN repo rp ON rp.id = mp.repo_id
                        WHERE rc.name <> rp.name
                        ORDER BY rc.name, rp.name""")
                .mapToMap().list());
        for (Map<String, Object> row : restRows) {
            if (affected.contains(String.valueOf(row.get("provider_repo")))) {
                contracts.add(new Contract(String.valueOf(row.get("client_repo")),
                        "calls " + row.get("verb") + " " + row.get("norm") + " on "
                                + row.get("provider_repo") + " (" + row.get("confidence") + ")"));
            }
        }
        List<Map<String, Object>> kafkaRows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT rp.name AS producer_repo, rc.name AS consumer_repo, t.name AS topic
                        FROM kafka_role prod
                        JOIN kafka_topic t ON t.id = prod.topic_id
                        JOIN module mp ON mp.id = prod.module_id
                        JOIN repo rp ON rp.id = mp.repo_id
                        JOIN kafka_role cons ON cons.topic_id = prod.topic_id AND cons.role = 'CONSUMER'
                        JOIN module mc ON mc.id = cons.module_id
                        JOIN repo rc ON rc.id = mc.repo_id
                        WHERE prod.role = 'PRODUCER' AND rp.name <> rc.name
                        ORDER BY rp.name, rc.name""")
                .mapToMap().list());
        for (Map<String, Object> row : kafkaRows) {
            if (affected.contains(String.valueOf(row.get("producer_repo")))) {
                contracts.add(new Contract(String.valueOf(row.get("consumer_repo")),
                        "consumes topic " + row.get("topic") + " produced by " + row.get("producer_repo")));
            }
        }
        for (Contract contract : contracts) {
            if (affected.add(contract.consumerRepo())) {
                added.put(contract.consumerRepo(), new AffectedRepo(contract.consumerRepo(),
                        "contract", "PENDING_CONTRACT", List.of(), List.of(contract.reason())));
            } else if (added.containsKey(contract.consumerRepo())) {
                AffectedRepo existing = added.get(contract.consumerRepo());
                List<String> reasons = new ArrayList<>(existing.reasons());
                if (!reasons.contains(contract.reason())) {
                    reasons.add(contract.reason());
                    added.put(existing.repo(), new AffectedRepo(existing.repo(), existing.role(),
                            existing.annotation(), existing.covers(), reasons));
                }
            }
        }
    }

    /** Iterative Tarjan over the induced consumer->provider graph of affected repos. */
    private static List<String> cycles(List<RepoEdge> edges, Set<String> affected, List<String> warnings) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (String repo : affected) {
            graph.put(repo, new ArrayList<>());
        }
        for (RepoEdge edge : edges) {
            if (affected.contains(edge.consumer()) && affected.contains(edge.provider())) {
                graph.get(edge.consumer()).add(edge.provider());
            }
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Set<String> onStack = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        List<List<String>> sccs = new ArrayList<>();
        int[] counter = {0};
        record Frame(String node, int childIndex) {
        }
        for (String start : graph.keySet()) {
            if (index.containsKey(start)) {
                continue;
            }
            Deque<Frame> frames = new ArrayDeque<>();
            frames.push(new Frame(start, 0));
            index.put(start, counter[0]);
            low.put(start, counter[0]);
            counter[0]++;
            stack.push(start);
            onStack.add(start);
            while (!frames.isEmpty()) {
                Frame frame = frames.pop();
                List<String> children = graph.get(frame.node());
                int i = frame.childIndex();
                boolean descended = false;
                while (i < children.size()) {
                    String child = children.get(i);
                    i++;
                    if (!index.containsKey(child)) {
                        frames.push(new Frame(frame.node(), i));
                        frames.push(new Frame(child, 0));
                        index.put(child, counter[0]);
                        low.put(child, counter[0]);
                        counter[0]++;
                        stack.push(child);
                        onStack.add(child);
                        descended = true;
                        break;
                    } else if (onStack.contains(child)) {
                        low.put(frame.node(), Math.min(low.get(frame.node()), index.get(child)));
                    }
                }
                if (descended) {
                    continue;
                }
                if (low.get(frame.node()).equals(index.get(frame.node()))) {
                    List<String> scc = new ArrayList<>();
                    String popped;
                    do {
                        popped = stack.pop();
                        onStack.remove(popped);
                        scc.add(popped);
                    } while (!popped.equals(frame.node()));
                    if (scc.size() > 1) {
                        sccs.add(scc);
                    }
                }
                if (!frames.isEmpty()) {
                    Frame parent = frames.peek();
                    low.put(parent.node(), Math.min(low.get(parent.node()), low.get(frame.node())));
                }
            }
        }
        List<String> cycleStrings = new ArrayList<>();
        for (List<String> scc : sccs) {
            List<String> sorted = new ArrayList<>(new TreeSet<>(scc));
            cycleStrings.add(String.join(" <-> ", sorted));
            warnings.add("dependency cycle among affected repos (co-scheduled as one unit): "
                    + String.join(" <-> ", sorted));
        }
        return cycleStrings;
    }

    private static void statusWarnings(Jdbi jdbi, Set<String> affected, List<String> warnings) {
        List<Map<String, Object>> rows = jdbi.withHandle(h -> h.createQuery("""
                        SELECT name, gradle_status, parse_status FROM repo
                        WHERE (gradle_status IN ('DEGRADED','FAILED','STALE_OK')
                               OR parse_status IN ('DEGRADED','FAILED','STALE_OK'))
                        ORDER BY name""")
                .mapToMap().list());
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("name"));
            if (affected.contains(name)) {
                String status = row.get("gradle_status") != null
                        && !"OK".equals(row.get("gradle_status"))
                        ? String.valueOf(row.get("gradle_status"))
                        : String.valueOf(row.get("parse_status"));
                warnings.add("affected repo " + name + " indexed with status " + status
                        + " — downgrade confidence in its facts");
            }
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS.** The deterministic emission order (which the test's `containsExactly` pins) is: BFS additions in queue order, each consumer registered BEFORE the BOM sites it pulls (the BOM block sits after the add/merge chain, deduped per consumer via `bomConsumersSeen`), contracts appended last. If the test fails on order, the implementation deviated from this rule — fix the implementation.
Run: `./gradlew :sdd-plan:test --tests 'sdd.plan.impact.ClosureTest'`

- [ ] **Step 4: Record the annotation adaptation in the design authority.** Append to `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` (after the existing 2026-08-11 amendment):

```markdown

## Amendment (2026-08-12): closure annotation source

The M1 "code change likely" vs "bump/rebuild only" annotation derives from `api_usage`
evidence (consumer modules referencing provider-repo types), not from api/implementation
declaration scope: `dep_edge.configuration` records resolved classpath names
(compileClasspath/runtimeClasspath), so declaration scope is not available in the KB. The
annotation still never limits propagation.
```

- [ ] **Step 5: Full sdd-plan suite, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src docs/superpowers/specs
git commit -m "feat: deterministic closure with contracts, BOM sites, and SCC detection"
```

---

### Task 7: ModelSeeder — one assistive DeepSeek call

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/impact/ModelSeeder.java`
- Test: `sdd-plan/src/test/java/sdd/plan/impact/ModelSeederTest.java`

**Interfaces:**
- Produces: `public final class ModelSeeder` with nested `public record ModelSeed(String repo, String role, List<String> covers, String reason)` and `public record SeedingOutcome(List<ModelSeed> seeds, List<String> warnings)` and `public static SeedingOutcome seed(Jdbi jdbi, NormalizedSpec spec, List<Seed> deterministicSeeds, List<Seed> candidates, ChatModel planner, String modelName, int maxTokens)`. `static String composeInput(...)` (package-private, same 4 first params) for prompt-content tests.
- Consumes: `Seed` (Task 4), `SpecRenderer.render` (3A), `ChatModel` seam, repo inventory + `repo_card`.

Rules: input = rendered spec + repo inventory (`name (kind): card_line`, plus `card_md` capped at 2000 chars per repo — covers a design-conforming ≤450-token card; the cap is a deliberate prompt-budget guard, recorded here — repos ordered by name) + the deterministic seed/candidate lists with provenance. Response contract: ONE JSON object `{"repos": [{"repo": string, "role": "primary"|"contributor", "covers": [string...], "reason": string}...]}`. Failure containment (deterministic-first): `ModelException`, `finish_reason=length`, malformed/non-object JSON → `SeedingOutcome(List.of(), List.of("model seeding unavailable: <detail>"))`. Repo names not present in the KB → dropped from seeds, recorded as warning `model named unknown repo '<name>'`. `covers` entries filtered to the spec's actual requirement ids (unknown ids → warning). Temperature 0.15, empty tools, maxTokens passed through, system prompt is a `static final String SYSTEM_PROMPT`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSeederTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/p','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/l','LIBRARY')");
            h.execute("INSERT INTO repo_card(repo_id, card_md, card_line, model, input_hash, created_at) "
                    + "VALUES (1,'## Purpose\\nPrices things.','Pricing service.','qwen','h','t')");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "tier pricing")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
    }

    @Test
    void promptCarriesSpecCardsAndSeedProvenance() {
        String input = ModelSeeder.composeInput(db.jdbi(), spec(),
                List.of(new Seed("svc-pricing", "touchpoint", "repo:svc-pricing")),
                List.of(new Seed("lib-core", "fts", "R1 hit: LoyaltyTier")));

        assertThat(input).contains("- R1: tier pricing")
                .contains("svc-pricing (SERVICE): Pricing service.")
                .contains("Prices things.")
                .contains("lib-core (LIBRARY)")
                .contains("touchpoint repo:svc-pricing")
                .contains("fts R1 hit: LoyaltyTier");
    }

    @Test
    void validResponseYieldsSeedsAndFiltersUnknowns() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response("""
                {"repos": [
                  {"repo": "svc-pricing", "role": "primary", "covers": ["R1", "R9"], "reason": "owns pricing"},
                  {"repo": "ghost-repo", "role": "primary", "covers": [], "reason": "hallucinated"}
                ]}""", "stop")));

        ModelSeeder.SeedingOutcome outcome = ModelSeeder.seed(db.jdbi(), spec(),
                List.of(), List.of(), planner, "deepseek-v4-flash", 4096);

        assertThat(outcome.seeds()).singleElement().satisfies(s -> {
            assertThat(s.repo()).isEqualTo("svc-pricing");
            assertThat(s.role()).isEqualTo("primary");
            assertThat(s.covers()).containsExactly("R1");
            assertThat(s.reason()).isEqualTo("owns pricing");
        });
        assertThat(outcome.warnings()).anySatisfy(w -> assertThat(w).contains("ghost-repo"))
                .anySatisfy(w -> assertThat(w).contains("R9"));
        assertThat(planner.requests()).singleElement().satisfies(r ->
                assertThat(r.maxTokens()).isEqualTo(4096));
    }

    @Test
    void fencedJsonResponseIsUnwrapped() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(
                "```json\n{\"repos\": [{\"repo\": \"svc-pricing\", \"role\": \"primary\", "
                        + "\"covers\": [\"R1\"], \"reason\": \"r\"}]}\n```", "stop")));

        ModelSeeder.SeedingOutcome outcome = ModelSeeder.seed(db.jdbi(), spec(),
                List.of(), List.of(), planner, "m", 256);

        assertThat(outcome.seeds()).singleElement().satisfies(s ->
                assertThat(s.repo()).isEqualTo("svc-pricing"));
        assertThat(outcome.warnings()).isEmpty();
    }

    @Test
    void modelFailuresDegradeToWarningsNeverThrow() {
        ScriptedChatModel truncated = new ScriptedChatModel(List.of(response("{", "length")));
        assertThat(ModelSeeder.seed(db.jdbi(), spec(), List.of(), List.of(), truncated, "m", 16)
                .warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"));

        ScriptedChatModel garbage = new ScriptedChatModel(List.of(response("not json", "stop")));
        assertThat(ModelSeeder.seed(db.jdbi(), spec(), List.of(), List.of(), garbage, "m", 16)
                .warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"));

        sdd.core.llm.ChatModel refusing = req -> {
            throw new sdd.core.llm.ModelException("connection refused", 0);
        };
        assertThat(ModelSeeder.seed(db.jdbi(), spec(), List.of(), List.of(), refusing, "m", 16)
                .warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"));
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `ModelSeeder.java`:

```java
package sdd.plan.impact;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.SpecRenderer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Impact stage B (design): ONE assistive planner call — spec + repo cards + deterministic
 * seeds in, {repo, role, covers, reason} out. Assistive means: any failure (endpoint down,
 * truncation, malformed JSON) degrades to a warning and analysis continues deterministic-only.
 * The model can propose repos; it can never veto deterministic ones or abort the run.
 */
public final class ModelSeeder {
    private static final ObjectMapper JSON = new ObjectMapper();
    // covers a full design-conforming card (<=450 tokens); prompt-budget cap, recorded deviation
    private static final int CARD_MD_CAP = 2000;
    static final String SYSTEM_PROMPT = """
            You select which repositories of a multi-repo estate a feature specification will \
            touch. You receive the spec, one summary card per repository, and deterministic \
            seed evidence. Return exactly ONE JSON object, no markdown fences:
            {"repos": [{"repo": string, "role": "primary"|"contributor",
                        "covers": [requirement ids like "R1"], "reason": string}, ...]}
            Rules:
            - Name only repositories from the provided inventory, using their exact names.
            - "primary" = implements requirements directly; "contributor" = needs changes to support them.
            - Base every selection on evidence in the spec or cards; do not speculate.
            - Prefer precision: an empty list is better than guessed repositories.
            """;

    public record ModelSeed(String repo, String role, List<String> covers, String reason) {
        public ModelSeed {
            covers = List.copyOf(covers);
        }
    }

    public record SeedingOutcome(List<ModelSeed> seeds, List<String> warnings) {
        public SeedingOutcome {
            seeds = List.copyOf(seeds);
            warnings = List.copyOf(warnings);
        }
    }

    private ModelSeeder() {
    }

    public static SeedingOutcome seed(Jdbi jdbi, NormalizedSpec spec, List<Seed> deterministicSeeds,
                                      List<Seed> candidates, ChatModel planner, String modelName,
                                      int maxTokens) {
        String input = composeInput(jdbi, spec, deterministicSeeds, candidates);
        ChatResponse response;
        try {
            response = planner.complete(new ChatRequest(modelName,
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
                    List.of(), maxTokens, 0.15));
        } catch (ModelException e) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: " + e.getMessage()));
        }
        if ("length".equals(response.finishReason())) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: response truncated (finish_reason=length)"));
        }
        return parse(jdbi, spec, response.message().content());
    }

    private static SeedingOutcome parse(Jdbi jdbi, NormalizedSpec spec, String content) {
        if (content == null) {
            return new SeedingOutcome(List.of(), List.of("model seeding unavailable: empty response"));
        }
        String stripped = content.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                stripped = stripped.substring(firstNewline + 1, lastFence).strip();
            }
        }
        JsonNode root;
        try {
            root = JSON.readTree(stripped);
        } catch (JacksonException e) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: response is not valid JSON"));
        }
        if (!root.isObject()) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: response is not a JSON object"));
        }
        Set<String> knownRepos = new LinkedHashSet<>(jdbi.withHandle(h ->
                h.createQuery("SELECT name FROM repo ORDER BY name").mapTo(String.class).list()));
        Set<String> requirementIds = new LinkedHashSet<>(
                spec.requirements().stream().map(SpecItem::id).toList());
        List<ModelSeed> seeds = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (JsonNode node : root.path("repos")) {
            String repo = node.path("repo").asText().strip();
            if (!knownRepos.contains(repo)) {
                warnings.add("model named unknown repo '" + repo + "' — ignored");
                continue;
            }
            List<String> covers = new ArrayList<>();
            for (JsonNode cover : node.path("covers")) {
                String id = cover.asText().strip();
                if (requirementIds.contains(id)) {
                    covers.add(id);
                } else if (!id.isBlank()) {
                    warnings.add("model claimed " + repo + " covers unknown requirement '" + id + "' — ignored");
                }
            }
            String role = "primary".equals(node.path("role").asText()) ? "primary" : "contributor";
            seeds.add(new ModelSeed(repo, role, covers, node.path("reason").asText().strip()));
        }
        return new SeedingOutcome(seeds, warnings);
    }

    static String composeInput(Jdbi jdbi, NormalizedSpec spec, List<Seed> deterministicSeeds,
                               List<Seed> candidates) {
        StringBuilder input = new StringBuilder("# Specification\n\n");
        input.append(SpecRenderer.render(spec));
        input.append("\n# Repository inventory\n\n");
        List<Map<String, Object>> repos = jdbi.withHandle(h -> h.createQuery("""
                        SELECT r.name AS name, r.kind AS kind, c.card_line AS line, c.card_md AS md
                        FROM repo r LEFT JOIN repo_card c ON c.repo_id = r.id
                        ORDER BY r.name""")
                .mapToMap().list());
        for (Map<String, Object> repo : repos) {
            input.append("## ").append(repo.get("name")).append(" (").append(repo.get("kind")).append(")");
            if (repo.get("line") != null) {
                input.append(": ").append(repo.get("line"));
            }
            input.append('\n');
            if (repo.get("md") != null) {
                String md = String.valueOf(repo.get("md"));
                input.append(md.length() > CARD_MD_CAP ? md.substring(0, CARD_MD_CAP) : md).append('\n');
            }
        }
        input.append("\n# Deterministic seed evidence\n\n");
        for (Seed seed : deterministicSeeds) {
            input.append("- seed ").append(seed.repo()).append(" — ")
                    .append(seed.source()).append(' ').append(seed.detail()).append('\n');
        }
        for (Seed candidate : candidates) {
            input.append("- candidate ").append(candidate.repo()).append(" — ")
                    .append(candidate.source()).append(' ').append(candidate.detail()).append('\n');
        }
        return input.toString();
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: assistive model seeding over repo cards with failure containment"
```

---

### Task 8: ImpactAnalysis orchestrator

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/impact/ImpactAnalysis.java`
- Test: `sdd-plan/src/test/java/sdd/plan/impact/ImpactAnalysisTest.java`

**Interfaces:**
- Produces: `public final class ImpactAnalysis` with `public static ImpactResult analyze(Jdbi jdbi, Retriever retriever, NormalizedSpec spec, ChatModel planner, String modelName, int maxTokens)`.
- Consumes: everything from Tasks 4-7.

Assembly rules:
- `seeds` = touchpoint seeds + model seeds (as `Seed(repo, "model", "covers R1,R2; <reason>")`, detail `"<reason>"` alone when covers empty).
- Closure roots = distinct repos of touchpoint seeds ∪ model seeds.
- `affected` = one `AffectedRepo` per root (role `"seed"`, annotation `"SEED"`, covers = union of model covers for that repo, reasons = all seed details) followed by `Closure.expand(...).added()`.
- `discrepancies`: model repo with NO touchpoint seed and NO FTS candidate for the same repo → `"model-only: <repo> (<reason>)"` — still included (never silently resolved). A touchpoint-seeded repo the model did not name → `"model omitted seeded repo: <repo>"` — suppressed ONLY when model seeding was unavailable (an available model returning an empty selection is a genuine disagreement and must surface).
- `excluded` = FTS candidates whose repo is not in the final affected set, re-labeled detail: `"<original detail> — not selected by model, not required by graph"`.
- `problems` = SeedFinder problems + `"no repo covers <id>"` for each requirement id no model seed covers (uncovered-requirement detector; skipped entirely — with warning `"coverage unknown: model seeding unavailable"` — when model seeding produced zero seeds AND a `model seeding unavailable` warning exists).
- `warnings` = SeedFinder-free (it has none) + ModelSeeder warnings + Closure warnings.
- Zero roots (no touchpoints resolved, model gave nothing) → `problems` gains `"no seeds: add touchpoints to the spec or check the knowledge base"`; result otherwise empty but well-formed.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.impact;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.Touchpoint;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ImpactAnalysisTest {
    @TempDir Path ws;
    private Database db;

    // lib-core <- svc-pricing (PINNED, api_usage evidence); svc-legacy exists with an FTS-matching
    // type but no dep edge — the model will not select it -> excluded.
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-legacy','/w/3','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
            h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, target_module_id, ref_kind) "
                    + "VALUES (2,'com.acme.LoyaltyTier',1,'IMPORT')");
            FtsSymbolWriter.insert(h, 3L, "LegacyLoyaltyAdapter", "com.acme.legacy.LegacyLoyaltyAdapter");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "loyalty tier pricing"), new SpecItem("R2", "unrelated")),
                List.of(new SpecItem("A1", "acc")), List.of(),
                List.of(new Touchpoint(Touchpoint.Kind.CLASS, "LoyaltyTier")),
                List.of(), List.of(), List.of());
    }

    @Test
    void assemblesSeedsClosureDiscrepanciesExclusionsAndCoverage() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                    "reason": "owns LoyaltyTier"}]}"""),
                "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "deepseek-v4-flash", 4096);

        assertThat(result.seeds()).extracting(Seed::repo, Seed::source).contains(
                tuple("lib-core", "touchpoint"),
                tuple("lib-core", "model"));
        assertThat(result.affected()).extracting(AffectedRepo::repo, AffectedRepo::annotation)
                .containsExactly(
                        tuple("lib-core", "SEED"),
                        tuple("svc-pricing", "CODE_CHANGE_LIKELY"));
        assertThat(result.affected().get(0).covers()).containsExactly("R1");
        assertThat(result.excluded()).singleElement().satisfies(e -> {
            assertThat(e.repo()).isEqualTo("svc-legacy");
            assertThat(e.detail()).contains("not selected by model, not required by graph");
        });
        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.problems()).containsExactly("no repo covers R2");
        assertThat(result.cycles()).isEmpty();
    }

    @Test
    void modelOnlyReposAreIncludedButFlaggedAndOmissionsSurfaced() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("""
                        {"repos": [{"repo": "svc-legacy", "role": "contributor", "covers": ["R1", "R2"],
                                    "reason": "legacy adapter"}]}"""),
                "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "deepseek-v4-flash", 4096);

        // svc-legacy has an FTS candidate, so it is NOT model-only; lib-core was seeded but unnamed
        assertThat(result.discrepancies()).containsExactly("model omitted seeded repo: lib-core");
        assertThat(result.affected()).extracting(AffectedRepo::repo)
                .contains("lib-core", "svc-legacy", "svc-pricing");
        assertThat(result.excluded()).isEmpty();
    }

    @Test
    void modelFailureDegradesToDeterministicOnlyWithCoverageUnknown() {
        sdd.core.llm.ChatModel down = req -> {
            throw new sdd.core.llm.ModelException("connection refused", 0);
        };

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), down, "m", 16);

        assertThat(result.affected()).extracting(AffectedRepo::repo)
                .containsExactly("lib-core", "svc-pricing");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("model seeding unavailable"))
                .anySatisfy(w -> assertThat(w).contains("coverage unknown"));
        assertThat(result.problems()).noneSatisfy(p -> assertThat(p).contains("no repo covers"));
    }

    @Test
    void emptyModelSelectionStillSurfacesOmissionDiscrepancies() {
        // model AVAILABLE but selecting nothing is a genuine model/graph disagreement
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                spec(), planner, "m", 16);

        assertThat(result.discrepancies()).contains("model omitted seeded repo: lib-core");
        assertThat(result.problems()).contains("no repo covers R1", "no repo covers R2");
    }

    @Test
    void zeroSeedsIsAProblemNotACrash() {
        NormalizedSpec bare = new NormalizedSpec("S-2", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "zzz qqq xxx")), List.of(new SpecItem("A1", "a")),
                List.of(), List.of(), List.of(), List.of(), List.of());
        ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
                ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1))));

        ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
                bare, planner, "m", 16);

        assertThat(result.affected()).isEmpty();
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("no seeds"));
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `ImpactAnalysis.java`:

```java
package sdd.plan.impact;

import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.ChatModel;
import sdd.core.retrieve.Retriever;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The three-stage impact analysis (design Component 2): A deterministic pre-seed, B assistive
 * model seeding, C deterministic closure. Model/graph discrepancies are recorded and surfaced,
 * never silently resolved.
 */
public final class ImpactAnalysis {

    private ImpactAnalysis() {
    }

    public static ImpactResult analyze(Jdbi jdbi, Retriever retriever, NormalizedSpec spec,
                                       ChatModel planner, String modelName, int maxTokens) {
        SeedFinder.SeedScan scan = SeedFinder.find(jdbi, retriever, spec);
        ModelSeeder.SeedingOutcome seeding = ModelSeeder.seed(jdbi, spec, scan.seeds(),
                scan.candidates(), planner, modelName, maxTokens);
        boolean modelUnavailable = seeding.seeds().isEmpty()
                && seeding.warnings().stream().anyMatch(w -> w.contains("model seeding unavailable"));

        List<Seed> seeds = new ArrayList<>(scan.seeds());
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            String detail = modelSeed.covers().isEmpty()
                    ? modelSeed.reason()
                    : "covers " + String.join(",", modelSeed.covers()) + "; " + modelSeed.reason();
            seeds.add(new Seed(modelSeed.repo(), "model", detail));
        }

        Set<String> touchpointRepos = new LinkedHashSet<>();
        for (Seed seed : scan.seeds()) {
            touchpointRepos.add(seed.repo());
        }
        Set<String> candidateRepos = new LinkedHashSet<>();
        for (Seed candidate : scan.candidates()) {
            candidateRepos.add(candidate.repo());
        }
        Set<String> modelRepos = new LinkedHashSet<>();
        Map<String, List<String>> coversByRepo = new LinkedHashMap<>();
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            modelRepos.add(modelSeed.repo());
            coversByRepo.computeIfAbsent(modelSeed.repo(), k -> new ArrayList<>())
                    .addAll(modelSeed.covers());
        }

        List<String> discrepancies = new ArrayList<>();
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            if (!touchpointRepos.contains(modelSeed.repo()) && !candidateRepos.contains(modelSeed.repo())) {
                discrepancies.add("model-only: " + modelSeed.repo() + " (" + modelSeed.reason() + ")");
            }
        }
        for (String seeded : touchpointRepos) {
            // suppressed ONLY when the model was unavailable — a model that answered with an
            // empty selection genuinely disagrees with the graph, and that must surface
            if (!modelUnavailable && !modelRepos.contains(seeded)) {
                discrepancies.add("model omitted seeded repo: " + seeded);
            }
        }

        Set<String> roots = new LinkedHashSet<>(touchpointRepos);
        roots.addAll(modelRepos);

        List<String> problems = new ArrayList<>(scan.problems());
        List<String> warnings = new ArrayList<>(seeding.warnings());

        List<AffectedRepo> affected = new ArrayList<>();
        Closure.Expansion expansion;
        if (roots.isEmpty()) {
            problems.add("no seeds: add touchpoints to the spec or check the knowledge base");
            expansion = new Closure.Expansion(List.of(), List.of(), List.of());
        } else {
            for (String root : roots) {
                List<String> reasons = new ArrayList<>();
                for (Seed seed : seeds) {
                    if (seed.repo().equals(root)) {
                        reasons.add(seed.source() + " " + seed.detail());
                    }
                }
                affected.add(new AffectedRepo(root, "seed", "SEED",
                        coversByRepo.getOrDefault(root, List.of()), reasons));
            }
            expansion = Closure.expand(jdbi, roots);
            affected.addAll(expansion.added());
        }
        warnings.addAll(expansion.warnings());

        if (modelUnavailable) {
            warnings.add("coverage unknown: model seeding unavailable");
        } else {
            Set<String> covered = new LinkedHashSet<>();
            coversByRepo.values().forEach(covered::addAll);
            for (SpecItem requirement : spec.requirements()) {
                if (!covered.contains(requirement.id())) {
                    problems.add("no repo covers " + requirement.id());
                }
            }
        }

        Set<String> affectedRepos = new LinkedHashSet<>();
        for (AffectedRepo repo : affected) {
            affectedRepos.add(repo.repo());
        }
        List<Seed> excluded = new ArrayList<>();
        for (Seed candidate : scan.candidates()) {
            if (!affectedRepos.contains(candidate.repo())) {
                excluded.add(new Seed(candidate.repo(), candidate.source(),
                        candidate.detail() + " — not selected by model, not required by graph"));
            }
        }

        return new ImpactResult(seeds, affected, excluded, expansion.cycles(),
                discrepancies, problems, warnings);
    }
}
```

- [ ] **Step 3: Run — expect PASS** (`zeroSeedsIsAProblemNotACrash` exercises the empty-roots path; check the coverage detector fires only for the first test).
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 4: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: three-stage impact analysis with discrepancy surfacing"
```

---

### Task 9: CLI integration + e2e

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (`validate()` runs the analysis)
- Test: `sdd-cli/src/test/java/sdd/cli/PlanCommandTest.java`

**Interfaces:**
- Consumes: `ImpactAnalysis.analyze`, `ImpactResult`/`AffectedRepo`/`Seed`, `Database.open`, `FtsRetriever`, planner endpoint + `plannerForTest` seam.
- Produces: `sdd plan <spec.md>` output contract (all stdout, exit 0 on analyzed spec):

```
spec OK: <id> — ...                                   (existing line, unchanged)
impact: <N> repos affected (<S> seeds, <D> dependents, <C> contracts, <B> bom-sites)
  <repo>  <annotation>  <first reason>                (one line per affected repo, in result order)
  excluded: <repo> — <detail>                         (when any)
  discrepancy: <text>                                 (when any)
  cycle: <text>                                       (when any)
  impact problem: <text>                              (when any)
  warn: <text>                                        (when any)
plan.md rendering is not implemented yet (Phase 3C)
```

Empty KB (zero rows in `repo`) → `error: knowledge base is empty — run sdd index first` (stderr, exit 1) BEFORE any model construction. The final line replaces the old `impact analysis is not implemented yet (Phase 3B)`.

- [ ] **Step 1: Write the failing e2e test** (append to `PlanCommandTest`; the seeded estate mirrors ImpactAnalysisTest's shape):

```java
@Test
void validSpecRunsImpactAnalysisEndToEnd() throws Exception {
    Files.writeString(ws.resolve("sdd.yml"), yaml());
    try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
        });
    }
    Path spec = ws.resolve("loyalty.md");
    Files.writeString(spec, """
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

            ## Touchpoints
            - class: LoyaltyTier
            """);
    PlanCommand cmd = new PlanCommand();
    cmd.plannerForTest = new ScriptedChatModel(List.of(new ChatResponse(
            ChatMessage.assistant("""
                    {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                "reason": "owns LoyaltyTier"}]}"""),
            "stop", new Usage(10, 10))));

    Run run = plan(cmd, "--workspace", ws.toString(), spec.toString());

    assertThat(run.out())
            .contains("spec OK: SPEC-7")
            .contains("impact: 2 repos affected (1 seeds, 1 dependents, 0 contracts, 0 bom-sites)")
            .contains("lib-core")
            .contains("SEED")
            .contains("svc-pricing")
            .contains("BUMP_REBUILD_ONLY")   // no api_usage row in this estate — annotation must survive to the CLI
            .contains("plan.md rendering is not implemented yet (Phase 3C)");
    assertThat(run.exitCode()).isZero();
}

@Test
void emptyKnowledgeBaseFailsBeforeAnyModelWork() throws Exception {
    Files.writeString(ws.resolve("sdd.yml"), yaml());
    Path spec = ws.resolve("loyalty.md");
    Files.writeString(spec, VALID_SPEC);

    Run run = plan(new PlanCommand(), "--workspace", ws.toString(), spec.toString());

    assertThat(run.out()).contains("error: knowledge base is empty — run sdd index first");
    assertThat(run.exitCode()).isEqualTo(1);
}
```

Note: `emptyKnowledgeBaseFailsBeforeAnyModelWork` reuses `VALID_SPEC` — the existing `validCanonicalSpecPrintsSummary` and `planIsRegisteredOnTheRootCommand` tests currently expect exit 0 for that spec with NO knowledge base. Their workspaces have no `.sdd/index.db` rows, so under the new behavior they would exit 1. **Update both existing tests in the same commit**: seed a minimal KB (one repo + module via `Database.open` as above), set `cmd.plannerForTest` (`planIsRegisteredOnTheRootCommand` constructs `SddCli` — it cannot inject the seam, so its spec must avoid model work entirely: THAT IS IMPOSSIBLE once analysis always runs — instead give it a scripted... it cannot. Resolution: `planIsRegisteredOnTheRootCommand` keeps proving registration only, so change its assertion to the empty-KB error path: execute `plan` via the root command against a workspace with NO seeded KB and assert `error: knowledge base is empty` + exit 1 — registration is still proven by the command being found and dispatching). `validCanonicalSpecPrintsSummary` gets the seeded KB + a scripted planner returning `{"repos": []}` (its VALID_SPEC has zero touchpoints, so zero roots): it keeps asserting `spec OK: SPEC-7`, REPLACES the old `.contains("Phase 3B")` assertion with `.contains("plan.md rendering is not implemented yet (Phase 3C)")`, and additionally asserts `impact: 0 repos affected (0 seeds, 0 dependents, 0 contracts, 0 bom-sites)`, the `impact problem: no seeds` line, and the `impact problem: no repo covers R1` line (the model answered, so the coverage detector runs). `semanticProblemsFailNamingEachOne` and `parseErrorsSurfaceWithLineNumber` stay untouched because the KB check runs only AFTER spec validation passes. **`confluenceExportNormalizesWritesGateFileAndReparses` needs migration too**: its second run (`plan(new PlanCommand(), ...)` on the written gate file) goes down the validate path against an EMPTY KB — change its round-trip assertions to `assertThat(second.out()).contains("spec OK: spec-loyalty-page").contains("error: knowledge base is empty — run sdd index first"); assertThat(second.exitCode()).isEqualTo(1);` — the round trip still proves the gate file re-parses and validates (the `spec OK` line prints before the KB check), and seeding a KB there is not viable because the second `PlanCommand` has no planner seam (a seeded KB would make it retry a real HTTP call against the unroutable endpoint for seconds). The remaining tests (`missingConfigFailsCleanly`, `outOptionRedirectsTheGateFile`, `outOptionRejectsHtmlTargets`, `followUpHintCarriesNonDefaultWorkspace`) are genuinely unaffected.

- [ ] **Step 2: Run — expect FAIL.** Then implement. In `PlanCommand.validate(...)`, replace the final `println("impact analysis is not implemented yet (Phase 3B)")` with:

```java
try (Database db = Database.open(workspace)) {
    Integer repoCount = db.jdbi().withHandle(h ->
            h.createQuery("SELECT count(*) FROM repo").mapTo(Integer.class).one());
    if (repoCount == 0) {
        errWriter.println("error: knowledge base is empty — run sdd index first");
        return 1;
    }
    ModelEndpoint planner = config.models().get("planner");
    ChatModel model = plannerForTest != null ? plannerForTest : new HttpChatModel(planner);
    ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
            parsed, model, planner.model(), planner.maxTokens());
    printImpact(outWriter, result);
}
outWriter.println("plan.md rendering is not implemented yet (Phase 3C)");
return 0;
```

`validate` needs the config — change its signature to `private Integer validate(SddConfig config, PrintWriter outWriter, PrintWriter errWriter)` and pass `config` from `call()`. Add the printer:

```java
private void printImpact(PrintWriter outWriter, ImpactResult result) {
    long seeds = result.affected().stream().filter(a -> a.role().equals("seed")).count();
    long dependents = result.affected().stream().filter(a -> a.role().equals("dependent")).count();
    long contracts = result.affected().stream().filter(a -> a.role().equals("contract")).count();
    long bomSites = result.affected().stream().filter(a -> a.role().equals("bom-site")).count();
    outWriter.printf(Locale.ROOT, "impact: %d repos affected (%d seeds, %d dependents, %d contracts, %d bom-sites)%n",
            result.affected().size(), seeds, dependents, contracts, bomSites);
    for (AffectedRepo repo : result.affected()) {
        String reason = repo.reasons().isEmpty() ? "" : "  " + repo.reasons().get(0);
        outWriter.printf(Locale.ROOT, "  %-28s %-20s%s%n", repo.repo(), repo.annotation(), reason);
    }
    for (Seed excluded : result.excluded()) {
        outWriter.println("  excluded: " + excluded.repo() + " — " + excluded.detail());
    }
    for (String discrepancy : result.discrepancies()) {
        outWriter.println("  discrepancy: " + discrepancy);
    }
    for (String cycle : result.cycles()) {
        outWriter.println("  cycle: " + cycle);
    }
    for (String problem : result.problems()) {
        outWriter.println("  impact problem: " + problem);
    }
    for (String warning : result.warnings()) {
        outWriter.println("  warn: " + warning);
    }
}
```

New imports: `sdd.core.db.Database`, `sdd.core.retrieve.FtsRetriever`, `sdd.plan.impact.AffectedRepo`, `sdd.plan.impact.ImpactAnalysis`, `sdd.plan.impact.ImpactResult`, `sdd.plan.impact.Seed`. Update the two existing tests exactly as resolved in Step 1's note.

- [ ] **Step 3: Run sdd-cli tests — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 4: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: sdd plan runs impact analysis after spec validation"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. `ImpactAnalysisTest` pins the three-stage assembly incl. discrepancy and degradation semantics; `ClosureTest` pins transitivity, annotations, contracts, BOM sites, SCC; `PlanCommandTest.validSpecRunsImpactAnalysisEndToEnd` proves the whole chain through the CLI.
3. Manual smoke (optional, needs a real indexed workspace + DeepSeek key): `sdd plan <spec.md> --workspace <ws>` prints the affected-repo table.
4. Explicitly still deferred (3A entry pointer, unchanged): the 2-3 repo perf smoke run on the real estate — an operational step needing the user's estate workspace path, scheduled before the first full 40-repo index, not a code task in this plan.

## Self-Review (completed at write time)

1. **Spec coverage:** design stage A (touchpoints via KB + free text via Retriever + provenance) → Task 5; stage B (one DeepSeek call, spec+cards+seeds → {repo, role, covers, reason}, discrepancies surfaced never resolved) → Tasks 7-8; stage C (full transitive closure never limited by annotation (M1), REST/Kafka 1-hop pending-contract, BOM declaration-site pull-in (M2), repo-level SCC co-scheduled (M3), status downgrades) → Task 6; 3A hardening bundle items 1-4 + T8-a → Tasks 1-2; sdd-plan⇸sdd-index direction preserved via the Routes move → Task 3. The api-usage annotation adaptation (configuration column lacks api/implementation) is recorded in Global Constraints. Uncovered-requirement detection is included in Task 8 (with model-unavailable suppression); full Open-Questions detectors and plan.md rendering remain Phase 3C by the stated decomposition.
2. **Placeholder scan:** Task 3's `Routes` body says "moved verbatim" with explicit source line references instead of duplicating code that must not drift during the move — deliberate: the source of truth is the current file content, and the moved tests pin the semantics. No TBD/TODO anywhere.
3. **Type consistency:** `Seed(String repo, String source, String detail)` and `AffectedRepo(repo, role, annotation, covers, reasons)` used identically in Tasks 4, 5, 6, 8, 9; `SeedFinder.SeedScan(seeds, candidates, problems)` in 5, 8; `Closure.Expansion(added, cycles, warnings)` in 6, 8; `ModelSeeder.ModelSeed/SeedingOutcome` in 7, 8; `ImpactAnalysis.analyze(Jdbi, Retriever, NormalizedSpec, ChatModel, String, int)` in 8, 9; `Routes.normalize/join/templatesMatch/verbsCompatible` in 3, 5. Role strings (`seed|dependent|contract|bom-site`) and annotation strings match between Tasks 4, 6, 8 and Task 9's counters.
4. **Adversarial critique pass (3 independent critics vs the real codebase, findings folded in):** JDK-21 `HttpClient` stub completed (`cookieHandler` was missing — would not compile); `Closure` emission order made self-consistent (consumer registered before its BOM sites, `bomConsumersSeen` dedupe) and pinned by the test; the invisible U+2028/U+2029/U+0085 literals in the Task-2 test rewritten as visible Java escapes; the omission-discrepancy guard corrected so an AVAILABLE model returning an empty selection still surfaces disagreements (design law), with a pinning test; `confluenceExportNormalizesWritesGateFileAndReparses` migration added (its round-trip second run hits the new empty-KB check); reason-merge branches, verb-less endpoint, fenced-JSON seeding, and CLI annotation survival all gained tests; CARD_MD_CAP raised to 2000 and recorded as a deviation; the api_usage annotation adaptation now lands as a dated design-spec amendment (Task 6 Step 4); `import java.util.Map` noted for RestMatcher; tuple() via static import per repo convention.

---

## Execution Outcome (2026-08-12)

Executed via superpowers:subagent-driven-development on branch `feature/phase3b-impact-analysis` (base aff5cdc, HEAD 197dffe, 10 commits). All 9 tasks completed with clean per-task reviews (fix round: Task 8 ×1 — union-dedupe for covers/reasons/model-only, per the brief's prose over its reference code). Final whole-branch review (most capable model): **APPROVE, no fix wave**; fresh `./gradlew clean build` green (297 tests repo-wide). Reviewers reproduced pre-fix REDs in disposable worktrees for Tasks 1/2/8, stress-tested Closure with five extra graph shapes (root-in-cycle SCC, diamond, self-loop, edgeless root, dual-affected Kafka pair), and byte-verified the Routes move per method.

**Merged: NO — user directive.** The real estate (trading-* repos) arrived mid-execution; testing proceeds on branches and nothing merges to the trading repos' main branches. The sdd branch stays unmerged pending real-estate smoke results.

### Phase-3C entry checklist (from final review)

1. **[Medium] Seeding-call stall guard** — `PlanCommand.validate` uses default 6-attempt `HttpChatModel`; a blackholed planner endpoint stalls up to ~6×timeout before the "model seeding unavailable" degradation. Use `new HttpChatModel(planner, 2)` for the assistive seeding call (precedent: cards cap) or a short per-call timeout.
2. FtsRetriever `ORDER BY score` needs a deterministic tiebreaker (`, identifier, module_id`) BEFORE `plan approve` SHA-pins plan.md — unstable candidate order would churn byte-pinned output.
3. `ImpactResult.seeds()` keeps duplicate model Seed rows for a repeated repo (covers/reasons/discrepancies are deduped; the seeds list is verbatim by rule) — dedupe or render-dedupe when 3C prints provenance.
4. FTS provenance is dropped when the model confirms a candidate (only unconfirmed candidates surface in excluded) — carry "R1 hit: X" evidence into the seed reasons for plan.md's "why" column.
5. `sdd plan` on an unindexed workspace creates `.sdd/index.db` via migrations before printing the empty-KB error (cosmetic).
6. Deferred minors, all OK-TO-DEFER (ledger): test-fixture nits (blank lines, literal-backslash-n card fixture), untested null-content/non-object containment paths (code trivially correct), model-only dedupe unpinned by committed test, modelUnavailable stringly-typed seam (consider a boolean on SeedingOutcome in 3C).

### Real-estate smoke run (2026-08-12, trading estate, branch build)

Workspace: `~/projects/github/trading-estate` (symlinks to the six trading-* repos + sdd.yml). All repos remained on clean `main` — indexing is read-only for scanned repos.

- **`sdd index --no-cards`: 40 s wall for 6 repos / 33 modules / 469 java files — every repo OK/OK** (no DEGRADED fallbacks; symlinked repo dirs work). 34 internal edges, 0 orphans, 342 internal type refs, 15 REST endpoints. Projected ~4.5 min for a 40-repo estate — well inside design assumptions.
- **Zeros verified against ground truth:** 0 REST clients and 0 Kafka roles are CORRECT — production services communicate via Redis pub/sub (`common-messaging`), WebSockets, and FIX; all RestTemplate/WebClient usage lives in tests/load tooling. ESTATE FINDING for 3C+: inter-service edges here are Redis channels (unmodeled edge type, cousin of the deferred spring-cloud-stream item); closure leans on the rich Gradle+api_usage graph.
- **Curation report caught a real smell:** three repos publish the same GA `com.trading:services` (aggregator naming collision) — surfaced as loud conflicts, linking unaffected; fix via estate rename or `artifact_overrides`.
- **`sdd plan` live e2e (real DeepSeek): 25 s.** All three touchpoints resolved (repo, endpoint via Routes template match, class); closure pulled the other three repos off the platform-libs SNAPSHOT hub with correctly differentiated annotations (candles/product-b CODE_CHANGE_LIKELY via api_usage evidence; ops BUMP_REBUILD_ONLY); 0 contracts/bom-sites (correct for this estate); model seeding succeeded, named all seeded repos (no omission discrepancies) and covered R1-R3 (no coverage problems).
- CLI observation for 3C: SEED rows print only the first reason; model covers/reasons surface only in plan.md — consider a covers column in the CLI table.
