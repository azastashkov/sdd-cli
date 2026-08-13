# Phase 4D: Contract Actualization + japicmp — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After an upstream repo goes green, `sdd implement` re-extracts its REAL API signatures/endpoints/topics into every downstream work order, and a contract declared `binary-compatible` is gated with japicmp against a baseline jar built from the pinned base tree — breaking drift fails the upstream before any consumer starts (design line 62, critique M4).

**Architecture:** The `compat` marker threads through the whole contract pipeline (drafter prompt → plan.md heading → strict parser → validator → plan.json → PlanModel). Execution side, all in `sdd.cli.implement`: `ContractActualizer` re-extracts a green provider's tree with the existing standalone sdd-index extractors (JavaParser-only — no Gradle, no KB writes) and `RunStore` persists per-contract actualized bodies under `runs/<runId>/contracts/`; consumers receive them through the existing `priorDigest` work-order channel. `JarBuilder` (orchestrator-owned `assemble` subprocess, never model-reachable) produces the baseline jars right after branch start (tree = pinned base) and the candidate jars after success; `JapicmpCheck` compares them and the orchestrator转 FAILED on binary-incompatible drift before the SUCCEEDED transition.

**Tech Stack:** Java 21, japicmp (new dependency, sdd-cli), JavaParser via sdd-index's `SourceParser`/`ApiSurfaceExtractor`/`RestEndpointExtractor`/`KafkaExtractor`, `javax.tools.JavaCompiler` + `JarOutputStream` for fixture jars, JUnit 5 + AssertJ, FixtureRepo + stub `gradlew`.

## Global Constraints

- **Scope = in-run, automated, orchestrator-owned actualization + binary-compat gate** (design Component 3). Explicitly NOT this phase: Phase 5's review-time contract re-check (Component 4 — full-estate, human-adjudicated warnings), Kafka payload-type sharpening via resolved classpath jars (re-extraction runs jar-less; payload types degrade to raw expressions exactly as the indexer does without jars), deep multi-module discovery (module roots = repoRoot + depth-1 children containing `src/main/java` — matches all fixtures; deeper nesting is a real-estate-time refinement), and any Nexus integration.
- **Ratified interpretations (flag at review if you disagree):** (a) the design's "baseline jar resolved from Nexus" is unreconciled with v1's fixed "no Nexus publishing" decision — the baseline is BUILT FROM THE PINNED BASE TREE right after `startBranch` (tree is at `base_sha`, clean), stashed in the run dir; (b) `compat` is optional, its only legal value is `binary-compatible`, and it is legal ONLY on `java-api` contracts (validator-enforced); (c) actualized contract text SUPERSEDES the drafted body for downstream work orders but is delivered via the `priorDigest` append channel (an explicit `## Actualized contracts` section) — `RepoStep`/`WorkOrder`/`ContractRef` signatures stay untouched; (d) a jar-build (`assemble`) failure at baseline or candidate time SKIPS the japicmp gate with a loud event (`"japicmp skipped: <which> build failed"`) rather than failing the repo — the verify gate already proved the tree builds, and a missing gate must not be silent nor fatal; (e) jars are matched baseline↔candidate by file name with the version stripped (`lib-1.0.jar` ↔ `lib-1.1.jar`); unmatched jars are skipped with an event; `-sources`/`-javadoc` jars are excluded; (f) actualization runs for EVERY green provider that provides contracts (all kinds); japicmp runs only for `binary-compatible` ones; (g) actualized bodies are capped at 4000 chars (work-order hygiene), truncation marked.
- **Guardrail invariants:** `GradleTool.ALLOWED` untouched; `JarBuilder` is orchestrator-only (MavenLocalPublisher precedent: own ProcessBuilder, env-scrubbed to PATH/HOME/LANG/TMPDIR + JAVA_HOME, hard timeout, log shaped `"exit N\n…"`); jar builds acquire the run's `gradlePermits` semaphore at the Orchestrator call site.
- **japicmp** is added to sdd-cli only. The coordinate `com.github.siom79.japicmp:japicmp` and the `JarArchiveComparator` API shape below are from prior knowledge — the implementer MUST verify the latest stable version resolves and adapt the call shape to the library's actual API, recording any deviation in the report.
- **Arity changes and complete call-site lists:** `PlanDrafter.DraftContract`, `PlanDocument.PlanContract`, `PlanJson.Contract`, `PlanModel.PlanContract` each gain a trailing nullable `String compat`. Complete construction-site list (critic-verified; re-check with `grep -rn "DraftContract(\|PlanContract(" sdd-*/src` — NOTE tests use the qualified `new PlanDrafter.DraftContract(` spelling, and `new Contract(` in `sdd/plan/impact/Closure.java` is an UNRELATED class, leave it alone): PlanDrafter:172, Sections:109, PlanJson:109, PlanJsonReader:43, PlanJsonTest:40, PlanValidatorTest:49/111/112/142, PlanMdParserSectionsTest:36/62/100/108, PlanMdRendererTest:38/134. `ContractRef` (sdd-agent) is deliberately unchanged.
- **Zero-test-breaking** outside files a task explicitly edits; full `./gradlew build` green at every task boundary.
- Commit messages: conventional commits, ending with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Context (verified against the code, 2026-08-13, main @ 3b90b04)

- Contract pipeline today: `PlanDrafter.DraftContract(id, kind, provider, consumers, body)` (body = model-drafted prose; kinds java-api/rest/kafka) → `PlanMdRenderer` heading `### <id> (<kind>) — <provider> -> <consumers>` + ```yaml fenced body → `Sections.CONTRACT_HEAD = Pattern.compile("### (.+?) \\((java-api|rest|kafka)\\) — (\\S+)(?: -> (.+))?")` → `PlanDocument.PlanContract` → `PlanJson.Contract` (snake_case writer) → `PlanJsonReader`/`PlanModel.PlanContract` → `RepoStepResolver.refs()` → `ContractRef` → `WorkOrder.appendContracts`. No `compat` anywhere.
- Re-extraction is standalone and cheap: `SourceParser.parseModule(repoRoot, moduleDir, List.of())` works jar-less (ReflectionTypeSolver + source roots); `ApiSurfaceExtractor.extract(session, true)` is pure (syntactic types, no resolution) and has `hash(...)`; `RestEndpointExtractor.extract(session, ConfigFileParser.parseModuleConfig(repoRoot, moduleDir))` pure; `KafkaExtractor.extract(session, props, List.of(), keys)` degrades gracefully jar-less. sdd-cli main already depends on sdd-index main (ImplementCommand imports GradleExtractor).
- Orchestrator post-4C-3b: `runRepo(runDir, runId, plan, steps, state, repo)` has `plan` in scope; the SUCCESS branch (post-publish, pre-`transitionLocked(SUCCEEDED)`) is the "upstream green, consumers not yet started" seam (layers gate on RepoState); attempt-1 `priorDigest` is `""` today, attempt-2 is `attemptDigest(outcome)`; `settingsFor.apply(repo).gradlePermits()` is the semaphore handle; `RunGit.startBranch` leaves the tree at base, clean.
- `check` does NOT produce jars (no dependency on `jar`); only `assemble`/`build` do — hence the dedicated `JarBuilder`. `dep_edge` stores no jar paths; `GradleModel.ResolvedDep.files` is in-memory only.
- Run dir has no `contracts/`; `RunStore` write patterns established (atomic state, plain writes for snapshots).
- `FixtureGradleRepo` (real Gradle) is available to sdd-cli tests via existing testFixtures wiring, but unit-level jar fixtures are cheaper via `javax.tools.JavaCompiler` + `JarOutputStream` (no Gradle at all).

---

### Task 1: `compat` threads through the contract pipeline

**Files:**
- Modify: `sdd-plan/src/main/java/sdd/plan/gen/PlanDrafter.java`, `sdd-plan/src/main/java/sdd/plan/gen/PlanMdRenderer.java`, `sdd-plan/src/main/java/sdd/plan/approve/Sections.java`, `sdd-plan/src/main/java/sdd/plan/approve/PlanDocument.java`, `sdd-plan/src/main/java/sdd/plan/approve/PlanValidator.java`, `sdd-plan/src/main/java/sdd/plan/approve/PlanJson.java`, `sdd-cli/src/main/java/sdd/cli/implement/PlanModel.java`, `sdd-cli/src/main/java/sdd/cli/implement/PlanJsonReader.java`
- Test: existing suites in both modules gain cases — `PlanMdRenderer`/`Sections` round-trip, `PlanValidatorTest`, `PlanJsonTest`, `PlanJsonReaderTest`

**Interfaces:**
- Produces: every contract record gains a trailing `String compat` (null = none): `DraftContract(id, kind, provider, consumers, body, compat)`, `PlanDocument.PlanContract(...)`, `PlanJson.Contract(...)` (JSON key `compat`, omitted... written as null is fine — Jackson writes null; keep it simple and always write the field), `PlanModel.PlanContract(...)`. plan.md heading grammar becomes `### <id> (<kind>[, binary-compatible]) — <provider> -> <consumers>`: `CONTRACT_HEAD = Pattern.compile("### (.+?) \\((java-api|rest|kafka)(?:, (binary-compatible))?\\) — (\\S+)(?: -> (.+))?")` (group indexes shift: provider = group 4, consumers = group 5). Renderer emits `, binary-compatible` inside the parens when compat is set. Drafter prompt: the contracts JSON schema line gains `"compat": "binary-compatible" (OPTIONAL, java-api only — declare it when consumers must keep binary compatibility)`; drafter coercion drops any other value with a note (same pattern as unknown kinds). Validator: `compat` non-null on a non-java-api contract → problem `"contract <id>: compat is only valid on java-api contracts"`; value validation is structural (the regex only admits `binary-compatible`; the drafter coerces). `PlanJsonReader` reads `compat` as nullable text.
- Consumes: nothing new.

- [ ] **Step 1: Write the failing tests.** Add to the sdd-plan suite (locate the existing test classes for renderer/parser/validator/json and follow their fixture idioms — read them first):

```java
    // PlanMdRenderer test class:
    @Test
    void rendersTheCompatMarkerInsideTheKindParens() {
        // a DraftContract with compat "binary-compatible" renders as:
        //   ### c1 (java-api, binary-compatible) — lib -> svc
        // and one with null compat renders exactly as before.
    }

    // Sections/PlanMdParser test class:
    @Test
    void parsesACompatMarkedContractHeading() {
        // "### c1 (java-api, binary-compatible) — lib -> svc" -> PlanContract.compat() == "binary-compatible"
        // "### c2 (rest) — lib -> svc" -> compat() == null
        // "### c3 (java-api, source-compatible) — lib" -> PlanParseException (regex rejects)
    }

    // PlanValidator test class:
    @Test
    void compatOnANonJavaApiContractIsAProblem() {
        // rest contract with compat "binary-compatible" -> problem containing "only valid on java-api"
    }

    // PlanJsonTest:
    @Test
    void compatRoundTripsIntoPlanJson() {
        // compiled plan.json contract object contains "compat" : "binary-compatible"
    }

    // PlanJsonReaderTest (sdd-cli):
    @Test
    void readsCompatFromPlanJson() {
        // a contracts[] entry with "compat":"binary-compatible" -> PlanContract.compat() equals it;
        // an entry without the key -> null
    }
```

Write these as REAL tests against the actual fixture idioms of each test class (the comments above are the specifications; the implementer expands them with the class's existing helpers — e.g. `PlanJsonReaderTestFixture.PLAN` gets a compat contract variant or a small inline JSON).

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-plan:test :sdd-cli:test`

- [ ] **Step 3: Implement** in pipeline order (drafter → renderer → parser → document → validator → json → reader/model), updating EVERY constructor call site from the Global Constraints list (append `, null` mechanically where compat is irrelevant). Specifics: `Sections.contracts` new group indexes (`group(1)` id, `group(2)` kind, `group(3)` compat-or-null, `group(4)` provider, `group(5)` consumers) AND its `PlanParseException` grammar message must be updated to `"contract headings must look like '### <id> (<kind>[, binary-compatible]) — <provider> -> <consumers>'"`; `PlanJsonReader` must read compat NULL-PRESERVINGLY (`c.hasNonNull("compat") ? c.get("compat").asText() : null`) — the existing `text()` helper coerces missing to `""` and would fail the null test. `RepoStepResolver.refs()` keeps building the 5-field `ContractRef` (compat is an orchestrator concern, not an agent-prompt concern).

- [ ] **Step 4: Run — expect PASS, then full build.**
Run: `./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-plan/src sdd-cli/src
git commit -m "feat: contracts carry an optional binary-compatible compat marker"
```

---

### Task 2: ContractActualizer + run-dir contract files

**Files:**
- Create: `sdd-cli/src/main/java/sdd/cli/implement/ContractActualizer.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/RunStore.java` (writeContract/readContract)
- Test: `sdd-cli/src/test/java/sdd/cli/implement/ContractActualizerTest.java` (create), `sdd-cli/src/test/java/sdd/cli/implement/RunStoreTest.java` (add)

**Interfaces:**
- Produces: `ContractActualizer.actualize(Path repoRoot, List<PlanModel.PlanContract> provided)` → `Map<String, String>` contract-id → actualized body. Per kind: `java-api` → public API type signatures from `ApiSurfaceExtractor` (types whose FQCN or simple name appears in the drafted body; when none match, ALL api types), fqcn line + indented `"<signature>: <returnType>"` member lines, under a `"# actualized (<kind>)"` header line; `rest` → one line per endpoint `"<httpMethod> <pathTemplate> -> <classFqcn>#<methodName>"`; `kafka` → one line per use `"<role> <topic>"`. Bodies capped at 4000 chars with a `"…(truncated)"` tail. Module roots: `repoRoot` itself plus depth-1 children containing `src/main/java`; jar-less parsing (`SourceParser.parseModule(repoRoot, moduleDir, List.of())`). `RunStore.writeContract(Path runDir, String contractId, String body)` → `<runDir>/contracts/<sanitized-id>.md` (same `[^A-Za-z0-9._-]` sanitizer as repo dirs); `RunStore.readContract(Path runDir, String contractId)` → body or null when absent.
- Consumes: sdd-index extractors (existing, standalone); Task 1's `PlanContract.compat` is NOT needed here (actualization is kind-driven).

- [ ] **Step 1: Write the failing tests.** `ContractActualizerTest.java` — real source trees, no Gradle:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractActualizerTest {
    @TempDir Path repo;

    private void javaFile(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void actualizesAJavaApiContractFromTheRealTree() throws Exception {
        javaFile("src/main/java/com/acme/lib/TierResolver.java", """
                package com.acme.lib;
                public class TierResolver {
                    public String resolve(String account) { return account; }
                    private int internal() { return 0; }
                }
                """);
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of("svc"), "TierResolver.resolve(String): String — planned delta", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c1"))
                .contains("com.acme.lib.TierResolver")
                .contains("resolve(String)")
                .doesNotContain("internal");   // private members never in the surface
    }

    @Test
    void unmatchedTypeNamesFallBackToTheWholeSurface() throws Exception {
        javaFile("src/main/java/com/acme/lib/Alpha.java",
                "package com.acme.lib;\npublic class Alpha { public void a() {} }\n");
        javaFile("src/main/java/com/acme/lib/Beta.java",
                "package com.acme.lib;\npublic class Beta { public void b() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c1", "java-api", "lib",
                List.of(), "something about Gamma", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c1")).contains("Alpha").contains("Beta");
    }

    @Test
    void actualizesARestContract() throws Exception {
        javaFile("src/main/java/com/acme/svc/SpreadController.java", """
                package com.acme.svc;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class SpreadController {
                    @GetMapping("/admin/spreads")
                    public String spreads() { return ""; }
                }
                """);
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c2", "rest", "svc",
                List.of(), "GET /admin/spreads", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c2")).contains("GET").contains("/admin/spreads");
    }

    @Test
    void depthOneModulesAreDiscovered() throws Exception {
        javaFile("core/src/main/java/com/acme/core/Deep.java",
                "package com.acme.core;\npublic class Deep { public void d() {} }\n");
        PlanModel.PlanContract contract = new PlanModel.PlanContract("c3", "java-api", "lib",
                List.of(), "Deep", null);

        Map<String, String> actual = ContractActualizer.actualize(repo, List.of(contract));

        assertThat(actual.get("c3")).contains("com.acme.core.Deep");
    }
}
```

(These construct `PlanModel.PlanContract` with the Task-1 6-field shape — `null` compat.) Append to `RunStoreTest`:

```java
    @Test
    void contractFilesRoundTripUnderTheContractsDir() {
        RunStore store = new RunStore(InstantSource.fixed(Instant.EPOCH));
        Path runDir = store.create(ws, "S-v1", "{}", "");

        store.writeContract(runDir, "c1/api", "actual body");

        assertThat(runDir.resolve("contracts/c1-api.md")).exists();
        assertThat(store.readContract(runDir, "c1/api")).isEqualTo("actual body");
        assertThat(store.readContract(runDir, "ghost")).isNull();
    }
```

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.ContractActualizerTest' --tests 'sdd.cli.implement.RunStoreTest'`

- [ ] **Step 3: Implement.** `ContractActualizer` (read `SourceExtraction.java` first to mirror how it assembles `Session`s, config props, and extractor calls — but jar-less and DB-free):

```java
package sdd.cli.implement;

import sdd.index.source.ApiSurfaceExtractor;
import sdd.index.source.SourceModel;
import sdd.index.source.SourceParser;
import sdd.index.spring.ConfigFileParser;
import sdd.index.spring.KafkaExtractor;
import sdd.index.spring.RestEndpointExtractor;
import sdd.index.spring.SpringConfigPersistence;
import sdd.index.spring.SpringModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-extracts a green provider's REAL interface surface into actualized contract bodies
 * (design line 62, M4): downstream work orders see what the tree actually exposes, not what the
 * planner drafted. Jar-less on purpose — ApiSurface/REST extraction is syntactic; Kafka payload
 * types degrade to raw expressions exactly as the indexer does without a resolved classpath.
 * The KB is never touched (read-only during implement).
 */
public final class ContractActualizer {
    static final int MAX_BODY = 4000;

    private ContractActualizer() {
    }

    public static Map<String, String> actualize(Path repoRoot, List<PlanModel.PlanContract> provided) {
        if (provided.isEmpty()) {
            return Map.of();
        }
        List<ModuleSession> sessions = new ArrayList<>();
        for (Path moduleDir : moduleRoots(repoRoot)) {
            try {
                sessions.add(new ModuleSession(moduleDir,
                        SourceParser.parseModule(repoRoot, moduleDir, List.of())));
            } catch (RuntimeException e) {
                // an unparseable module degrades that module's surface, never the whole actualization
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (PlanModel.PlanContract contract : provided) {
            String body = switch (contract.kind()) {
                case "java-api" -> javaApi(sessions, contract.body());
                case "rest" -> rest(repoRoot, sessions);
                case "kafka" -> kafka(repoRoot, sessions);
                default -> "";
            };
            if (!body.isBlank()) {
                result.put(contract.id(), cap("# actualized (" + contract.kind() + ")\n" + body));
            }
        }
        return result;
    }

    /** Session alone loses the module dir the REST/Kafka config parse needs — keep the pair. */
    private record ModuleSession(Path moduleDir, SourceParser.Session session) {
    }

    private static String javaApi(List<ModuleSession> sessions, String draftedBody) {
        List<SourceModel.TypeInfo> all = new ArrayList<>();
        for (ModuleSession module : sessions) {
            all.addAll(ApiSurfaceExtractor.extract(module.session(), true));
        }
        List<SourceModel.TypeInfo> relevant = all.stream()
                .filter(type -> draftedBody.contains(type.fqcn()) || draftedBody.contains(simple(type.fqcn())))
                .toList();
        StringBuilder body = new StringBuilder();
        for (SourceModel.TypeInfo type : relevant.isEmpty() ? all : relevant) {
            body.append(type.fqcn()).append('\n');
            for (SourceModel.MemberInfo member : type.members()) {
                body.append("  ").append(member.signature()).append(": ")
                        .append(member.returnType()).append('\n');
            }
        }
        return body.toString();
    }

    private static String rest(Path repoRoot, List<ModuleSession> sessions) {
        StringBuilder body = new StringBuilder();
        for (ModuleSession module : sessions) {
            // parseModuleConfig returns Result(entries, issues); flatten to default-profile props
            Map<String, String> props = SpringConfigPersistence.defaultProfileProps(
                    ConfigFileParser.parseModuleConfig(repoRoot, module.moduleDir()).entries());
            for (SpringModel.EndpointInfo endpoint : RestEndpointExtractor.extract(module.session(), props)) {
                body.append(endpoint.httpMethod()).append(' ').append(endpoint.pathTemplate())
                        .append(" -> ").append(endpoint.classFqcn()).append('#')
                        .append(endpoint.methodName()).append('\n');
            }
        }
        return body.toString();
    }

    private static String kafka(Path repoRoot, List<ModuleSession> sessions) {
        StringBuilder body = new StringBuilder();
        for (ModuleSession module : sessions) {
            Map<String, String> props = SpringConfigPersistence.defaultProfileProps(
                    ConfigFileParser.parseModuleConfig(repoRoot, module.moduleDir()).entries());
            KafkaExtractor.KafkaResult kafka = KafkaExtractor.extract(module.session(), props,
                    List.of(), props.keySet());
            kafka.uses().forEach(use -> body.append(use.role()).append(' ')
                    .append(use.topic()).append('\n'));
        }
        return body.toString();
    }

    private static List<Path> moduleRoots(Path repoRoot) {
        List<Path> roots = new ArrayList<>();
        if (Files.isDirectory(repoRoot.resolve("src/main/java"))) {
            roots.add(repoRoot);
        }
        try (var children = Files.list(repoRoot)) {
            children.filter(child -> Files.isDirectory(child.resolve("src/main/java")))
                    .sorted()
                    .forEach(roots::add);
        } catch (java.io.IOException e) {
            // unreadable repo root: fall through with whatever we found
        }
        return roots;
    }

    private static String simple(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static String cap(String body) {
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "\n…(truncated)";
    }
}
```

**API names above are critic-verified against the real sdd-index sources** (`TypeInfo.fqcn()/members()`, `MemberInfo.signature()/returnType()`, `EndpointInfo.httpMethod()/pathTemplate()/classFqcn()/methodName()`, `KafkaResult.uses()` of `KafkaUse.role()/topic()`, `ConfigFileParser.parseModuleConfig(...).entries()` flattened via `SpringConfigPersistence.defaultProfileProps`, `SourceParser.Session(units, issues)` — no moduleDir, hence the local `ModuleSession` pair). Also add a kafka-kind test (a `@KafkaListener(topics = "t.orders")` class + kafka contract → body contains the topic) and a cap test (>4000-char surface asserts the `…(truncated)` tail) to `ContractActualizerTest` — the kafka branch must not ship untested. `RunStore` additions:

```java
    public void writeContract(Path runDir, String contractId, String body) {
        try {
            Path dir = Files.createDirectories(runDir.resolve("contracts"));
            Files.writeString(dir.resolve(sanitize(contractId) + ".md"), body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The actualized contract body, or null when the provider has not gone green yet. */
    public String readContract(Path runDir, String contractId) {
        Path file = runDir.resolve("contracts").resolve(sanitize(contractId) + ".md");
        try {
            return Files.exists(file) ? Files.readString(file) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "-");
    }
```

(Refactor `writeAgentEvents`' inline sanitizer to use the same helper.)

- [ ] **Step 4: Run — expect PASS.**
Run: `./gradlew :sdd-cli:test`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: re-extract green providers into actualized run-dir contract bodies"
```

---

### Task 3: JarBuilder + JapicmpCheck

**Files:**
- Modify: `sdd-cli/build.gradle.kts` (japicmp dependency)
- Create: `sdd-cli/src/main/java/sdd/cli/implement/JarBuilder.java`, `sdd-cli/src/main/java/sdd/cli/implement/JapicmpCheck.java`
- Test: `sdd-cli/src/test/java/sdd/cli/implement/JarBuilderTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/JapicmpCheckTest.java`, `sdd-cli/src/test/java/sdd/cli/implement/TestJars.java` (shared helper)

**Interfaces:**
- Produces: `JarBuilder` — `JarBuilder()` (10-min timeout) / `JarBuilder(Duration)`; `Result build(Path repoRoot, Path javaHome, Path outDir)` with `record Result(boolean ok, List<Path> jars, String log)`: runs `./gradlew assemble --no-configuration-cache --no-daemon -q` (env-scrubbed like MavenLocalPublisher), then copies every `*.jar` under any `build/libs` directory (repoRoot + depth-1 modules), excluding `-sources`/`-javadoc`, into `outDir`, returning the copied paths; log shaped `"exit N\n…"` / `"timed out after Ns"` / `"no gradle wrapper in …"`. `JapicmpCheck.compare(Path baselineJar, Path candidateJar)` → `record Verdict(boolean binaryCompatible, String report)` — report lists each binary-incompatible class and its change kinds, empty-ish when compatible. `TestJars.jar(Path dir, String jarName, String className, String source)` → compiles ONE class with `javax.tools.ToolProvider.getSystemJavaCompiler()` and packages it into `<dir>/<jarName>` via `JarOutputStream`, returning the jar path (shared by Task 3/4 tests; no Gradle).
- Consumes: nothing from Tasks 1-2.

- [ ] **Step 1: Add the dependency.** In `sdd-cli/build.gradle.kts`: `implementation("com.github.siom79.japicmp:japicmp:0.21.2")` — VERIFY this is the latest stable on resolution (`./gradlew :sdd-cli:dependencies --configuration runtimeClasspath | grep japicmp`); bump if a newer stable exists; record the final version in the report.

- [ ] **Step 2: Write the failing tests.** `TestJars.java`:

```java
package sdd.cli.implement;

import javax.tools.ToolProvider;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Compiles one class and jars it — real bytecode for japicmp tests without touching Gradle.
 *  PUBLIC: ImplementCommandContractTest lives in package sdd.cli and imports this. */
public final class TestJars {
    private TestJars() {
    }

    public static Path jar(Path dir, String jarName, String className, String source) throws Exception {
        Path work = Files.createTempDirectory(dir, "jarsrc");
        Path src = work.resolve(className + ".java");
        Files.writeString(src, source);
        Path classes = Files.createDirectories(work.resolve("classes"));
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", classes.toString(), src.toString());
        if (rc != 0) {
            throw new IllegalStateException("fixture compile failed for " + className);
        }
        Path jar = dir.resolve(jarName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                try (var in = Files.newInputStream(file)) {
                    in.transferTo((OutputStream) out);
                }
                out.closeEntry();
            }
        }
        return jar;
    }
}
```

`JapicmpCheckTest.java`:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JapicmpCheckTest {
    @TempDir Path dir;

    @Test
    void aChangedReturnTypeIsBinaryIncompatible() throws Exception {
        Path baseline = TestJars.jar(dir, "lib-1.0.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path candidate = TestJars.jar(dir, "lib-1.1.jar", "Api",
                "public class Api { public long f(int x) { return x; } }");

        JapicmpCheck.Verdict verdict = JapicmpCheck.compare(baseline, candidate);

        assertThat(verdict.binaryCompatible()).isFalse();
        assertThat(verdict.report()).contains("Api");
    }

    @Test
    void anAddedMethodIsBinaryCompatible() throws Exception {
        Path baseline = TestJars.jar(dir, "lib-1.0.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path candidate = TestJars.jar(dir, "lib-1.1.jar", "Api",
                "public class Api { public int f(int x) { return x; } public int g() { return 1; } }");

        JapicmpCheck.Verdict verdict = JapicmpCheck.compare(baseline, candidate);

        assertThat(verdict.binaryCompatible()).isTrue();
    }
}
```

`JarBuilderTest.java` — stub gradlew plants a jar, argv captured:

```java
package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JarBuilderTest {
    @TempDir Path ws;

    private Path repoWith(String script) throws Exception {
        Path repo = Files.createDirectories(ws.resolve("lib"));
        Path gradlew = repo.resolve("gradlew");
        Files.writeString(gradlew, "#!/bin/sh\n" + script + "\n");
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        return repo;
    }

    @Test
    void buildsAndCollectsJarsExcludingSourcesAndJavadoc() throws Exception {
        Path repo = repoWith("mkdir -p build/libs; touch build/libs/lib-1.0.jar "
                + "build/libs/lib-1.0-sources.jar build/libs/lib-1.0-javadoc.jar; echo \"$*\" > args; exit 0");
        Path out = ws.resolve("baseline");

        JarBuilder.Result result = new JarBuilder().build(repo, null, out);

        assertThat(result.ok()).isTrue();
        assertThat(result.jars()).containsExactly(out.resolve("lib-1.0.jar"));
        assertThat(Files.readString(repo.resolve("args"))).contains("assemble");
    }

    @Test
    void aFailedAssembleReportsNotOk() throws Exception {
        Path repo = repoWith("echo 'boom'; exit 1");

        JarBuilder.Result result = new JarBuilder().build(repo, null, ws.resolve("out"));

        assertThat(result.ok()).isFalse();
        assertThat(result.log()).startsWith("exit 1");
    }
}
```

- [ ] **Step 3: Run — expect COMPILE FAILURE / RED**, then implement. `JarBuilder` mirrors `MavenLocalPublisher` (ProcessBuilder, env-scrub PATH/HOME/LANG/TMPDIR + JAVA_HOME, temp log, process-tree kill, MAX_LOG cap) with argv `./gradlew assemble --no-configuration-cache --no-daemon -q`, then:

```java
    private static List<Path> collectJars(Path repoRoot, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        List<Path> copied = new ArrayList<>();
        List<Path> libDirs = new ArrayList<>();
        libDirs.add(repoRoot.resolve("build/libs"));
        try (var children = Files.list(repoRoot)) {
            children.filter(Files::isDirectory)
                    .map(child -> child.resolve("build/libs"))
                    .sorted()
                    .forEach(libDirs::add);
        }
        for (Path libDir : libDirs) {
            if (!Files.isDirectory(libDir)) {
                continue;
            }
            try (var jars = Files.list(libDir)) {
                for (Path jar : jars.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    String name = jar.getFileName().toString();
                    if (name.endsWith("-sources.jar") || name.endsWith("-javadoc.jar")) {
                        continue;
                    }
                    copied.add(Files.copy(jar, outDir.resolve(name),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING));
                }
            }
        }
        return copied;
    }
```

`JapicmpCheck` (expected shape — ADAPT to the library's real API and record deviations):

```java
package sdd.cli.implement;

import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.model.JApiClass;

import java.nio.file.Path;
import java.util.List;

/** Binary-compatibility gate for compat: binary-compatible contracts (design line 62). */
public final class JapicmpCheck {
    public record Verdict(boolean binaryCompatible, String report) {
    }

    private JapicmpCheck() {
    }

    public static Verdict compare(Path baselineJar, Path candidateJar) {
        JarArchiveComparatorOptions options = new JarArchiveComparatorOptions();
        JarArchiveComparator comparator = new JarArchiveComparator(options);
        List<JApiClass> classes = comparator.compare(
                new JApiCmpArchive(baselineJar.toFile(), "baseline"),
                new JApiCmpArchive(candidateJar.toFile(), "candidate"));
        StringBuilder report = new StringBuilder();
        boolean compatible = true;
        for (JApiClass jApiClass : classes) {
            if (!jApiClass.isBinaryCompatible()) {
                compatible = false;
                report.append(jApiClass.getFullyQualifiedName()).append(": ");
                // Since 0.18 getCompatibilityChanges() returns wrapper objects; map to the change
                // type for a readable report (adapt if the resolved version differs).
                jApiClass.getCompatibilityChanges().forEach(change ->
                        report.append(change.getType()).append(' '));
                report.append('\n');
            }
        }
        return new Verdict(compatible, report.toString());
    }
}
```

- [ ] **Step 4: Run — expect PASS, then full build.**
Run: `./gradlew :sdd-cli:test && ./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/build.gradle.kts sdd-cli/src
git commit -m "feat: orchestrator-owned jar builds and japicmp binary-compat verdicts"
```

---

### Task 4: Orchestrator wiring + end-to-end proofs

**Files:**
- Modify: `sdd-cli/src/main/java/sdd/cli/implement/Orchestrator.java`, `sdd-cli/src/main/java/sdd/cli/ImplementCommand.java` (constructor arg only)
- Test: `sdd-cli/src/test/java/sdd/cli/implement/OrchestratorTest.java` (add 3), `sdd-cli/src/test/java/sdd/cli/ImplementCommandContractTest.java` (create)

**Interfaces:**
- Produces: `Orchestrator` gains an 11th constructor arg `JarBuilder jarBuilder` — FIVE construction sites total (critic-verified): the OrchestratorTest helper (line ~76) + three inline test ctors (~197, ~413, ~473) + ImplementCommand (~289); all gain `, new JarBuilder()`. Behavior in `runRepo`: (1) after the FIRST `startBranch` + `applyBumps` (tree at pinned base), when the repo provides any `binary-compatible` contract (`plan.contracts()` where `provider == repo && "binary-compatible".equals(compat())`): build baseline jars into `<runDir>/contracts/<repo>-baseline/` under the gradle permit; failure → event `"japicmp skipped: baseline build failed"` + flag disabling the gate for this repo; (2) attempt-1 `priorDigest` becomes `contractDigest(runDir, step)` — a `"\n\n## Actualized contracts (re-extracted from green upstreams — these supersede the drafted deltas)\n"` section listing each consumed contract id whose `RunStore.readContract` is non-null with its body (empty string when none); attempt-2 digest = `contractDigest + attemptDigest(outcome)`; (3) in the SUCCESS branch after publish, before SUCCEEDED: actualize (`ContractActualizer.actualize(step.repoRoot(), providedContracts)`) and `writeContract` each (event per contract `"contract <id> actualized"`); then when the gate is armed: build candidate jars into `<runDir>/contracts/<repo>-candidate/` (permit-wrapped; failure → `"japicmp skipped: candidate build failed"` event), match jars to baseline by name-minus-version (`name.replaceAll("-[0-9][^/]*\\.jar$", "")`), compare each pair — ANY binary-incompatible verdict ⇒ `transitionLocked(..., FAILED, ..., "binary-incompatible drift: " + first 200 chars of report)` + events, and the SUCCEEDED transition is skipped (cascade then blocks consumers); unmatched jars → event, no gate. Both two-jar stub tests must carry the comment `// NOTE: escalation would re-run startBranch+clean, deleting .baseline-done — these scripts stay on attempt 1 (done -> verify exit 0)` so a future script change can't silently invert the gate.
- Consumes: Task 1 `PlanContract.compat()`, Task 2 `ContractActualizer` + `RunStore.writeContract/readContract`, Task 3 `JarBuilder`/`JapicmpCheck`/`TestJars`.

- [ ] **Step 1: Write the failing tests.** Append to `OrchestratorTest` (update the `orchestrator(...)` helpers to pass `new JarBuilder()` as the 11th arg; reuse `repoWith`/`step`/`call` helpers; `TestJars` is package-visible):

```java
    private static PlanModel planWithContract(String libBase, String svcBase, String compat) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", libBase),
                        new PlanModel.PlanRepo("svc", "dependent", "CODE_CHANGE_LIKELY", "patch", svcBase)),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "NONE")),
                List.of(new PlanModel.PlanContract("c1", "java-api", "lib", List.of("svc"),
                        "Api.f(int): int", compat)),
                List.of(new PlanModel.PlanStep("lib", List.of(), "minor", List.of("c1"), List.of(),
                                List.of(), List.of(), "provider step"),
                        new PlanModel.PlanStep("svc", List.of(), "patch", List.of(), List.of("c1"),
                                List.of(), List.of(), "consumer step")));
    }

    @Test
    void binaryIncompatibleDriftFailsTheProviderBeforeConsumersStart() throws Exception {
        // Baseline jar (built at branch start, tree at base) has f(int): int; the candidate jar the
        // stub plants after the agent "worked" has f(int): long — japicmp must fail lib, cascade svc.
        Path jars = Files.createDirectories(ws.resolve("prebuilt"));
        Path baselineJar = TestJars.jar(jars, "base.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path brokenJar = TestJars.jar(jars, "broken.jar", "Api",
                "public class Api { public long f(int x) { return x; } }");
        // First assemble (baseline) copies base.jar; every later assemble copies broken.jar.
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *assemble*) mkdir -p build/libs; "
                        + "if [ -f .baseline-done ]; then cp " + brokenJar + " build/libs/lib-1.0.jar; "
                        + "else cp " + baselineJar + " build/libs/lib-1.0.jar; touch .baseline-done; fi; exit 0 ;; "
                        + "*) exit 0 ;; esac");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of(
                "lib", new RepoStep("lib", lib.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()),
                "svc", new RepoStep("svc", svc.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}")));

        Orchestrator.RunResult result = orchestrator(model)
                .run(runDir, planWithContract(lib.headSha(), svc.headSha(), "binary-compatible"), steps);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.state().stateOf("lib")).isEqualTo(RepoState.FAILED);
        assertThat(result.state().repos().stream()
                .filter(r -> r.repo().equals("lib")).findFirst().orElseThrow().detail())
                .contains("binary-incompatible");
        assertThat(result.state().stateOf("svc")).isEqualTo(RepoState.SKIPPED_UPSTREAM_FAILED);
    }

    @Test
    void compatibleDriftPassesTheGateAndActualizesContracts() throws Exception {
        Path jars = Files.createDirectories(ws.resolve("prebuilt"));
        Path baselineJar = TestJars.jar(jars, "base.jar", "Api",
                "public class Api { public int f(int x) { return x; } }");
        Path grownJar = TestJars.jar(jars, "grown.jar", "Api",
                "public class Api { public int f(int x) { return x; } public int g() { return 1; } }");
        FixtureRepo lib = repoWith("lib",
                "case \"$*\" in *assemble*) mkdir -p build/libs; "
                        + "if [ -f .baseline-done ]; then cp " + grownJar + " build/libs/lib-1.0.jar; "
                        + "else cp " + baselineJar + " build/libs/lib-1.0.jar; touch .baseline-done; fi; exit 0 ;; "
                        + "*) exit 0 ;; esac");
        // lib's tree carries real source so actualization has a surface to extract:
        lib.file("src/main/java/com/acme/Api.java",
                        "package com.acme;\npublic class Api { public int f(int x) { return x; } }\n")
                .commit("api source");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        Map<String, RepoStep> steps = Map.of(
                "lib", new RepoStep("lib", lib.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()),
                "svc", new RepoStep("svc", svc.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"svc ok\"}")));

        Orchestrator.RunResult result = orchestrator(model)
                .run(runDir, planWithContract(lib.headSha(), svc.headSha(), "binary-compatible"), steps);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(new RunStore(InstantSource.fixed(Instant.EPOCH)).readContract(runDir, "c1"))
                .contains("com.acme.Api");
    }

    @Test
    void consumersReceiveActualizedContractsInTheirWorkOrder() throws Exception {
        FixtureRepo lib = repoWith("lib", "exit 0");
        lib.file("src/main/java/com/acme/Api.java",
                        "package com.acme;\npublic class Api { public int f(int x) { return x; } }\n")
                .commit("api source");
        FixtureRepo svc = repoWith("svc", "exit 0");
        Path runDir = new RunStore(InstantSource.fixed(Instant.EPOCH)).create(ws, "S-v1", "{}");
        // CRITICAL: contractDigest iterates step.consumes() — the hand-built svc step MUST carry the
        // ContractRef (in production RepoStepResolver fills it from plan.json; unit tests must too).
        Map<String, RepoStep> steps = Map.of(
                "lib", new RepoStep("lib", lib.path(), "x", List.of(), List.of(), List.of(), List.of(), List.of()),
                "svc", new RepoStep("svc", svc.path(), "x", List.of(), List.of(), List.of(),
                        List.of(new sdd.agent.run.ContractRef("c1", "java-api", "lib", List.of("svc"),
                                "Api.f(int): int")), List.of()));
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                call("1", "done", "{\"result\":\"success\",\"summary\":\"lib ok\"}"),
                call("2", "done", "{\"result\":\"success\",\"summary\":\"svc ok\"}")));

        orchestrator(model).run(runDir,
                planWithContract(lib.headSha(), svc.headSha(), null), steps);   // no japicmp gate

        // svc's FIRST request (its work order) must contain the actualized section with lib's real API.
        String svcWorkOrder = model.requests().get(1).messages().stream()
                .map(m -> m.content() == null ? "" : m.content())
                .reduce("", String::concat);
        assertThat(svcWorkOrder).contains("Actualized contracts").contains("com.acme.Api");
    }
```

`ImplementCommandContractTest.java` — e2e drift proof through the CLI, fixture copied verbatim from `ImplementCommandPropagationTest` with these deltas: plan.json gains the c1 contract (`"compat":"binary-compatible"`, provider lib, consumers ["svc"], lib step provides c1, svc step consumes c1), the edge mechanism is `"NONE"`; lib's stub gradlew is the two-jar baseline/broken pattern above (jars pre-built with `TestJars` into the workspace); svc's stub is `exit 0`. Assert exit 2, output contains `lib: FAILED` with `binary-incompatible` and `svc: SKIPPED_UPSTREAM_FAILED`. Write the fixture out fully.

- [ ] **Step 2: Run — expect COMPILE FAILURE / RED.**
Run: `./gradlew :sdd-cli:test --tests 'sdd.cli.implement.OrchestratorTest' --tests 'sdd.cli.ImplementCommandContractTest'`

- [ ] **Step 3: Implement.** `Orchestrator` — new field + 11th ctor arg `JarBuilder jarBuilder`; new private helpers:

```java
    private List<PlanModel.PlanContract> providedContracts(PlanModel plan, String repo) {
        return plan.contracts().stream().filter(c -> c.provider().equals(repo)).toList();
    }

    private boolean needsCompatGate(PlanModel plan, String repo) {
        return providedContracts(plan, repo).stream()
                .anyMatch(c -> "binary-compatible".equals(c.compat()));
    }

    private String contractDigest(Path runDir, RepoStep step) {
        StringBuilder digest = new StringBuilder();
        for (sdd.agent.run.ContractRef consumed : step.consumes()) {
            String actual = store.readContract(runDir, consumed.id());
            if (actual != null) {
                digest.append("\n### ").append(consumed.id()).append(" (actualized)\n")
                        .append(actual).append('\n');
            }
        }
        return digest.isEmpty() ? "" : "\n\n## Actualized contracts (re-extracted from green "
                + "upstreams — these supersede the drafted deltas)\n" + digest;
    }

    private JarBuilder.Result buildJars(RepoStep step, String repo, Path outDir) {
        RunnerSettings settings = settingsFor.apply(repo);
        java.util.concurrent.Semaphore permits = settings.gradlePermits();
        if (permits != null) {
            permits.acquireUninterruptibly();
        }
        try {
            return jarBuilder.build(step.repoRoot(), settings.javaHome(), outDir);
        } finally {
            if (permits != null) {
                permits.release();
            }
        }
    }
```

In `runRepo`: **declare `baselineDir` and `compatGate` BEFORE the try block** (they are consumed in the SUCCESS branch AFTER the try/catch — declaring them inside the try is a compile error):

```java
            Path baselineDir = runDir.resolve("contracts").resolve(slug(repo) + "-baseline");
            boolean compatGate = false;
```

then, INSIDE the try, immediately after the first `startBranch` + `applyBumps`, arm the gate:

```java
            if (needsCompatGate(plan, repo)) {
                JarBuilder.Result baseline = buildJars(step, repo, baselineDir);
                if (baseline.ok() && !baseline.jars().isEmpty()) {
                    compatGate = true;
                } else {
                    events.add("japicmp skipped: baseline build failed — "
                            + summarize(baseline.log()));
                }
            }
```

Attempt digests: `String contracts = contractDigest(runDir, step);` — attempt 1 passes `contracts` (was `""`), attempt 2 passes `contracts + attemptDigest(outcome)`. In the SUCCESS branch, after the publish block and before the SUCCEEDED transition:

```java
            Map<String, String> actualized = ContractActualizer.actualize(step.repoRoot(),
                    providedContracts(plan, repo));
            for (Map.Entry<String, String> entry : actualized.entrySet()) {
                store.writeContract(runDir, entry.getKey(), entry.getValue());
                events.add("contract " + entry.getKey() + " actualized");
            }
            if (compatGate) {
                JarBuilder.Result candidate = buildJars(step, repo,
                        runDir.resolve("contracts").resolve(slug(repo) + "-candidate"));
                if (!candidate.ok() || candidate.jars().isEmpty()) {
                    events.add("japicmp skipped: candidate build failed — " + summarize(candidate.log()));
                } else {
                    String drift = compatDrift(baselineDir, candidate.jars(), events);
                    if (drift != null) {
                        store.writeAgentEvents(runDir, repo, events);
                        synchronized (lock) {
                            transitionLocked(runDir, state, repo, RepoState.FAILED, branch, null,
                                    attemptTag + "binary-incompatible drift: " + drift);
                        }
                        return true;
                    }
                }
            }
            store.writeAgentEvents(runDir, repo, events);
```

with:

```java
    /** Compares matched baseline/candidate jars; returns a short drift report or null when clean. */
    private static String compatDrift(Path baselineDir, List<Path> candidates, List<String> events) {
        StringBuilder drift = new StringBuilder();
        for (Path candidate : candidates) {
            String key = versionless(candidate.getFileName().toString());
            Path baseline;
            try (var jars = Files.list(baselineDir)) {
                baseline = jars.filter(p -> versionless(p.getFileName().toString()).equals(key))
                        .findFirst().orElse(null);
            } catch (java.io.IOException e) {
                baseline = null;
            }
            if (baseline == null) {
                events.add("japicmp skipped for " + candidate.getFileName() + ": no matching baseline jar");
                continue;
            }
            JapicmpCheck.Verdict verdict = JapicmpCheck.compare(baseline, candidate);
            events.add("japicmp " + candidate.getFileName() + ": "
                    + (verdict.binaryCompatible() ? "binary-compatible" : "BREAKING"));
            if (!verdict.binaryCompatible()) {
                drift.append(verdict.report());
            }
        }
        if (drift.isEmpty()) {
            return null;
        }
        String report = drift.toString().replace('\n', ' ').strip();
        return report.length() > 200 ? report.substring(0, 200) : report;
    }

    private static String versionless(String jarName) {
        return jarName.replaceAll("-[0-9][^/]*\\.jar$", "");
    }
```

(new imports: `java.nio.file.Files`, `sdd.agent.run.ContractRef` if not FQ'd. **Do NOT remove any existing `writeAgentEvents` call** — the post-try unconditional write and the post-publish write both stay (overwrite semantics make extra calls harmless, and removing them would drop the publish event from the publish-failure paths); the flows above only ADD a write on the drift-FAILED path and the final pre-SUCCEEDED write shown in the snippet.) `ImplementCommand`: the orchestrator construction gains `, new JarBuilder()`. OrchestratorTest helper ctors gain the same. Class javadoc gains: "Contract actualization + japicmp gate (design line 62): baseline jars from the pinned base tree, candidates after green, breaking drift fails the provider before consumers start."

- [ ] **Step 4: Run — expect PASS, then the full suite and build** (existing tests: no contracts in their plans → no gate, no baseline builds, contractDigest is empty → attempt-1 digest `""`-equivalent; escalation digest test unaffected since contracts text is empty there).
Run: `./gradlew :sdd-cli:test && ./gradlew build`

- [ ] **Step 5: Commit**

```bash
git add sdd-cli/src
git commit -m "feat: actualize contracts after green and gate binary compatibility with japicmp"
```

---

## Verification

1. `./gradlew build` — all modules green; allowlist test untouched.
2. Design line 62 clause by clause: re-extraction into downstream work orders (`consumersReceiveActualizedContractsInTheirWorkOrder` — real JavaParser extraction, real prompt content assertion); japicmp on `compat: binary-compatible` with breaking drift → provider FAILED before consumers (`binaryIncompatibleDriftFailsTheProviderBeforeConsumersStart` + the CLI-level `ImplementCommandContractTest`); actual contracts in `runs/<runId>/contracts/` (round-trip test + green-path assertion); KB read-only throughout (ContractActualizer takes no Jdbi).
3. The baseline interpretation (base-tree build, not Nexus) is recorded in Global Constraints and the Orchestrator javadoc.
4. Real-estate smoke unchanged: still blocked on the served coder; everything here is fixture-proven (real bytecode via TestJars, real extraction via JavaParser).

## Self-Review (completed at write time)

1. **Spec coverage:** design line 62 fully mapped (see Verification 2); the "from Nexus" clause consciously re-ratified as base-tree baseline (Global Constraints a). Phase-5 boundary respected (no review-time re-check here).
2. **Placeholder scan:** Task 1 Step 1 gives specifications-as-comments for tests whose fixture idioms live in files the implementer must read first — each names the exact class, input, and assertion; Task 4's e2e fixture is pinned to a named template with exhaustive deltas. External-library API shapes (japicmp, sdd-index extractor records) are flagged VERIFY-AND-ADAPT with the structure fixed — a deliberate uncertainty boundary, not an omission.
3. **Type consistency:** `PlanContract.compat()` (T1) consumed by T4's `needsCompatGate`; `ContractActualizer.actualize(Path, List<PlanContract>)` (T2) and `RunStore.writeContract/readContract` (T2) consumed by T4; `JarBuilder.Result(ok, jars, log)` and `JapicmpCheck.Verdict(binaryCompatible, report)` (T3) consumed by T4; `TestJars.jar(Path, String, String, String)` (T3) used by T3+T4 tests; Orchestrator 10→11 args with all four construction sites listed.
4. **Judgment calls for reviewers:** ratified list (a)-(g); the two-jar stub-gradlew pattern trades realism (no real Gradle in unit tests) for speed while `TestJars` keeps the BYTECODE real — japicmp sees genuine class files; assemble-failure-skips-gate is the deliberate non-fatal choice (d).
