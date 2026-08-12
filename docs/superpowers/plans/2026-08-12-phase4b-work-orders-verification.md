# Phase 4B — Work Orders + Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wrap the Phase-4A `AgentLoop` in a repo-step runner that builds a lean KB-grounded work order, compacts Gradle output deterministically (JUnit XML + javac, never console-scraped), runs an independent verification on `done`, and retries verify failures / restarts on context exhaustion — one attempt end-to-end.

**Architecture:** New package `sdd.agent.run` in the existing `sdd-agent` module (KB via `Jdbi` directly, mirroring sdd-plan — NO dependency on sdd-index or sdd-plan). `WorkOrder` reads the KB (repo card + a ranked file manifest from `file_ref`/`java_type`) into a ≤25k-token prompt; `OutputCompactor` turns raw Gradle output into a ≤4k-token summary; `Toolbox` gains a compactor-applying overload; `VerificationRunner` runs the deterministic gate; `RepoStepRunner` drives one attempt: work order → loop → verify → retry-on-verify-fail (≤2) → restart-on-exhaustion (≤1) → typed `StepOutcome`. Design authority: `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` Component 3 (work order, verification, context management, budgets).

**Tech Stack:** Java 21, Jdbi 3 (sdd-core), `javax.xml` DOM (no `jackson-dataformat-xml` on the classpath), the Phase-4A `sdd.agent.{tool,loop}` seam, `ScriptedChatModel` testFixtures. NO new dependencies.

## Global Constraints

- Java 21; no new dependencies; `sdd-agent` depends only on sdd-core (+ existing libs). This phase is a LIBRARY — no CLI, no config parsing, no `SddConfig` change (the runner takes its inputs as parameters; 4C wires them from plan.json + config).
- SCOPE is ONE ATTEMPT for one repo step, single model. The runner produces a typed `StepOutcome`; 4C calls it per repo, decides retries/escalation, and owns git + run state + config. Explicitly DEFERRED, grouped by the dependency that forces the deferral:
  - **Needs git + run state (→ 4C):** multi-attempt (max 2), attempt-2 escalation to DeepSeek, hard-reset-to-base between attempts, cross-repo orchestration, the `sdd implement` CLI, INFRA-failure classification + retry-once + `PAUSED_INFRA` (design: "INFRA-classified failures — resolution/network/daemon/Docker — never reach the agent"). **4B limitation to record:** with no INFRA classifier, an infra flake during `verify()` (dependency-resolution, daemon, timeout) compacts to non-`exit 0`, becomes `VERIFY_FAILED`, and is fed back to the agent to "fix" — burning a verify cycle on an unfixable error. 4C closes this.
  - **Needs config (`sdd.yml`/`SddConfig`) parsing, which this phase forbids (→ 4C):** per-repo verification-task exclusions surfaced as the design's M7 "not locally verified". This is a *config* dependency, not git.
  - **Needs the plan.json read model (→ 4C, in sdd-cli which sees both sdd-plan + sdd-agent):** parsing plan.json into `RepoStep`/`ContractRef`, and resolving `covers[]`/contract-ID strings against the spec + `contracts[]`.
- Deterministic-first / testing seam: the only model interaction is `ChatModel.complete` (via `AgentLoop`); every test scripts it with `ScriptedChatModel`. The runner adds NO agent tools and NO git verbs.
- Work order (design B2 — lean beats context-dump for a 35B): ≤25k tokens; contains the sub-spec, the covered requirement bullets, the interface contracts it PROVIDES (must expose) and CONSUMES (may rely on) with their bodies as never-trimmed floors, the repo card (graceful when absent — `repo_card` can be empty), and a ranked file manifest with one-line reasons: seed files (the step's own `files` + `is_api` types) → 1–2 hops over `file_ref` → matching test files (path heuristic). The agent reads files itself.
- Output compaction (design): ≤4k tokens, deterministic, from JUnit XML (`**/build/test-results/**/TEST-*.xml`, parsed via DOM — never console-scraped) + javac error lines (`<path>.java:<n>: error: <msg>`), with a per-section cap and an "N more omitted" marker; a green build with no failures compacts to a short tail. **The compactor MUST see the full build log, head-preserving.** `GradleTool.run()` tail-caps at 8000 chars, but javac prints root-cause errors at the HEAD; scraping the tail loses them. So the compacting path feeds the compactor `GradleTool.runFull()` (head-preserving cap) — this is the closure 4A's self-review promised 4B would deliver ("a stable per-error signature arrives with 4B's structured compaction"). JUnit XML is read off disk and is immune to either cap. The compactor is **task-gated**: it only harvests `build/test-results` XML for test-running tasks (`test`/`check`/`build`), so a `compileJava` run (or a timeout) never reports *stale* failures from an earlier test run. Project-package stack FILTERING (keeping only project frames) is deferred — it is string-only but needs a project-package prefix threaded in, which 4B does not have; 4B does the cheap part: for a failure/error whose `message` attribute is blank, it falls back to the first non-empty line of the element text so an exception isn't reported with no location at all.
- Verification: on `done`, the runner independently runs the deterministic gate (an allowlisted Gradle task, default `check`) via `GradleTool`; PASS iff the compacted result begins `exit 0`. The plan step's prose `verification[]` items are NOT runnable — they ride in the work order as acceptance guidance and surface in the outcome as "not locally verified" (human-confirmed). **Design interpretation to ratify at review** (spec:63 says the orchestrator "runs the plan step's verification tasks (per-repo exclusions from sdd.yml … M7)"): (a) the drafted `verification[]` entries are free-form DeepSeek prose ("Run lib-core tests"), not Gradle tasks (`PlanDrafter` imposes no format), and executing model-authored free text would violate the "allowlisted tasks, no generic shell" guardrail — so 4B runs one fixed allowlisted gate rather than the step's declared tasks; (b) 4B reuses the phrase "not locally verified" for these prose acceptance items, which is a DIFFERENT concept from M7's `sdd.yml`-excluded tasks (deferred to 4C). 4C must not overload the phrase.
- Verify-retry (design): a `done` whose verification fails re-runs the loop with the compacted failure appended (edits persist on disk); 2 done→verify-fail cycles ⇒ `VERIFY_FAILED`. Context exhaustion re-runs the loop once with a machine-built re-orientation digest; a second exhaustion ⇒ `EXHAUSTED`. Each re-run is a FRESH `AgentLoop.run()` — the design supplies no resume-an-existing-context entry point, and its continuity philosophy is machine-built re-orientation over disk-persisted edits ("edits persist on disk"; "No model summarization"), not the model's retained in-context memory. `AgentLoop.run()` is safe to call repeatedly on one instance: every per-run counter/window is a local inside `run()`, all instance fields are `final` (verified against 4A source), so the runner builds the loop once and re-invokes it.
- CLI/protocol taxonomy is inherited from 4A; nothing here changes `AgentLoop`.
- Never read or print `.env` or any `api_key`. Never push. Full `./gradlew build` before any commit touching more than one module.

---

## File Structure

**Task 1:** `sdd-agent/src/main/java/sdd/agent/run/{ContractRef,RepoStep,WorkOrder}.java` + test
**Task 2:** `sdd-agent/src/main/java/sdd/agent/run/OutputCompactor.java` + test
**Task 3:** `sdd-agent/src/main/java/sdd/agent/tool/GradleTool.java` (`runFull`) + `Toolbox.java` (compactor overload) + test
**Task 4:** `sdd-agent/src/main/java/sdd/agent/run/VerificationRunner.java` + test
**Task 5:** `sdd-agent/src/main/java/sdd/agent/run/{StepResult,StepOutcome,RepoStepRunner}.java` + test

---

### Task 1: Work order builder

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/run/ContractRef.java`, `RepoStep.java`, `WorkOrder.java`
- Test: `sdd-agent/src/test/java/sdd/agent/run/WorkOrderTest.java`

**Interfaces:**
- Produces (Tasks 4-5 depend on these):
  - `public record ContractRef(String id, String kind, String provider, List<String> consumers, String body)` — mirrors plan.json's contract shape; `List.copyOf`, `Objects.requireNonNull` on strings.
  - `public record RepoStep(String repo, Path repoRoot, String subSpec, List<String> requirements, List<String> files, List<ContractRef> provides, List<ContractRef> consumes, List<String> acceptanceChecks)` — `requirements` are pre-formatted `"R1: text"` (4C resolves from the spec); `files` are the step's file hints; `acceptanceChecks` are the prose `verification[]` items. All lists `List.copyOf`.
  - `public final class WorkOrder { public static String build(Jdbi jdbi, RepoStep step) }` — deterministic; constants `MAX_MANIFEST_FILES = 24`, `MAX_CARD_CHARS = 2000`.
- Consumes: `org.jdbi.v3.core.Jdbi` (sdd-core), the KB schema (`repo`, `module`, `java_type`, `file_ref`, `repo_card`).
- Manifest ranking (all queries carry a full ORDER BY for determinism): seed set = the step's `files` matched to real `java_type.file_path` (exact or `LIKE '%/' || <name>` for bare names) UNION `is_api=1` type files for the repo (ORDER BY `is_api DESC, file_path`); expansion = `file_ref.dst_file` where `src_file` ∈ seeds (ORDER BY `ref_count DESC, dst_file`); tests = repo `file_path`s matching `%/test/%` OR ending `Test.java`/`IT.java`. Dedup, cap at `MAX_MANIFEST_FILES`, each line `<path> — <reason>` (`seed`, `api surface`, `referenced (<n>)`, `test`).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkOrderTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/lib','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.TierResolver','CLASS',1,'src/main/java/com/acme/TierResolver.java')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.Internal','CLASS',0,'src/main/java/com/acme/Internal.java')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.TierResolverTest','CLASS',0,'src/test/java/com/acme/TierResolverTest.java')");
            h.execute("INSERT INTO file_ref(repo_id, src_file, dst_file, ref_count) "
                    + "VALUES (1,'src/main/java/com/acme/TierResolver.java','src/main/java/com/acme/Internal.java',5)");
            h.execute("INSERT INTO repo_card(repo_id, card_md, card_line, model, input_hash, created_at) "
                    + "VALUES (1,'## Purpose\\nResolves loyalty tiers.','Tier library.','qwen','h','t')");
        });
    }

    private static RepoStep step() {
        return new RepoStep("lib-core", Path.of("/w/lib"), "Add tierFor(clientId) to TierResolver.",
                List.of("R1: Price response includes the customer tier."),
                List.of("TierResolver.java"),
                List.of(new ContractRef("C-1", "java-api", "lib-core", List.of("svc-a"),
                        "Tier tierFor(String clientId)")),
                List.of(),
                List.of("Run lib-core tests", "Verify a changed mapping is observed without restart"));
    }

    @Test
    void buildsALeanGroundedWorkOrder() {
        String wo = WorkOrder.build(db.jdbi(), step());

        assertThat(wo)
                .contains("Add tierFor(clientId) to TierResolver.")
                .contains("R1: Price response includes the customer tier.")
                .contains("Provides").contains("C-1 (java-api)").contains("Tier tierFor(String clientId)")
                .contains("Resolves loyalty tiers.")                                   // repo card
                .contains("src/main/java/com/acme/TierResolver.java" + WorkOrder.SEP + "seed")          // step file + api
                .contains("src/main/java/com/acme/Internal.java" + WorkOrder.SEP + "referenced (5)")    // file_ref hop
                .contains("src/test/java/com/acme/TierResolverTest.java" + WorkOrder.SEP + "test")      // test heuristic
                .contains("Run lib-core tests");                                        // acceptance (human)
    }

    @Test
    void gracefulWithoutARepoCard() {
        db.jdbi().useHandle(h -> h.execute("DELETE FROM repo_card"));

        String wo = WorkOrder.build(db.jdbi(), step());

        assertThat(wo).contains("Add tierFor(clientId) to TierResolver.")
                .doesNotContain("null")
                .contains("(no repo card)");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `ContractRef.java`:

```java
package sdd.agent.run;

import java.util.List;
import java.util.Objects;

/** A plan.json interface contract as the work order embeds it. */
public record ContractRef(String id, String kind, String provider, List<String> consumers, String body) {
    public ContractRef {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(provider);
        consumers = List.copyOf(consumers);
        Objects.requireNonNull(body);
    }
}
```

`RepoStep.java`:

```java
package sdd.agent.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One repo's slice of an approved plan, as the runner receives it (4C fills it from plan.json + spec). */
public record RepoStep(String repo, Path repoRoot, String subSpec, List<String> requirements,
                       List<String> files, List<ContractRef> provides, List<ContractRef> consumes,
                       List<String> acceptanceChecks) {
    public RepoStep {
        Objects.requireNonNull(repo);
        Objects.requireNonNull(repoRoot);
        Objects.requireNonNull(subSpec);
        requirements = List.copyOf(requirements);
        files = List.copyOf(files);
        provides = List.copyOf(provides);
        consumes = List.copyOf(consumes);
        acceptanceChecks = List.copyOf(acceptanceChecks);
    }
}
```

`WorkOrder.java`:

```java
package sdd.agent.run;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the lean, KB-grounded work order (design B2): a fresh 35B coder needs the sub-spec, the
 * contracts it must honor, and a short ranked file manifest — not the whole repo. Deterministic
 * given the KB and the step.
 */
public final class WorkOrder {
    static final int MAX_MANIFEST_FILES = 24;
    static final int MAX_CARD_CHARS = 2000;
    /** Manifest line separator. Shared with the test so the em dash (U+2014) stays byte-identical. */
    public static final String SEP = " — ";

    private WorkOrder() {
    }

    public static String build(Jdbi jdbi, RepoStep step) {
        StringBuilder wo = new StringBuilder();
        wo.append("# Task for repo: ").append(step.repo()).append("\n\n");
        wo.append("## Sub-spec\n").append(step.subSpec().strip()).append("\n\n");

        if (!step.requirements().isEmpty()) {
            wo.append("## Requirements you are implementing\n");
            for (String requirement : step.requirements()) {
                wo.append("- ").append(requirement).append('\n');
            }
            wo.append('\n');
        }
        appendContracts(wo, "Provides (you MUST expose these)", step.provides());
        appendContracts(wo, "Consumes (you may rely on these)", step.consumes());

        wo.append("## Repo card\n");
        String card = repoCard(jdbi, step.repo());
        wo.append(card == null ? "(no repo card)" : card).append("\n\n");

        wo.append("## Files most likely relevant\n");
        for (String line : manifest(jdbi, step)) {
            wo.append("- ").append(line).append('\n');
        }
        wo.append('\n');

        if (!step.acceptanceChecks().isEmpty()) {
            wo.append("## Acceptance checks (a human will confirm these — not run locally)\n");
            for (String check : step.acceptanceChecks()) {
                wo.append("- ").append(check).append('\n');
            }
            wo.append('\n');
        }
        wo.append("""
                ## How to work
                Read the relevant files, make focused edits with apply_edit, and run_gradle to \
                check your work (compileJava, then test). Do not add unrelated changes. When the \
                sub-spec is implemented and compiles, call done with result=success and a short \
                summary. If you cannot proceed, call done with result=blocked and explain why.
                """);
        return wo.toString();
    }

    private static void appendContracts(StringBuilder wo, String title, List<ContractRef> contracts) {
        if (contracts.isEmpty()) {
            return;
        }
        wo.append("## ").append(title).append('\n');
        for (ContractRef contract : contracts) {
            wo.append("### ").append(contract.id()).append(" (").append(contract.kind()).append(")\n");
            wo.append(contract.body().strip()).append("\n\n");
        }
    }

    private static String repoCard(Jdbi jdbi, String repo) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT c.card_md FROM repo_card c
                        JOIN repo r ON r.id = c.repo_id
                        WHERE r.name = :r""")
                .bind("r", repo).mapTo(String.class).findOne()
                .map(md -> md.length() > MAX_CARD_CHARS ? md.substring(0, MAX_CARD_CHARS) : md)
                .orElse(null));
    }

    private static List<String> manifest(Jdbi jdbi, RepoStep step) {
        Map<String, String> ranked = new LinkedHashMap<>();   // path -> reason, insertion order = rank

        // seeds: the step's named files (resolved) + is_api type files
        List<Map<String, Object>> types = jdbi.withHandle(h -> h.createQuery("""
                        SELECT t.file_path AS path, t.is_api AS is_api
                        FROM java_type t
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r AND t.file_path IS NOT NULL
                        ORDER BY t.is_api DESC, t.file_path""")
                .bind("r", step.repo()).mapToMap().list());
        for (Map<String, Object> row : types) {
            String path = String.valueOf(row.get("path"));
            boolean seed = step.files().stream().anyMatch(f -> path.equals(f) || path.endsWith("/" + f));
            if (seed) {
                ranked.putIfAbsent(path, "seed");
            }
        }
        for (Map<String, Object> row : types) {
            String path = String.valueOf(row.get("path"));
            boolean api = ((Number) row.get("is_api")).intValue() == 1;
            boolean test = isTest(path);
            if (!ranked.containsKey(path) && api && !test) {
                ranked.putIfAbsent(path, "api surface");
            }
        }
        // 1-hop expansion over file_ref from the seeds
        List<String> seeds = new ArrayList<>(ranked.keySet());
        List<Map<String, Object>> refs = jdbi.withHandle(h -> h.createQuery("""
                        SELECT fr.dst_file AS dst, fr.ref_count AS n
                        FROM file_ref fr JOIN repo r ON r.id = fr.repo_id
                        WHERE r.name = :r AND fr.src_file IN (<seeds>)
                        ORDER BY fr.ref_count DESC, fr.dst_file""")
                .bind("r", step.repo()).bindList("seeds", seeds.isEmpty() ? List.of("") : seeds)
                .mapToMap().list());
        for (Map<String, Object> row : refs) {
            ranked.putIfAbsent(String.valueOf(row.get("dst")),
                    "referenced (" + ((Number) row.get("n")).intValue() + ")");
        }
        // matching tests
        for (Map<String, Object> row : types) {
            String path = String.valueOf(row.get("path"));
            if (!ranked.containsKey(path) && isTest(path)) {
                ranked.putIfAbsent(path, "test");
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : ranked.entrySet()) {
            if (lines.size() >= MAX_MANIFEST_FILES) {
                break;
            }
            lines.add(entry.getKey() + SEP + entry.getValue());
        }
        return lines;
    }

    private static boolean isTest(String path) {
        return path.contains("/test/") || path.endsWith("Test.java") || path.endsWith("IT.java");
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: kb-grounded lean work order builder"
```

---

### Task 2: OutputCompactor

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/run/OutputCompactor.java`
- Test: `sdd-agent/src/test/java/sdd/agent/run/OutputCompactorTest.java`

**Interfaces:**
- Produces: `public final class OutputCompactor` — ctor `(Path repoRoot)`; `String compact(String rawGradleOutput, String task)`. Constants `MAX_CHARS = 6000`, `MAX_ERRORS = 20`, `MAX_FAILURES = 20`; `TEST_TASKS = Set.of("test", "check", "build")`. There is NO `identity()` factory — the raw (no-compaction) path is expressed by a null compactor in `Toolbox` (Task 3), so nothing constructs a no-op compactor.
- Behavior: preserve the leading `exit <code>` / `timed out …` line. Scrape javac error lines (`Pattern` `^(.*\.java):(\d+): error: (.*)$`) from the raw output, cap at `MAX_ERRORS` + `"... N more compile errors omitted"`. Harvest JUnit XML ONLY when `TEST_TASKS.contains(task)` (a `compileJava` run or a timeout never surfaces stale failures): walk `repoRoot/**/build/test-results/**/*.xml` (skip if repoRoot absent), DOM-parse, and for each `<testcase>` with a `<failure>`/`<error>` child emit `<classname>#<name>: <type>: <detail>` where `<detail>` is the first line of the `message` attribute, or — when `message` is blank — the first non-empty line of the element text (so a bare exception keeps some location); cap at `MAX_FAILURES` + omitted marker, headed by `"<N> failed:"` (a count of failing testcases, not a sum of `<testsuite>` totals). If NO compile errors and NO test failures, return the exit line + the last ≤2000 chars of the raw output BODY (everything after the first line — the header is never duplicated). Final result truncated to `MAX_CHARS` (head kept, tail marked).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OutputCompactorTest {
    @TempDir Path repo;

    private void writeReport(String name, String body) throws Exception {
        Path results = Files.createDirectories(repo.resolve("svc/build/test-results/test"));
        Files.writeString(results.resolve(name), body);
    }

    @Test
    void scrapesJavacErrorsFromRawOutput() {
        String raw = """
                exit 1
                > Task :compileJava FAILED
                /r/src/main/java/A.java:12: error: cannot find symbol
                  symbol:   variable tier
                /r/src/main/java/A.java:40: error: ';' expected
                BUILD FAILED
                """;

        String compact = new OutputCompactor(repo).compact(raw, "compileJava");

        assertThat(compact).startsWith("exit 1")
                .contains("A.java:12: error: cannot find symbol")
                .contains("A.java:40: error: ';' expected");
    }

    @Test
    void summarizesJunitXmlFailuresNotConsoleText() throws Exception {
        writeReport("TEST-com.acme.FooTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.FooTest" tests="2" skipped="0" failures="1" errors="0">
                  <testcase name="passes" classname="com.acme.FooTest" time="0.01"/>
                  <testcase name="tierIsApplied" classname="com.acme.FooTest" time="0.02">
                    <failure message="expected: 2 but was: 1" type="org.opentest4j.AssertionFailedError">stack...</failure>
                  </testcase>
                </testsuite>
                """);

        String compact = new OutputCompactor(repo).compact("exit 1\nThere were failing tests.\n", "test");

        assertThat(compact).startsWith("exit 1")
                .contains("1 failed").contains("com.acme.FooTest#tierIsApplied")
                .contains("expected: 2 but was: 1")
                .doesNotContain("stack...");
    }

    @Test
    void staleTestResultsAreNotHarvestedForACompileTask() throws Exception {
        writeReport("TEST-com.acme.FooTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.FooTest" tests="1" failures="1" errors="0">
                  <testcase name="old" classname="com.acme.FooTest">
                    <failure message="stale failure" type="X">s</failure>
                  </testcase>
                </testsuite>
                """);

        String compact = new OutputCompactor(repo).compact("exit 0\nBUILD SUCCESSFUL\n", "compileJava");

        assertThat(compact).doesNotContain("stale failure").doesNotContain("failed");
    }

    @Test
    void blankFailureMessageFallsBackToElementText() throws Exception {
        writeReport("TEST-com.acme.BarTest.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.acme.BarTest" tests="1" failures="0" errors="1">
                  <testcase name="npes" classname="com.acme.BarTest">
                    <error message="" type="java.lang.NullPointerException">java.lang.NullPointerException
                	at com.acme.Bar.run(Bar.java:5)</error>
                  </testcase>
                </testsuite>
                """);

        String compact = new OutputCompactor(repo).compact("exit 1\n", "test");

        assertThat(compact).contains("com.acme.BarTest#npes")
                .contains("java.lang.NullPointerException");
    }

    @Test
    void greenBuildCompactsToAShortTailWithNoDuplicatedHeader() {
        String compact = new OutputCompactor(repo).compact("exit 0\nBUILD SUCCESSFUL in 3s\n", "check");

        assertThat(compact).startsWith("exit 0").contains("BUILD SUCCESSFUL")
                .doesNotContain("exit 0\nexit 0");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `OutputCompactor.java`:

```java
package sdd.agent.run;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns raw Gradle output into a deterministic ≤4k-token summary (design): javac error lines are
 * scraped from the console — fed the FULL log via GradleTool.runFull so head-of-log root-cause
 * errors survive — while test failures come from the JUnit XML reports on disk, NEVER from console
 * scraping. XML is harvested only for test-running tasks, so a compile-only run (or a timeout)
 * never surfaces stale failures from an earlier test run. A green build compacts to a short tail.
 */
public final class OutputCompactor {
    static final int MAX_CHARS = 6000;
    static final int MAX_ERRORS = 20;
    static final int MAX_FAILURES = 20;
    static final Set<String> TEST_TASKS = Set.of("test", "check", "build");
    private static final Pattern JAVAC = Pattern.compile("^(.*\\.java):(\\d+): error: (.*)$");

    private final Path repoRoot;

    public OutputCompactor(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public String compact(String rawGradleOutput, String task) {
        List<String> lines = rawGradleOutput.lines().toList();
        String header = lines.isEmpty() ? "" : lines.get(0);   // "exit N" / "timed out ..."
        StringBuilder out = new StringBuilder(header).append('\n');

        List<String> compileErrors = new ArrayList<>();
        for (String line : lines) {
            Matcher m = JAVAC.matcher(line);
            if (m.matches()) {
                compileErrors.add(shortPath(m.group(1)) + ":" + m.group(2) + ": error: " + m.group(3));
            }
        }
        if (!compileErrors.isEmpty()) {
            out.append("Compile errors:\n");
            appendCapped(out, compileErrors, MAX_ERRORS, "compile errors");
        }

        List<String> failures = TEST_TASKS.contains(task) ? testFailures() : List.of();
        if (!failures.isEmpty()) {
            out.append(failures.size()).append(" failed:\n");
            appendCapped(out, failures, MAX_FAILURES, "test failures");
        }

        if (compileErrors.isEmpty() && failures.isEmpty()) {
            int nl = rawGradleOutput.indexOf('\n');          // append the BODY only — header is never duplicated
            String body = nl >= 0 ? rawGradleOutput.substring(nl + 1) : "";
            String tail = body.length() > 2000 ? body.substring(body.length() - 2000) : body;
            out.append(tail);
        }
        String result = out.toString();
        return result.length() > MAX_CHARS
                ? result.substring(0, MAX_CHARS) + "\n... (compacted output truncated)" : result;
    }

    private static void appendCapped(StringBuilder out, List<String> items, int cap, String noun) {
        int shown = Math.min(items.size(), cap);
        for (int i = 0; i < shown; i++) {
            out.append("  ").append(items.get(i)).append('\n');
        }
        if (items.size() > cap) {
            out.append("  ... ").append(items.size() - cap).append(" more ").append(noun).append(" omitted\n");
        }
    }

    private List<String> testFailures() {
        List<String> failures = new ArrayList<>();
        if (repoRoot == null || !Files.isDirectory(repoRoot)) {
            return failures;
        }
        List<Path> reports = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/build/test-results/"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .forEach(reports::add);
        } catch (IOException e) {
            return failures;
        }
        for (Path report : reports) {
            parseReport(report, failures);
        }
        return failures;
    }

    private static void parseReport(Path report, List<String> failures) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document doc = factory.newDocumentBuilder().parse(report.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element testcase = (Element) cases.item(i);
                Element problem = firstChild(testcase, "failure");
                if (problem == null) {
                    problem = firstChild(testcase, "error");
                }
                if (problem != null) {
                    String message = problem.getAttribute("message");
                    String type = problem.getAttribute("type");
                    String detail = message.isBlank()   // blank message → first non-empty line of the element text
                            ? problem.getTextContent().lines().map(String::strip)
                                    .filter(s -> !s.isEmpty()).findFirst().orElse("")
                            : message.lines().findFirst().orElse("");
                    failures.add(testcase.getAttribute("classname") + "#" + testcase.getAttribute("name")
                            + ": " + type + ": " + detail);
                }
            }
        } catch (Exception e) {
            // unreadable/oddly-formatted report — skip, deterministic best effort
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return (Element) children.item(i);
            }
        }
        return null;
    }

    private static String shortPath(String path) {
        int slash = path.replace('\\', '/').lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: deterministic gradle output compactor (junit xml + javac)"
```

---

### Task 3: GradleTool full-log accessor + Toolbox compaction overload

**Files:**
- Modify: `sdd-agent/src/main/java/sdd/agent/tool/GradleTool.java`
- Modify: `sdd-agent/src/main/java/sdd/agent/tool/Toolbox.java`
- Test: `sdd-agent/src/test/java/sdd/agent/tool/ToolboxCompactionTest.java`

**Interfaces:**
- `GradleTool` gains `public String runFull(String task)` — identical to `run()` but HEAD-preserving (keeps the START of a long log so javac's first, root-cause errors survive for the compactor). `run()` and `runFull()` share a private `execute(task, headPreserving)`; new constant `MAX_FULL_OUTPUT = 200_000`. `run()`'s behavior is byte-identical to 4A (tail-cap at `MAX_OUTPUT`), so 4A's `GradleToolTest` stays green.
- `Toolbox` gains a NULLABLE `OutputCompactor compactor` field and a 3-arg ctor `Toolbox(FileTools, GradleTool, OutputCompactor)`; the existing `Toolbox(FileTools, GradleTool)` passes `null` (no compaction — 4A's raw `gradle.run` path, unchanged). The `run_gradle` dispatch is `compactor == null ? gradle.run(task) : compactor.compact(gradle.runFull(task), task)`. There is no identity compactor — null IS the raw path, and it also selects `run()` (tail-cap) vs `runFull()` (full log). Every other tool and all of `specs()`/`dispatch` routing is unchanged (4A's `ToolboxTest` stays green).
- Consumes: `sdd.agent.run.OutputCompactor` (Task 2).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.run.OutputCompactor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ToolboxCompactionTest {
    @TempDir Path repo;

    private void gradlew(String script) throws Exception {
        Path g = repo.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Toolbox compactingToolbox() {
        return new Toolbox(new FileTools(new PathJail(repo)),
                new GradleTool(repo, null, Duration.ofSeconds(10)),
                new OutputCompactor(repo));
    }

    @Test
    void runGradleResultIsCompactedWhenACompactorIsProvided() throws Exception {
        gradlew("echo '/r/A.java:3: error: bad'; exit 1");

        String result = compactingToolbox().dispatch("run_gradle", "{\"task\":\"compileJava\"}");

        assertThat(result).startsWith("exit 1").contains("A.java:3: error: bad")
                .doesNotContain("/r/A.java");   // compactor shortened the path
    }

    @Test
    void headJavacErrorSurvivesALogLongerThanTheTailCap() throws Exception {
        // >8000 chars with the root-cause error at the HEAD: run()'s tail-cap would drop it;
        // the compacting path uses runFull()'s head-preserving cap, so the compactor still sees it.
        gradlew("echo '/r/Root.java:1: error: first cause'\n"
                + "i=0; while [ $i -lt 400 ]; do echo 'noise noise noise noise noise noise noise'; i=$((i+1)); done\n"
                + "exit 1");

        String result = compactingToolbox().dispatch("run_gradle", "{\"task\":\"compileJava\"}");

        assertThat(result).contains("Root.java:1: error: first cause");
    }
}
```

- [ ] **Step 2a: Run — expect COMPILE FAILURE.** Edit `GradleTool.java`: add `MAX_FULL_OUTPUT`, extract the subprocess body into a private `execute(task, headPreserving)` that `run()` and the new `runFull()` share, and cap head- vs tail-preserving accordingly. `run()`'s output is byte-identical to before.

```java
    static final int MAX_OUTPUT = 8000;
    static final int MAX_FULL_OUTPUT = 200_000;
```

```java
    /** Model-facing output, tail-capped at MAX_OUTPUT (the 4A behavior). */
    public String run(String task) {
        return execute(task, false);
    }

    /**
     * The full build log, HEAD-preserving (capped at MAX_FULL_OUTPUT). javac prints root-cause
     * errors first, so the compactor — which scrapes head-first — must see the start of a long log,
     * not run()'s tail. Fed to OutputCompactor by the compacting Toolbox path and VerificationRunner.
     */
    public String runFull(String task) {
        return execute(task, true);
    }

    private String execute(String task, boolean headPreserving) {
        if (!ALLOWED.contains(task)) {
            throw new ToolException("gradle task not allowed: " + task);
        }
        Path gradlew = repoRoot.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            throw new ToolException("no gradle wrapper in " + repoRoot);
        }
        Path log = null;
        try {
            log = Files.createTempFile("sdd-agent-gradle", ".log");
            ProcessBuilder builder = new ProcessBuilder(List.of("./gradlew", task,
                    "--no-configuration-cache", "--no-daemon", "-q"));
            builder.directory(repoRoot.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(log.toFile());
            scrubEnvironment(builder.environment());
            Process process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return "timed out after " + timeout.toSeconds() + "s";
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            output = headPreserving ? headCap(output) : tailCap(output);
            return "exit " + process.exitValue() + "\n" + output;
        } catch (IOException e) {
            throw new ToolException("gradle run failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolException("gradle run interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    private static String tailCap(String output) {
        if (output.length() > MAX_OUTPUT) {
            return "... (head omitted)\n" + output.substring(output.length() - MAX_OUTPUT);
        }
        return output;
    }

    private static String headCap(String output) {
        if (output.length() > MAX_FULL_OUTPUT) {
            return output.substring(0, MAX_FULL_OUTPUT) + "\n... (tail omitted)";
        }
        return output;
    }
```

Delete the old inline `run(String task)` body (the `if (output.length() > MAX_OUTPUT)` tail-cap now lives in `tailCap`).

- [ ] **Step 2b: Edit `Toolbox.java`:** add a nullable `OutputCompactor` field, the 3-arg ctor, delegate the 2-arg ctor with `null`, and change the dispatch line:

```java
    private final FileTools files;
    private final GradleTool gradle;
    private final sdd.agent.run.OutputCompactor compactor;   // null = raw (no compaction), 4A path

    public Toolbox(FileTools files, GradleTool gradle) {
        this(files, gradle, null);
    }

    public Toolbox(FileTools files, GradleTool gradle, sdd.agent.run.OutputCompactor compactor) {
        this.files = files;
        this.gradle = gradle;
        this.compactor = compactor;
    }
```

and in `dispatch`:

```java
            case "run_gradle" -> {
                String task = str(args, "task");
                yield compactor == null ? gradle.run(task) : compactor.compact(gradle.runFull(task), task);
            }
```

- [ ] **Step 3: Run the full sdd-agent suite — expect PASS** (4A's `ToolboxTest` and `GradleToolTest` must stay green: the 2-arg `Toolbox` ctor now passes a null compactor, so `run_gradle` still returns raw `gradle.run` output, and `run()`'s tail-cap is unchanged).
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 4: Commit**

```bash
git add sdd-agent/src
git commit -m "feat: full-log gradle accessor + toolbox output compaction"
```

---

### Task 4: VerificationRunner

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/run/VerificationRunner.java`
- Test: `sdd-agent/src/test/java/sdd/agent/run/VerificationRunnerTest.java`

**Interfaces:**
- Produces: `public final class VerificationRunner` with nested `public record Verdict(boolean passed, String output)`; ctor `(GradleTool gradle, OutputCompactor compactor)`; `Verdict verify(String task)` — runs the allowlisted gradle task via `runFull` (full log), compacts it with the task, `passed = output.startsWith("exit 0")`.
- Consumes: `GradleTool` (4A), `OutputCompactor` (Task 2).

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.agent.tool.GradleTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationRunnerTest {
    @TempDir Path repo;

    private void wrapper(String script) throws Exception {
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private VerificationRunner runner() {
        return new VerificationRunner(new GradleTool(repo, null, Duration.ofSeconds(5)),
                new OutputCompactor(repo));
    }

    @Test
    void passesOnAGreenGate() throws Exception {
        wrapper("echo BUILD SUCCESSFUL; exit 0");

        VerificationRunner.Verdict verdict = runner().verify("check");

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.output()).startsWith("exit 0");
    }

    @Test
    void failsAndCarriesTheCompactedFailure() throws Exception {
        wrapper("echo '/r/A.java:9: error: nope'; exit 1");

        VerificationRunner.Verdict verdict = runner().verify("check");

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.output()).startsWith("exit 1").contains("A.java:9: error: nope");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `VerificationRunner.java`:

```java
package sdd.agent.run;

import sdd.agent.tool.GradleTool;

/**
 * The independent deterministic gate the runner applies on `done` (design M7): run an allowlisted
 * Gradle task and read the compacted result. Prose acceptance items from plan.json are NOT run
 * here — they ride in the work order and surface as human-confirmed.
 */
public final class VerificationRunner {
    private final GradleTool gradle;
    private final OutputCompactor compactor;

    public record Verdict(boolean passed, String output) {
    }

    public VerificationRunner(GradleTool gradle, OutputCompactor compactor) {
        this.gradle = gradle;
        this.compactor = compactor;
    }

    public Verdict verify(String task) {
        String compacted = compactor.compact(gradle.runFull(task), task);
        return new Verdict(compacted.startsWith("exit 0"), compacted);
    }
}
```

- [ ] **Step 3: Run — expect PASS, then commit**

```bash
./gradlew :sdd-agent:test
git add sdd-agent/src
git commit -m "feat: independent verification gate over the compacted build result"
```

---

### Task 5: RepoStepRunner — one attempt end-to-end

**Files:**
- Create: `sdd-agent/src/main/java/sdd/agent/run/StepResult.java`, `StepOutcome.java`, `RepoStepRunner.java`
- Test: `sdd-agent/src/test/java/sdd/agent/run/RepoStepRunnerTest.java`

**Interfaces:**
- Produces:
  - `public enum StepResult { SUCCESS, VERIFY_FAILED, BLOCKED, EXHAUSTED, BUDGET, MALFORMED, WEDGED }`.
  - `public record StepOutcome(StepResult result, String summary, List<String> events, String verificationOutput)` (`List.copyOf`).
  - `public record RunnerSettings(AgentBudget budget, int contextSoftCap, java.time.InstantSource clock, java.nio.file.Path javaHome, java.time.Duration gradleTimeout, String verificationTask, int maxTokensPerCall, String systemPrompt)` with `static RunnerSettings defaults(java.nio.file.Path javaHome)` = `(AgentBudget.defaults(), 80_000, InstantSource.system(), javaHome, Duration.ofMinutes(15), "check", 4096, DEFAULT_SYSTEM_PROMPT)`.
  - `public final class RepoStepRunner` — ctor `(Jdbi jdbi)` (the runner holds only the stable KB handle); `StepOutcome run(RepoStep step, ChatModel model, String modelName, RunnerSettings settings)`. The per-attempt-varying inputs (model, its name, and the model-co-varying settings — context cap, `maxTokensPerCall`, budgets) travel on `run()`, so 4C's attempt/escalation loop reads as `for each attempt: pick model + settings; reset to base; runner.run(step, model, name, settings)` against one stable runner — no runner-per-attempt.
- Consumes: `WorkOrder` (T1), `OutputCompactor` (T2), the compacting `Toolbox` (T3), `VerificationRunner` (T4), `FileTools`/`GradleTool`/`PathJail` (4A), `AgentLoop`/`AgentBudget`/`AgentOutcome`/`AgentResult` (4A), `ChatModel`, `Jdbi`.
- `run(...)` flow (one attempt):
  1. Build the compacting toolbox from `step.repoRoot()`: `new Toolbox(new FileTools(new PathJail(root)), new GradleTool(root, settings.javaHome(), settings.gradleTimeout()), new OutputCompactor(root))`; a `VerificationRunner` over the same GradleTool + compactor; an `AgentLoop`.
  2. `workOrder = WorkOrder.build(jdbi, step)`; loop-drive:
     - Run `AgentLoop.run(systemPrompt, workOrder, modelName, maxTokensPerCall)`.
     - `CONTEXT_EXHAUSTED` and not yet restarted → append the re-orientation digest to `workOrder`, mark restarted, re-run once; a second `CONTEXT_EXHAUSTED` → `EXHAUSTED`.
     - `DONE` → `verify(settings.verificationTask())`: passed → `SUCCESS`; failed and `verifyCycles < 2` → append `"Verification failed:\n" + verdict.output()` to `workOrder`, increment cycles, re-run; second failure → `VERIFY_FAILED` (carry the last verification output).
     - `BLOCKED`/`BUDGET_*`/`MALFORMED`/`WEDGED` → the matching `StepResult` (all three BUDGET_* map to `BUDGET`).
  - The digest is machine-built: `"# Previous attempt ran out of context after <turns> turns.\nYour edits persist on disk. Re-read the files under the sub-spec and continue. Last build:\n<summary or 'none'>"`.

- [ ] **Step 1: Write the failing test:**

```java
package sdd.agent.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ToolCall;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepoStepRunnerTest {
    @TempDir Path ws;
    private Database db;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/lib','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.A','CLASS',1,'A.java')");
        });
        repoRoot = Files.createDirectories(ws.resolve("lib-core"));
        Files.writeString(repoRoot.resolve("A.java"), "class A {}\n");
    }

    private void gradlew(String script) throws Exception {
        Path g = repoRoot.resolve("gradlew");
        Files.writeString(g, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(g, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static ChatResponse call(String id, String tool, String args) {
        return new ChatResponse(new ChatMessage("assistant", null,
                List.of(new ToolCall(id, tool, args)), null), "tool_calls", new Usage(10, 5));
    }

    private static RepoStep step(Path root) {
        return new RepoStep("lib-core", root, "Add a field to A.", List.of("R1: x"),
                List.of("A.java"), List.of(), List.of(), List.of());
    }

    private StepOutcome run(ScriptedChatModel model) {
        return new RepoStepRunner(db.jdbi())
                .run(step(repoRoot), model, "qwen", RunnerSettings.defaults(null));
    }

    @Test
    void editThenDoneThenGreenVerificationSucceeds() throws Exception {
        gradlew("exit 0");   // the agent's run_gradle AND the verification gate both pass
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int x; }\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"added x\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);
        assertThat(outcome.summary()).isEqualTo("added x");
        assertThat(Files.readString(repoRoot.resolve("A.java"))).contains("int x;");
    }

    @Test
    void twoDoneVerifyFailCyclesEndInVerifyFailed() throws Exception {
        gradlew("echo '/r/A.java:1: error: broken'; exit 1");   // verification always fails
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"try 1\"}"),   // cycle 1 → verify fails → re-loop
                call("2", "done", "{\"result\":\"success\",\"summary\":\"try 2\"}")));  // cycle 2 → verify fails → VERIFY_FAILED

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.VERIFY_FAILED);
        assertThat(outcome.verificationOutput()).contains("A.java:1: error: broken");
    }

    @Test
    void contextExhaustionRestartsOnceWithADigest() throws Exception {
        gradlew("exit 0");
        // turn 1 = a huge-promptToken content-only response (over the 80k cap, nothing evictable) → CONTEXT_EXHAUSTED;
        // after the restart, the second loop does apply_edit then done → verify green → SUCCESS
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                new ChatResponse(ChatMessage.assistant("thinking"), "stop", new Usage(90_000, 5)),
                call("2", "apply_edit", "{\"path\":\"A.java\",\"search\":\"class A {}\",\"replace\":\"class A { int y; }\"}"),
                call("3", "done", "{\"result\":\"success\",\"summary\":\"after restart\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.SUCCESS);
        // the restarted loop saw a re-orientation digest in its work order (index-independent:
        // search every request's messages so a change to the exhaustion path's call count can't break it)
        assertThat(model.requests()).anySatisfy(req ->
                assertThat(req.messages()).anySatisfy(m ->
                        assertThat(m.content()).contains("ran out of context")));
    }

    @Test
    void blockedAgentSurfacesBlocked() throws Exception {
        gradlew("exit 0");
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"blocked\",\"summary\":\"need a decision\"}")));

        StepOutcome outcome = run(model);

        assertThat(outcome.result()).isEqualTo(StepResult.BLOCKED);
        assertThat(outcome.summary()).isEqualTo("need a decision");
    }
}
```

- [ ] **Step 2: Run — expect COMPILE FAILURE.** Implement `StepResult.java`:

```java
package sdd.agent.run;

public enum StepResult { SUCCESS, VERIFY_FAILED, BLOCKED, EXHAUSTED, BUDGET, MALFORMED, WEDGED }
```

`StepOutcome.java`:

```java
package sdd.agent.run;

import java.util.List;

/** The terminal state of one repo-step attempt. verificationOutput is the compacted gate result (or ""). */
public record StepOutcome(StepResult result, String summary, List<String> events,
                          String verificationOutput) {
    public StepOutcome {
        events = List.copyOf(events);
    }
}
```

`RunnerSettings.java`:

```java
package sdd.agent.run;

import sdd.agent.loop.AgentBudget;

import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;

/** Everything the runner needs beyond the model, KB, and step. */
public record RunnerSettings(AgentBudget budget, int contextSoftCap, InstantSource clock,
                             Path javaHome, Duration gradleTimeout, String verificationTask,
                             int maxTokensPerCall, String systemPrompt) {
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a careful senior engineer making a focused change to ONE repository. Use the
            tools to read files, make minimal edits, and run Gradle to check your work. Change
            only what the sub-spec requires. When it compiles and implements the sub-spec, call
            done(success). If blocked by a missing decision, call done(blocked) and explain.""";

    public static RunnerSettings defaults(Path javaHome) {
        return new RunnerSettings(AgentBudget.defaults(), 80_000, InstantSource.system(), javaHome,
                Duration.ofMinutes(15), "check", 4096, DEFAULT_SYSTEM_PROMPT);
    }
}
```

`RepoStepRunner.java`:

```java
package sdd.agent.run;

import org.jdbi.v3.core.Jdbi;
import sdd.agent.loop.AgentLoop;
import sdd.agent.loop.AgentOutcome;
import sdd.agent.loop.AgentResult;
import sdd.agent.tool.FileTools;
import sdd.agent.tool.GradleTool;
import sdd.agent.tool.PathJail;
import sdd.agent.tool.Toolbox;
import sdd.core.llm.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives ONE attempt for one repo step (design Component 3): build a lean work order, run the
 * agent loop, verify independently on done, retry a verify-fail (≤2 cycles), and restart once on
 * context exhaustion with a machine digest. Multi-attempt escalation + git are Phase 4C's (they
 * need run state and git); this returns a typed StepOutcome for 4C to act on.
 */
public final class RepoStepRunner {
    private static final int MAX_VERIFY_CYCLES = 2;

    private final Jdbi jdbi;

    public RepoStepRunner(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public StepOutcome run(RepoStep step, ChatModel model, String modelName, RunnerSettings settings) {
        OutputCompactor compactor = new OutputCompactor(step.repoRoot());
        GradleTool gradle = new GradleTool(step.repoRoot(), settings.javaHome(), settings.gradleTimeout());
        Toolbox toolbox = new Toolbox(new FileTools(new PathJail(step.repoRoot())), gradle, compactor);
        VerificationRunner verifier = new VerificationRunner(gradle, compactor);
        AgentLoop loop = new AgentLoop(model, toolbox, settings.budget(), settings.contextSoftCap(),
                settings.clock());

        String workOrder = WorkOrder.build(jdbi, step);
        List<String> events = new ArrayList<>();
        int verifyCycles = 0;
        boolean restarted = false;
        String lastVerification = "";

        while (true) {
            AgentOutcome outcome = loop.run(settings.systemPrompt(), workOrder, modelName,
                    settings.maxTokensPerCall());
            events.addAll(outcome.events());

            switch (outcome.result()) {
                case DONE -> {
                    VerificationRunner.Verdict verdict = verifier.verify(settings.verificationTask());
                    lastVerification = verdict.output();
                    if (verdict.passed()) {
                        return outcome(StepResult.SUCCESS, outcome.summary(), events, lastVerification);
                    }
                    if (++verifyCycles >= MAX_VERIFY_CYCLES) {
                        return outcome(StepResult.VERIFY_FAILED, "verification failed", events, lastVerification);
                    }
                    workOrder = WorkOrder.build(jdbi, step)
                            + "\n\n## Verification failed — fix and finish again\n" + verdict.output();
                }
                case BLOCKED -> {
                    return outcome(StepResult.BLOCKED, outcome.summary(), events, lastVerification);
                }
                case CONTEXT_EXHAUSTED -> {
                    if (restarted) {
                        return outcome(StepResult.EXHAUSTED, "context exhausted", events, lastVerification);
                    }
                    restarted = true;
                    workOrder = WorkOrder.build(jdbi, step) + digest(outcome, lastVerification);
                }
                case BUDGET_TURNS, BUDGET_TIME, BUDGET_TOKENS -> {
                    return outcome(StepResult.BUDGET, outcome.summary(), events, lastVerification);
                }
                case MALFORMED -> {
                    return outcome(StepResult.MALFORMED, outcome.summary(), events, lastVerification);
                }
                case WEDGED -> {
                    return outcome(StepResult.WEDGED, outcome.summary(), events, lastVerification);
                }
            }
        }
    }

    private static String digest(AgentOutcome outcome, String lastVerification) {
        return "\n\n## Previous attempt ran out of context after " + outcome.turns() + " turns\n"
                + "Your edits persist on disk. Re-read the files named in the sub-spec and continue.\n"
                + "Last build:\n" + (lastVerification.isEmpty() ? "none" : lastVerification);
    }

    private static StepOutcome outcome(StepResult result, String summary, List<String> events,
                                       String verification) {
        return new StepOutcome(result, summary, events, verification);
    }
}
```

- [ ] **Step 3: Run — expect PASS.**
Run: `./gradlew :sdd-agent:test`

- [ ] **Step 4: Full build, then commit**

```bash
./gradlew build
git add sdd-agent/src
git commit -m "feat: repo-step runner with verify-retry and context-restart"
```

---

## Verification

1. `./gradlew build` — all modules green.
2. `RepoStepRunnerTest` proves the whole attempt: work order → loop → edit → done → green verify → SUCCESS; the verify-fail-twice → VERIFY_FAILED path; the context-exhaustion → restart-with-digest → SUCCESS path; BLOCKED surfacing. `WorkOrderTest`/`OutputCompactorTest`/`VerificationRunnerTest` pin the pieces; `ToolboxCompactionTest.headJavacErrorSurvivesALogLongerThanTheTailCap` is the regression guard for the tail-cap fix.
3. Real-estate smoke is deferred to 4C (needs the served Qwen coder + plan.json wiring + git); 4B is a library exercised entirely through `ScriptedChatModel` and stub gradlew scripts.

## Self-Review (completed at write time)

1. **Spec coverage (Component 3, one-attempt slice):** lean KB-grounded work order (sub-spec + contracts as floors + repo card + ranked file manifest via file_ref hops + acceptance) → Task 1; ≤4k-token deterministic compaction from JUnit XML (DOM, never console-scraped) + javac patterns (scraped from the FULL head-preserving log via `GradleTool.runFull`, not the tail-cap) + "N more omitted", task-gated so stale XML never leaks → Task 2, wired into run_gradle → Task 3; independent verification gate on done → Task 4; the attempt driver with 2-done→verify-fail = failure and CONTEXT_EXHAUSTED restart-with-digest → Task 5. Explicitly DEFERRED (grouped by dependency in Global Constraints): **git+run-state → 4C** — multi-attempt (max 2), attempt-2 DeepSeek escalation, hard-reset-to-base, orchestration, `sdd implement` CLI, INFRA-failure classification (4B note: infra flakes currently surface as `VERIFY_FAILED` and reach the agent); **config → 4C** — per-repo `sdd.yml` verification exclusions (the design's real M7 "not locally verified"); **plan.json read model → 4C/sdd-cli** — parsing into `RepoStep`/`ContractRef` and resolving covers/contract IDs. Recorded 4B→later refinement: project-package stack FILTERING (needs a project-prefix input 4B lacks; 4B does the cheap blank-message→element-text fallback).
2. **Design interpretations flagged for ratification at review** (not smuggled): (a) 4B runs one fixed allowlisted gate (`check`) rather than the step's declared `verification[]`, because those are free-form DeepSeek prose, not runnable tasks; (b) 4B reuses "not locally verified" for prose acceptance items, distinct from M7's `sdd.yml`-exclusion meaning; (c) the verify-fail re-run restarts a FRESH `AgentLoop` (disk edits persist), mirroring the exhaustion-restart philosophy, since 4A exposes no resume entry point.
3. **Placeholder scan:** none; every code step is complete.
4. **Type consistency:** `RepoStep`/`ContractRef` used by Tasks 1 and 5; `WorkOrder.build(Jdbi, RepoStep)` in 1 and 5, sharing `WorkOrder.SEP` with its test; `OutputCompactor(Path).compact(String raw, String task)` (no `identity()`) in 2, 3, 4, 5; `GradleTool.runFull(String)` (T3) consumed by T3+T4; `Toolbox(FileTools, GradleTool, OutputCompactor)` with a nullable compactor in 3 and 5; `VerificationRunner(GradleTool, OutputCompactor).verify(String) → Verdict(passed, output)` in 4 and 5; `AgentLoop(model, toolbox, budget, softCap, clock).run(sys, wo, modelName, maxTokens) → AgentOutcome` (4A, re-entrant — verified) in 5; `RunnerSettings.defaults(Path)` + `RepoStepRunner(Jdbi).run(RepoStep, ChatModel, String, RunnerSettings) → StepOutcome` in 5.
5. **Adversarial hardening (3 critics against the real 4A/sdd-core code):** compile-correctness — clean (all seams verified). Design-conformance — one ship-blocker (compactor over the tail-capped log → head javac errors lost) FIXED via `runFull`; interpretation/deferral notes folded. Test-quality — no failing/vacuous/flaky test; folded fixes: green-branch header duplication, task-gated stale-XML harvest, index-independent restart-digest assertion, `SEP` constant; confirmed `AgentLoop.run()` is re-entrant so the build-loop-once design stands.
