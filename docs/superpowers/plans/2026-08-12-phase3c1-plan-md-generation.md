# Phase 3C-1 — plan.md Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdd plan <spec.md>` produces the human-editable Gate-1 artifact `<spec>.plan.md` — deterministic Summary/Affected/Excluded/Execution-Order/Open-Questions sections from the impact result, plus model-drafted Repo Steps and Interface Contracts grounded in KB evidence — and the Phase-3C entry-checklist hardening lands first.

**Architecture:** New package `sdd.plan.gen` (`ExecutionOrder` Kahn-with-SCC-units, `OpenQuestions` deterministic detectors, `PlanDrafter` — the phase's second and last model call, with total failure containment degrading to a `[blocking]` open question — and `PlanMdRenderer`). The deterministic sections NEVER depend on the model: a dead planner still yields a reviewable plan.md whose blocking question says drafting is unavailable. `plan approve`/plan.json/revise are Phase 3C-2. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` Component 2 ("plan.md" bullet) + amendments; entry checklist: bottom of `docs/superpowers/plans/2026-08-12-phase3b-impact-analysis.md`.

**Tech Stack:** Java 21, Jdbi 3, Jackson (drafter JSON), StringBuilder markdown (CurationReport/SpecRenderer precedent — SnakeYAML stays load-only), ScriptedChatModel testFixtures. NO new dependencies.

## Global Constraints

- Java 21; NO new third-party dependencies; sdd-plan depends only on sdd-core.
- Deterministic-first: `PlanDrafter.draft` is the ONLY model call this phase adds. Its failure (ModelException, `finish_reason=length`, malformed/non-object JSON, null content) degrades to a Draft marked unavailable whose blocking question reads `plan drafting unavailable: <detail> — rerun sdd plan`; plan.md is STILL written with every deterministic section populated. The model can never veto, abort, or corrupt deterministic content.
- Drafter outputs are validated in code, never trusted: step repos filtered to the affected set, covers filtered to real requirement ids, provides/consumes filtered to defined contract ids, version_action coerced into `none|patch|minor|major`, contract kind coerced into `java-api|rest|kafka` — every drop recorded as a generation note. File paths the model names are soft-checked against `java_type.file_path` for that repo (mismatch → note, kept — the human gate judges).
- Drafter-controlled free text can never forge plan.md STRUCTURE: the renderer whitespace-collapses single-line fields (summary, question texts, contract heading parts, bullet values, notes) and, for sub-spec prose, strips leading heading markers and neutralizes bare `---` lines — the renderer alone owns section boundaries and front matter (3C-2's parser pins this layout).
- plan.md location: sibling of the spec — `<ref minus trailing .md>.plan.md` (mirrors the `.spec.md` gate-file convention). Front matter exactly `spec: <id>` and `plan_version: 1`.
- Section order in plan.md exactly: `Summary`, `Open Questions`, `Affected Repos`, `Excluded Candidates`, `Execution Order`, `Interface Contracts`, `Repo Steps`, `Generation Notes`. Open-Questions lines are `- Q<n> [blocking]: <text>` / `- Q<n>: <text>`, numbered by the renderer in emission order (detectors first, drafter questions after).
- Execution order: Kahn over the affected subgraph — Gradle edges (`v_repo_dep_edge`, provider before consumer) plus REST/Kafka provider→consumer pseudo-edges as tie-breaks; SCC cycles from `ImpactResult.cycles()` collapse into co-scheduled units; ready units are processed in alphabetical order; if pseudo-edges deadlock the remainder, remaining units append alphabetically (3C-2's validator owns hard enforcement).
- Entry-checklist hardening is Tasks 1-2 and binds this phase: FtsRetriever deterministic tiebreaker (`ORDER BY score, identifier, module_id`); `SeedingOutcome` gains a `boolean unavailable` component replacing the stringly seam; the seeding call in `PlanCommand.validate` caps attempts at 2 (`SEED_MAX_ATTEMPTS`, cards precedent — the DRAFTING call also uses cap 2, both assistive-degradable); the empty-KB check tests `Files.exists(<ws>/.sdd/index.db)` BEFORE `Database.open` so `sdd plan` never creates the database; FTS provenance of model-confirmed candidates carries into seed reasons; the seeds list dedupes identical (repo, source, detail) triples.
- CLI taxonomy unchanged: exceptions → `error: <msg>` stderr exit 1; validation findings → `problem: <text>` exit 1; impact/plan content → stdout exit 0. All printf `Locale.ROOT`.
- Never read or print `.env` or any `api_key`; test yaml uses unreachable endpoints.
- Never push. Full `./gradlew build` before any commit touching more than one module.

---

## File Structure

**Task 1 (hardening A):** `sdd-core/.../retrieve/FtsRetriever.java` + `FtsRetrieverTest`; `sdd-plan/.../impact/ModelSeeder.java` + test; `sdd-plan/.../impact/ImpactAnalysis.java`; `sdd-cli/.../PlanCommand.java` + `PlanCommandTest`
**Task 2 (hardening B):** `sdd-plan/.../impact/ImpactAnalysis.java` + `ImpactAnalysisTest`
**Task 3:** `sdd-plan/src/main/java/sdd/plan/gen/ExecutionOrder.java` + test
**Task 4:** `sdd-plan/src/main/java/sdd/plan/gen/OpenQuestions.java` + test
**Task 5:** `sdd-plan/src/main/java/sdd/plan/gen/PlanDrafter.java` + test
**Task 6:** `sdd-plan/src/main/java/sdd/plan/gen/PlanMdRenderer.java` + test
**Task 7:** `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (write plan.md) + `PlanCommandTest` e2e

---

### Task 1: Hardening A — tiebreaker, unavailable flag, attempt cap, no-create KB check

**Files:**
- Modify: `sdd-core/src/main/java/sdd/core/retrieve/FtsRetriever.java:23-26`
- Modify: `sdd-plan/src/main/java/sdd/plan/impact/ModelSeeder.java` (record + 6 construction sites)
- Modify: `sdd-plan/src/main/java/sdd/plan/impact/ImpactAnalysis.java:31-32`
- Modify: `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (validate: exists-check + capped client)
- Test: `sdd-core/src/test/java/sdd/core/retrieve/FtsRetrieverTest.java`, `sdd-plan/src/test/java/sdd/plan/impact/ModelSeederTest.java`, `sdd-cli/src/test/java/sdd/cli/PlanCommandTest.java`

**Interfaces:**
- Produces: `public record SeedingOutcome(List<ModelSeed> seeds, List<String> warnings, boolean unavailable)` (THIRD component appended — Tasks 5's Draft mirrors the shape); `SEED_MAX_ATTEMPTS = 2` used for the validate-path planner client; empty-KB error WITHOUT creating `.sdd/index.db`.
- Consumes: existing `HttpChatModel(ModelEndpoint, int)` (3A Task 2).

- [ ] **Step 1: FtsRetriever tiebreaker.** Add a failing test to `FtsRetrieverTest` (it already opens a `Database` on a `@TempDir` and inserts `fts_symbol` rows — mirror its existing insert helper):

```java
@Test
void tiedScoresOrderDeterministicallyByIdentifierThenModule() {
    // two rows with identical tokens => identical bm25 score; order must be pinned
    db.jdbi().useHandle(h -> {
        h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES ('ZetaWidget','com.acme.z.ZetaWidget','zeta widget',2)");
        h.execute("INSERT INTO fts_symbol(identifier, fqcn, words, module_id) VALUES ('AlphaWidget','com.acme.a.AlphaWidget','alpha widget',1)");
    });

    List<Hit> first = new FtsRetriever(db.jdbi()).search("widget", 10);
    List<Hit> second = new FtsRetriever(db.jdbi()).search("widget", 10);

    assertThat(first).extracting(Hit::identifier).containsExactly("AlphaWidget", "ZetaWidget");
    assertThat(second).extracting(Hit::identifier).isEqualTo(
            first.stream().map(Hit::identifier).toList());
}
```

(If the existing fixture's seed rows also match "widget", adjust the query token to something unique to these two rows — the point is two equal-score rows with a pinned relative order.)

- [ ] **Step 2: Run — expect FAIL or flaky-pass; then change the query** (FtsRetriever.java:23-26) `ORDER BY score` → `ORDER BY score, identifier, module_id`. Run `./gradlew :sdd-core:test` — PASS.

- [ ] **Step 3: SeedingOutcome boolean.** In `ModelSeeder.java`: change the record to

```java
public record SeedingOutcome(List<ModelSeed> seeds, List<String> warnings, boolean unavailable) {
    public SeedingOutcome {
        seeds = List.copyOf(seeds);
        warnings = List.copyOf(warnings);
    }
}
```

Update ALL FIVE unavailable construction sites (ModelException :72, length :76, empty response :83, not valid JSON :98, not a JSON object :102) to pass `true`, and the success return in `parse(...)` to pass `false`. In `ImpactAnalysis.java:31-32` replace the substring inference with:

```java
boolean modelUnavailable = seeding.unavailable();
```

Extend `ModelSeederTest`: in `modelFailuresDegradeToWarningsNeverThrow` assert `.unavailable()` is `true` for all three scripted failures; in `validResponseYieldsSeedsAndFiltersUnknowns` and `fencedJsonResponseIsUnwrapped` assert `.unavailable()` is `false`. (Warnings about unknown repos/ids do NOT make the outcome unavailable.)

- [ ] **Step 4: Run sdd-plan tests — expect PASS** (all ImpactAnalysis tests must stay green — behavior identical, the flag is now explicit).
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 5: PlanCommand — no-create KB check + capped seeding client.** Add a failing assertion to the existing `emptyKnowledgeBaseFailsBeforeAnyModelWork` test:

```java
        assertThat(Files.exists(ws.resolve(".sdd/index.db"))).isFalse();   // plan must never CREATE the KB
```

Run `./gradlew :sdd-cli:test --tests 'sdd.cli.PlanCommandTest'` — expect FAIL (Database.open migrates a fresh db today). Then in `PlanCommand.validate(...)`, BEFORE the `try (Database db = Database.open(workspace))` block, insert:

```java
if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
    errWriter.println("error: knowledge base is empty — run sdd index first");
    return 1;
}
```

(The in-db `repoCount == 0` check stays — it covers a created-but-empty schema from a failed index.) And add the attempt cap: a constant next to the class's other fields

```java
private static final int SEED_MAX_ATTEMPTS = 2;   // assistive calls fail fast — analysis degrades, reruns are cheap
```

and in `validate(...)` change the model construction to `plannerForTest != null ? plannerForTest : new HttpChatModel(planner, SEED_MAX_ATTEMPTS)`. (The Confluence `normalize(...)` path keeps the default 6 attempts — its call is load-bearing, not assistive.)

- [ ] **Step 6: Full build, then commit**

```bash
./gradlew build
git add sdd-core/src sdd-plan/src sdd-cli/src
git commit -m "fix: deterministic fts order, explicit unavailable flag, no-create KB check"
```

---

### Task 2: Hardening B — FTS provenance carry + seeds dedupe

**Files:**
- Modify: `sdd-plan/src/main/java/sdd/plan/impact/ImpactAnalysis.java:34-40` (seeds assembly)
- Test: `sdd-plan/src/test/java/sdd/plan/impact/ImpactAnalysisTest.java`

**Interfaces:**
- Consumes/Produces: `ImpactAnalysis.analyze` signature unchanged. New behavior: when the model confirms an FTS candidate, the candidate's `Seed(repo, "fts", detail)` joins the seeds list (before that repo's model seed), so the SEED row's reasons carry `fts <detail>`; the seeds list holds no two identical (repo, source, detail) triples.

- [ ] **Step 1: Write the failing test** (append to `ImpactAnalysisTest`; its fixture already gives svc-legacy an FTS hit via `LegacyLoyaltyAdapter`):

```java
@Test
void modelConfirmedCandidateCarriesFtsProvenance() {
    ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
            ChatMessage.assistant("""
                    {"repos": [{"repo": "svc-legacy", "role": "contributor", "covers": ["R1"],
                                "reason": "legacy adapter"}]}"""),
            "stop", new Usage(1, 1))));

    ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
            spec(), planner, "m", 16);

    assertThat(result.seeds()).extracting(Seed::repo, Seed::source).contains(
            tuple("svc-legacy", "fts"), tuple("svc-legacy", "model"));
    AffectedRepo legacy = result.affected().stream()
            .filter(a -> a.repo().equals("svc-legacy")).findFirst().orElseThrow();
    assertThat(legacy.reasons()).anySatisfy(r ->
            assertThat(r).isEqualTo("fts R1 hit: LegacyLoyaltyAdapter"));
    // regression pin, not a RED driver: a model-selected candidate is affected today too,
    // so excluded is already empty pre-change — this pins that the rework keeps it so
    assertThat(result.excluded()).isEmpty();
}

@Test
void seedsListHoldsNoIdenticalTriples() {
    ScriptedChatModel planner = new ScriptedChatModel(List.of(new ChatResponse(
            ChatMessage.assistant("""
                    {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"], "reason": "owns it"},
                               {"repo": "lib-core", "role": "primary", "covers": ["R1"], "reason": "owns it"}]}"""),
            "stop", new Usage(1, 1))));

    ImpactResult result = ImpactAnalysis.analyze(db.jdbi(), new FtsRetriever(db.jdbi()),
            spec(), planner, "m", 16);

    long modelSeedRows = result.seeds().stream()
            .filter(s -> s.repo().equals("lib-core") && s.source().equals("model")).count();
    assertThat(modelSeedRows).isEqualTo(1);
}
```

- [ ] **Step 2: Run — expect FAIL.** Then rework the seeds-assembly block (ImpactAnalysis.java:34-40) to:

```java
        Map<String, Seed> candidateByRepo = new LinkedHashMap<>();
        for (Seed candidate : scan.candidates()) {
            candidateByRepo.putIfAbsent(candidate.repo(), candidate);
        }
        List<Seed> seeds = new ArrayList<>(scan.seeds());
        Set<String> seedKeys = new LinkedHashSet<>();
        for (Seed seed : seeds) {
            seedKeys.add(seed.repo() + " " + seed.source() + " " + seed.detail());
        }
        for (ModelSeeder.ModelSeed modelSeed : seeding.seeds()) {
            Seed candidate = candidateByRepo.get(modelSeed.repo());
            if (candidate != null) {
                addSeed(seeds, seedKeys, candidate);   // model confirmed the FTS candidate: keep its evidence
            }
            String detail = modelSeed.covers().isEmpty()
                    ? modelSeed.reason()
                    : "covers " + String.join(",", modelSeed.covers()) + "; " + modelSeed.reason();
            addSeed(seeds, seedKeys, new Seed(modelSeed.repo(), "model", detail));
        }
```

with the helper:

```java
    private static void addSeed(List<Seed> seeds, Set<String> seedKeys, Seed seed) {
        if (seedKeys.add(seed.repo() + " " + seed.source() + " " + seed.detail())) {
            seeds.add(seed);
        }
    }
```

(Everything downstream — roots, root reasons, excluded — already works off this list and the untouched `scan` sets; the confirmed candidate lands in `affected`, so `excluded` drops it exactly as before.)

- [ ] **Step 3: Run — expect PASS** (all existing ImpactAnalysis tests must stay green).
Run: `./gradlew :sdd-plan:test`

- [ ] **Step 4: Commit**

```bash
git add sdd-plan/src
git commit -m "feat: carry fts provenance into confirmed seeds and dedupe seed triples"
```

---

### Task 3: ExecutionOrder — Kahn over the affected subgraph with SCC units

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/gen/ExecutionOrder.java`
- Test: `sdd-plan/src/test/java/sdd/plan/gen/ExecutionOrderTest.java`

**Interfaces:**
- Produces: `public final class ExecutionOrder` with nested `public record Unit(List<String> repos)` (List.copyOf; singleton = normal repo, multi = co-scheduled cycle, members sorted) and `public static List<Unit> order(Jdbi jdbi, ImpactResult result)` — providers before consumers; ready units alphabetical by first member; REST/Kafka provider→consumer pseudo-edges as tie-breaks; deadlocked remainder appended alphabetically.
- Consumes: `ImpactResult.affected()/cycles()` (cycle strings are `"a <-> b"`, members sorted — produced by Closure), `v_repo_dep_edge`, the same REST/Kafka pair queries Closure's contracts stage uses.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.gen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionOrderTest {
    @TempDir Path ws;
    private Database db;

    // lib-core <- lib-api <- svc-orders (gradle chain); svc-billing REST-calls svc-orders;
    // platform is an isolated bom-site node
    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-api','/w/2','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-orders','/w/3','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-billing','/w/4','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('platform','/w/5','LIBRARY')");
            for (int i = 1; i <= 5; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (3,'com.acme','lib-api','compileClasspath','1.0','DIRECT','PINNED',1,2)");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (3,'OrdersController','get','GET','/orders/{id}','/orders/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (4,'FEIGN','OrdersClient','site','GET','/orders/{id}','/orders/{}','orders','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (1,1,'HIGH','FEIGN_NAME_PATH')");
        });
    }

    private static AffectedRepo repo(String name, String role, String annotation) {
        return new AffectedRepo(name, role, annotation, List.of(), List.of());
    }

    private static ImpactResult result(List<AffectedRepo> affected, List<String> cycles) {
        return new ImpactResult(List.of(), affected, List.of(), cycles, List.of(), List.of(), List.of());
    }

    @Test
    void providersComeFirstContractConsumersAfterTheirProvidersIsolatedNodesAlphabetical() {
        ImpactResult result = result(List.of(
                repo("lib-core", "seed", "SEED"),
                repo("lib-api", "dependent", "BUMP_REBUILD_ONLY"),
                repo("svc-orders", "dependent", "CODE_CHANGE_LIKELY"),
                repo("svc-billing", "contract", "PENDING_CONTRACT"),
                repo("platform", "bom-site", "BOM_DECLARATION_SITE")), List.of());

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        assertThat(order).extracting(u -> u.repos().get(0)).containsExactly(
                "lib-core", "lib-api", "platform", "svc-orders", "svc-billing");
        // lib-core and platform are both ready at the start: alphabetical => lib-core first;
        // emitting lib-core makes lib-api ready, and 'lib-api' < 'platform', so lib-api is
        // emitted before platform (newly-ready units interleave into the alphabetical pool);
        // svc-billing is held back by the REST pseudo-edge until svc-orders is emitted
    }

    @Test
    void kafkaProducerPrecedesConsumerViaPseudoEdge() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (3,1,'PRODUCER')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (5,1,'CONSUMER')");
        });
        ImpactResult result = result(List.of(
                repo("platform", "contract", "PENDING_CONTRACT"),
                repo("svc-orders", "seed", "SEED")), List.of());

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        // without the kafka pseudo-edge, 'platform' would win alphabetically — genuine pin
        assertThat(order).extracting(u -> u.repos().get(0)).containsExactly("svc-orders", "platform");
    }

    @Test
    void cycleMembersFormOneCoScheduledUnit() {
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1,'com.acme','lib-api','compileClasspath','1.0','DIRECT','PINNED',1,2)"));
        ImpactResult result = result(List.of(
                repo("lib-core", "seed", "SEED"),
                repo("lib-api", "dependent", "CODE_CHANGE_LIKELY"),
                repo("svc-orders", "dependent", "CODE_CHANGE_LIKELY")),
                List.of("lib-api <-> lib-core"));

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        assertThat(order).hasSize(2);
        assertThat(order.get(0).repos()).containsExactly("lib-api", "lib-core");
        assertThat(order.get(1).repos()).containsExactly("svc-orders");
    }

    @Test
    void pseudoEdgeDeadlockAppendsRemainderAlphabetically() {
        // two services REST-calling each other, no gradle edges: pseudo-edges form a 2-cycle
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (4,'BillingController','get','GET','/bill/{id}','/bill/{}')");
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'FEIGN','BillingClient','site','GET','/bill/{id}','/bill/{}','billing','LITERAL','raw')");
            h.execute("INSERT INTO rest_call_edge(client_id, endpoint_id, confidence, matched_by) VALUES (2,2,'HIGH','FEIGN_NAME_PATH')");
        });
        ImpactResult result = result(List.of(
                repo("svc-orders", "seed", "SEED"),
                repo("svc-billing", "contract", "PENDING_CONTRACT")), List.of());

        List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);

        assertThat(order).extracting(u -> u.repos().get(0))
                .containsExactly("svc-billing", "svc-orders");   // deadlock => alphabetical remainder
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `ExecutionOrder.java`:

```java
package sdd.plan.gen;

import org.jdbi.v3.core.Jdbi;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic execution order over the affected subgraph: Gradle edges order providers
 * before consumers; REST/Kafka provider->consumer pseudo-edges break ties (design: contract
 * provider-first); SCC cycles co-schedule as one unit (design M3). Ready units emit in
 * alphabetical order. A pseudo-edge deadlock appends the remainder alphabetically — the
 * Phase 3C-2 validator owns hard enforcement of a legal order.
 */
public final class ExecutionOrder {

    public record Unit(List<String> repos) {
        public Unit {
            repos = List.copyOf(repos);
        }
    }

    private ExecutionOrder() {
    }

    public static List<Unit> order(Jdbi jdbi, ImpactResult result) {
        Set<String> affected = new LinkedHashSet<>();
        for (AffectedRepo repo : result.affected()) {
            affected.add(repo.repo());
        }
        // collapse cycles into units keyed by their (sorted) first member
        Map<String, String> unitOf = new HashMap<>();
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (String cycle : result.cycles()) {
            List<String> cycleMembers = List.of(cycle.split(" <-> "));
            String key = cycleMembers.get(0);
            for (String member : cycleMembers) {
                unitOf.put(member, key);
            }
            members.put(key, new ArrayList<>(cycleMembers));
        }
        for (String repo : affected) {
            String key = unitOf.computeIfAbsent(repo, k -> k);
            members.computeIfAbsent(key, k -> new ArrayList<>(List.of(repo)));
        }

        // edges: provider-unit -> consumer-unit (provider must come first)
        Map<String, Set<String>> consumersOf = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String key : members.keySet()) {
            consumersOf.put(key, new LinkedHashSet<>());
            indegree.put(key, 0);
        }
        for (String[] edge : edges(jdbi, affected)) {
            String providerUnit = unitOf.get(edge[0]);
            String consumerUnit = unitOf.get(edge[1]);
            if (providerUnit.equals(consumerUnit)) {
                continue;
            }
            if (consumersOf.get(providerUnit).add(consumerUnit)) {
                indegree.merge(consumerUnit, 1, Integer::sum);
            }
        }

        TreeSet<String> ready = new TreeSet<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<Unit> order = new ArrayList<>();
        Set<String> emitted = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String key = ready.pollFirst();
            emitted.add(key);
            order.add(new Unit(members.get(key).stream().sorted().toList()));
            for (String consumer : consumersOf.get(key)) {
                if (indegree.merge(consumer, -1, Integer::sum) == 0) {
                    ready.add(consumer);
                }
            }
        }
        // pseudo-edge deadlock: append whatever remains, alphabetically
        members.keySet().stream().filter(k -> !emitted.contains(k)).sorted()
                .forEach(k -> order.add(new Unit(members.get(k).stream().sorted().toList())));
        return order;
    }

    /** [provider, consumer] pairs among affected repos: gradle edges + REST/Kafka pseudo-edges. */
    private static List<String[]> edges(Jdbi jdbi, Set<String> affected) {
        List<String[]> edges = new ArrayList<>();
        jdbi.useHandle(h -> {
            h.createQuery("""
                            SELECT rt.name AS provider, rf.name AS consumer
                            FROM v_repo_dep_edge v
                            JOIN repo rf ON rf.id = v.from_repo_id
                            JOIN repo rt ON rt.id = v.to_repo_id
                            ORDER BY rt.name, rf.name""")
                    .mapToMap().forEach(row -> edges.add(new String[]{
                            String.valueOf(row.get("provider")), String.valueOf(row.get("consumer"))}));
            h.createQuery("""
                            SELECT DISTINCT rp.name AS provider, rc.name AS consumer
                            FROM rest_call_edge ce
                            JOIN rest_client c ON c.id = ce.client_id
                            JOIN module mc ON mc.id = c.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            JOIN rest_endpoint e ON e.id = ce.endpoint_id
                            JOIN module mp ON mp.id = e.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            WHERE rc.name <> rp.name
                            ORDER BY rp.name, rc.name""")
                    .mapToMap().forEach(row -> edges.add(new String[]{
                            String.valueOf(row.get("provider")), String.valueOf(row.get("consumer"))}));
            h.createQuery("""
                            SELECT DISTINCT rp.name AS provider, rc.name AS consumer
                            FROM kafka_role prod
                            JOIN module mp ON mp.id = prod.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            JOIN kafka_role cons ON cons.topic_id = prod.topic_id AND cons.role = 'CONSUMER'
                            JOIN module mc ON mc.id = cons.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            WHERE prod.role = 'PRODUCER' AND rp.name <> rc.name
                            ORDER BY rp.name, rc.name""")
                    .mapToMap().forEach(row -> edges.add(new String[]{
                            String.valueOf(row.get("provider")), String.valueOf(row.get("consumer"))}));
        });
        return edges.stream().filter(e -> affected.contains(e[0]) && affected.contains(e[1])).toList();
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: deterministic execution order with co-scheduled cycle units"
```

---

### Task 4: OpenQuestions — deterministic detectors

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/gen/OpenQuestions.java`
- Test: `sdd-plan/src/test/java/sdd/plan/gen/OpenQuestionsTest.java`

**Interfaces:**
- Produces: `public record Question(String text, boolean blocking)` (top-level record in `sdd.plan.gen`, file `Question.java` — Task 5's Draft reuses it) and `public final class OpenQuestions` with `public static List<Question> detect(Jdbi jdbi, ImpactResult result)`.
- Detector rules (design Component 2 detectors, mapped to available signals):
  - every `result.problems()` entry → BLOCKING, text verbatim.
  - every `result.discrepancies()` entry → non-blocking, `"model/graph discrepancy: " + entry`.
  - every `result.warnings()` entry containing `"indexed with status"` → non-blocking, verbatim.
  - disconnected seed: an affected repo with role `seed` having NO `v_repo_dep_edge` edge (either direction) to another affected repo AND no REST/Kafka pair link (either direction) to another affected repo, when the affected set has more than one repo → non-blocking `"seed <repo> is disconnected from the rest of the affected set — verify the spec's scope"`.
  - unresolved callers: `SELECT count(*) FROM rest_client WHERE norm_path IS NULL` > 0 AND any affected repo owns at least one rest_endpoint → non-blocking `"<n> unresolved (DYNAMIC) REST clients exist in the estate — callers of affected endpoints may be missing (see curation report)"`.
  - unresolved kafka topics (symmetric): `SELECT count(*) FROM kafka_topic WHERE resolution = 'DYNAMIC'` > 0 AND any affected repo owns at least one kafka_role row → non-blocking `"<n> unresolved (DYNAMIC) Kafka topics exist in the estate — messaging links touching affected repos may be missing (see curation report)"`.
- Order: problems, discrepancies, status warnings, disconnected seeds (alphabetical), unresolved REST callers, unresolved kafka topics. (The renderer numbers them; the drafter's model questions are appended after these by the renderer.)

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.gen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class OpenQuestionsTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-lonely','/w/3','SERVICE')");
            for (int i = 1; i <= 3; i++) {
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + i + ",':','UNKNOWN')");
            }
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                    + "VALUES (2,'PriceController','get','GET','/price/{id}','/price/{}')");
            // one DYNAMIC client somewhere in the estate
            h.execute("INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site, http_method, uri_template, norm_path, target_hint, resolution, raw_expr) "
                    + "VALUES (3,'RESTTEMPLATE','Dyn','site','GET',NULL,NULL,NULL,'DYNAMIC','raw')");
        });
    }

    @Test
    void mapsProblemsDiscrepanciesStatusesDisconnectionAndDynamicCallers() {
        ImpactResult result = new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of(), List.of()),
                        new AffectedRepo("svc-pricing", "dependent", "CODE_CHANGE_LIKELY", List.of(), List.of()),
                        new AffectedRepo("svc-lonely", "seed", "SEED", List.of(), List.of())),
                List.of(), List.of(),
                List.of("model-only: svc-lonely (hunch)"),
                List.of("no repo covers R2"),
                List.of("affected repo svc-pricing indexed with status DEGRADED — downgrade confidence in its facts",
                        "model seeding unavailable: connection refused"));

        List<Question> questions = OpenQuestions.detect(db.jdbi(), result);

        assertThat(questions).extracting(Question::text, Question::blocking).containsExactly(
                tuple("no repo covers R2", true),
                tuple("model/graph discrepancy: model-only: svc-lonely (hunch)", false),
                tuple("affected repo svc-pricing indexed with status DEGRADED — downgrade confidence in its facts", false),
                tuple("seed svc-lonely is disconnected from the rest of the affected set — verify the spec's scope", false),
                tuple("1 unresolved (DYNAMIC) REST clients exist in the estate — callers of affected endpoints may be missing (see curation report)", false));
        // note: 'model seeding unavailable' warning is NOT a question (it lacks 'indexed with status');
        // lib-core is connected via the gradle edge to svc-pricing, so no disconnection question for it
    }

    @Test
    void dynamicKafkaTopicsRaiseAQuestionWhenAffectedReposUseKafka() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('orders.events','LITERAL')");
            h.execute("INSERT INTO kafka_topic(name, resolution) VALUES ('#{dyn}','DYNAMIC')");
            h.execute("INSERT INTO kafka_role(module_id, topic_id, role) VALUES (2,1,'PRODUCER')");
        });
        ImpactResult result = new ImpactResult(List.of(),
                List.of(new AffectedRepo("svc-pricing", "seed", "SEED", List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<Question> questions = OpenQuestions.detect(db.jdbi(), result);

        assertThat(questions).anySatisfy(q -> assertThat(q.text()).isEqualTo(
                "1 unresolved (DYNAMIC) Kafka topics exist in the estate"
                        + " — messaging links touching affected repos may be missing (see curation report)"));
    }

    @Test
    void singleRepoAffectedSetRaisesNoDisconnectionAndNoDynamicQuestionWithoutEndpoints() {
        ImpactResult result = new ImpactResult(List.of(),
                List.of(new AffectedRepo("svc-lonely", "seed", "SEED", List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<Question> questions = OpenQuestions.detect(db.jdbi(), result);

        assertThat(questions).isEmpty();   // one repo => no disconnection; svc-lonely owns no endpoints => no DYNAMIC question
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `Question.java`:

```java
package sdd.plan.gen;

import java.util.Objects;

/** One Gate-1 open question; blocking questions need written resolutions before approve (3C-2). */
public record Question(String text, boolean blocking) {
    public Question {
        Objects.requireNonNull(text);
    }
}
```

and `OpenQuestions.java`:

```java
package sdd.plan.gen;

import org.jdbi.v3.core.Jdbi;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic Open-Questions detectors (design Component 2). The drafter's model-emitted
 * questions are appended by the renderer AFTER these; blocking resolution enforcement is 3C-2.
 */
public final class OpenQuestions {

    private OpenQuestions() {
    }

    public static List<Question> detect(Jdbi jdbi, ImpactResult result) {
        List<Question> questions = new ArrayList<>();
        for (String problem : result.problems()) {
            questions.add(new Question(problem, true));
        }
        for (String discrepancy : result.discrepancies()) {
            questions.add(new Question("model/graph discrepancy: " + discrepancy, false));
        }
        for (String warning : result.warnings()) {
            if (warning.contains("indexed with status")) {
                questions.add(new Question(warning, false));
            }
        }
        disconnectedSeeds(jdbi, result, questions);
        dynamicCallers(jdbi, result, questions);
        dynamicKafka(jdbi, result, questions);
        return questions;
    }

    private static void disconnectedSeeds(Jdbi jdbi, ImpactResult result, List<Question> questions) {
        Set<String> affected = new LinkedHashSet<>();
        for (AffectedRepo repo : result.affected()) {
            affected.add(repo.repo());
        }
        if (affected.size() < 2) {
            return;
        }
        Set<String> connected = new LinkedHashSet<>();
        jdbi.useHandle(h -> {
            for (Map<String, Object> row : h.createQuery("""
                            SELECT rf.name AS a, rt.name AS b FROM v_repo_dep_edge v
                            JOIN repo rf ON rf.id = v.from_repo_id
                            JOIN repo rt ON rt.id = v.to_repo_id""")
                    .mapToMap().list()) {
                markPair(connected, affected, row);
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rc.name AS a, rp.name AS b
                            FROM rest_call_edge ce
                            JOIN rest_client c ON c.id = ce.client_id
                            JOIN module mc ON mc.id = c.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            JOIN rest_endpoint e ON e.id = ce.endpoint_id
                            JOIN module mp ON mp.id = e.module_id
                            JOIN repo rp ON rp.id = mp.repo_id""")
                    .mapToMap().list()) {
                markPair(connected, affected, row);
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT DISTINCT rp.name AS a, rc.name AS b
                            FROM kafka_role prod
                            JOIN module mp ON mp.id = prod.module_id
                            JOIN repo rp ON rp.id = mp.repo_id
                            JOIN kafka_role cons ON cons.topic_id = prod.topic_id AND cons.role = 'CONSUMER'
                            JOIN module mc ON mc.id = cons.module_id
                            JOIN repo rc ON rc.id = mc.repo_id
                            WHERE prod.role = 'PRODUCER'""")
                    .mapToMap().list()) {
                markPair(connected, affected, row);
            }
        });
        Set<String> lonely = new TreeSet<>();
        for (AffectedRepo repo : result.affected()) {
            if (repo.role().equals("seed") && !connected.contains(repo.repo())) {
                lonely.add(repo.repo());
            }
        }
        for (String repo : lonely) {
            questions.add(new Question(
                    "seed " + repo + " is disconnected from the rest of the affected set — verify the spec's scope",
                    false));
        }
    }

    private static void markPair(Set<String> connected, Set<String> affected, Map<String, Object> row) {
        String a = String.valueOf(row.get("a"));
        String b = String.valueOf(row.get("b"));
        if (!a.equals(b) && affected.contains(a) && affected.contains(b)) {
            connected.add(a);
            connected.add(b);
        }
    }

    private static void dynamicCallers(Jdbi jdbi, ImpactResult result, List<Question> questions) {
        List<String> affectedNames = result.affected().stream().map(AffectedRepo::repo).toList();
        if (affectedNames.isEmpty()) {
            return;
        }
        boolean anyEndpoints = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM rest_endpoint e
                        JOIN module m ON m.id = e.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name IN (<names>)""")
                .bindList("names", affectedNames).mapTo(Integer.class).one()) > 0;
        if (!anyEndpoints) {
            return;
        }
        int dynamic = jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM rest_client WHERE norm_path IS NULL").mapTo(Integer.class).one());
        if (dynamic > 0) {
            questions.add(new Question(dynamic + " unresolved (DYNAMIC) REST clients exist in the estate"
                    + " — callers of affected endpoints may be missing (see curation report)", false));
        }
    }

    private static void dynamicKafka(Jdbi jdbi, ImpactResult result, List<Question> questions) {
        List<String> affectedNames = result.affected().stream().map(AffectedRepo::repo).toList();
        if (affectedNames.isEmpty()) {
            return;
        }
        boolean anyKafka = jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM kafka_role kr
                        JOIN module m ON m.id = kr.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name IN (<names>)""")
                .bindList("names", affectedNames).mapTo(Integer.class).one()) > 0;
        if (!anyKafka) {
            return;
        }
        int dynamic = jdbi.withHandle(h -> h.createQuery(
                "SELECT count(*) FROM kafka_topic WHERE resolution = 'DYNAMIC'").mapTo(Integer.class).one());
        if (dynamic > 0) {
            questions.add(new Question(dynamic + " unresolved (DYNAMIC) Kafka topics exist in the estate"
                    + " — messaging links touching affected repos may be missing (see curation report)", false));
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: deterministic open-question detectors for plan.md"
```

---

### Task 5: PlanDrafter — the drafting model call with containment

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/gen/PlanDrafter.java`
- Test: `sdd-plan/src/test/java/sdd/plan/gen/PlanDrafterTest.java`

**Interfaces:**
- Produces (Task 6/7 depend on these EXACT shapes), all nested in `PlanDrafter`:
  - `public record DraftStep(String repo, List<String> covers, String subSpec, List<String> files, List<String> providesContracts, List<String> consumesContracts, String versionAction, List<String> verification)`
  - `public record DraftContract(String id, String kind, String provider, List<String> consumers, String body)`
  - `public record Draft(String summary, List<DraftStep> steps, List<DraftContract> contracts, List<Question> questions, List<String> notes, boolean unavailable)`
  - `public static Draft draft(Jdbi jdbi, NormalizedSpec spec, ImpactResult result, List<ExecutionOrder.Unit> order, ChatModel planner, String modelName, int maxTokens)` — the caller passes the already-computed execution order (no duplicate computation)
  - `static String composeInput(Jdbi jdbi, NormalizedSpec spec, ImpactResult result, List<ExecutionOrder.Unit> order)` (package-private, for prompt tests)
- Model contract (SYSTEM_PROMPT): ONE JSON object `{"summary": string, "questions": [{"text": string, "blocking": bool}], "contracts": [{"id": string, "kind": "java-api"|"rest"|"kafka", "provider": string, "consumers": [string], "body": string}], "repo_steps": [{"repo": string, "covers": [string], "sub_spec": string, "files": [string], "provides_contracts": [string], "consumes_contracts": [string], "version_action": "none"|"patch"|"minor"|"major", "verification": [string]}]}`.
- Validation (never trusted): steps for repos outside the affected set dropped + note `drafter named unknown repo '<r>' — step dropped`; covers filtered to spec requirement ids (unknown → note); provides/consumes filtered to DEFINED contract ids (unknown → note); version_action outside the enum → `none` + note; contract kind outside the enum → `java-api` + note; contract provider outside the affected set → contract dropped + note; a model file path not present in `java_type.file_path` for that repo → note `step file '<f>' not found in the knowledge base for <repo>`, kept.
- Containment: ModelException / `length` / null content / malformed / non-object → `Draft("", List.of(), List.of(), List.of(new Question("plan drafting unavailable: <detail> — rerun sdd plan", true)), List.of(), true)`.
- composeInput sections, deterministic: `# Specification` (SpecRenderer.render) + `# Impact` (one line per affected repo: `- <repo> | <role> | <annotation> | covers: ... | why: reason1; reason2`) + `# Execution order` (`1. a` / `2. b + c (co-scheduled)`) + `# Knowledge-base evidence` per affected repo: types (`- <fqcn> (<kind>) @ <file_path>`; `ORDER BY is_api DESC, fqcn`, LIMIT 25), API members of `is_api=1` types (`- <fqcn>#<signature>: <return_type>`; `ORDER BY fqcn, signature`, LIMIT 40), endpoints (`- <verb> <norm_path> req=<request_type> res=<response_type>`; `ORDER BY norm_path, http_method`, LIMIT 25); per-repo evidence capped at `EVIDENCE_CAP = 4000` chars (truncate + `"…(truncated)"`). Temperature 0.15, empty tools, maxTokens passed through.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.gen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDrafterTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.LoyaltyTier','CLASS',1,'src/main/java/com/acme/LoyaltyTier.java')");
            h.execute("INSERT INTO api_member(type_id, name, signature, return_type) "
                    + "VALUES (1,'tierFor','tierFor(String)','Tier')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path, request_type, response_type) "
                    + "VALUES (2,'PriceController','get','GET','/price/{sku}','/price/{}',NULL,'PriceResponse')");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "tier pricing")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ImpactResult impact() {
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"), List.of("touchpoint class:LoyaltyTier")),
                        new AffectedRepo("svc-pricing", "dependent", "CODE_CHANGE_LIKELY", List.of(), List.of("depends on lib-core (PINNED)"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<ExecutionOrder.Unit> order() {
        return List.of(new ExecutionOrder.Unit(List.of("lib-core")),
                new ExecutionOrder.Unit(List.of("svc-pricing")));
    }

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
    }

    private static final String GOOD_JSON = """
            {"summary": "Add tier lookups to lib-core and apply them in svc-pricing.",
             "questions": [{"text": "Which tiers exist?", "blocking": false}],
             "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                            "consumers": ["svc-pricing"],
                            "body": "class: com.acme.LoyaltyTier\\nmethod: Tier tierFor(String customerId)"}],
             "repo_steps": [
               {"repo": "lib-core", "covers": ["R1", "R9"], "sub_spec": "Add tierFor lookup.",
                "files": ["src/main/java/com/acme/LoyaltyTier.java"],
                "provides_contracts": ["C-1"], "consumes_contracts": [],
                "version_action": "minor", "verification": ["./gradlew test"]},
               {"repo": "svc-pricing", "covers": [], "sub_spec": "Apply tier spread.",
                "files": ["src/main/java/com/acme/Missing.java"],
                "provides_contracts": [], "consumes_contracts": ["C-1", "C-9"],
                "version_action": "shipit", "verification": ["./gradlew test"]},
               {"repo": "ghost-repo", "covers": [], "sub_spec": "x", "files": [],
                "provides_contracts": [], "consumes_contracts": [], "version_action": "none",
                "verification": []}]}""";

    @Test
    void promptCarriesSpecImpactOrderAndKbEvidence() {
        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact(),
                ExecutionOrder.order(db.jdbi(), impact()));

        assertThat(input).contains("- R1: tier pricing")
                .contains("- lib-core | seed | SEED | covers: R1 | why: touchpoint class:LoyaltyTier")
                .contains("1. lib-core").contains("2. svc-pricing")
                .contains("- com.acme.LoyaltyTier (CLASS) @ src/main/java/com/acme/LoyaltyTier.java")
                .contains("- com.acme.LoyaltyTier#tierFor(String): Tier")
                .contains("- GET /price/{} req=null res=PriceResponse");
    }

    @Test
    void validatesEveryUntrustedFieldWithNotes() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(), planner, "m", 4096);

        assertThat(draft.unavailable()).isFalse();
        assertThat(draft.summary()).startsWith("Add tier lookups");
        assertThat(draft.questions()).containsExactly(new Question("Which tiers exist?", false));
        assertThat(draft.contracts()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("C-1");
            assertThat(c.body()).contains("tierFor(String customerId)");
        });
        assertThat(draft.steps()).hasSize(2);   // ghost-repo dropped
        PlanDrafter.DraftStep libCore = draft.steps().get(0);
        assertThat(libCore.covers()).containsExactly("R1");                     // R9 filtered
        PlanDrafter.DraftStep pricing = draft.steps().get(1);
        assertThat(pricing.consumesContracts()).containsExactly("C-1");         // C-9 filtered
        assertThat(pricing.versionAction()).isEqualTo("none");                  // 'shipit' coerced
        assertThat(draft.notes()).anySatisfy(n -> assertThat(n).contains("ghost-repo"))
                .anySatisfy(n -> assertThat(n).contains("R9"))
                .anySatisfy(n -> assertThat(n).contains("C-9"))
                .anySatisfy(n -> assertThat(n).contains("shipit"))
                .anySatisfy(n -> assertThat(n).contains("Missing.java"));
        assertThat(planner.requests()).singleElement().satisfies(r ->
                assertThat(r.maxTokens()).isEqualTo(4096));
    }

    @Test
    void fencedJsonResponseIsUnwrapped() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(
                "```json\n{\"summary\": \"S.\", \"questions\": [], \"contracts\": [], \"repo_steps\": []}\n```",
                "stop")));

        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(),
                planner, "m", 256);

        assertThat(draft.unavailable()).isFalse();
        assertThat(draft.summary()).isEqualTo("S.");
    }

    @Test
    void perRepoEvidenceIsCapped() {
        db.jdbi().useHandle(h -> {
            for (int i = 0; i < 25; i++) {
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) VALUES "
                        + "(1,'com.acme.p" + i + "." + "X".repeat(300) + i + "','CLASS',0,'src/"
                        + "y".repeat(300) + i + ".java')");
            }
        });

        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact(),
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))));

        assertThat(input).contains("…(truncated)");
        int start = input.indexOf("## lib-core");
        int end = input.indexOf("## svc-pricing");
        assertThat(end - start).isLessThan(PlanDrafter.EVIDENCE_CAP + 200);
    }

    @Test
    void allFailureChannelsDegradeToABlockingQuestion() {
        for (ChatResponse bad : List.of(response("{", "length"), response("not json", "stop"),
                response("[1,2]", "stop"))) {
            PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(),
                    new ScriptedChatModel(List.of(bad)), "m", 16);
            assertThat(draft.unavailable()).as(bad.message().content()).isTrue();
            assertThat(draft.questions()).singleElement().satisfies(q -> {
                assertThat(q.blocking()).isTrue();
                assertThat(q.text()).startsWith("plan drafting unavailable: ").endsWith("— rerun sdd plan");
            });
        }
        sdd.core.llm.ChatModel down = req -> {
            throw new sdd.core.llm.ModelException("connection refused", 0);
        };
        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(), down, "m", 16);
        assertThat(draft.unavailable()).isTrue();
        assertThat(draft.questions().get(0).text()).contains("connection refused");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `PlanDrafter.java`:

```java
package sdd.plan.gen;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.SpecRenderer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plan-drafting model call (the only model call Phase 3C-1 adds). Output is validated
 * field-by-field against the affected set, the spec's requirement ids, and the KB — never
 * trusted. Total failure degrades to an unavailable Draft whose single BLOCKING question
 * tells the human to rerun; every deterministic plan.md section renders regardless.
 */
public final class PlanDrafter {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int EVIDENCE_CAP = 4000;
    private static final Set<String> VERSION_ACTIONS = Set.of("none", "patch", "minor", "major");
    private static final Set<String> CONTRACT_KINDS = Set.of("java-api", "rest", "kafka");
    static final String SYSTEM_PROMPT = """
            You draft the repo-by-repo implementation plan for a feature specification across a \
            multi-repo estate. You receive the spec, the impact analysis (which repos and why), \
            the execution order, and knowledge-base evidence (real classes, files, API members, \
            endpoints). Return exactly ONE JSON object, no markdown fences:
            {"summary": string,
             "questions": [{"text": string, "blocking": boolean}, ...],
             "contracts": [{"id": string, "kind": "java-api"|"rest"|"kafka", "provider": string,
                            "consumers": [string, ...], "body": string}, ...],
             "repo_steps": [{"repo": string, "covers": [requirement ids], "sub_spec": string,
                             "files": [string, ...], "provides_contracts": [contract ids],
                             "consumes_contracts": [contract ids],
                             "version_action": "none"|"patch"|"minor"|"major",
                             "verification": [string, ...]}, ...]}
            Rules:
            - One repo_steps entry per affected repo that needs work; name only affected repos.
            - Name only files and classes present in the evidence; contracts' bodies are concrete
              interface deltas (java signatures, REST verb+path+types, topic+payload).
            - Every contract referenced by a step must be defined in "contracts".
            - Anything uncertain becomes a question, not an invention.
            """;

    public record DraftStep(String repo, List<String> covers, String subSpec, List<String> files,
                            List<String> providesContracts, List<String> consumesContracts,
                            String versionAction, List<String> verification) {
        public DraftStep {
            covers = List.copyOf(covers);
            files = List.copyOf(files);
            providesContracts = List.copyOf(providesContracts);
            consumesContracts = List.copyOf(consumesContracts);
            verification = List.copyOf(verification);
        }
    }

    public record DraftContract(String id, String kind, String provider, List<String> consumers,
                                String body) {
        public DraftContract {
            consumers = List.copyOf(consumers);
        }
    }

    public record Draft(String summary, List<DraftStep> steps, List<DraftContract> contracts,
                        List<Question> questions, List<String> notes, boolean unavailable) {
        public Draft {
            steps = List.copyOf(steps);
            contracts = List.copyOf(contracts);
            questions = List.copyOf(questions);
            notes = List.copyOf(notes);
        }
    }

    private PlanDrafter() {
    }

    public static Draft draft(Jdbi jdbi, NormalizedSpec spec, ImpactResult result,
                              List<ExecutionOrder.Unit> order, ChatModel planner,
                              String modelName, int maxTokens) {
        String input = composeInput(jdbi, spec, result, order);
        ChatResponse response;
        try {
            response = planner.complete(new ChatRequest(modelName,
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
                    List.of(), maxTokens, 0.15));
        } catch (ModelException e) {
            return unavailable(e.getMessage());
        }
        if ("length".equals(response.finishReason())) {
            return unavailable("response truncated (finish_reason=length)");
        }
        return parse(jdbi, spec, result, response.message().content());
    }

    private static Draft unavailable(String detail) {
        return new Draft("", List.of(), List.of(),
                List.of(new Question("plan drafting unavailable: " + detail + " — rerun sdd plan", true)),
                List.of(), true);
    }

    private static Draft parse(Jdbi jdbi, NormalizedSpec spec, ImpactResult result, String content) {
        if (content == null) {
            return unavailable("empty response");
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
            return unavailable("response is not valid JSON");
        }
        if (!root.isObject()) {
            return unavailable("response is not a JSON object");
        }

        Set<String> affected = new LinkedHashSet<>();
        for (AffectedRepo repo : result.affected()) {
            affected.add(repo.repo());
        }
        Set<String> requirementIds = new LinkedHashSet<>(
                spec.requirements().stream().map(SpecItem::id).toList());
        List<String> notes = new ArrayList<>();

        List<DraftContract> contracts = new ArrayList<>();
        Set<String> contractIds = new LinkedHashSet<>();
        for (JsonNode node : root.path("contracts")) {
            String id = node.path("id").asText().strip();
            String provider = node.path("provider").asText().strip();
            if (id.isBlank() || !affected.contains(provider)) {
                notes.add("drafter contract '" + id + "' has unknown provider '" + provider + "' — dropped");
                continue;
            }
            String kind = node.path("kind").asText();
            if (!CONTRACT_KINDS.contains(kind)) {
                notes.add("drafter contract '" + id + "' kind '" + kind + "' coerced to java-api");
                kind = "java-api";
            }
            List<String> consumers = new ArrayList<>();
            for (JsonNode consumer : node.path("consumers")) {
                String name = consumer.asText().strip();
                if (affected.contains(name)) {
                    consumers.add(name);
                } else {
                    notes.add("drafter contract '" + id + "' names unknown consumer '" + name + "' — dropped");
                }
            }
            contracts.add(new DraftContract(id, kind, provider, consumers,
                    node.path("body").asText()));
            contractIds.add(id);
        }

        List<DraftStep> steps = new ArrayList<>();
        for (JsonNode node : root.path("repo_steps")) {
            String repo = node.path("repo").asText().strip();
            if (!affected.contains(repo)) {
                notes.add("drafter named unknown repo '" + repo + "' — step dropped");
                continue;
            }
            List<String> covers = filtered(node, "covers", requirementIds, notes,
                    "step " + repo + " claims unknown requirement");
            List<String> provides = filtered(node, "provides_contracts", contractIds, notes,
                    "step " + repo + " references undefined contract");
            List<String> consumes = filtered(node, "consumes_contracts", contractIds, notes,
                    "step " + repo + " references undefined contract");
            String versionAction = node.path("version_action").asText();
            if (!VERSION_ACTIONS.contains(versionAction)) {
                notes.add("step " + repo + " version_action '" + versionAction + "' coerced to none");
                versionAction = "none";
            }
            List<String> files = new ArrayList<>();
            for (JsonNode file : node.path("files")) {
                String path = file.asText().strip();
                if (!path.isBlank()) {
                    files.add(path);
                    if (!fileKnown(jdbi, repo, path)) {
                        notes.add("step file '" + path + "' not found in the knowledge base for " + repo);
                    }
                }
            }
            List<String> verification = new ArrayList<>();
            for (JsonNode v : node.path("verification")) {
                if (!v.asText().isBlank()) {
                    verification.add(v.asText());
                }
            }
            steps.add(new DraftStep(repo, covers, node.path("sub_spec").asText(), files,
                    provides, consumes, versionAction, verification));
        }

        List<Question> questions = new ArrayList<>();
        for (JsonNode node : root.path("questions")) {
            String text = node.path("text").asText().strip();
            if (!text.isBlank()) {
                questions.add(new Question(text, node.path("blocking").asBoolean(false)));
            }
        }
        return new Draft(root.path("summary").asText().strip(), steps, contracts, questions,
                notes, false);
    }

    private static List<String> filtered(JsonNode node, String field, Set<String> allowed,
                                         List<String> notes, String notePrefix) {
        List<String> kept = new ArrayList<>();
        for (JsonNode value : node.path(field)) {
            String id = value.asText().strip();
            if (allowed.contains(id)) {
                kept.add(id);
            } else if (!id.isBlank()) {
                notes.add(notePrefix + " '" + id + "' — dropped");
            }
        }
        return kept;
    }

    private static boolean fileKnown(Jdbi jdbi, String repo, String path) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT count(*) FROM java_type t
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r AND t.file_path = :p""")
                .bind("r", repo).bind("p", path).mapTo(Integer.class).one()) > 0;
    }

    static String composeInput(Jdbi jdbi, NormalizedSpec spec, ImpactResult result,
                               List<ExecutionOrder.Unit> order) {
        StringBuilder input = new StringBuilder("# Specification\n\n");
        input.append(SpecRenderer.render(spec));
        input.append("\n# Impact\n\n");
        for (AffectedRepo repo : result.affected()) {
            input.append("- ").append(repo.repo()).append(" | ").append(repo.role())
                    .append(" | ").append(repo.annotation())
                    .append(" | covers: ").append(String.join(",", repo.covers()))
                    .append(" | why: ").append(String.join("; ", repo.reasons())).append('\n');
        }
        input.append("\n# Execution order\n\n");
        for (int i = 0; i < order.size(); i++) {
            ExecutionOrder.Unit unit = order.get(i);
            input.append(i + 1).append(". ").append(String.join(" + ", unit.repos()));
            if (unit.repos().size() > 1) {
                input.append(" (co-scheduled)");
            }
            input.append('\n');
        }
        input.append("\n# Knowledge-base evidence\n");
        for (AffectedRepo repo : result.affected()) {
            input.append("\n## ").append(repo.repo()).append('\n');
            input.append(evidence(jdbi, repo.repo()));
        }
        return input.toString();
    }

    private static String evidence(Jdbi jdbi, String repo) {
        StringBuilder evidence = new StringBuilder();
        jdbi.useHandle(h -> {
            for (Map<String, Object> row : h.createQuery("""
                            SELECT t.fqcn AS fqcn, t.kind AS kind, t.file_path AS path
                            FROM java_type t
                            JOIN module m ON m.id = t.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r ORDER BY t.is_api DESC, t.fqcn LIMIT 25""")
                    .bind("r", repo).mapToMap().list()) {
                evidence.append("- ").append(row.get("fqcn")).append(" (").append(row.get("kind"))
                        .append(") @ ").append(row.get("path")).append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT jt.fqcn AS fqcn, am.signature AS sig, am.return_type AS ret
                            FROM api_member am
                            JOIN java_type jt ON jt.id = am.type_id
                            JOIN module m ON m.id = jt.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r AND jt.is_api = 1
                            ORDER BY jt.fqcn, am.signature LIMIT 40""")
                    .bind("r", repo).mapToMap().list()) {
                evidence.append("- ").append(row.get("fqcn")).append('#').append(row.get("sig"))
                        .append(": ").append(row.get("ret")).append('\n');
            }
            for (Map<String, Object> row : h.createQuery("""
                            SELECT e.http_method AS verb, e.norm_path AS norm,
                                   e.request_type AS req, e.response_type AS res
                            FROM rest_endpoint e
                            JOIN module m ON m.id = e.module_id
                            JOIN repo r ON r.id = m.repo_id
                            WHERE r.name = :r ORDER BY e.norm_path, e.http_method LIMIT 25""")
                    .bind("r", repo).mapToMap().list()) {
                evidence.append("- ").append(row.get("verb")).append(' ').append(row.get("norm"))
                        .append(" req=").append(row.get("req")).append(" res=").append(row.get("res"))
                        .append('\n');
            }
        });
        if (evidence.length() > EVIDENCE_CAP) {
            evidence.setLength(EVIDENCE_CAP);
            evidence.append("…(truncated)\n");
        }
        return evidence.toString();
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: plan-drafting model call with field-level validation and containment"
```

---

### Task 6: PlanMdRenderer

**Files:**
- Create: `sdd-plan/src/main/java/sdd/plan/gen/PlanMdRenderer.java`
- Test: `sdd-plan/src/test/java/sdd/plan/gen/PlanMdRendererTest.java`

**Interfaces:**
- Produces: `public static String render(NormalizedSpec spec, ImpactResult result, List<ExecutionOrder.Unit> order, List<Question> detectorQuestions, PlanDrafter.Draft draft)` on `public final class PlanMdRenderer`.
- Layout (exact — 3C-2's parser will pin it):

```markdown
---
spec: <spec.id()>
plan_version: 1
---

## Summary
<draft.summary(), or "Impact analysis for '<spec.title()>': <n> repos affected." when blank>

## Open Questions
- Q1 [blocking]: <text>          (detector questions first, then draft questions; "- none" if empty)

## Affected Repos
- <repo> — <role>/<annotation> — covers: <ids or "-"> — why: <reason1; reason2>

## Excluded Candidates
- <repo> — <detail>              ("- none" if empty)

## Execution Order
1. <repo>
2. <a> + <b> (co-scheduled)

## Interface Contracts
### <id> (<kind>) — <provider> -> <consumer1, consumer2>
```yaml
<body verbatim, with any ``` inside replaced by ''' >
```
                                  ("- none" if no contracts)

## Repo Steps
### <repo>
- covers: <ids or "-">
- version_action: <action>
- provides: <ids or "-">
- consumes: <ids or "-">
- files:
  - <path>
- verification:
  - <cmd>

<sub_spec prose>
                                  ("- none (drafting unavailable)" under Repo Steps when draft.unavailable(); "- none" when available but empty)

## Generation Notes
- <draft note / result warning>  ("- none" if empty)
```

- Question numbering `Q1..Qn` assigned in emission order. Generation Notes = `draft.notes()` then `result.warnings()`. Body fence-collision guard: `body.replace("```", "'''")`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.plan.gen;

import org.junit.jupiter.api.Test;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanMdRendererTest {

    private static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-9", "Tier pricing", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ImpactResult impact() {
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"),
                                List.of("touchpoint class:LoyaltyTier", "model covers R1; owns it")),
                        new AffectedRepo("svc-pricing", "dependent", "CODE_CHANGE_LIKELY",
                                List.of(), List.of("depends on lib-core (PINNED)"))),
                List.of(new Seed("svc-legacy", "fts", "R1 hit: X — not selected by model, not required by graph")),
                List.of(), List.of(), List.of(), List.of("model seeding unavailable: down"));
    }

    @Test
    void rendersEverySectionInOrderWithNumberedQuestions() {
        PlanDrafter.Draft draft = new PlanDrafter.Draft("Do the thing.",
                List.of(new PlanDrafter.DraftStep("lib-core", List.of("R1"), "Add lookup.",
                        List.of("src/main/java/A.java"), List.of("C-1"), List.of(), "minor",
                        List.of("./gradlew test"))),
                List.of(new PlanDrafter.DraftContract("C-1", "java-api", "lib-core",
                        List.of("svc-pricing"), "method: Tier tierFor(String)\n```evil```")),
                List.of(new Question("From the model?", false)),
                List.of("drafter note"), false);
        List<Question> detectors = List.of(new Question("no repo covers R1", true));
        List<ExecutionOrder.Unit> order = List.of(
                new ExecutionOrder.Unit(List.of("lib-core")),
                new ExecutionOrder.Unit(List.of("a", "b")));

        String md = PlanMdRenderer.render(spec(), impact(), order, detectors, draft);

        assertThat(md).startsWith("---\nspec: SPEC-9\nplan_version: 1\n---\n");
        int summary = md.indexOf("## Summary");
        int questions = md.indexOf("## Open Questions");
        int affected = md.indexOf("## Affected Repos");
        int excluded = md.indexOf("## Excluded Candidates");
        int orderIdx = md.indexOf("## Execution Order");
        int contracts = md.indexOf("## Interface Contracts");
        int steps = md.indexOf("## Repo Steps");
        int notes = md.indexOf("## Generation Notes");
        assertThat(summary).isLessThan(questions);
        assertThat(questions).isLessThan(affected);
        assertThat(affected).isLessThan(excluded);
        assertThat(excluded).isLessThan(orderIdx);
        assertThat(orderIdx).isLessThan(contracts);
        assertThat(contracts).isLessThan(steps);
        assertThat(steps).isLessThan(notes);

        assertThat(md).contains("Do the thing.")
                .contains("- Q1 [blocking]: no repo covers R1")
                .contains("- Q2: From the model?")
                .contains("- lib-core — seed/SEED — covers: R1 — why: touchpoint class:LoyaltyTier; model covers R1; owns it")
                .contains("- svc-pricing — dependent/CODE_CHANGE_LIKELY — covers: - — why: depends on lib-core (PINNED)")
                .contains("- svc-legacy — R1 hit: X — not selected by model, not required by graph")
                .contains("1. lib-core").contains("2. a + b (co-scheduled)")
                .contains("### C-1 (java-api) — lib-core -> svc-pricing")
                .contains("method: Tier tierFor(String)")
                .contains("'''evil'''")
                .doesNotContain("```evil```")
                .contains("### lib-core")
                .contains("- version_action: minor")
                .contains("Add lookup.")
                .contains("- drafter note")
                .contains("- model seeding unavailable: down");
    }

    @Test
    void drafterTextCannotForgeStructure() {
        PlanDrafter.Draft hostile = new PlanDrafter.Draft(
                "Sneaky.\n## Repo Steps\n- fake",
                List.of(new PlanDrafter.DraftStep("lib-core", List.of(), "line\n# Fake heading\n---\nrest",
                        List.of(), List.of(), List.of(), "none", List.of())),
                List.of(),
                List.of(new Question("q\n## Affected Repos", false)),
                List.of("note\n---"), false);

        String md = PlanMdRenderer.render(spec(), impact(),
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), hostile);

        assertThat(md.lines().filter(l -> l.startsWith("## ")).count())
                .isEqualTo(8);                                            // only the renderer's own sections
        assertThat(md.lines().filter(l -> l.equals("---")).count())
                .isEqualTo(2);                                            // only the front-matter pair
        assertThat(md).contains("Sneaky. ## Repo Steps - fake")            // collapsed, not structural
                .contains("- Q1: q ## Affected Repos")
                .contains("line\nFake heading\n—\nrest")
                .contains("- note ---");
    }

    @Test
    void unavailableDraftStillRendersDeterministicSections() {
        PlanDrafter.Draft draft = new PlanDrafter.Draft("", List.of(), List.of(),
                List.of(new Question("plan drafting unavailable: down — rerun sdd plan", true)),
                List.of(), true);

        String md = PlanMdRenderer.render(spec(), impact(),
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), List.of(), draft);

        assertThat(md).contains("## Summary")
                .contains("Impact analysis for 'Tier pricing': 2 repos affected.")
                .contains("- Q1 [blocking]: plan drafting unavailable: down — rerun sdd plan")
                .contains("## Interface Contracts\n- none")
                .contains("## Repo Steps\n- none (drafting unavailable)");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Then implement `PlanMdRenderer.java`:

```java
package sdd.plan.gen;

import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.impact.Seed;
import sdd.plan.spec.NormalizedSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the Gate-1 plan.md. Deterministic given its inputs; the drafted sections degrade
 * to "- none (drafting unavailable)" while every deterministic section always renders.
 * Phase 3C-2's parser pins this exact layout.
 */
public final class PlanMdRenderer {

    private PlanMdRenderer() {
    }

    public static String render(NormalizedSpec spec, ImpactResult result,
                                List<ExecutionOrder.Unit> order, List<Question> detectorQuestions,
                                PlanDrafter.Draft draft) {
        StringBuilder md = new StringBuilder();
        md.append("---\nspec: ").append(spec.id()).append("\nplan_version: 1\n---\n");

        md.append("\n## Summary\n");
        String summary = inline(draft.summary());
        md.append(summary.isBlank()
                ? "Impact analysis for '" + spec.title() + "': " + result.affected().size() + " repos affected."
                : summary).append('\n');

        md.append("\n## Open Questions\n");
        List<Question> questions = new ArrayList<>(detectorQuestions);
        questions.addAll(draft.questions());
        if (questions.isEmpty()) {
            md.append("- none\n");
        } else {
            for (int i = 0; i < questions.size(); i++) {
                Question q = questions.get(i);
                md.append("- Q").append(i + 1).append(q.blocking() ? " [blocking]: " : ": ")
                        .append(inline(q.text())).append('\n');
            }
        }

        md.append("\n## Affected Repos\n");
        for (AffectedRepo repo : result.affected()) {
            md.append("- ").append(repo.repo()).append(" — ").append(repo.role()).append('/')
                    .append(repo.annotation())
                    .append(" — covers: ").append(repo.covers().isEmpty() ? "-" : String.join(",", repo.covers()))
                    .append(" — why: ").append(String.join("; ", repo.reasons())).append('\n');
        }

        md.append("\n## Excluded Candidates\n");
        if (result.excluded().isEmpty()) {
            md.append("- none\n");
        } else {
            for (Seed seed : result.excluded()) {
                md.append("- ").append(seed.repo()).append(" — ").append(seed.detail()).append('\n');
            }
        }

        md.append("\n## Execution Order\n");
        for (int i = 0; i < order.size(); i++) {
            ExecutionOrder.Unit unit = order.get(i);
            md.append(i + 1).append(". ").append(String.join(" + ", unit.repos()));
            if (unit.repos().size() > 1) {
                md.append(" (co-scheduled)");
            }
            md.append('\n');
        }

        md.append("\n## Interface Contracts\n");
        if (draft.contracts().isEmpty()) {
            md.append(draft.unavailable() ? "- none (drafting unavailable)\n" : "- none\n");
        } else {
            for (PlanDrafter.DraftContract contract : draft.contracts()) {
                md.append("\n### ").append(inline(contract.id())).append(" (").append(contract.kind())
                        .append(") — ").append(contract.provider()).append(" -> ")
                        .append(String.join(", ", contract.consumers())).append('\n');
                md.append("```yaml\n").append(contract.body().replace("```", "'''"))
                        .append("\n```\n");
            }
        }

        md.append("\n## Repo Steps\n");
        if (draft.steps().isEmpty()) {
            md.append(draft.unavailable() ? "- none (drafting unavailable)\n" : "- none\n");
        } else {
            for (PlanDrafter.DraftStep step : draft.steps()) {
                md.append("\n### ").append(step.repo()).append('\n');
                md.append("- covers: ").append(step.covers().isEmpty() ? "-" : String.join(",", step.covers())).append('\n');
                md.append("- version_action: ").append(step.versionAction()).append('\n');
                md.append("- provides: ").append(step.providesContracts().isEmpty() ? "-" : String.join(",", step.providesContracts())).append('\n');
                md.append("- consumes: ").append(step.consumesContracts().isEmpty() ? "-" : String.join(",", step.consumesContracts())).append('\n');
                bullets(md, "files", step.files());
                bullets(md, "verification", step.verification());
                String subSpec = prose(step.subSpec());
                if (!subSpec.isBlank()) {
                    md.append('\n').append(subSpec).append('\n');
                }
            }
        }

        md.append("\n## Generation Notes\n");
        List<String> notes = new ArrayList<>(draft.notes());
        notes.addAll(result.warnings());
        if (notes.isEmpty()) {
            md.append("- none\n");
        } else {
            for (String note : notes) {
                md.append("- ").append(inline(note)).append('\n');
            }
        }
        return md.toString();
    }

    private static void bullets(StringBuilder md, String label, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        md.append("- ").append(label).append(":\n");
        for (String value : values) {
            md.append("  - ").append(inline(value)).append('\n');
        }
    }

    /** Drafter-controlled single-line text may never forge headings or front matter. */
    private static String inline(String value) {
        return value.replaceAll("(?U)\\s+", " ").strip();
    }

    /** Sub-spec prose keeps its lines but loses structural markers the renderer owns. */
    private static String prose(String value) {
        return value.replaceAll("(?m)^\\s*#+\\s*", "")
                .replaceAll("(?m)^---\\s*$", "—")
                .strip();
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-plan:test
git add sdd-plan/src
git commit -m "feat: gate-1 plan.md renderer"
```

---

### Task 7: CLI — `sdd plan` writes plan.md + e2e

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/PlanCommand.java` (validate: draft + render + write after printImpact)
- Test: `sdd-cli/src/test/java/sdd/cli/PlanCommandTest.java`

**Interfaces:**
- Consumes: Tasks 3-6 (`ExecutionOrder.order`, `OpenQuestions.detect`, `PlanDrafter.draft`, `PlanMdRenderer.render`), the `plannerForTest` seam (which now serves BOTH validate-path model calls — seeding then drafting: scripted tests queue TWO responses).
- Produces: after `printImpact`, the validate path computes order/questions/draft, renders, and writes `<ref minus trailing .md>.plan.md`; prints `plan written: <path>` then `review and edit the plan, then run: sdd plan approve (Phase 3C-2)`. The old `plan.md rendering is not implemented yet (Phase 3C)` line is REMOVED. Drafting uses the SAME capped client as seeding (both assistive-degradable; a written plan.md with a blocking question beats an hour-long stall).

- [ ] **Step 1: Write the failing e2e tests** (append to `PlanCommandTest`):

```java
@Test
void validSpecWritesGate1PlanMd() throws Exception {
    Files.writeString(ws.resolve("sdd.yml"), yaml());
    try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.LoyaltyTier','CLASS',1,'src/main/java/com/acme/LoyaltyTier.java')");
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
    cmd.plannerForTest = new ScriptedChatModel(List.of(
            new ChatResponse(ChatMessage.assistant("""
                    {"repos": [{"repo": "lib-core", "role": "primary", "covers": ["R1"],
                                "reason": "owns LoyaltyTier"}]}"""), "stop", new Usage(10, 10)),
            new ChatResponse(ChatMessage.assistant("""
                    {"summary": "Add the tier lookup to lib-core; svc-pricing rebuilds.",
                     "questions": [],
                     "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                                    "consumers": ["svc-pricing"], "body": "method: Tier tierFor(String)"}],
                     "repo_steps": [{"repo": "lib-core", "covers": ["R1"],
                                     "sub_spec": "Add tierFor to LoyaltyTier.",
                                     "files": ["src/main/java/com/acme/LoyaltyTier.java"],
                                     "provides_contracts": ["C-1"], "consumes_contracts": [],
                                     "version_action": "minor",
                                     "verification": ["./gradlew test"]}]}"""), "stop", new Usage(10, 10))));

    Run run = plan(cmd, "--workspace", ws.toString(), spec.toString());

    assertThat(run.exitCode()).isZero();
    Path planPath = ws.resolve("loyalty.plan.md");
    assertThat(run.out()).contains("plan written: " + planPath)
            .contains("review and edit the plan, then run: sdd plan approve (Phase 3C-2)")
            .doesNotContain("plan.md rendering is not implemented yet");
    String planMd = Files.readString(planPath);
    assertThat(planMd).startsWith("---\nspec: SPEC-7\nplan_version: 1\n---\n")
            .contains("Add the tier lookup to lib-core")
            .contains("- lib-core — seed/SEED")
            .contains("- svc-pricing — dependent/")
            .contains("### C-1 (java-api) — lib-core -> svc-pricing")
            .contains("### lib-core")
            .contains("Add tierFor to LoyaltyTier.");
}

@Test
void draftingFailureStillWritesPlanMdWithBlockingQuestion() throws Exception {
    Files.writeString(ws.resolve("sdd.yml"), yaml());
    try (sdd.core.db.Database db = sdd.core.db.Database.open(ws)) {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1,'com.acme.LoyaltyTier','CLASS')");
        });
    }
    Path spec = ws.resolve("loyalty.md");
    Files.writeString(spec, VALID_SPEC.replace("## Acceptance Criteria", """
            ## Touchpoints
            - class: LoyaltyTier

            ## Acceptance Criteria"""));
    PlanCommand cmd = new PlanCommand();
    // seeding succeeds (empty selection), drafting response is garbage
    cmd.plannerForTest = new ScriptedChatModel(List.of(
            new ChatResponse(ChatMessage.assistant("{\"repos\": []}"), "stop", new Usage(1, 1)),
            new ChatResponse(ChatMessage.assistant("not json"), "stop", new Usage(1, 1))));

    Run run = plan(cmd, "--workspace", ws.toString(), spec.toString());

    assertThat(run.exitCode()).isZero();
    String planMd = Files.readString(ws.resolve("loyalty.plan.md"));
    assertThat(planMd).contains("[blocking]: plan drafting unavailable:")
            .contains("## Affected Repos")
            .contains("- lib-core — seed/SEED")
            .contains("## Repo Steps\n- none (drafting unavailable)");
}
```

Wait — `VALID_SPEC`'s Touchpoints insertion: the canonical section order is Goal, Background, Requirements, Acceptance Criteria, Constraints, Touchpoints... **Touchpoints comes AFTER Acceptance Criteria** — the replace above would put Touchpoints BEFORE Acceptance Criteria and the parser rejects out-of-order sections. Use this instead in the second test:

```java
    Files.writeString(spec, VALID_SPEC + """

            ## Touchpoints
            - class: LoyaltyTier
            """);
```

(VALID_SPEC ends after the Acceptance Criteria item, so appending Touchpoints keeps canonical order.)

- [ ] **Step 2: Migrate the TWO existing impact tests in the same commit.** `validCanonicalSpecPrintsSummary` and `validSpecRunsImpactAnalysisEndToEnd` currently script ONE response and assert the old final line: give each a SECOND scripted response (the drafting call — reuse the garbage `"not json"` response for `validCanonicalSpecPrintsSummary` since it asserts nothing about the plan body, and a minimal valid draft `{"summary": "S.", "questions": [], "contracts": [], "repo_steps": []}` for `validSpecRunsImpactAnalysisEndToEnd`), replace the `.contains("plan.md rendering is not implemented yet (Phase 3C)")` assertions with `.contains("plan written: ")`, and for `validCanonicalSpecPrintsSummary` (zero seeds → still writes a plan) also assert the written file exists. Other existing tests are unaffected (they exit before the write).

- [ ] **Step 3: Run — expect FAIL.** Then implement in `PlanCommand.validate(...)`: replace the final `outWriter.println("plan.md rendering is not implemented yet (Phase 3C)"); return 0;` (and keep everything before it, still inside the try-with-resources so `db` is in scope) with:

```java
            List<ExecutionOrder.Unit> order = ExecutionOrder.order(db.jdbi(), result);
            List<Question> questions = OpenQuestions.detect(db.jdbi(), result);
            PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), parsed, result, order,
                    model, planner.model(), planner.maxTokens());
            String planMd = PlanMdRenderer.render(parsed, result, order, questions, draft);
            String base = ref.endsWith(".md") ? ref.substring(0, ref.length() - 3) : ref;
            Path planPath = Path.of(base + ".plan.md");
            try {
                Files.writeString(planPath, planMd);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            outWriter.println("plan written: " + planPath);
            outWriter.println("review and edit the plan, then run: sdd plan approve (Phase 3C-2)");
        }
        return 0;
```

(`model` is the already-constructed capped ChatModel from the seeding step — reuse it; add imports `sdd.plan.gen.ExecutionOrder`, `sdd.plan.gen.OpenQuestions`, `sdd.plan.gen.PlanDrafter`, `sdd.plan.gen.PlanMdRenderer`, `sdd.plan.gen.Question`.)

- [ ] **Step 4: Full build — expect PASS.**
Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: sdd plan writes the gate-1 plan.md"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. `PlanCommandTest.validSpecWritesGate1PlanMd` proves spec → impact → order → questions → draft → plan.md end-to-end; `draftingFailureStillWritesPlanMdWithBlockingQuestion` proves the deterministic-first degradation.
3. Manual smoke (real estate + DeepSeek): `sdd plan trading-estate/spec-tier-spreads.md --workspace trading-estate` should write `spec-tier-spreads.plan.md` with real drafted steps — worth running before merge.
4. Phase 3C-2 (next plan): plan.md parser + validators (INCLUDING the contract-vs-constraint conflict detector, which runs on parsed contracts), blocking-question resolution enforcement, plan.json compiler, `--include-build` smoke test, SHA-256 pinning, `sdd plan revise`.

## Self-Review (completed at write time)

1. **Spec coverage:** design plan.md bullet — Summary ✓(T6), Open Questions with deterministic detectors (unresolvable touchpoint = problems; disconnected seeds; uncovered requirement = problems; unresolved-caller warnings; model-emitted questions) ✓(T4/T5/T6; contract-vs-constraint conflict detection deferred to 3C-2's validator where contracts are parsed — recorded here), Affected Repos with role/why/mode ✓, Excluded Candidates with reasons ✓, Execution Order ✓(T3, provider-first + co-scheduled units), Interface Contracts fenced YAML ✓(T5/T6), Repo Steps with covers/provides/consumes/version_action/verification naming real files from the KB ✓(T5 evidence + soft file check). Entry checklist: seeding attempt cap ✓(T1), bm25 tiebreaker ✓(T1), seeds dedupe ✓(T2), FTS provenance carry ✓(T2), early-db-create ✓(T1), stringly unavailable seam ✓(T1). plan approve/plan.json/revise → 3C-2 by decomposition.
2. **Placeholder scan:** none; every code step complete.
3. **Type consistency:** `Question(String, boolean)` shared by T4/T5/T6; `ExecutionOrder.Unit(List<String>)` by T3/T5/T6/T7; `PlanDrafter.Draft/DraftStep/DraftContract` by T5/T6/T7; `SeedingOutcome` 3-component shape by T1 and ModelSeeder call sites; renderer layout strings match T7's e2e assertions; `draft/composeInput` carry the caller-computed `List<ExecutionOrder.Unit>` in T5 code, T5 tests, and T7's call site.
4. **Adversarial critique pass (2 independent critics vs the real codebase, findings folded in):** ExecutionOrder test-1 expected order corrected to the algorithm's true emission (`lib-core, lib-api, platform, …` — newly-ready units interleave into the alphabetical pool; both critics independently simulated the Kahn trace); drafter free text can no longer forge plan.md structure (renderer `inline()`/`prose()` sanitizers + hostile-input test — the 3C-2 parser pins this layout); Task 2's `excluded()` assertion relabeled a regression pin (true pre-change); Kafka pseudo-edge ordering, fenced-JSON drafting, and EVIDENCE_CAP truncation gained tests; the unresolved-caller detector gained its Kafka counterpart; the contract-vs-constraint conflict detector is now explicitly named in 3C-2's scope; `ExecutionOrder.order` computed once and passed into the drafter. Critics verified every record shape, line reference, import, and SQL column against the real repo; the `GOOD_JSON` escape semantics and `req=null` rendering were confirmed correct.

---

## Execution Outcome (2026-08-12)

Executed via superpowers:subagent-driven-development on branch `feature/phase3c1-plan-md-generation` (base 2fc828d, HEAD 1abcf1f, 8 commits). All 7 tasks completed with clean per-task reviews and ZERO task-level fix rounds. Final whole-branch review (most capable model): **one fix wave** — two sanitizer leaks no per-task scope could see (seeding-model text reached the Affected-Repos "why" column un-inlined; `prose()` missed backtick fences) — fixed in one commit, each finding independently RED-verified by the scoped re-review. Full `./gradlew clean build` green.

### Real-estate drafting smoke (trading estate, real DeepSeek)

- **Containment live-proven twice:** with `max_tokens: 16384` the drafting call hit `finish_reason=length` on BOTH runs (~165 s/227 s) and degraded exactly as designed — complete deterministic artifact, one `[blocking]` question, exit 0, all 8 sections intact.
- **ESTATE FINDING: the design's "planner ≥16k max_tokens" floor is insufficient for DRAFTING.** At `max_tokens: 32768` the draft succeeded (~211 s): 2 grounded contracts (java-api platform-libs→product-a; rest trading-core admin surface for R3), 6 repo steps with real files/verification tasks, full R1-R3 coverage, and six blocking questions that are precisely the right human decisions (resolver method shape, publication-pipeline location, extend-vs-new endpoint, config home, A2 cache invalidation, product-b scope). The file soft-check caught the drafter's one bare filename (`JdbcTierResolver.java` without its repo-relative path) in Generation Notes, as designed.

### Phase 3C-2 entry checklist

1. `plan approve`: plan.md parser + validators (INCLUDING contract-vs-constraint conflict detection on parsed contracts), blocking-question resolution enforcement, plan.json compiler, `--include-build` smoke test, SHA-256 pinning, `sdd plan revise`.
2. **Overwrite guard before plan.md edits become load-bearing:** `sdd plan` (and `normalize`) silently overwrite hand-edited artifacts — the revise design must add a guard/backup (final-review ruling: safe today, gating for 3C-2).
3. **Drafting token budget:** recommend `max_tokens ≥ 32768` for the planner in docs/sdd.yml.example, or design per-repo draft chunking; consider a distinct drafting-tokens config knob (drafting needs ~2× seeding).
4. Deferred minors: duplicate contract ids kept (3C-2 validator owns uniqueness; parser must tolerate); empty contract consumers render a trailing "-> "; blank-problem producer-side check; duplicate drafter steps per repo (render both; validator judges).
5. Estate follow-ups: Redis-channel edges as a future edge type; `com.trading:services` GA collision cleanup via rename or artifact_overrides.
