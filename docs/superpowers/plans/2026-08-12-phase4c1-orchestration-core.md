# Phase 4C-1 — Orchestration Core (single-attempt `sdd implement`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the multi-repo orchestration layer — `sdd implement <plan.json>` — that reads an approved plan, resolves per-repo work into 4B `RepoStep`s, walks the execution order committing each repo's work on its own branch, persists resumable run state, and reports a typed exit code. Single attempt per repo (coder model only); propagation, escalation, and resilience are later 4C sub-phases.

**Architecture:** New package `sdd.cli.implement` in the existing `sdd-cli` module (the only module that sees both sdd-plan's plan/spec types and sdd-agent's `RepoStep`/`RepoStepRunner`). A tree-based `PlanJsonReader` parses the approved plan.json into public `PlanModel` records; `RepoStepResolver` joins steps against the spec + contracts + KB paths into `RepoStep`s; a JGit-backed `RunGit` does branch/checkout/commit/reset; `RunState`+`RunStore` persist an atomic `state.json` + append-only `events.jsonl` under `<workspace>/.sdd/runs/<runId>/`; `Scheduler` walks the pre-grouped `order` with an upstream-failure cascade; `PreFlight` gates on clean tree + base-SHA; the `Orchestrator` drives one attempt per repo via `RepoStepRunner.run(step, coder, name, settings)`; `ImplementCommand` wires it all from config + KB. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` Component 3 (orchestration — lines 54–63) + the Phase-4B plan's "Phase 4C entry pointers".

**Tech Stack:** Java 21, picocli (CLI), JGit 6.10 (already catalogued), Jackson `readTree` (no parameter-names module — tree-based parsing only), Jdbi (KB), the merged 4A/4B `sdd.agent.run`/`sdd.agent.loop` seam, `ScriptedChatModel` + `FixtureRepo` testFixtures.

## Global Constraints

- Java 21; the only NEW dependencies added to `sdd-cli/build.gradle.kts` are `implementation(project(":sdd-agent"))`, `implementation(libs.jgit)`, and `implementation(libs.jackson)` (all already exist in the catalog / other modules). No new catalog entries. Jackson is `implementation` in every module, so it does NOT leak onto sdd-cli's compile classpath transitively — sdd-cli must declare it directly.
- **Module direction:** all new code lives in `sdd-cli` (`sdd.cli.implement` + one command in `sdd.cli`). sdd-agent and sdd-plan are unchanged. The plan.json→`RepoStep` adaptation belongs here because only sdd-cli sees both sides.
- **Deterministic-first / test seam:** the ONLY model interaction is `RepoStepRunner.run(step, ChatModel, name, settings)`; every test drives it with `ScriptedChatModel`. No live models, ever. Git is real (JGit over `FixtureRepo`s); Gradle is real subprocess over `#!/bin/sh` `gradlew` stubs (the 4B pattern).
- **SCOPE = one attempt per repo, sequential.** Explicitly DEFERRED: **4C-2** — propagation (`--include-build`/mavenLocal/version-bump injection, the `GradleTool` extra-args change); **4C-3** — multi-attempt + DeepSeek escalation + hard-reset-to-base, INFRA classification, `PAUSED_INFRA`/`PAUSED_ENDPOINT`, `--resume`, full M8 staleness recovery, virtual-thread concurrency + semaphores, and the run-wide 30M-token budget. This phase is model-free at the orchestration layer beyond the single coder attempt.
- **plan.json parsing is tree-based** (`ObjectMapper.readTree` + literal snake_case field reads) — the writer's records are package-private in sdd-plan and there is no parameter-names module, so record data-binding will NOT work. Field names are literal snake_case (`spec_id`, `plan_version`, `version_action`, `sub_spec`, `base_sha`, `from_repo`, `to_repo`).
- **Run identity:** `runId = <sanitized spec_id>-v<plan_version>` (e.g. `SPEC-101-v1`) — stable per approved plan version, so a re-run addresses the same run dir. Sanitize to `[A-Za-z0-9._-]` (others → `-`).
- **Run dir layout:** `<workspace>/.sdd/runs/<runId>/` holds `plan.json` (immutable copy), `state.json` (atomic temp-file + `ATOMIC_MOVE`/`REPLACE_EXISTING`), `events.jsonl` (append-only), and a `lock` file (created at run start, removed at end; a stale lock aborts with an explanatory message).
- **Per-repo states (this phase):** `PENDING`, `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `SKIPPED_UPSTREAM_FAILED`. (4C-3 adds `PAUSED_*`.) **Exit taxonomy (this phase):** `0 = COMPLETE` (all `SUCCEEDED`), `2 = PARTIAL` (finished but some `FAILED`/`SKIPPED`), `4 = ABORTED` (pre-flight failure / lock held). Exit `3 = PAUSED` is 4C-3.
- **Git:** orchestrator owns git via JGit — branch `sdd/<runId>/<repo>` off the repo's `base_sha`, checkout, add+commit a checkpoint on `SUCCEEDED`, reset-to-recorded-SHA available. NEVER push/remote. The agent still has zero git verbs.
- **Pre-flight (this phase):** per repo — repo path exists, `gradlew` executable, working tree clean, live HEAD == plan `base_sha` (drift ⇒ hard-fail/ABORTED). Full M8 staleness recovery (re-index/auto-advance) is 4C-3.
- **KB is read-only during a run** (design line 62). This phase never writes the KB.
- Never read or print `.env` or any `api_key`. Never push. Full `./gradlew build` before any commit touching more than one module.

---

## File Structure

**Task 1:** `sdd-cli/build.gradle.kts` (deps) + `sdd-cli/src/main/java/sdd/cli/implement/{PlanModel,PlanJsonReader}.java` + test
**Task 2:** `sdd-cli/src/main/java/sdd/cli/implement/RepoStepResolver.java` + test
**Task 3:** `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java` + test
**Task 4:** `sdd-cli/src/main/java/sdd/cli/implement/{RepoState,RepoRun,RunState,RunStore}.java` + test
**Task 5:** `sdd-cli/src/main/java/sdd/cli/implement/{Scheduler,PreFlight}.java` + test
**Task 6:** `sdd-cli/src/main/java/sdd/cli/implement/Orchestrator.java` + test
**Task 7:** `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java` + `SddCli.java` (register) + test

---

### Task 1: Plan model + tree-based plan.json reader

**Files:**
- Modify: `sdd-cli/build.gradle.kts` (add `implementation(project(":sdd-agent"))` and `implementation(libs.jgit)`)
- Create: `sdd-cli/src/main/java/sdd/cli/implement/PlanModel.java`, `PlanJsonReader.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/PlanJsonReaderTest.java`

**Interfaces:**
- Produces:
  - `public record PlanModel(String specId, int planVersion, String specSha256, String planSha256, List<PlanRepo> repos, List<List<String>> order, List<PlanEdge> edges, List<PlanContract> contracts, List<PlanStep> steps)` with nested public records `PlanRepo(String name, String role, String annotation, String versionAction, String baseSha)`, `PlanEdge(String fromRepo, String toRepo, String mode, String mechanism)`, `PlanContract(String id, String kind, String provider, List<String> consumers, String body)`, `PlanStep(String repo, List<String> covers, String versionAction, List<String> provides, List<String> consumes, List<String> files, List<String> verification, String subSpec)`. All lists `List.copyOf`. Add convenience `PlanModel.repo(String name)` → `Optional<PlanRepo>` and `PlanModel.step(String repo)` → `Optional<PlanStep>`.
  - `public final class PlanJsonReader { public static PlanModel read(String json); }` — tree-based, reads literal snake_case fields.
- Consumes: Jackson `com.fasterxml.jackson.databind.{ObjectMapper,JsonNode}` (declared directly in Step 1 — it is `implementation` in every module, so it does NOT reach sdd-cli's compile classpath transitively).

- [ ] **Step 1: Add the module deps.** In `sdd-cli/build.gradle.kts`, inside the `dependencies { }` block alongside the existing `implementation(project(...))` lines, add:

```kotlin
    implementation(project(":sdd-agent"))
    implementation(libs.jgit)
    implementation(libs.jackson)
```

- [ ] **Step 2: Write the failing test:**

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanJsonReaderTest {
    private static final String PLAN = """
            {
              "spec_id" : "SPEC-101",
              "plan_version" : 1,
              "spec_sha256" : "aaa",
              "plan_sha256" : "bbb",
              "repos" : [ {
                "name" : "lib", "role" : "seed", "annotation" : "SEED",
                "version_action" : "minor", "base_sha" : "sha-lib"
              }, {
                "name" : "svc", "role" : "dependent", "annotation" : "CODE_CHANGE_LIKELY",
                "version_action" : "patch", "base_sha" : "sha-svc"
              } ],
              "order" : [ [ "lib" ], [ "svc" ] ],
              "edges" : [ { "from_repo" : "svc", "to_repo" : "lib", "mode" : "SNAPSHOT", "mechanism" : "INCLUDE_BUILD" } ],
              "contracts" : [ {
                "id" : "c1", "kind" : "java-api", "provider" : "lib",
                "consumers" : [ "svc" ], "body" : "Tier tierFor(String id)"
              } ],
              "steps" : [ {
                "repo" : "lib", "covers" : [ "R1" ], "version_action" : "minor",
                "provides" : [ "c1" ], "consumes" : [ ], "files" : [ "Tier.java" ],
                "verification" : [ "Run lib tests" ], "sub_spec" : "Expose tierFor."
              }, {
                "repo" : "svc", "covers" : [ "R1", "R2" ], "version_action" : "patch",
                "provides" : [ ], "consumes" : [ "c1" ], "files" : [ ],
                "verification" : [ ], "sub_spec" : "Consume tierFor."
              } ]
            }
            """;

    @Test
    void parsesTheApprovedPlan() {
        PlanModel plan = PlanJsonReader.read(PLAN);

        assertThat(plan.specId()).isEqualTo("SPEC-101");
        assertThat(plan.planVersion()).isEqualTo(1);
        assertThat(plan.specSha256()).isEqualTo("aaa");
        assertThat(plan.order()).containsExactly(java.util.List.of("lib"), java.util.List.of("svc"));
        assertThat(plan.repo("svc")).get().extracting(PlanModel.PlanRepo::baseSha).isEqualTo("sha-svc");
        assertThat(plan.edges()).singleElement()
                .extracting(PlanModel.PlanEdge::fromRepo, PlanModel.PlanEdge::toRepo)
                .containsExactly("svc", "lib");   // real plan.json direction: from=consumer, to=provider
        assertThat(plan.contracts()).singleElement()
                .extracting(PlanModel.PlanContract::id, PlanModel.PlanContract::body)
                .containsExactly("c1", "Tier tierFor(String id)");
        PlanModel.PlanStep lib = plan.step("lib").orElseThrow();
        assertThat(lib.covers()).containsExactly("R1");
        assertThat(lib.provides()).containsExactly("c1");
        assertThat(lib.files()).containsExactly("Tier.java");
        assertThat(lib.subSpec()).isEqualTo("Expose tierFor.");
        assertThat(plan.step("svc").orElseThrow().consumes()).containsExactly("c1");
    }

    @Test
    void toleratesMissingOptionalArrays() {
        PlanModel plan = PlanJsonReader.read(
                "{\"spec_id\":\"S\",\"plan_version\":2,\"repos\":[],\"order\":[],\"steps\":[]}");
        assertThat(plan.planVersion()).isEqualTo(2);
        assertThat(plan.edges()).isEmpty();
        assertThat(plan.contracts()).isEmpty();
    }
}
```

- [ ] **Step 3: Run — expect COMPILE FAILURE.** Implement `PlanModel.java`:

```java
package sdd.cli.implement;

import java.util.List;
import java.util.Optional;

/** The approved plan.json as an in-memory model (sdd-cli mirror of sdd-plan's package-private records). */
public record PlanModel(String specId, int planVersion, String specSha256, String planSha256,
                        List<PlanRepo> repos, List<List<String>> order, List<PlanEdge> edges,
                        List<PlanContract> contracts, List<PlanStep> steps) {

    public PlanModel {
        repos = List.copyOf(repos);
        order = order.stream().map(List::copyOf).toList();
        edges = List.copyOf(edges);
        contracts = List.copyOf(contracts);
        steps = List.copyOf(steps);
    }

    public record PlanRepo(String name, String role, String annotation, String versionAction, String baseSha) {
    }

    public record PlanEdge(String fromRepo, String toRepo, String mode, String mechanism) {
    }

    public record PlanContract(String id, String kind, String provider, List<String> consumers, String body) {
        public PlanContract {
            consumers = List.copyOf(consumers);
        }
    }

    public record PlanStep(String repo, List<String> covers, String versionAction, List<String> provides,
                           List<String> consumes, List<String> files, List<String> verification, String subSpec) {
        public PlanStep {
            covers = List.copyOf(covers);
            provides = List.copyOf(provides);
            consumes = List.copyOf(consumes);
            files = List.copyOf(files);
            verification = List.copyOf(verification);
        }
    }

    public Optional<PlanRepo> repo(String name) {
        return repos.stream().filter(r -> r.name().equals(name)).findFirst();
    }

    public Optional<PlanStep> step(String repo) {
        return steps.stream().filter(s -> s.repo().equals(repo)).findFirst();
    }
}
```

`PlanJsonReader.java`:

```java
package sdd.cli.implement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses an approved plan.json into a {@link PlanModel}. Tree-based on purpose: the writer's records
 * are package-private in sdd-plan and there is no jackson parameter-names module on the classpath,
 * so record data-binding by component name would not work. Field names are literal snake_case.
 */
public final class PlanJsonReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PlanJsonReader() {
    }

    public static PlanModel read(String json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("plan.json is not valid JSON: " + e.getOriginalMessage());
        }
        List<PlanModel.PlanRepo> repos = new ArrayList<>();
        for (JsonNode r : root.path("repos")) {
            repos.add(new PlanModel.PlanRepo(text(r, "name"), text(r, "role"), text(r, "annotation"),
                    text(r, "version_action"), text(r, "base_sha")));
        }
        List<List<String>> order = new ArrayList<>();
        for (JsonNode group : root.path("order")) {
            order.add(strings(group));
        }
        List<PlanModel.PlanEdge> edges = new ArrayList<>();
        for (JsonNode e : root.path("edges")) {
            edges.add(new PlanModel.PlanEdge(text(e, "from_repo"), text(e, "to_repo"),
                    text(e, "mode"), text(e, "mechanism")));
        }
        List<PlanModel.PlanContract> contracts = new ArrayList<>();
        for (JsonNode c : root.path("contracts")) {
            contracts.add(new PlanModel.PlanContract(text(c, "id"), text(c, "kind"), text(c, "provider"),
                    strings(c.path("consumers")), text(c, "body")));
        }
        List<PlanModel.PlanStep> steps = new ArrayList<>();
        for (JsonNode s : root.path("steps")) {
            steps.add(new PlanModel.PlanStep(text(s, "repo"), strings(s.path("covers")),
                    text(s, "version_action"), strings(s.path("provides")), strings(s.path("consumes")),
                    strings(s.path("files")), strings(s.path("verification")), text(s, "sub_spec")));
        }
        return new PlanModel(text(root, "spec_id"), root.path("plan_version").asInt(),
                text(root, "spec_sha256"), text(root, "plan_sha256"),
                repos, order, edges, contracts, steps);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.asText());
        }
        return out;
    }
}
```

- [ ] **Step 4: Run — expect PASS, then commit**

```bash
./gradlew :sdd-cli:test
git add sdd-cli/build.gradle.kts sdd-cli/src
git commit -m "feat: tree-based plan.json reader + plan model"
```

---

### Task 2: RepoStepResolver — plan.json + spec + KB → RepoStep

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/RepoStepResolver.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/RepoStepResolverTest.java`

**Interfaces:**
- Produces: `public final class RepoStepResolver { public static Map<String, RepoStep> resolve(PlanModel plan, NormalizedSpec spec, Map<String, Path> repoPaths); }` — one `RepoStep` per plan step, keyed by repo name, insertion-ordered by the flattened `order`. Joins: `subSpec`←`step.subSpec`; `files`←`step.files`; `acceptanceChecks`←`step.verification`; `requirements`←each `covers` id → `"<id>: <text>"` from `spec.requirements()` (fallback `"<id>: (requirement text unavailable)"`); `provides`/`consumes`←each id → the matching `plan.contracts()` entry inflated to `ContractRef` (hard-fail on an undefined contract id); `repoRoot`←`repoPaths.get(step.repo)` (hard-fail if absent).
- Consumes: `sdd.agent.run.RepoStep`, `sdd.agent.run.ContractRef` (sdd-agent), `sdd.plan.spec.NormalizedSpec`, `sdd.plan.spec.SpecItem` (sdd-plan), `PlanModel` (Task 1).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import sdd.agent.run.ContractRef;
import sdd.agent.run.RepoStep;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoStepResolverTest {
    private static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-101", "Tiers", "me", "approved", "goal", "bg",
                List.of(new SpecItem("R1", "Price includes the customer tier."),
                        new SpecItem("R2", "Mapping changes need no restart.")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static PlanModel plan() {
        return PlanJsonReader.read(PlanJsonReaderTestFixture.PLAN);
    }

    @Test
    void buildsRepoStepsWithResolvedContractsAndRequirements() {
        Map<String, RepoStep> steps = RepoStepResolver.resolve(plan(), spec(),
                Map.of("lib", Path.of("/w/lib"), "svc", Path.of("/w/svc")));

        assertThat(steps.keySet()).containsExactly("lib", "svc");   // flattened order
        RepoStep lib = steps.get("lib");
        assertThat(lib.repoRoot()).isEqualTo(Path.of("/w/lib"));
        assertThat(lib.subSpec()).isEqualTo("Expose tierFor.");
        assertThat(lib.requirements()).containsExactly("R1: Price includes the customer tier.");
        assertThat(lib.acceptanceChecks()).containsExactly("Run lib tests");
        assertThat(lib.provides()).singleElement()
                .extracting(ContractRef::id, ContractRef::body)
                .containsExactly("c1", "Tier tierFor(String id)");
        RepoStep svc = steps.get("svc");
        assertThat(svc.requirements()).containsExactly(
                "R1: Price includes the customer tier.", "R2: Mapping changes need no restart.");
        assertThat(svc.consumes()).singleElement().extracting(ContractRef::id).isEqualTo("c1");
    }

    @Test
    void failsHardOnAnUnknownRepoPath() {
        assertThatThrownBy(() -> RepoStepResolver.resolve(plan(), spec(), Map.of("lib", Path.of("/w/lib"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("svc");
    }
}
```

Also create the shared fixture `sdd-cli/src/test/java/sdd/cli/implement/PlanJsonReaderTestFixture.java`:

```java
package sdd.cli.implement;

/** The canonical 2-repo approved plan used across implement tests. */
final class PlanJsonReaderTestFixture {
    private PlanJsonReaderTestFixture() {
    }

    static final String PLAN = """
            {
              "spec_id" : "SPEC-101", "plan_version" : 1, "spec_sha256" : "aaa", "plan_sha256" : "bbb",
              "repos" : [
                { "name" : "lib", "role" : "seed", "annotation" : "SEED", "version_action" : "minor", "base_sha" : "sha-lib" },
                { "name" : "svc", "role" : "dependent", "annotation" : "CODE_CHANGE_LIKELY", "version_action" : "patch", "base_sha" : "sha-svc" }
              ],
              "order" : [ [ "lib" ], [ "svc" ] ],
              "edges" : [ { "from_repo" : "svc", "to_repo" : "lib", "mode" : "SNAPSHOT", "mechanism" : "INCLUDE_BUILD" } ],
              "contracts" : [ { "id" : "c1", "kind" : "java-api", "provider" : "lib", "consumers" : [ "svc" ], "body" : "Tier tierFor(String id)" } ],
              "steps" : [
                { "repo" : "lib", "covers" : [ "R1" ], "version_action" : "minor", "provides" : [ "c1" ], "consumes" : [ ], "files" : [ "Tier.java" ], "verification" : [ "Run lib tests" ], "sub_spec" : "Expose tierFor." },
                { "repo" : "svc", "covers" : [ "R1", "R2" ], "version_action" : "patch", "provides" : [ ], "consumes" : [ "c1" ], "files" : [ ], "verification" : [ ], "sub_spec" : "Consume tierFor." }
              ]
            }
            """;
}
```

- [ ] **Step 2: Run — expect COMPILE/TEST FAILURE.** Implement `RepoStepResolver.java`:

```java
package sdd.cli.implement;

import sdd.agent.run.ContractRef;
import sdd.agent.run.RepoStep;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the approved {@link PlanModel} into per-repo {@link RepoStep}s (4B's runner input). This is
 * the join the module boundary forces into sdd-cli: contract-id strings → full {@link ContractRef}
 * from the top-level contracts[]; requirement-id strings → "R1: text" from the re-parsed spec;
 * repo names → filesystem paths from the KB.
 */
public final class RepoStepResolver {
    private RepoStepResolver() {
    }

    public static Map<String, RepoStep> resolve(PlanModel plan, NormalizedSpec spec,
                                                Map<String, Path> repoPaths) {
        Map<String, PlanModel.PlanContract> contracts = new LinkedHashMap<>();
        for (PlanModel.PlanContract c : plan.contracts()) {
            contracts.put(c.id(), c);
        }
        Map<String, String> reqText = new LinkedHashMap<>();
        for (SpecItem item : spec.requirements()) {
            reqText.put(item.id(), item.text());
        }
        Map<String, RepoStep> steps = new LinkedHashMap<>();
        for (String repo : flatten(plan.order())) {
            PlanModel.PlanStep step = plan.step(repo).orElse(null);
            if (step == null) {
                continue;   // a repo in order with no step (e.g. bom site) — nothing to run
            }
            Path root = repoPaths.get(repo);
            if (root == null) {
                throw new IllegalStateException("repo " + repo + " is not in the knowledge base");
            }
            List<String> requirements = step.covers().stream()
                    .map(id -> id + ": " + reqText.getOrDefault(id, "(requirement text unavailable)"))
                    .toList();
            steps.put(repo, new RepoStep(repo, root, step.subSpec(), requirements, step.files(),
                    refs(step.provides(), contracts), refs(step.consumes(), contracts),
                    step.verification()));
        }
        return steps;
    }

    private static List<ContractRef> refs(List<String> ids, Map<String, PlanModel.PlanContract> contracts) {
        return ids.stream().map(id -> {
            PlanModel.PlanContract c = contracts.get(id);
            if (c == null) {
                throw new IllegalStateException("plan.json references undefined contract: " + id);
            }
            return new ContractRef(c.id(), c.kind(), c.provider(), c.consumers(), c.body());
        }).toList();
    }

    private static List<String> flatten(List<List<String>> order) {
        return order.stream().flatMap(List::stream).toList();
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-cli:test
git add sdd-cli/src
git commit -m "feat: resolve plan.json steps into RepoSteps"
```

---

### Task 3: RunGit — write-capable JGit facade

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/RunGit.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/RunGitTest.java`

**Interfaces:**
- Produces: `public final class RunGit` with static methods `String head(Path repo)`, `boolean isClean(Path repo)`, `void startBranch(Path repo, String branch, String baseSha)` (create+checkout off baseSha, or checkout+reset-hard if it already exists), `String commitAll(Path repo, String message)` (stage all incl. deletions, commit, return new sha), `void resetHard(Path repo, String sha)`. All throw `IllegalStateException` wrapping JGit errors. Never pushes.
- Consumes: JGit (`org.eclipse.jgit.api.*`).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunGitTest {
    @TempDir Path tmp;

    @Test
    void branchesCommitsAndResets() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();

        RunGit.startBranch(repo.path(), "sdd/RUN/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int x; }\n");
        String checkpoint = RunGit.commitAll(repo.path(), "sdd: RUN lib");

        assertThat(checkpoint).isNotEqualTo(base);
        assertThat(RunGit.head(repo.path())).isEqualTo(checkpoint);
        assertThat(RunGit.isClean(repo.path())).isTrue();
        assertThat(Files.readString(repo.path().resolve("A.java"))).contains("int x;");

        RunGit.resetHard(repo.path(), base);
        assertThat(RunGit.head(repo.path())).isEqualTo(base);
        assertThat(Files.readString(repo.path().resolve("A.java"))).isEqualTo("class A {}\n");
    }

    @Test
    void startBranchOnAnExistingBranchResetsItToBase() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        String base = repo.headSha();
        RunGit.startBranch(repo.path(), "sdd/RUN/lib", base);
        Files.writeString(repo.path().resolve("A.java"), "class A { int z; }\n");
        RunGit.commitAll(repo.path(), "first");   // branch now ahead (real change, not an empty commit)

        RunGit.startBranch(repo.path(), "sdd/RUN/lib", base);   // re-entry hard-resets to base

        assertThat(RunGit.head(repo.path())).isEqualTo(base);
        assertThat(Files.readString(repo.path().resolve("A.java"))).isEqualTo("class A {}\n");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `RunGit.java`:

```java
package sdd.cli.implement;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;

import java.nio.file.Path;

/**
 * The orchestrator's write-capable git facade (design: orchestrator owns git via JGit —
 * branch/checkout/add/commit/reset-to-recorded-SHA only, never push/remote). LiveGit reads;
 * this writes.
 */
public final class RunGit {
    private static final PersonIdent IDENT = new PersonIdent("sdd", "sdd@local");

    private RunGit() {
    }

    public static String head(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            var id = git.getRepository().resolve("HEAD");
            return id == null ? "" : id.name();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read HEAD of " + repo + ": " + e.getMessage(), e);
        }
    }

    public static boolean isClean(Path repo) {
        try (Git git = Git.open(repo.toFile())) {
            return git.status().call().isClean();
        } catch (Exception e) {
            throw new IllegalStateException("cannot read status of " + repo + ": " + e.getMessage(), e);
        }
    }

    /** Create the branch off baseSha and check it out; if it already exists, check out and hard-reset it to base. */
    public static void startBranch(Path repo, String branch, String baseSha) {
        try (Git git = Git.open(repo.toFile())) {
            boolean exists = git.branchList().call().stream()
                    .map(Ref::getName).anyMatch(("refs/heads/" + branch)::equals);
            if (exists) {
                git.checkout().setName(branch).call();
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(baseSha).call();
            } else {
                git.checkout().setCreateBranch(true).setName(branch).setStartPoint(baseSha).call();
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot start branch " + branch + " in " + repo + ": "
                    + e.getMessage(), e);
        }
    }

    /** Stage everything (including deletions) and commit; returns the new commit SHA. */
    public static String commitAll(Path repo, String message) {
        try (Git git = Git.open(repo.toFile())) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();   // stage deletions
            return git.commit().setMessage(message).setAuthor(IDENT).setCommitter(IDENT).call().getName();
        } catch (Exception e) {
            throw new IllegalStateException("cannot commit in " + repo + ": " + e.getMessage(), e);
        }
    }

    public static void resetHard(Path repo, String sha) {
        try (Git git = Git.open(repo.toFile())) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sha).call();
        } catch (Exception e) {
            throw new IllegalStateException("cannot reset " + repo + " to " + sha + ": " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-cli:test
git add sdd-cli/src
git commit -m "feat: write-capable jgit facade for run branches"
```

---

### Task 4: Run state + persistence

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/RepoState.java`, `RepoRun.java`, `RunState.java`, `RunStore.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java`

**Interfaces:**
- Produces:
  - `public enum RepoState { PENDING, IN_PROGRESS, SUCCEEDED, FAILED, SKIPPED_UPSTREAM_FAILED }`.
  - `public record RepoRun(String repo, RepoState state, String branch, String checkpointSha, String detail)`.
  - `public final class RunState` — mutable, insertion-ordered map of `repo → RepoRun`; ctor `RunState(String runId, List<String> repoNames)` seeds every repo `PENDING`; `void set(String repo, RepoState, String branch, String checkpointSha, String detail)`; `RepoState stateOf(String repo)`; `List<RepoRun> repos()`; `String runId()`.
  - `public final class RunStore` — `Path create(Path workspace, String runId, String planJson)` (makes `<ws>/.sdd/runs/<runId>/`, writes immutable `plan.json`, acquires `lock` — throws if a lock is already held); `void writeState(Path runDir, RunState state)` (atomic temp+`ATOMIC_MOVE`); `void appendEvent(Path runDir, String repo, RepoState from, RepoState to, String detail)` (append one JSON line to `events.jsonl`, timestamp from the injected clock); `void writeAgentEvents(Path runDir, String repo, List<String> events)` (per-repo `<repo>/agent-events.jsonl` from `StepOutcome.events()`); `void releaseLock(Path runDir)`. Ctor `RunStore(InstantSource clock)`; static `RunStore system()`.
- Consumes: Jackson (serialize a snapshot record), `java.time.InstantSource`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStoreTest {
    @TempDir Path ws;
    private final RunStore store = new RunStore(InstantSource.fixed(Instant.parse("2026-08-12T00:00:00Z")));

    @Test
    void createsRunDirWithImmutablePlanAndLock() throws Exception {
        Path runDir = store.create(ws, "SPEC-101-v1", "{\"plan\":true}");

        assertThat(runDir).isEqualTo(ws.resolve(".sdd/runs/SPEC-101-v1"));
        assertThat(Files.readString(runDir.resolve("plan.json"))).isEqualTo("{\"plan\":true}");
        assertThat(runDir.resolve("lock")).exists();
        assertThatThrownBy(() -> store.create(ws, "SPEC-101-v1", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("in progress");
    }

    @Test
    void writesAtomicStateAndAppendsEvents() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        RunState state = new RunState("R", List.of("lib", "svc"));
        state.set("lib", RepoState.SUCCEEDED, "sdd/R/lib", "abc123", "done");
        store.writeState(runDir, state);
        store.appendEvent(runDir, "lib", RepoState.IN_PROGRESS, RepoState.SUCCEEDED, "done");

        String stateJson = Files.readString(runDir.resolve("state.json"));
        assertThat(stateJson).contains("\"runId\" : \"R\"").contains("SUCCEEDED").contains("abc123");
        String events = Files.readString(runDir.resolve("events.jsonl"));
        assertThat(events).contains("\"repo\":\"lib\"").contains("\"to\":\"SUCCEEDED\"")
                .contains("2026-08-12T00:00:00Z").endsWith("\n");
    }

    @Test
    void runStateSeedsPendingAndTracksTransitions() {
        RunState state = new RunState("R", List.of("lib", "svc"));
        assertThat(state.stateOf("lib")).isEqualTo(RepoState.PENDING);
        state.set("lib", RepoState.FAILED, "b", null, "boom");
        assertThat(state.stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(state.repos()).extracting(RepoRun::repo).containsExactly("lib", "svc");
    }

    @Test
    void writesPerRepoAgentEvents() throws Exception {
        Path runDir = store.create(ws, "R", "{}");
        store.writeAgentEvents(runDir, "lib", List.of("no tool call", "wedged"));
        assertThat(Files.readString(runDir.resolve("lib/agent-events.jsonl")))
                .contains("no tool call").contains("wedged");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `RepoState.java`:

```java
package sdd.cli.implement;

public enum RepoState { PENDING, IN_PROGRESS, SUCCEEDED, FAILED, SKIPPED_UPSTREAM_FAILED }
```

`RepoRun.java`:

```java
package sdd.cli.implement;

/** One repo's status within a run. branch/checkpointSha are null until set. */
public record RepoRun(String repo, RepoState state, String branch, String checkpointSha, String detail) {
}
```

`RunState.java`:

```java
package sdd.cli.implement;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable per-run status, seeded PENDING for every repo, in execution order. */
public final class RunState {
    private final String runId;
    private final Map<String, RepoRun> repos = new LinkedHashMap<>();

    public RunState(String runId, List<String> repoNames) {
        this.runId = runId;
        for (String repo : repoNames) {
            repos.put(repo, new RepoRun(repo, RepoState.PENDING, null, null, ""));
        }
    }

    public String runId() {
        return runId;
    }

    public void set(String repo, RepoState state, String branch, String checkpointSha, String detail) {
        repos.put(repo, new RepoRun(repo, state, branch, checkpointSha, detail));
    }

    public RepoState stateOf(String repo) {
        RepoRun run = repos.get(repo);
        return run == null ? null : run.state();
    }

    public List<RepoRun> repos() {
        return List.copyOf(repos.values());
    }
}
```

`RunStore.java`:

```java
package sdd.cli.implement;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.InstantSource;
import java.util.List;

/** Persists a run under {@code <workspace>/.sdd/runs/<runId>/}: immutable plan.json, atomic state.json,
 *  append-only events.jsonl, and a lock file. */
public final class RunStore {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InstantSource clock;

    public RunStore(InstantSource clock) {
        this.clock = clock;
    }

    public static RunStore system() {
        return new RunStore(InstantSource.system());
    }

    public Path create(Path workspace, String runId, String planJson) {
        Path runDir = workspace.resolve(".sdd/runs/" + runId);
        try {
            Files.createDirectories(runDir);
            Path lock = runDir.resolve("lock");
            try {
                Files.createFile(lock);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalStateException("run " + runId + " is already in progress (lock held at "
                        + lock + "); remove the lock to override");
            }
            Files.writeString(runDir.resolve("plan.json"), planJson);
            return runDir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void releaseLock(Path runDir) {
        try {
            Files.deleteIfExists(runDir.resolve("lock"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeState(Path runDir, RunState state) {
        record Snapshot(String runId, List<RepoRun> repos) {
        }
        try {
            String json = JSON.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new Snapshot(state.runId(), state.repos()));
            Path tmp = runDir.resolve("state.json.tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, runDir.resolve("state.json"),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, runDir.resolve("state.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void appendEvent(Path runDir, String repo, RepoState from, RepoState to, String detail) {
        String line = "{\"at\":\"" + clock.instant() + "\",\"repo\":\"" + repo + "\",\"from\":\""
                + from + "\",\"to\":\"" + to + "\",\"detail\":" + jsonString(detail) + "}\n";
        try {
            Files.writeString(runDir.resolve("events.jsonl"), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Persists the agent loop's notable events for one repo to {@code <repo>/agent-events.jsonl}. NOTE:
     * this is what 4B's {@code StepOutcome.events()} exposes — NOT the design's full model-turn
     * {@code transcript.jsonl} or structured {@code edits.jsonl}, which need a 4B change to surface the
     * ContextWindow turns / applied edits and are deferred to a later phase.
     */
    public void writeAgentEvents(Path runDir, String repo, List<String> events) {
        try {
            Path repoDir = runDir.resolve(repo.replaceAll("[^A-Za-z0-9._-]", "-"));
            Files.createDirectories(repoDir);
            StringBuilder lines = new StringBuilder();
            for (String event : events) {
                lines.append(jsonString(event)).append('\n');
            }
            Files.writeString(repoDir.resolve("agent-events.jsonl"), lines.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String jsonString(String value) {
        try {
            return JSON.writeValueAsString(value == null ? "" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"\"";
        }
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-cli:test
git add sdd-cli/src
git commit -m "feat: resumable run-state persistence (state.json + events.jsonl)"
```

---

### Task 5: Scheduler + pre-flight

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/Scheduler.java`, `PreFlight.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/SchedulerTest.java`, `PreFlightTest.java`

**Interfaces:**
- Produces:
  - `public final class Scheduler` — `static List<String> sequence(List<List<String>> order)` (flattened execution order); `static Set<String> upstreams(String repo, List<PlanModel.PlanEdge> edges)` (the provider repos this one depends on: `edge.toRepo` where `edge.fromRepo == repo` — real plan.json direction is `from=consumer, to=provider`, per `PlanJson.java:73-104`); `static boolean blockedByUpstream(String repo, List<PlanModel.PlanEdge> edges, RunState state)` (true iff any upstream is `FAILED` or `SKIPPED_UPSTREAM_FAILED`).
  - `public final class PreFlight` — `record Result(boolean ok, List<String> problems)`; `static Result check(Map<String, RepoStep> steps, PlanModel plan)` — per repo: `repoRoot` is a dir, `gradlew` is executable, working tree clean, live `RunGit.head` == plan `base_sha` (drift ⇒ problem). Uses `RunGit`.
- Consumes: `PlanModel`, `RunState`/`RepoState`, `RunGit`, `RepoStep`.

- [ ] **Step 1: Write the failing tests:**

`SchedulerTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTest {
    // real plan.json edge direction: from_repo = consumer, to_repo = provider (svc consumes lib)
    private static final List<PlanModel.PlanEdge> EDGES = List.of(
            new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"));

    @Test
    void flattensOrderAndFindsUpstreams() {
        assertThat(Scheduler.sequence(List.of(List.of("lib"), List.of("svc")))).containsExactly("lib", "svc");
        assertThat(Scheduler.upstreams("svc", EDGES)).containsExactly("lib");
        assertThat(Scheduler.upstreams("lib", EDGES)).isEmpty();
    }

    @Test
    void blocksADownstreamWhenItsUpstreamFailed() {
        RunState state = new RunState("R", List.of("lib", "svc"));
        assertThat(Scheduler.blockedByUpstream("svc", EDGES, state)).isFalse();   // lib PENDING/running
        state.set("lib", RepoState.FAILED, null, null, "boom");
        assertThat(Scheduler.blockedByUpstream("svc", EDGES, state)).isTrue();
        assertThat(Scheduler.blockedByUpstream("lib", EDGES, state)).isFalse();   // no upstreams
    }

    @Test
    void cascadesThroughASkippedUpstream() {
        RunState state = new RunState("R", List.of("lib", "svc", "app"));
        // app consumes svc consumes lib (from=consumer, to=provider)
        List<PlanModel.PlanEdge> chain = List.of(
                new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("app", "svc", "SNAPSHOT", "INCLUDE_BUILD"));
        state.set("lib", RepoState.FAILED, null, null, "boom");
        state.set("svc", RepoState.SKIPPED_UPSTREAM_FAILED, null, null, "upstream lib failed");
        assertThat(Scheduler.blockedByUpstream("app", chain, state)).isTrue();
    }
}
```

`PreFlightTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.RepoStep;
import sdd.core.testing.FixtureRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreFlightTest {
    @TempDir Path tmp;

    private RepoStep step(Path root) {
        return new RepoStep("lib", root, "s", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private void gradlew(Path root) throws Exception {
        Path g = root.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private PlanModel planWithBase(String base) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", base)),
                List.of(List.of("lib")), List.of(), List.of(), List.of());
    }

    @Test
    void passesOnACleanRepoAtBaseSha() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        gradlew(repo.path());
        repo.commit("with gradlew");
        String base = RunGit.head(repo.path());

        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase(base));

        assertThat(result.ok()).isTrue();
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void flagsDriftAndMissingWrapper() throws Exception {
        FixtureRepo repo = FixtureRepo.in(tmp, "lib").file("A.java", "class A {}\n").commit("base");
        // no gradlew; base_sha points at a different sha
        PreFlight.Result result = PreFlight.check(
                Map.of("lib", step(repo.path())), planWithBase("0000000000000000000000000000000000000000"));

        assertThat(result.ok()).isFalse();
        assertThat(result.problems()).anyMatch(p -> p.contains("gradle wrapper"))
                .anyMatch(p -> p.contains("HEAD"));
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `Scheduler.java`:

```java
package sdd.cli.implement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure scheduling logic over the plan's pre-grouped order + dependency edges. Sequential this phase;
 *  parallel-within-level is 4C-3. */
public final class Scheduler {
    private Scheduler() {
    }

    public static List<String> sequence(List<List<String>> order) {
        return order.stream().flatMap(List::stream).toList();
    }

    public static Set<String> upstreams(String repo, List<PlanModel.PlanEdge> edges) {
        // Real plan.json edge direction (PlanJson.java:73-104): from_repo = consumer, to_repo = provider.
        // A repo's upstream providers are the to_repo of edges where THIS repo is the consuming from_repo.
        Set<String> up = new LinkedHashSet<>();
        for (PlanModel.PlanEdge edge : edges) {
            if (edge.fromRepo().equals(repo)) {
                up.add(edge.toRepo());
            }
        }
        return up;
    }

    public static boolean blockedByUpstream(String repo, List<PlanModel.PlanEdge> edges, RunState state) {
        for (String up : upstreams(repo, edges)) {
            RepoState s = state.stateOf(up);
            if (s == RepoState.FAILED || s == RepoState.SKIPPED_UPSTREAM_FAILED) {
                return true;
            }
        }
        return false;
    }
}
```

`PreFlight.java`:

```java
package sdd.cli.implement;

import sdd.agent.run.RepoStep;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gates a run before any repo executes: clean trees at the pinned base SHAs, with runnable wrappers.
 *  Full M8 staleness recovery (re-index / auto-advance on drift) is 4C-3; this phase hard-fails on drift. */
public final class PreFlight {
    private PreFlight() {
    }

    public record Result(boolean ok, List<String> problems) {
        public Result {
            problems = List.copyOf(problems);
        }
    }

    public static Result check(Map<String, RepoStep> steps, PlanModel plan) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, RepoStep> entry : steps.entrySet()) {
            String repo = entry.getKey();
            var root = entry.getValue().repoRoot();
            if (!Files.isDirectory(root)) {
                problems.add(repo + ": checkout not found at " + root);
                continue;
            }
            if (!Files.isExecutable(root.resolve("gradlew"))) {
                problems.add(repo + ": no executable gradle wrapper at " + root.resolve("gradlew"));
            }
            String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
            try {
                if (!RunGit.isClean(root)) {
                    problems.add(repo + ": working tree is dirty");
                }
                String head = RunGit.head(root);
                if (!base.isEmpty() && !head.equals(base)) {
                    problems.add(repo + ": HEAD " + head + " has drifted from the plan base " + base
                            + " — re-approve the plan");
                }
            } catch (IllegalStateException e) {
                problems.add(repo + ": " + e.getMessage());
            }
        }
        return new Result(problems.isEmpty(), problems);
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-cli:test
git add sdd-cli/src
git commit -m "feat: scheduler cascade + run pre-flight"
```

---

### Task 6: Orchestrator — the single-attempt run loop

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/Orchestrator.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/OrchestratorTest.java`

**Interfaces:**
- Produces: `public final class Orchestrator` with nested `public record RunResult(int exitCode, RunState state)`; ctor `Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName, Function<String, RunnerSettings> settingsFor, RunStore store, InstantSource clock)`; `RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps)`. `RunState` is seeded from the RUNNABLE subset — `Scheduler.sequence(plan.order())` filtered to repos that have a step (step-less bom/bump-only sites are excluded so they don't orphan at `PENDING`). Walks that subset: skip→`SKIPPED_UPSTREAM_FAILED` when `blockedByUpstream`; else `IN_PROGRESS` → `RunGit.startBranch(repoRoot, "sdd/<runId>/<slug(repo)>", baseSha)` → `runner.run(step, coder, name, settingsFor(repo))` → capture `outcome.events()` to the repo's `agent-events.jsonl` → on `SUCCESS` commit checkpoint + `SUCCEEDED`, else `FAILED`. Persists state + events on every transition; `releaseLock` in a `finally` (with the first `writeState` inside the `try`). Exit code: `0` if all `SUCCEEDED`, else `2`.
- Consumes: `RepoStepRunner`, `ChatModel`, `RunnerSettings`, `StepOutcome`/`StepResult` (sdd-agent), `RunGit`, `RunStore`, `RunState`, `Scheduler`, `PlanModel`.
- Note: `runDir` is created by the caller (`RunStore.create`); the orchestrator reads `runId` from `runDir.getFileName()`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli.implement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void kb() {
        db = Database.open(ws);
    }

    private static ChatResponse call(String id, String tool, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, tool, args)), null), "tool_calls", new Usage(10, 5));
    }

    private FixtureRepo repoWith(String name, String gradlewScript) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + gradlewScript + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo.commit("base");
    }

    private static RepoStep step(String repo, Path root) {
        return new RepoStep(repo, root, "edit A", List.of(), List.of("A.java"), List.of(), List.of(), List.of());
    }

    private static PlanModel plan(String libBase, String svcBase) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "CODE_CHANGE_LIKELY", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD")),   // svc consumes lib
                List.of(), List.of());
    }

    private Orchestrator orchestrator(ScriptedChatModel model) {
        return new Orchestrator(new RepoStepRunner(db.jdbi()), model, "qwen",
                repo -> RunnerSettings.defaults(null), new RunStore(InstantSource.fixed(Instant.EPOCH)),
                InstantSource.fixed(Instant.EPOCH));
    }

    @Test
    void runsBothReposAndCommitsCheckpoints() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        // each repo: apply_edit then done → verify (gradlew exit 0) → SUCCESS
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"lib done\"}"),
                call("3", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int y; }\"}"),
                call("4", "done", "{\"result\":\"success\",\"summary\":\"svc done\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SUCCEEDED);
        assertThat(Files.readString(runDir.resolve("state.json"))).contains("SUCCEEDED");
        assertThat(Files.readString(lib.path().resolve("A.java"))).contains("int x;");
        assertThat(Files.exists(runDir.resolve("lib/agent-events.jsonl"))).isTrue();   // events captured
    }

    @Test
    void failedUpstreamCascadesToDownstreamSkip() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 1");   // verify always fails → lib FAILED
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of("lib", step("lib", lib.path()), "svc", step("svc", svc.path()));
        // lib: two done→verify-fail cycles → VERIFY_FAILED; svc is never reached (skipped)
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try1\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try2\"}")));

        Orchestrator.RunResult result = orchestrator(model).run(runDir, plan(lib.headSha(), svc.headSha()), steps);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `Orchestrator.java`:

```java
package sdd.cli.implement;

import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.agent.run.StepOutcome;
import sdd.agent.run.StepResult;
import sdd.core.llm.ChatModel;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Drives one attempt per repo across the plan's execution order (design Component 3 orchestration,
 * single-attempt slice): branch off base, run the 4B agent, checkpoint-commit on success, cascade a
 * failure to downstream skips, persisting state + events throughout. Multi-attempt/escalation,
 * propagation, and resilience are later 4C sub-phases.
 */
public final class Orchestrator {
    private final RepoStepRunner runner;
    private final ChatModel coder;
    private final String coderModelName;
    private final Function<String, RunnerSettings> settingsFor;
    private final RunStore store;
    private final InstantSource clock;

    public record RunResult(int exitCode, RunState state) {
    }

    public Orchestrator(RepoStepRunner runner, ChatModel coder, String coderModelName,
                        Function<String, RunnerSettings> settingsFor, RunStore store, InstantSource clock) {
        this.runner = runner;
        this.coder = coder;
        this.coderModelName = coderModelName;
        this.settingsFor = settingsFor;
        this.store = store;
        this.clock = clock;
    }

    public RunResult run(Path runDir, PlanModel plan, Map<String, RepoStep> steps) {
        String runId = runDir.getFileName().toString();
        // Only repos with a runnable step are tracked. Step-less affected repos (bom / bump-only sites,
        // whose version-bump edits are 4C-2) would otherwise orphan at PENDING and force a spurious exit 2.
        List<String> runnable = Scheduler.sequence(plan.order()).stream()
                .filter(steps::containsKey).toList();
        RunState state = new RunState(runId, runnable);
        try {
            store.writeState(runDir, state);   // inside the try so an IO failure still releases the lock
            for (String repo : runnable) {
                RepoStep step = steps.get(repo);
                if (Scheduler.blockedByUpstream(repo, plan.edges(), state)) {
                    transition(runDir, state, repo, RepoState.SKIPPED_UPSTREAM_FAILED, null, null,
                            "upstream failed");
                    continue;
                }
                transition(runDir, state, repo, RepoState.IN_PROGRESS, null, null, "");
                String branch = "sdd/" + runId + "/" + slug(repo);
                String base = plan.repo(repo).map(PlanModel.PlanRepo::baseSha).orElse("");
                RunGit.startBranch(step.repoRoot(), branch, base);
                StepOutcome outcome = runner.run(step, coder, coderModelName, settingsFor.apply(repo));
                store.writeAgentEvents(runDir, repo, outcome.events());
                if (outcome.result() == StepResult.SUCCESS) {
                    String sha = RunGit.commitAll(step.repoRoot(), "sdd: " + runId + " " + repo);
                    transition(runDir, state, repo, RepoState.SUCCEEDED, branch, sha, outcome.summary());
                } else {
                    transition(runDir, state, repo, RepoState.FAILED, branch, null,
                            outcome.result() + ": " + outcome.summary());
                }
            }
        } finally {
            store.releaseLock(runDir);
        }
        boolean allSucceeded = state.repos().stream().allMatch(r -> r.state() == RepoState.SUCCEEDED);
        return new RunResult(allSucceeded ? 0 : 2, state);
    }

    private static String slug(String repo) {
        return repo.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private void transition(Path runDir, RunState state, String repo, RepoState to, String branch,
                            String sha, String detail) {
        RepoState from = state.stateOf(repo);
        state.set(repo, to, branch, sha, detail);
        store.appendEvent(runDir, repo, from, to, detail);
        store.writeState(runDir, state);
    }
}
```

- [ ] **Step 3: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 4: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: single-attempt run orchestrator with failure cascade"
```

---

### Task 7: `sdd implement` CLI command

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/SddCli.java` (register the subcommand)
- Test: `sdd-cli/src/test/java/sdd/cli/ImplementCommandTest.java`

**Interfaces:**
- Produces: `@Command(name = "implement") public final class ImplementCommand implements Callable<Integer>` with `@Option --workspace`, `@Parameters Path planJsonPath`, `@Spec CommandSpec spec`, and a package-private test seam `ChatModel coderForTest`. Flow: read plan.json (`PlanJsonReader`), derive+read spec sibling (`.plan.json`→`.md`, `SpecParser.parse`, warn on `spec_sha256` mismatch), KB guard + `Database.open`, repo→path map, `RepoStepResolver.resolve`, build coder `ChatModel` (`coderForTest` or `new HttpChatModel(config.models().get("coder"))`), a per-repo `RunnerSettings` via `GradleExtractor.jdkMajorFor(wrapperVersion(root))` + `config.jdkHomes()`, `runId = sanitize(specId)+"-v"+planVersion`, `PreFlight.check` (fail ⇒ `problem:` lines + exit 4), `RunStore.create` + `Orchestrator.run`, print a per-repo status line + return the run's exit code (0/2, or 4 on abort). Register in `SddCli.subcommands`.
- Consumes: everything above + `sdd.core.config.{SddConfig,ConfigLoader,ModelEndpoint}`, `sdd.core.llm.HttpChatModel`, `sdd.core.db.Database`, `sdd.index.gradle.GradleExtractor`, `sdd.plan.spec.SpecParser`, `sdd.plan.approve.Hashes`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.FixtureRepo;
import sdd.core.testing.ScriptedChatModel;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImplementCommandTest {
    @TempDir Path ws;

    private FixtureRepo repo(String name) throws Exception {
        FixtureRepo repo = FixtureRepo.in(ws, name).file("A.java", "class A {}\n");
        Path g = repo.path().resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
        // a real wrapper version so jdkMajorFor resolves
        Path props = repo.path().resolve("gradle/wrapper/gradle-wrapper.properties");
        Files.createDirectories(props.getParent());
        Files.writeString(props, "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        return repo.commit("base");
    }

    @Test
    void runsASingleRepoPlanToCompletion() throws Exception {
        FixtureRepo lib = repo("lib");
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> h.execute(
                    "INSERT INTO repo(name, path, kind) VALUES ('lib', ?, 'LIBRARY')", lib.path().toString()));
        }
        Files.writeString(ws.resolve("sdd.yml"), """
                models:
                  planner: { base_url: http://x/v1, model: p, api_key: k }
                  coder: { base_url: http://y/v1, model: qwen }
                """);
        Files.writeString(ws.resolve("s.md"), """
                ---
                id: SPEC-101
                title: Tiers
                owner: me
                status: approved
                ---

                ## Goal
                g

                ## Requirements
                - R1: Expose tierFor.

                ## Acceptance Criteria
                - A1: tierFor returns a tier.
                """);
        String specSha = sdd.plan.approve.Hashes.sha256(Files.readString(ws.resolve("s.md")));
        Files.writeString(ws.resolve("s.plan.json"), """
                { "spec_id":"SPEC-101","plan_version":1,"spec_sha256":"%s","plan_sha256":"z",
                  "repos":[{"name":"lib","role":"seed","annotation":"SEED","version_action":"minor","base_sha":"%s"}],
                  "order":[["lib"]],"edges":[],"contracts":[],
                  "steps":[{"repo":"lib","covers":["R1"],"version_action":"minor","provides":[],"consumes":[],
                    "files":["A.java"],"verification":[],"sub_spec":"Add x to A."}] }
                """.formatted(specSha, lib.headSha()));

        ImplementCommand cmd = new ImplementCommand();
        cmd.coderForTest = new ScriptedChatModel(List.of(
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("1", "apply_edit",
                                "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}")),
                        null), "tool_calls", new Usage(10, 5)),
                new ChatResponse(new ChatMessage("assistant", null,
                        List.of(new ToolCall("2", "done", "{\"result\":\"success\",\"summary\":\"done\"}")),
                        null), "tool_calls", new Usage(10, 5))));

        StringWriter out = new StringWriter();
        CommandLine cli = new CommandLine(cmd);
        cli.setOut(new PrintWriter(out));
        int exit = cli.execute("--workspace", ws.toString(), ws.resolve("s.plan.json").toString());

        assertThat(exit).isEqualTo(0);
        assertThat(out.toString()).contains("lib").contains("SUCCEEDED");
        assertThat(Files.exists(ws.resolve(".sdd/runs/SPEC-101-v1/state.json"))).isTrue();
        assertThat(Files.readString(lib.path().resolve("A.java"))).contains("int x;");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `ImplementCommand.java`:

```java
package sdd.cli;

import org.jdbi.v3.core.Jdbi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import sdd.agent.run.RepoStep;
import sdd.agent.run.RepoStepRunner;
import sdd.agent.run.RunnerSettings;
import sdd.cli.implement.Orchestrator;
import sdd.cli.implement.PlanJsonReader;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.PreFlight;
import sdd.cli.implement.RepoStepResolver;
import sdd.cli.implement.RunStore;
import sdd.core.config.ConfigLoader;
import sdd.core.config.ModelEndpoint;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.llm.ChatModel;
import sdd.core.llm.HttpChatModel;
import sdd.index.gradle.GradleExtractor;
import sdd.plan.approve.Hashes;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecParser;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

@Command(name = "implement",
        description = "Execute an approved plan.json across the estate (single attempt per repo)")
public final class ImplementCommand implements Callable<Integer> {
    @Option(names = "--workspace", description = "Workspace directory (default: current dir)")
    Path workspace = Path.of(".");

    @Parameters(index = "0", description = "The approved <spec>.plan.json")
    Path planJsonPath;

    @Spec CommandSpec spec;

    ChatModel coderForTest;   // test seam — mirrors ApproveCommand.smokeForTest

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        try {
            String name = planJsonPath.getFileName().toString();
            if (!name.endsWith(".plan.json")) {
                err.println("error: implement expects a .plan.json file");
                return 4;
            }
            PlanModel plan = PlanJsonReader.read(Files.readString(planJsonPath));
            Path specPath = planJsonPath.resolveSibling(
                    name.substring(0, name.length() - ".plan.json".length()) + ".md");
            String specText = Files.readString(specPath);
            NormalizedSpec parsedSpec = SpecParser.parse(specText);
            if (!plan.specSha256().isEmpty() && !Hashes.sha256(specText).equals(plan.specSha256())) {
                out.println("warn: spec " + specPath.getFileName() + " has changed since approval — "
                        + "requirement text may not match the plan");
            }
            if (!Files.exists(workspace.resolve(".sdd/index.db"))) {
                err.println("error: knowledge base is empty — run sdd index first");
                return 4;
            }
            SddConfig config = ConfigLoader.load(workspace);
            try (Database db = Database.open(workspace)) {
                Jdbi jdbi = db.jdbi();
                Map<String, Path> paths = new HashMap<>();
                jdbi.useHandle(h -> h.createQuery("SELECT name, path FROM repo").mapToMap()
                        .forEach(row -> paths.put(String.valueOf(row.get("name")),
                                Path.of(String.valueOf(row.get("path"))))));
                Map<String, RepoStep> steps = RepoStepResolver.resolve(plan, parsedSpec, paths);

                PreFlight.Result preflight = PreFlight.check(steps, plan);
                if (!preflight.ok()) {
                    for (String problem : preflight.problems()) {
                        err.println("problem: " + problem);
                    }
                    return 4;
                }

                ModelEndpoint coderEndpoint = config.models().get("coder");
                ChatModel coder = coderForTest != null ? coderForTest : new HttpChatModel(coderEndpoint);
                String coderName = coderEndpoint.model();
                Function<String, RunnerSettings> settingsFor = repo -> {
                    Path root = steps.get(repo).repoRoot();
                    Path javaHome = config.jdkHomes()
                            .get(GradleExtractor.jdkMajorFor(GradleExtractor.wrapperVersion(root)));
                    return RunnerSettings.defaults(javaHome);
                };

                String runId = sanitize(plan.specId()) + "-v" + plan.planVersion();
                RunStore store = RunStore.system();
                Path runDir = store.create(workspace, runId, Files.readString(planJsonPath));
                Orchestrator orchestrator = new Orchestrator(new RepoStepRunner(jdbi), coder, coderName,
                        settingsFor, store, java.time.InstantSource.system());
                Orchestrator.RunResult result = orchestrator.run(runDir, plan, steps);

                for (var repo : result.state().repos()) {
                    out.println(repo.repo() + ": " + repo.state()
                            + (repo.detail() == null || repo.detail().isBlank() ? "" : " — " + repo.detail()));
                }
                out.println("run " + runId + " " + (result.exitCode() == 0 ? "COMPLETE" : "PARTIAL")
                        + " (state: " + runDir.resolve("state.json") + ")");
                return result.exitCode();
            }
        } catch (RuntimeException | java.io.IOException e) {
            err.println("error: " + e.getMessage());
            return 4;
        }
    }

    private static String sanitize(String id) {
        String cleaned = id == null ? "" : id.replaceAll("[^A-Za-z0-9._-]", "-");
        return cleaned.isBlank() ? "run" : cleaned;
    }
}
```

- [ ] **Step 3: Register the subcommand** in `SddCli.java`: add `ImplementCommand.class` to the `subcommands` array:

```java
        subcommands = {DoctorCommand.class, IndexCommand.class, PlanCommand.class, GraphCommand.class,
                ImplementCommand.class})
```

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Full build, then commit**

```bash
./gradlew build
git add sdd-cli/src
git commit -m "feat: sdd implement command wiring the orchestration core"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. `OrchestratorTest` proves the multi-repo happy path (both repos succeed, checkpoints committed, state.json written) and the failure cascade (upstream `FAILED` → downstream `SKIPPED_UPSTREAM_FAILED`, exit 2). `ImplementCommandTest` proves the CLI wires config + KB + spec + a scripted coder into a real single-repo run producing `.sdd/runs/<runId>/state.json`. The unit tasks (reader/resolver/git/store/scheduler/preflight) pin the pieces.
3. Real-estate smoke (an actual coding run on the trading estate) needs the served Qwen coder — deferred to when the model is available; 4C-1 is exercised entirely through `ScriptedChatModel` + `FixtureRepo`s + stub `gradlew` scripts. Propagation (needed for a *dependent* repo to compile against an in-run upstream) is 4C-2.

## Self-Review (completed at write time)

1. **Spec coverage (Component 3 orchestration, single-attempt slice):** plan.json read model + tree parser → Task 1; plan→`RepoStep` join (contracts + requirements + KB paths) → Task 2; write-capable JGit facade (branch/commit/reset) → Task 3; resumable run-state (immutable plan.json, atomic state.json, events.jsonl, lock) → Task 4; scheduler order-walk + upstream-failure cascade + pre-flight (clean-tree/base-SHA) → Task 5; single-attempt run loop with checkpoint-commit + cascade + persistence → Task 6; `sdd implement` CLI + exit taxonomy + config/KB/model wiring → Task 7.
2. **Deferred (recorded in Global Constraints):** **4C-2** — propagation injection (`--include-build`/mavenLocal/version-bump; the `GradleTool` extra-args change to `sdd-agent`); **4C-3** — multi-attempt + DeepSeek escalation + hard-reset-to-base + attempt-1 digest, INFRA classification + retry-once + `PAUSED_INFRA`, `PAUSED_ENDPOINT` + `--wait-endpoint`, `--resume` + crash-resume idempotency, full M8 staleness recovery, virtual-thread concurrency + `gradle_workers`/`model_concurrency` semaphores, run-wide 30M-token budget, per-repo `sdd.yml` verification exclusions (M7), contract actualization hook (4D). **Run-dir / operational deferrals recorded here** (surfaced by the final-review critics): the design's full per-repo `transcript.jsonl` (model turns) + `edits.jsonl` need a 4B change to expose the `ContextWindow` turns / applied-edit list — 4C-1 ships `agent-events.jsonl` (`StepOutcome.events()`) only; `m2/` is propagation's (4C-2); `review/` is Phase 5's; per-transition live console lines (design line 71) are simplified to an end-of-run summary (the `Orchestrator` is a library with no writer; a `Consumer<String>` progress hook is a later nicety); the spec is read live (not snapshotted into the run dir) — 4C-3's `--resume` should snapshot it like plan.json; `settingsFor` silently falls back to the ambient JDK when no `jdkHomes` entry matches (a "no JDK for Gradle X" guard is a real-estate-time improvement); `gradlew --version` warm-up + disk-space checks are dropped from pre-flight (4C-3); and — a known rough edge until `--resume` (4C-3) — a `FAILED` repo is left with the agent's uncommitted edits on its run branch, so re-running the same plan aborts at pre-flight's clean-tree check and needs manual cleanup or a hard reset first. Note the cascade AMPLIFIES an unclassified INFRA flake (4C-3 closes this): a transient daemon/network blip at an upstream's verify gate becomes `FAILED` and cascade-skips its whole downstream subtree.
3. **Design interpretations to ratify at review** (design-underspecified, resolved here): (a) `sdd implement` takes the `<spec>.plan.json` path (not a bare `<runId>`, which does not yet exist post-approve) and bootstraps `.sdd/runs/<runId>/`; (b) `runId = <sanitized spec_id>-v<plan_version>`, stable per plan version; (c) per-repo states `PENDING/IN_PROGRESS/SUCCEEDED/FAILED/SKIPPED_UPSTREAM_FAILED`; (d) exit taxonomy `0/2/4` (3=PAUSED is 4C-3); (e) sequential walk (parallel-within-level → 4C-3); (f) pre-flight hard-fails on HEAD drift (full M8 → 4C-3); (g) spec-hash mismatch warns (does not abort) so an edited spec doesn't hard-block, while flagging the drift.
4. **Placeholder scan:** none; every code step is complete.
5. **Type consistency:** `PlanModel` + nested records used by Tasks 1,2,5,6,7; `PlanJsonReader.read(String)→PlanModel` in 1,7; `RepoStepResolver.resolve(PlanModel, NormalizedSpec, Map<String,Path>)→Map<String,RepoStep>` in 2,7; `RunGit.{head,isClean,startBranch,commitAll,resetHard}` in 3,5,6; `RepoState`/`RepoRun`/`RunState(runId, repoNames)`/`RunStore(clock).{create,writeState,appendEvent,writeAgentEvents,releaseLock}` in 4,6,7; `Scheduler.{sequence,upstreams,blockedByUpstream}` (edge direction `from=consumer,to=provider`) + `PreFlight.check(steps, plan)→Result` in 5,6,7; `Orchestrator(runner, coder, name, settingsFor, store, clock).run(runDir, plan, steps)→RunResult(exitCode, state)` in 6,7; consumes the merged `RepoStep`/`ContractRef`/`RepoStepRunner(Jdbi).run(step, model, name, settings)→StepOutcome(result, summary,...)`/`RunnerSettings.defaults(Path)` (4B) and `HttpChatModel(ModelEndpoint)`/`ConfigLoader.load`/`SddConfig`/`GradleExtractor.{wrapperVersion,jdkMajorFor}`/`SpecParser.parse`/`Hashes.sha256` (existing).
6. **Adversarial hardening (3 critics against the real code):** compile-correctness — two fixes folded: `implementation(libs.jackson)` was missing (jackson is `implementation`-only everywhere, doesn't reach sdd-cli's compile classpath), and the `ImplementCommandTest` `s.md` needed real front matter + `## Acceptance Criteria` (SpecParser rejects otherwise). Design-conformance — one CRITICAL fix: the failure-cascade edge direction was inverted vs the real `PlanJson` writer (`from_repo=consumer, to_repo=provider`, verified at `PlanJson.java:73-104`), so `Scheduler.upstreams` + every edge fixture were corrected; plus step-less affected repos were orphaning at `PENDING` (fixed by seeding `RunState` from the runnable subset); plus cheap wins (sanitized branch slug, lock-leak move, capture dropped `StepOutcome.events()`); all 7 interpretations ratified. Test-quality — no vacuous/flaky tests; corroborated the `s.md` blocker and confirmed the corrected edge direction; hardened the `RunGitTest` empty-commit reliance.

---

## Execution Outcome (2026-08-12)

**Status: COMPLETE.** All 7 tasks implemented, reviewed, merged to `main`. Subagent-driven: fresh implementer per task + task review + fix loops, a final whole-branch review (most-capable tier), and one fix wave. `./gradlew build` green across all 5 modules.

**What shipped** (package `sdd.cli.implement` + `sdd.cli.ImplementCommand`, sdd-cli only; deps added: sdd-agent, jgit, jackson):
- `PlanJsonReader`→`PlanModel` (tree-based, snake_case) and `RepoStepResolver` (plan.json + spec + KB paths → 4B `RepoStep`s: contract-id→`ContractRef`, requirement-id→"R1: text", repoRoot from KB).
- `RunGit` (JGit branch/checkout/commit/reset; no push) and `RepoState`/`RepoRun`/`RunState`/`RunStore` (run dir `.sdd/runs/<runId>/`: immutable plan.json, atomic `state.json`, `events.jsonl`, per-repo `agent-events.jsonl`, `lock`).
- `Scheduler` (flatten the pre-grouped `order`; **transitive** upstream-failure cascade — edge direction `from=consumer,to=provider`) and `PreFlight` (clean-tree + base-SHA + wrapper + non-empty-base gate).
- `Orchestrator` — the single-attempt run loop (runnable subset → branch → `RepoStepRunner.run(coder)` → checkpoint-commit on SUCCESS / FAILED + cascade; lock in `finally`; exit 0/2).
- `sdd implement <plan.json>` — bootstraps the run dir, wires config + KB + coder model + per-repo JDK, exit `0` COMPLETE / `2` PARTIAL / `4` ABORTED.

**Branch commits:** sdd-explain amendment c392e7a; plan 248727f; T1 2f14d36; T2 b151e1a; T3 34de5bf; T4 5830ef2 + fix 727d43c; T5 24a8da6; T6 0b79a2f; T7 d2ccfe0; final fix wave 88d2b54.

**Interpretations ratified (a–g):** implement-takes-plan.json-path + bootstraps run dir; `runId = <spec_id>-v<plan_version>`; the 5 per-repo states; exit 0/2/4; sequential; hard-fail on HEAD drift; warn (not abort) on spec-hash mismatch. See Self-Review §3.

### Phase 4C-2 / 4C-3 entry pointers (what 4C-1 deferred, seam already shaped)
- **4C-2 = propagation** (the riskiest surgery — modifies shipped 4A/4B code): `--include-build <upstream-checkout>` primary + run-scoped `mavenLocal`/`m2` fallback + PINNED/BOM version-bump edits at the declaration site, per-edge mechanism from `plan.json.edges[].mechanism`. Requires threading an extra-Gradle-args list into `GradleTool` (both the agent's `run_gradle` and the verify gate) — likely `RunnerSettings` gains a `gradleExtraArgs`, and the Orchestrator computes each repo's inbound-edge flags. 4C-2 must also widen `PreFlight` to step-less bom sites (they get version-bump edits) and add `m2/` to the run dir.
- **4C-3 = resilience:** multi-attempt (attempt 1 coder → non-SUCCESS → `RunGit.resetHard(repoRoot, base_sha)` + attempt-1 digest → attempt 2 DeepSeek; the per-repo loop body is the insertion point, model/settings already on `run()`); INFRA classification (resolution/network/daemon/Docker patterns on the full `runFull` log → retry-once → `PAUSED_INFRA`/scoped-skip; `StepResult`/state likely gain an INFRA value); `PAUSED_ENDPOINT` + `--wait-endpoint`; `--resume` (verify branch HEADs = checkpoints, reset `IN_PROGRESS` to last checkpoint — must snapshot the spec into the run dir and NOT re-`create`/lock); full M8 staleness recovery; virtual-thread concurrency + `gradle_workers`/`model_concurrency` semaphores; run-wide 30M-token budget (accumulate `StepOutcome`/`AgentOutcome` tokens, pause on exhaustion); per-repo `sdd.yml` verification exclusions (M7 "not locally verified"); the design's full `transcript.jsonl`/`edits.jsonl` (needs a 4B `ContextWindow`/edit-list surface). Also fold the recorded final-review residuals: a **post-parse plan validation** pass (a hand-edited plan.json with a step whose repo is absent from `order` is silently dropped → false COMPLETE; a duplicated `order` entry runs a repo twice, the second `startBranch` wiping the first checkpoint); `@Command(exitCodeOnInvalidInput = 4)` so a picocli usage error doesn't collide with PARTIAL=2; and cross-run history mixing + a SUCCEEDED repo left on its run branch (re-running aborts at the drift gate with a misleading "re-approve" message) — both resolved by `--resume`/Phase-5 branch restore.
- **4D** (separate): contract actualization (re-extract real signatures from a green upstream into downstream work orders) + japicmp; the "after upstream green, before consumers start" seam exists in the sequential-per-level walk.
