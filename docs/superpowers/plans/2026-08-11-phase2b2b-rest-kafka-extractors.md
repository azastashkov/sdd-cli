# Phase 2B-2b — REST & Kafka Extractors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the knowledge layer's extraction: Spring REST endpoints, REST clients (Feign/RestTemplate/WebClient/RestClient) with ladder-resolved URIs, and Kafka producers/consumers — persisted into `rest_endpoint`/`rest_client`/`kafka_topic`/`kafka_role` with `module.kafka_status` — plus the repo-level shared solver and small hardenings carried from 2B-2a.

**Architecture:** `sdd.index.spring` gains `SpringModel` (records), `AnnotationValues` (attribute access), three extractors, `SpringPersistence`, and `SpringExtraction` (per-module orchestrator), all fed by the SAME parsed Sessions `SourceExtraction` already builds — one parse serves API-surface, references, and Spring extraction. A new `RepoSolver` builds ONE `CombinedTypeSolver` per repo (all modules' source roots + jar union; each fresh `JarTypeSolver` parented exactly once), replacing per-module solver construction. Deterministic — no model calls. REST client↔endpoint matching and Kafka topic linking are 2C.

**Tech Stack:** Existing stack only (JavaParser, snakeyaml, jdbi — all wired).

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — Component 1: REST endpoints ("@RestController… join class-level and method-level… context-path"), REST clients ("@FeignClient… RestTemplate/WebClient… resolution ladder… never ask the LLM"), Kafka ("@KafkaListener… KafkaTemplate.send… spring-cloud-stream → UNPARSED_STREAM"). Carry-forwards honored from `docs/superpowers/plans/2026-08-11-phase2b2a-spring-foundation.md` execution outcome: repo-level shared solver (#1), ValueResolver depth/visited guard + normalize(null) (#2), config precedence + error-append dedup (#3, #6). Deliberately deferred: `@Bean NewTopic` rows (2C curation enrichment), `.properties` activation keys, spring.profiles list form, Lombok static-field accessors, real-estate perf measurement (needs the estate).

## Global Constraints

- Java 21; never push; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` after a blank line.
- Deterministic-first: NO model calls; only `@Tag("gradle-it")` tests run real Gradle.
- Established contracts intact: `FtsSymbolWriter` sole FTS path; `dep_edge` declared-only; `api_usage` NULL-target filter convention; `Paths2.canonical*` for DB-bound paths; parse statuses `OK | DEGRADED | FAILED`; repo-atomic source persistence; never sink the run; `JarTypeSolver` instances are NEVER shared/re-parented.
- Ladder values exactly `LITERAL | CONSTANT | PROPERTY | DYNAMIC` (`MANUAL` is 2C-only). Client kinds exactly `FEIGN | RESTTEMPLATE | WEBCLIENT | RESTCLIENT`. Kafka roles exactly `PRODUCER | CONSUMER`. `module.kafka_status` values: `NULL | UNPARSED_STREAM`.
- Endpoint `norm_path` includes the module's `context_path` prefix; `path_template` is the annotation-derived path WITHOUT context path (spec: prepend at persistence).
- Pre-release schema: edit `V1__init.sql` in place (established precedent).

---

### Task 1: RepoSolver — one shared solver per repo

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/source/RepoSolver.java`
- Modify: `sdd-index/src/main/java/sdd/index/source/SourceParser.java` (extract `sourceRootsOf`; new overload taking a prebuilt `ParserConfiguration`)
- Modify: `sdd-index/src/main/java/sdd/index/source/SourceExtraction.java` (build one config per repo, pass to every parseModule)
- Test: `sdd-index/src/test/java/sdd/index/source/RepoSolverTest.java`

**Interfaces:**
- Consumes: `JarSolverCache` semantics (failure-only; fresh `JarTypeSolver` per good path — reuse the class), `TestJars` (tests).
- Produces:
  - `static List<Path> SourceParser.sourceRootsOf(Path moduleDir)` — `src/main/java` + `build/generated` when present (extracted from the existing body; existing behavior unchanged).
  - `final class RepoSolver` with `static ParserConfiguration configFor(List<Path> sourceRoots, List<Path> uniqueClasspathJars)` — builds `CombinedTypeSolver(ReflectionTypeSolver(true))` + one `JavaParserTypeSolver` per source root + one FRESH `JarTypeSolver` per jar (unreadable jars skipped via a local failure set), wraps in `JavaSymbolSolver`, returns a `ParserConfiguration` with `BLEEDING_EDGE` language level. Each `JarTypeSolver` is parented exactly once — by this single solver.
  - `static Session SourceParser.parseModule(Path repoRoot, Path moduleDir, ParserConfiguration config)` — walks THAT module's roots only, parses with the given config. Existing 3-/4-arg overloads unchanged (still per-module solvers) for compatibility and tests.
  - `SourceExtraction.extractRepo` switches to: collect all modules' `sourceRootsOf` + the union of all modules' classpath jars (deduped via `Paths2.canonicalString`) → ONE `RepoSolver.configFor(...)` → every module parsed with it.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.source;

import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RepoSolverTest {
    @TempDir Path repo;

    private Path module(String name, String pkgClassSource, String fileName) throws Exception {
        Path src = repo.resolve(name).resolve("src/main/java/com/acme/" + name);
        Files.createDirectories(src);
        Files.writeString(src.resolve(fileName), pkgClassSource);
        return repo.resolve(name);
    }

    @Test
    void crossModuleSourceTypesResolveThroughSharedSolver() throws Exception {
        Path modA = module("a", "package com.acme.a;\npublic class Alpha {}\n", "Alpha.java");
        Path modB = module("b", """
                package com.acme.b;
                import com.acme.a.Alpha;
                public class Beta { private Alpha alpha; }
                """, "Beta.java");

        ParserConfiguration config = RepoSolver.configFor(
                List.of(modA.resolve("src/main/java"), modB.resolve("src/main/java")), List.of());
        SourceParser.Session sessionB = SourceParser.parseModule(repo, modB, config);

        assertThat(sessionB.issues()).isEmpty();
        var field = sessionB.units().get(0).cu()
                .findAll(com.github.javaparser.ast.body.FieldDeclaration.class).get(0);
        assertThat(field.getVariable(0).getType().resolve().describe()).isEqualTo("com.acme.a.Alpha");
    }

    @Test
    void sharedJarAcrossModulesParsesBothModulesWithOneConfig() throws Exception {
        assumeTrue(TestJars.compilerAvailable());
        Path jar = TestJars.compiledJar(repo.resolve("libs"), "com.estate.lib.Widget");
        Path modA = module("a", """
                package com.acme.a;
                import com.estate.lib.Widget;
                public class A { private Widget w; }
                """, "A.java");
        Path modB = module("b", """
                package com.acme.b;
                import com.estate.lib.Widget;
                public class B { private Widget w; }
                """, "B.java");

        ParserConfiguration config = RepoSolver.configFor(
                List.of(modA.resolve("src/main/java"), modB.resolve("src/main/java")), List.of(jar));

        SourceParser.Session a = SourceParser.parseModule(repo, modA, config);
        SourceParser.Session b = SourceParser.parseModule(repo, modB, config);
        assertThat(a.issues()).isEmpty();
        assertThat(b.issues()).isEmpty();
        assertThat(b.units().get(0).cu()
                .findAll(com.github.javaparser.ast.body.FieldDeclaration.class).get(0)
                .getVariable(0).getType().resolve().describe()).isEqualTo("com.estate.lib.Widget");
    }

    @Test
    void unreadableJarIsSkippedNotFatal() {
        ParserConfiguration config = RepoSolver.configFor(List.of(), List.of(repo.resolve("ghost.jar")));
        assertThat(config).isNotNull();
    }
}
```
(Adjust `TestJars.compiledJar`'s signature to whatever it actually is — read the file first; if it only builds `com.estate.lib.Widget` fixed, use it as-is.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.RepoSolverTest'`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement**

`RepoSolver.java`:
```java
package sdd.index.source;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.nio.file.Path;
import java.util.List;

/**
 * One solver per repo: all modules' source roots plus the jar union.
 * Each JarTypeSolver is constructed fresh here and parented exactly once —
 * sharing instances across solvers is unsupported on JavaParser 3.26.2.
 */
public final class RepoSolver {
    private RepoSolver() {}

    public static ParserConfiguration configFor(List<Path> sourceRoots, List<Path> uniqueClasspathJars) {
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
        for (Path root : sourceRoots) {
            solver.add(new JavaParserTypeSolver(root));
        }
        for (Path jar : uniqueClasspathJars) {
            try {
                solver.add(new JarTypeSolver(jar));
            } catch (Exception ignored) {
                // unreadable jar — resolve without it
            }
        }
        return new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(solver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
    }
}
```

In `SourceParser`: extract the root-discovery block into `public static List<Path> sourceRootsOf(Path moduleDir)` and add:
```java
    public static Session parseModule(Path repoRoot, Path moduleDir, ParserConfiguration config) {
        List<Path> roots = sourceRootsOf(moduleDir);
        if (roots.isEmpty()) {
            return new Session(List.of(), List.of());
        }
        return parseRoots(repoRoot, roots, new JavaParser(config));
    }
```
Refactor the existing overloads to share `parseRoots` (the walk/parse/issues loop) — behavior identical, verified by the existing suites.

In `SourceExtraction.extractRepo`: before the module-work pass, collect `List<Path> allRoots` (every module's `sourceRootsOf(projectDir)`) and `List<Path> uniqueJars` (all modules' compileClasspath files, deduped by `Paths2.canonicalString`, insertion-ordered); build `ParserConfiguration repoConfig = RepoSolver.configFor(allRoots, uniqueJars);` once; replace the per-module `parseModule(repoRoot, projectDir, jars, jarCache)` call with `parseModule(repoRoot, projectDir, repoConfig)`. Remove the now-unused per-repo `JarSolverCache` from this flow (the class stays — the 3-/4-arg overloads still use it).

- [ ] **Step 4: Run the full module suite**

Run: `./gradlew :sdd-index:test`
Expected: PASS — including `SourceEndToEndTest`, `SourceParserTest`, `SourceExtractionTest`, ITs. Cross-module SAME-REPO types now resolve through the shared solver (previously only via the textual repoTypeIndex) — if any existing assertion counts change because same-repo refs now resolve as TYPE targets landing in file_ref instead of usages, adjust intent-preservingly and document in the report.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: repo-level shared symbol solver"
```

---

### Task 2: Carry-forward hardenings (ValueResolver guard, config precedence, normalize null, error dedup)

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/spring/ValueResolver.java`
- Modify: `sdd-index/src/main/java/sdd/index/spring/ConfigFileParser.java`
- Modify: `sdd-index/src/main/java/sdd/index/spring/RouteNormalizer.java`
- Modify: `sdd-index/src/main/java/sdd/index/store/SourcePersistence.java` + `sdd-index/src/main/java/sdd/index/store/IndexPersistence.java` (error-append dedup)
- Tests: extend the four corresponding test classes

**Interfaces:**
- Consumes: existing signatures (all unchanged).
- Produces: behavioral fixes only:
  1. `ValueResolver`: cycle safety via a visited set (identity of resolved `VariableDeclarator`s threaded through `resolvePart`), not stack exhaustion; the SOE catch stays as backstop.
  2. `ConfigFileParser.parseModuleConfig`: file processing order = `bootstrap*` first, then `application*` (alphabetical within each group) so application wins `defaultProfileProps` last-wins — matching real Spring precedence.
  3. `RouteNormalizer.normalize(null)` → `"/"`.
  4. `updateParseStatus`/`markStale` append is skipped when the existing error already contains the exact message (SQL `instr(COALESCE(error,''), :append) = 0` guard).

- [ ] **Step 1: Write the failing tests** (add to the existing classes)

`ValueResolverTest`:
```java
    @Test
    void cyclicConstantsResolveDynamicWithoutDeepRecursion() throws Exception {
        // existing cyclic test proves no hang; this pins the visited-set path:
        // depth stays shallow enough that no SOE is thrown internally — assert via timing-free contract:
        List<Expression> exprs = parseInitializers("""
                    Object X1 = CYC_A;
                """, """
                    static final String CYC_A = CYC_B;
                    static final String CYC_B = CYC_A;
                """);
        var r = ValueResolver.resolve(exprs.get(0), PROPS);
        assertThat(r.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
        assertThat(r.value()).isNull();
    }
```
(Adapt `parseInitializers` to accept extra static fields, or inline a second fixture writer — read the test class first; the existing cyclic test may already be structured so you can simply verify it still passes and add the extra-fields variant.)

`ConfigFileParserTest`:
```java
    @Test
    void applicationWinsOverBootstrapInDefaultProfileProps() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("bootstrap.yml"), "shared.key: from-bootstrap\n");
        Files.writeString(res.resolve("application.yml"), "shared.key: from-application\n");
        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);
        Map<String, String> defaults = SpringConfigPersistence.defaultProfileProps(r.entries());
        assertThat(defaults).containsEntry("shared.key", "from-application");
    }
```

`RouteNormalizerTest`:
```java
    @Test
    void nullTemplateNormalizesToRoot() {
        assertThat(RouteNormalizer.normalize(null)).isEqualTo("/");
    }
```

`IndexPersistenceTest`:
```java
    @Test
    void markStaleDoesNotAppendDuplicateIdenticalMessages() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            String error = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT error FROM repo WHERE name='svc-orders'").mapTo(String.class).one());
            assertThat(error.split("network down", -1).length - 1).isEqualTo(1);
        }
    }
```

- [ ] **Step 2: Run to verify the new tests fail, implement each fix minimally, verify green**

Implementation notes (adapt to actual code, read each file first):
- ValueResolver: thread `Set<VariableDeclarator> visited` (IdentityHashMap-backed via `Collections.newSetFromMap`) through `resolvePart`; on `!visited.add(v)` return `Optional.empty()`.
- ConfigFileParser: replace `files.sorted()` with an explicit comparator: `Comparator.comparing((Path f) -> f.getFileName().toString().startsWith("bootstrap") ? 0 : 1).thenComparing(f -> f.getFileName().toString())`.
- RouteNormalizer: `if (template == null) { return "/"; }` first line of `normalize`.
- Error dedup: wrap the append branch's CASE with `WHEN instr(COALESCE(error,''), :append) > 0 THEN error` (both `updateParseStatus` and `markStale` — keep the two SQL texts mirrored).

Run: `./gradlew :sdd-index:test`
Expected: all green.

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "fix: value-resolver visited set, config precedence, null route, error dedup"
```

---

### Task 3: `module.kafka_status` column + AnnotationValues helper

**Files:**
- Modify: `sdd-core/src/main/resources/sdd/db/V1__init.sql` (module gains `kafka_status TEXT` after `context_path`)
- Create: `sdd-index/src/main/java/sdd/index/spring/AnnotationValues.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/AnnotationValuesTest.java`

**Interfaces:**
- Consumes: JavaParser annotation AST.
- Produces (Tasks 4–6 consume):
```java
public final class AnnotationValues {
    public static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String simpleName)
    public static Optional<Expression> attr(AnnotationExpr ann, String name)
        // MarkerAnnotationExpr → empty; SingleMemberAnnotationExpr → present iff name is "value";
        // NormalAnnotationExpr → the pair named `name`
    public static List<Expression> attrList(AnnotationExpr ann, String name)
        // attr() unwrapped: ArrayInitializerExpr → its members; single expr → singleton; absent → empty
    public static List<Expression> attrListAny(AnnotationExpr ann, String first, String second)
        // attrList(first) if non-empty else attrList(second)  (Spring's value/path aliasing)
}
```

- [ ] **Step 1: Schema edit + failing tests**

`V1__init.sql` module table gains `kafka_status TEXT,` after `context_path TEXT` (adjust trailing commas).

`AnnotationValuesTest.java`:
```java
package sdd.index.spring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationValuesTest {
    private ClassOrInterfaceDeclaration parse(String annotations) {
        CompilationUnit cu = StaticJavaParser.parse(annotations + "\nclass T {}");
        return cu.getClassByName("T").orElseThrow();
    }

    @Test
    void marker() {
        var t = parse("@Deprecated");
        assertThat(AnnotationValues.annotation(t, "Deprecated")).isPresent();
        assertThat(AnnotationValues.attr(AnnotationValues.annotation(t, "Deprecated").get(), "value")).isEmpty();
    }

    @Test
    void singleMemberIsValue() {
        var t = parse("@RequestMapping(\"/api\")");
        var ann = AnnotationValues.annotation(t, "RequestMapping").get();
        assertThat(AnnotationValues.attr(ann, "value")).isPresent();
        assertThat(AnnotationValues.attr(ann, "path")).isEmpty();
        assertThat(AnnotationValues.attrList(ann, "value")).hasSize(1);
    }

    @Test
    void normalPairsAndArrays() {
        var t = parse("@RequestMapping(path = {\"/a\", \"/b\"}, method = RequestMethod.GET)");
        var ann = AnnotationValues.annotation(t, "RequestMapping").get();
        assertThat(AnnotationValues.attrList(ann, "path")).hasSize(2);
        assertThat(AnnotationValues.attr(ann, "method")).isPresent();
        assertThat(AnnotationValues.attrListAny(ann, "value", "path")).hasSize(2);
    }
}
```

- [ ] **Step 2: Run (fail), implement, run (pass)**

```java
package sdd.index.spring;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.List;
import java.util.Optional;

public final class AnnotationValues {
    private AnnotationValues() {}

    public static Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String simpleName) {
        return node.getAnnotations().stream()
                .filter(a -> a.getName().getIdentifier().equals(simpleName))
                .findFirst().map(a -> a);
    }

    public static Optional<Expression> attr(AnnotationExpr ann, String name) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return name.equals("value") ? Optional.of(single.getMemberValue()) : Optional.empty();
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals(name))
                    .findFirst().map(p -> p.getValue());
        }
        return Optional.empty();
    }

    public static List<Expression> attrList(AnnotationExpr ann, String name) {
        return attr(ann, name)
                .map(e -> e instanceof ArrayInitializerExpr arr
                        ? List.copyOf(arr.getValues())
                        : List.of(e))
                .orElse(List.of());
    }

    public static List<Expression> attrListAny(AnnotationExpr ann, String first, String second) {
        List<Expression> primary = attrList(ann, first);
        return primary.isEmpty() ? attrList(ann, second) : primary;
    }
}
```
Verify `./gradlew :sdd-core:test :sdd-index:test` stays green after the schema edit.

- [ ] **Step 3: Commit**

```bash
git add sdd-core/src sdd-index
git commit -m "feat: kafka_status column and annotation attribute helper"
```

---

### Task 4: SpringModel + RestEndpointExtractor

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/spring/SpringModel.java`
- Create: `sdd-index/src/main/java/sdd/index/spring/RestEndpointExtractor.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/RestEndpointExtractorTest.java`

**Interfaces:**
- Consumes: `SourceParser.Session`, `AnnotationValues`, `RouteNormalizer`, `ValueResolver`.
- Produces:
```java
public final class SpringModel {
    public record EndpointInfo(String classFqcn, String methodName, String httpMethod,
                               String pathTemplate, String requestType, String responseType) {}
    public record ClientInfo(String kind, String classFqcn, String methodOrSite, String httpMethod,
                             String uriTemplate, String targetHint, String resolution, String rawExpr) {}
    public record KafkaUse(String topic, String role, String classFqcn, String groupId,
                           String payloadType, String resolution, String rawExpr) {}
    public record SpringExtract(List<EndpointInfo> endpoints, List<ClientInfo> clients,
                                List<KafkaUse> kafka, boolean streamDetected) {}
}
public final class RestEndpointExtractor {
    public static List<SpringModel.EndpointInfo> extract(SourceParser.Session session,
                                                         Map<String, String> defaultProps)
}
```
Rules: classes with `@RestController`, or `@Controller` AND `@ResponseBody` (class-level; per-method `@ResponseBody` deferred to 2C — note, not TODO). Class-level bases = `@RequestMapping` `value|path` list resolved via `ValueResolver` (defaults `[""]` when absent/DYNAMIC→use rawExpr? No: DYNAMIC base → use `""` and keep going — path data best-effort). Method mappings: `@GetMapping→GET, @PostMapping→POST, @PutMapping→PUT, @DeleteMapping→DELETE, @PatchMapping→PATCH`, `@RequestMapping` with `method` attr containing `RequestMethod.X` → `X` (absent → `ANY`). Cross product bases × method paths → one `EndpointInfo` per pair, `pathTemplate = RouteNormalizer.join(base, methodPath)`. `requestType` = the `@RequestBody` parameter's declared type string (null if none); `responseType` = method return type string.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestEndpointExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session parse(String source) throws Exception {
        Path f = repo.resolve("src/main/java/com/acme/web/C.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void joinsClassAndMethodPathsAcrossVerbAnnotations() throws Exception {
        var session = parse("""
                package com.acme.web;
                import org.springframework.web.bind.annotation.*;
                @RestController
                @RequestMapping("/api/orders")
                public class C {
                    @GetMapping("/{id}") public String get(@PathVariable String id) { return id; }
                    @PostMapping public String create(@RequestBody OrderReq req) { return "x"; }
                    @RequestMapping(path = "/search", method = RequestMethod.GET)
                    public String search() { return "s"; }
                }
                class OrderReq {}
                """);
        List<SpringModel.EndpointInfo> endpoints =
                RestEndpointExtractor.extract(session, Map.of());

        assertThat(endpoints).hasSize(3);
        assertThat(endpoints).anySatisfy(e -> {
            assertThat(e.httpMethod()).isEqualTo("GET");
            assertThat(e.pathTemplate()).isEqualTo("/api/orders/{id}");
            assertThat(e.methodName()).isEqualTo("get");
            assertThat(e.responseType()).isEqualTo("String");
        });
        assertThat(endpoints).anySatisfy(e -> {
            assertThat(e.httpMethod()).isEqualTo("POST");
            assertThat(e.pathTemplate()).isEqualTo("/api/orders");
            assertThat(e.requestType()).isEqualTo("OrderReq");
        });
        assertThat(endpoints).anySatisfy(e -> {
            assertThat(e.httpMethod()).isEqualTo("GET");
            assertThat(e.pathTemplate()).isEqualTo("/api/orders/search");
        });
    }

    @Test
    void controllerWithResponseBodyCountsPlainControllerDoesNot() throws Exception {
        var session = parse("""
                package com.acme.web;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller @ResponseBody
                public class C { @GetMapping("/x") public String x() { return "x"; } }
                """);
        assertThat(RestEndpointExtractor.extract(session, Map.of())).hasSize(1);

        var mvc = parse("""
                package com.acme.web;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.*;
                @Controller
                public class C { @GetMapping("/page") public String page() { return "view"; } }
                """);
        assertThat(RestEndpointExtractor.extract(mvc, Map.of())).isEmpty();
    }

    @Test
    void multiplePathsProduceMultipleEndpointsAndPropertyPathsResolve() throws Exception {
        var session = parse("""
                package com.acme.web;
                import org.springframework.web.bind.annotation.*;
                @RestController
                public class C {
                    @GetMapping({"/a", "/b"}) public String multi() { return "m"; }
                    @GetMapping("${routes.health}") public String health() { return "h"; }
                }
                """);
        List<SpringModel.EndpointInfo> endpoints = RestEndpointExtractor.extract(
                session, Map.of("routes.health", "/health"));
        assertThat(endpoints).extracting(SpringModel.EndpointInfo::pathTemplate)
                .containsExactlyInAnyOrder("/a", "/b", "/health");
    }
}
```

- [ ] **Step 2: Run (fail), implement, run (pass)**

`SpringModel.java` exactly per the Interfaces block (package `sdd.index.spring`, private ctor, `java.util.List` import).

`RestEndpointExtractor.java`:
```java
package sdd.index.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import sdd.index.source.SourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RestEndpointExtractor {
    private static final Map<String, String> VERB_ANNOTATIONS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

    private RestEndpointExtractor() {}

    public static List<SpringModel.EndpointInfo> extract(SourceParser.Session session,
                                                         Map<String, String> defaultProps) {
        List<SpringModel.EndpointInfo> out = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            for (ClassOrInterfaceDeclaration c : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                boolean rest = AnnotationValues.annotation(c, "RestController").isPresent()
                        || (AnnotationValues.annotation(c, "Controller").isPresent()
                            && AnnotationValues.annotation(c, "ResponseBody").isPresent());
                if (!rest) {
                    continue;
                }
                String fqcn = c.getFullyQualifiedName().orElse(c.getNameAsString());
                List<String> bases = AnnotationValues.annotation(c, "RequestMapping")
                        .map(ann -> resolvePaths(ann, defaultProps)).orElse(List.of(""));
                for (MethodDeclaration m : c.getMethods()) {
                    for (Mapping mapping : mappingsOf(m, defaultProps)) {
                        for (String base : bases) {
                            for (String path : mapping.paths()) {
                                out.add(new SpringModel.EndpointInfo(fqcn, m.getNameAsString(),
                                        mapping.verb(), RouteNormalizer.join(base, path),
                                        requestBodyType(m), m.getType().asString()));
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private record Mapping(String verb, List<String> paths) {}

    private static List<Mapping> mappingsOf(MethodDeclaration m, Map<String, String> props) {
        List<Mapping> mappings = new ArrayList<>();
        for (Map.Entry<String, String> e : VERB_ANNOTATIONS.entrySet()) {
            AnnotationValues.annotation(m, e.getKey()).ifPresent(ann ->
                    mappings.add(new Mapping(e.getValue(), resolvePaths(ann, props))));
        }
        AnnotationValues.annotation(m, "RequestMapping").ifPresent(ann -> {
            String verb = AnnotationValues.attr(ann, "method")
                    .map(expr -> {
                        String text = expr.toString();
                        int dot = text.lastIndexOf('.');
                        return dot >= 0 ? text.substring(dot + 1) : text;
                    }).orElse("ANY");
            mappings.add(new Mapping(verb, resolvePaths(ann, props)));
        });
        return mappings;
    }

    private static List<String> resolvePaths(AnnotationExpr ann, Map<String, String> props) {
        List<Expression> exprs = AnnotationValues.attrListAny(ann, "value", "path");
        if (exprs.isEmpty()) {
            return List.of("");
        }
        List<String> paths = new ArrayList<>();
        for (Expression expr : exprs) {
            ValueResolver.Resolved r = ValueResolver.resolve(expr, props);
            paths.add(r.value() != null ? r.value() : "");
        }
        return paths;
    }

    private static String requestBodyType(MethodDeclaration m) {
        return m.getParameters().stream()
                .filter(p -> AnnotationValues.annotation(p, "RequestBody").isPresent())
                .map(p -> p.getType().asString())
                .findFirst().orElse(null);
    }
}
```
(If `AnnotationValues.annotation` needs a `NodeWithAnnotations<?>` bound adjustment for `Parameter`/`MethodDeclaration`, both implement it — no change expected.)

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.RestEndpointExtractorTest'` — PASS.

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "feat: spring model and rest endpoint extractor"
```

---

### Task 5: RestClientExtractor

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/spring/RestClientExtractor.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/RestClientExtractorTest.java`

**Interfaces:**
- Consumes: `AnnotationValues`, `ValueResolver`, `RouteNormalizer`, `SpringModel.ClientInfo`.
- Produces: `static List<SpringModel.ClientInfo> extract(SourceParser.Session session, Map<String,String> defaultProps)`.
Rules:
  - **Feign:** interfaces annotated `@FeignClient`; `targetHint` = resolved `url` attr if present else resolved `name`/`value` attr (DYNAMIC → rawExpr text); base = resolved `path` attr (default ""); one `ClientInfo(kind="FEIGN", methodOrSite=methodName, httpMethod, uriTemplate=join(base, methodPath), resolution, rawExpr)` per mapped method (same verb annotations as Task 4; resolution = the method-path resolution, LITERAL when the path came only from literals).
  - **RestTemplate:** `MethodCallExpr` with name in the verb map (`getForObject/getForEntity→GET, postForObject/postForEntity→POST, exchange→ANY, put→PUT, delete→DELETE, patchForObject→PATCH`) whose receiver resolves to a type whose qualified name ends with `RestTemplate` — OR (resolution failing) whose receiver text contains `restTemplate` ignoring case. First argument through the ladder → `uriTemplate`/`resolution`/`rawExpr`; `methodOrSite` = enclosing method name; `targetHint` = null; kind `RESTTEMPLATE`.
  - **WebClient/RestClient:** `MethodCallExpr` named `uri` whose scope CHAIN contains a call named `get|post|put|delete|patch` (walk `getScope()` while it is a MethodCallExpr) — verb = that call's name uppercased; kind = `WEBCLIENT` if any chain receiver resolves to/mentions `WebClient`, `RESTCLIENT` for `RestClient` (text heuristic on the chain string when unresolvable; if neither name appears, skip the site). First `uri(...)` argument through the ladder.
  - All resolution failures downgrade gracefully: a site whose URI arg can't resolve still emits a row with resolution `DYNAMIC` and the raw expression. `classFqcn` = enclosing type fqcn.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session parse(String relPath, String source) throws Exception {
        Path f = repo.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void feignClientMethodsBecomeClientRows() throws Exception {
        var session = parse("src/main/java/com/acme/BillingClient.java", """
                package com.acme;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.*;
                @FeignClient(name = "billing", path = "/pay")
                public interface BillingClient {
                    @PostMapping("/charge") String charge(@RequestBody String req);
                    @GetMapping("/status/{id}") String status(@PathVariable String id);
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());

        assertThat(clients).hasSize(2);
        assertThat(clients).allSatisfy(c -> {
            assertThat(c.kind()).isEqualTo("FEIGN");
            assertThat(c.targetHint()).isEqualTo("billing");
        });
        assertThat(clients).anySatisfy(c -> {
            assertThat(c.httpMethod()).isEqualTo("POST");
            assertThat(c.uriTemplate()).isEqualTo("/pay/charge");
        });
    }

    @Test
    void feignUrlAttributeWinsAndResolvesPlaceholders() throws Exception {
        var session = parse("src/main/java/com/acme/ExtClient.java", """
                package com.acme;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                @FeignClient(name = "ext", url = "${ext.base-url}")
                public interface ExtClient { @GetMapping("/ping") String ping(); }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(
                session, Map.of("ext.base-url", "http://ext:9000"));
        assertThat(clients).singleElement().satisfies(c ->
                assertThat(c.targetHint()).isEqualTo("http://ext:9000"));
    }

    @Test
    void restTemplateCallSitesWithConstantAndDynamicUris() throws Exception {
        var session = parse("src/main/java/com/acme/Caller.java", """
                package com.acme;
                public class Caller {
                    private static final String BASE = "http://billing:8080";
                    private Object restTemplate;
                    public void ok() { call(((org.springframework.web.client.RestTemplate) restTemplate)
                            .getForObject(BASE + "/charge", String.class)); }
                    public void dyn() { call(((org.springframework.web.client.RestTemplate) restTemplate)
                            .getForObject(System.getenv("URL"), String.class)); }
                    private void call(Object o) {}
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());

        assertThat(clients).hasSize(2);
        assertThat(clients).anySatisfy(c -> {
            assertThat(c.kind()).isEqualTo("RESTTEMPLATE");
            assertThat(c.httpMethod()).isEqualTo("GET");
            assertThat(c.uriTemplate()).isEqualTo("http://billing:8080/charge");
            assertThat(c.resolution()).isEqualTo("CONSTANT");
        });
        assertThat(clients).anySatisfy(c -> {
            assertThat(c.resolution()).isEqualTo("DYNAMIC");
            assertThat(c.uriTemplate()).isNull();
            assertThat(c.rawExpr()).contains("System.getenv");
        });
    }

    @Test
    void webClientChainYieldsVerbAndUri() throws Exception {
        var session = parse("src/main/java/com/acme/W.java", """
                package com.acme;
                public class W {
                    private org.springframework.web.reactive.function.client.WebClient webClient;
                    public void go() { webClient.get().uri("/api/items").retrieve(); }
                }
                """);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, Map.of());
        assertThat(clients).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo("WEBCLIENT");
            assertThat(c.httpMethod()).isEqualTo("GET");
            assertThat(c.uriTemplate()).isEqualTo("/api/items");
        });
    }
}
```
(Note: the Spring/Feign annotations are NOT on the test classpath — they parse as unresolved annotations, which is exactly the production situation when a repo's jars are missing; the extractor works on names, not resolved types. The RestTemplate cast in the fixture gives the receiver a resolvable-by-text type without needing Spring jars.)

- [ ] **Step 2: Run (fail), implement, run (pass)**

`RestClientExtractor.java`:
```java
package sdd.index.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import sdd.index.source.SourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RestClientExtractor {
    private static final Map<String, String> TEMPLATE_VERBS = Map.of(
            "getForObject", "GET", "getForEntity", "GET",
            "postForObject", "POST", "postForEntity", "POST",
            "exchange", "ANY", "put", "PUT", "delete", "DELETE", "patchForObject", "PATCH");
    private static final Map<String, String> FEIGN_VERBS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> CHAIN_VERBS = Set.of("get", "post", "put", "delete", "patch");

    private RestClientExtractor() {}

    public static List<SpringModel.ClientInfo> extract(SourceParser.Session session,
                                                       Map<String, String> defaultProps) {
        List<SpringModel.ClientInfo> out = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            extractFeign(unit, defaultProps, out);
            extractCallSites(unit, defaultProps, out);
        }
        return List.copyOf(out);
    }

    private static void extractFeign(SourceParser.ParsedUnit unit, Map<String, String> props,
                                     List<SpringModel.ClientInfo> out) {
        for (ClassOrInterfaceDeclaration c : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
            var feign = AnnotationValues.annotation(c, "FeignClient");
            if (!c.isInterface() || feign.isEmpty()) {
                continue;
            }
            String fqcn = c.getFullyQualifiedName().orElse(c.getNameAsString());
            String targetHint = AnnotationValues.attr(feign.get(), "url")
                    .or(() -> AnnotationValues.attr(feign.get(), "name"))
                    .or(() -> AnnotationValues.attr(feign.get(), "value"))
                    .map(expr -> {
                        ValueResolver.Resolved r = ValueResolver.resolve(expr, props);
                        return r.value() != null ? r.value() : r.rawExpr();
                    }).orElse(null);
            String base = AnnotationValues.attr(feign.get(), "path")
                    .map(expr -> Optional.ofNullable(ValueResolver.resolve(expr, props).value()).orElse(""))
                    .orElse("");
            for (MethodDeclaration m : c.getMethods()) {
                for (Map.Entry<String, String> verb : FEIGN_VERBS.entrySet()) {
                    AnnotationValues.annotation(m, verb.getKey()).ifPresent(ann -> {
                        List<Expression> paths = AnnotationValues.attrListAny(ann, "value", "path");
                        if (paths.isEmpty()) {
                            out.add(new SpringModel.ClientInfo("FEIGN", fqcn, m.getNameAsString(),
                                    verb.getValue(), RouteNormalizer.join(base, ""), targetHint,
                                    "LITERAL", ann.toString()));
                            return;
                        }
                        for (Expression pathExpr : paths) {
                            ValueResolver.Resolved r = ValueResolver.resolve(pathExpr, props);
                            out.add(new SpringModel.ClientInfo("FEIGN", fqcn, m.getNameAsString(),
                                    verb.getValue(),
                                    r.value() != null ? RouteNormalizer.join(base, r.value()) : null,
                                    targetHint, r.resolution().name(), r.rawExpr()));
                        }
                    });
                }
            }
        }
    }

    private static void extractCallSites(SourceParser.ParsedUnit unit, Map<String, String> props,
                                         List<SpringModel.ClientInfo> out) {
        for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (TEMPLATE_VERBS.containsKey(name) && receiverIsRestTemplate(call)) {
                emitSite(unit, call, "RESTTEMPLATE", TEMPLATE_VERBS.get(name), props, out);
            } else if (name.equals("uri")) {
                chainVerbAndKind(call).ifPresent(vk ->
                        emitSite(unit, call, vk.kind(), vk.verb(), props, out));
            }
        }
    }

    private static void emitSite(SourceParser.ParsedUnit unit, MethodCallExpr call, String kind,
                                 String verb, Map<String, String> props,
                                 List<SpringModel.ClientInfo> out) {
        if (call.getArguments().isEmpty()) {
            return;
        }
        ValueResolver.Resolved r = ValueResolver.resolve(call.getArgument(0), props);
        String fqcn = enclosingTypeFqcn(call);
        String site = call.findAncestor(MethodDeclaration.class)
                .map(MethodDeclaration::getNameAsString).orElse("<init>");
        out.add(new SpringModel.ClientInfo(kind, fqcn, site, verb,
                r.value(), null, r.resolution().name(), r.rawExpr()));
    }

    private static boolean receiverIsRestTemplate(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        try {
            String qualified = scope.get().calculateResolvedType().describe();
            if (qualified.endsWith("RestTemplate")) {
                return true;
            }
        } catch (Exception | StackOverflowError ignored) {
            // fall through to text heuristic
        }
        return scope.get().toString().toLowerCase(Locale.ROOT).contains("resttemplate");
    }

    private record VerbKind(String verb, String kind) {}

    private static Optional<VerbKind> chainVerbAndKind(MethodCallExpr uriCall) {
        String chainText = uriCall.toString();
        String kind = null;
        try {
            Optional<Expression> scope = uriCall.getScope();
            if (scope.isPresent()) {
                String resolved = scope.get().calculateResolvedType().describe();
                if (resolved.contains("WebClient")) {
                    kind = "WEBCLIENT";
                } else if (resolved.contains("RestClient")) {
                    kind = "RESTCLIENT";
                }
            }
        } catch (Exception | StackOverflowError ignored) {
            // text heuristic below
        }
        if (kind == null) {
            if (chainText.contains("webClient") || chainText.contains("WebClient")) {
                kind = "WEBCLIENT";
            } else if (chainText.contains("restClient") || chainText.contains("RestClient")) {
                kind = "RESTCLIENT";
            } else {
                return Optional.empty();
            }
        }
        Expression scope = uriCall.getScope().orElse(null);
        while (scope instanceof MethodCallExpr chained) {
            if (CHAIN_VERBS.contains(chained.getNameAsString())) {
                return Optional.of(new VerbKind(
                        chained.getNameAsString().toUpperCase(Locale.ROOT), kind));
            }
            scope = chained.getScope().orElse(null);
        }
        return Optional.empty();
    }

    private static String enclosingTypeFqcn(MethodCallExpr call) {
        return call.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                .orElse("unknown");
    }
}
```

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.RestClientExtractorTest'` — PASS (4 tests). WebClient chain note: `webClient.get().uri(...)` — the resolved-type path fails without Spring jars, so the text heuristic (`chainText.contains("webClient")`) carries the fixture; both paths are legitimate per the rules.

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "feat: rest client extractor with ladder-resolved uris"
```

---

### Task 6: KafkaExtractor

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/spring/KafkaExtractor.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/KafkaExtractorTest.java`

**Interfaces:**
- Consumes: `AnnotationValues`, `ValueResolver`, `SpringModel.KafkaUse`.
- Produces:
```java
public final class KafkaExtractor {
    public record KafkaResult(List<SpringModel.KafkaUse> uses, boolean streamDetected) {}
    public static KafkaResult extract(SourceParser.Session session, Map<String, String> defaultProps,
                                      List<Path> classpathJars, java.util.Collection<String> allConfigKeys)
}
```
Rules: consumers = methods annotated `@KafkaListener`: one `KafkaUse(role="CONSUMER")` per `topics` entry (ladder; DYNAMIC → topic = rawExpr text, resolution DYNAMIC); `topicPattern` attr → one row with topic = resolved-or-raw pattern, resolution DYNAMIC; `groupId` via ladder (value or null); `payloadType` = first parameter's declared type (null when none). Producers = `MethodCallExpr` named `send` whose receiver resolves to a type containing `KafkaTemplate` OR receiver text contains `kafkaTemplate` (ignore case): first arg via ladder → topic; `payloadType` = second argument's `calculateResolvedType().describe()` best-effort (null on failure); role `PRODUCER`; groupId null. `classFqcn` = declaring/enclosing type. `streamDetected` = any classpath jar filename contains `spring-cloud-stream` OR any config key starts with `spring.cloud.stream`.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session parse(String source) throws Exception {
        Path f = repo.resolve("src/main/java/com/acme/K.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void listenerTopicsAndTemplateSendsAreExtracted() throws Exception {
        var session = parse("""
                package com.acme;
                import org.springframework.kafka.annotation.KafkaListener;
                public class K {
                    private static final String OUT_TOPIC = "orders.v1.placed";
                    private Object kafkaTemplate;
                    @KafkaListener(topics = "${kafka.in-topic}", groupId = "orders")
                    public void onMessage(String payload) {}
                    public void publish(String event) {
                        ((org.springframework.kafka.core.KafkaTemplate) kafkaTemplate)
                                .send(OUT_TOPIC, event);
                    }
                }
                """);
        KafkaExtractor.KafkaResult result = KafkaExtractor.extract(
                session, Map.of("kafka.in-topic", "orders.v1.incoming"), List.of(), List.of());

        assertThat(result.streamDetected()).isFalse();
        assertThat(result.uses()).hasSize(2);
        assertThat(result.uses()).anySatisfy(u -> {
            assertThat(u.role()).isEqualTo("CONSUMER");
            assertThat(u.topic()).isEqualTo("orders.v1.incoming");
            assertThat(u.resolution()).isEqualTo("PROPERTY");
            assertThat(u.groupId()).isEqualTo("orders");
            assertThat(u.payloadType()).isEqualTo("String");
        });
        assertThat(result.uses()).anySatisfy(u -> {
            assertThat(u.role()).isEqualTo("PRODUCER");
            assertThat(u.topic()).isEqualTo("orders.v1.placed");
            assertThat(u.resolution()).isEqualTo("CONSTANT");
        });
    }

    @Test
    void topicPatternAndDynamicTopicsAreRecordedAsDynamic() throws Exception {
        var session = parse("""
                package com.acme;
                import org.springframework.kafka.annotation.KafkaListener;
                public class K {
                    @KafkaListener(topicPattern = "orders\\\\..*")
                    public void onAny(String payload) {}
                }
                """);
        KafkaExtractor.KafkaResult result = KafkaExtractor.extract(session, Map.of(), List.of(), List.of());
        assertThat(result.uses()).singleElement().satisfies(u -> {
            assertThat(u.role()).isEqualTo("CONSUMER");
            assertThat(u.resolution()).isEqualTo("DYNAMIC");
            assertThat(u.topic()).contains("orders");
        });
    }

    @Test
    void streamDetectionByJarNameAndConfigKey() throws Exception {
        var session = parse("package com.acme;\npublic class K {}\n");
        assertThat(KafkaExtractor.extract(session, Map.of(),
                List.of(Path.of("/cache/spring-cloud-stream-4.1.0.jar")), List.of())
                .streamDetected()).isTrue();
        assertThat(KafkaExtractor.extract(session, Map.of(), List.of(),
                List.of("spring.cloud.stream.bindings.input.destination"))
                .streamDetected()).isTrue();
    }
}
```

- [ ] **Step 2: Run (fail), implement, run (pass)**

`KafkaExtractor.java`:
```java
package sdd.index.spring;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import sdd.index.source.SourceParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class KafkaExtractor {
    public record KafkaResult(List<SpringModel.KafkaUse> uses, boolean streamDetected) {}

    private KafkaExtractor() {}

    public static KafkaResult extract(SourceParser.Session session, Map<String, String> defaultProps,
                                      List<Path> classpathJars, Collection<String> allConfigKeys) {
        List<SpringModel.KafkaUse> uses = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            for (MethodDeclaration m : unit.cu().findAll(MethodDeclaration.class)) {
                AnnotationValues.annotation(m, "KafkaListener").ifPresent(ann ->
                        extractListener(m, ann, defaultProps, uses));
            }
            for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("send") && receiverIsKafkaTemplate(call)
                        && !call.getArguments().isEmpty()) {
                    extractSend(call, defaultProps, uses);
                }
            }
        }
        boolean stream = classpathJars.stream().anyMatch(j ->
                        j.getFileName().toString().contains("spring-cloud-stream"))
                || allConfigKeys.stream().anyMatch(k -> k.startsWith("spring.cloud.stream"));
        return new KafkaResult(List.copyOf(uses), stream);
    }

    private static void extractListener(MethodDeclaration m,
                                        com.github.javaparser.ast.expr.AnnotationExpr ann,
                                        Map<String, String> props, List<SpringModel.KafkaUse> uses) {
        String fqcn = m.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName).orElse("unknown");
        String groupId = AnnotationValues.attr(ann, "groupId")
                .map(e -> ValueResolver.resolve(e, props).value()).orElse(null);
        String payloadType = m.getParameters().isEmpty() ? null
                : m.getParameter(0).getType().asString();
        for (Expression topicExpr : AnnotationValues.attrListAny(ann, "topics", "value")) {
            ValueResolver.Resolved r = ValueResolver.resolve(topicExpr, props);
            uses.add(new SpringModel.KafkaUse(
                    r.value() != null ? r.value() : r.rawExpr(), "CONSUMER", fqcn,
                    groupId, payloadType, r.resolution().name(), r.rawExpr()));
        }
        AnnotationValues.attr(ann, "topicPattern").ifPresent(patternExpr -> {
            ValueResolver.Resolved r = ValueResolver.resolve(patternExpr, props);
            uses.add(new SpringModel.KafkaUse(
                    r.value() != null ? r.value() : r.rawExpr(), "CONSUMER", fqcn,
                    groupId, payloadType, "DYNAMIC", r.rawExpr()));
        });
    }

    private static void extractSend(MethodCallExpr call, Map<String, String> props,
                                    List<SpringModel.KafkaUse> uses) {
        String fqcn = call.findAncestor(ClassOrInterfaceDeclaration.class)
                .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName).orElse("unknown");
        ValueResolver.Resolved r = ValueResolver.resolve(call.getArgument(0), props);
        String payloadType = null;
        if (call.getArguments().size() >= 2) {
            try {
                payloadType = call.getArgument(1).calculateResolvedType().describe();
            } catch (Exception | StackOverflowError ignored) {
                // best-effort
            }
        }
        uses.add(new SpringModel.KafkaUse(
                r.value() != null ? r.value() : r.rawExpr(), "PRODUCER", fqcn,
                null, payloadType, r.resolution().name(), r.rawExpr()));
    }

    private static boolean receiverIsKafkaTemplate(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        try {
            if (scope.get().calculateResolvedType().describe().contains("KafkaTemplate")) {
                return true;
            }
        } catch (Exception | StackOverflowError ignored) {
            // fall through
        }
        return scope.get().toString().toLowerCase(Locale.ROOT).contains("kafkatemplate");
    }
}
```

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.KafkaExtractorTest'` — PASS (3 tests). (Payload type of `String` literal second arg resolves via ReflectionTypeSolver as `java.lang.String` — the first test asserts CONSUMER payloadType from the parameter, not the producer's; producer payloadType is unasserted there by design.)

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "feat: kafka listener and template-send extractor with stream detection"
```

---

### Task 7: SpringPersistence

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/store/SpringPersistence.java`
- Test: `sdd-index/src/test/java/sdd/index/store/SpringPersistenceTest.java`

**Interfaces:**
- Consumes: `SpringModel`, schema tables `rest_endpoint`, `rest_client`, `kafka_topic`, `kafka_role`, `module.kafka_status`, `RouteNormalizer`.
- Produces:
```java
public final class SpringPersistence {
    public static void persistModuleSpring(org.jdbi.v3.core.Handle h, long moduleId,
                                           String contextPath, SpringModel.SpringExtract extract)
}
```
One Handle (joins the repo transaction): delete module's `rest_endpoint`, `rest_client`, `kafka_role` rows; insert endpoints (`norm_path = RouteNormalizer.normalize(RouteNormalizer.join(contextPath, e.pathTemplate()))`, `path_template` as extracted, `profile` NULL); insert clients (`norm_path = uriTemplate == null ? null : RouteNormalizer.normalize(uriTemplate)`); kafka: `INSERT INTO kafka_topic(name, resolution) ... ON CONFLICT(name) DO NOTHING` then id lookup, insert `kafka_role`; `UPDATE module SET kafka_status = :status` (`"UNPARSED_STREAM"` when `extract.streamDetected()`, else NULL).

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.spring.SpringModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringPersistenceTest {
    @TempDir Path ws;
    private Database db;
    private long moduleId;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
            moduleId = h.createQuery("SELECT id FROM module").mapTo(Long.class).one();
        });
    }

    private static SpringModel.SpringExtract extract(boolean stream) {
        return new SpringModel.SpringExtract(
                List.of(new SpringModel.EndpointInfo("com.acme.C", "get", "GET",
                        "/api/orders/{id}", null, "String")),
                List.of(new SpringModel.ClientInfo("FEIGN", "com.acme.B", "charge", "POST",
                        "/pay/charge", "billing", "LITERAL", "@PostMapping(\"/charge\")")),
                List.of(new SpringModel.KafkaUse("orders.v1", "PRODUCER", "com.acme.K",
                        null, "java.lang.String", "CONSTANT", "OUT_TOPIC")),
                stream);
    }

    @Test
    void persistsEndpointsClientsKafkaWithContextPathPrepend() {
        db.jdbi().useHandle(h ->
                SpringPersistence.persistModuleSpring(h, moduleId, "/orders", extract(false)));

        Map<String, Object> ep = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT http_method, path_template, norm_path FROM rest_endpoint").mapToMap().one());
        assertThat(ep).containsEntry("path_template", "/api/orders/{id}")
                .containsEntry("norm_path", "/orders/api/orders/{}");
        Map<String, Object> cl = db.jdbi().withHandle(h -> h.createQuery(
                "SELECT kind, target_hint, norm_path, resolution FROM rest_client").mapToMap().one());
        assertThat(cl).containsEntry("kind", "FEIGN").containsEntry("target_hint", "billing")
                .containsEntry("norm_path", "/pay/charge").containsEntry("resolution", "LITERAL");
        Map<String, Object> role = db.jdbi().withHandle(h -> h.createQuery("""
                SELECT t.name, r.role FROM kafka_role r JOIN kafka_topic t ON t.id = r.topic_id""")
                .mapToMap().one());
        assertThat(role).containsEntry("name", "orders.v1").containsEntry("role", "PRODUCER");
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT kafka_status FROM module").mapTo(String.class).one())).isNull();
    }

    @Test
    void repersistReplacesRowsAndTopicUpsertDoesNotDuplicate() {
        db.jdbi().useHandle(h -> {
            SpringPersistence.persistModuleSpring(h, moduleId, null, extract(false));
            SpringPersistence.persistModuleSpring(h, moduleId, null, extract(true));
        });
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM rest_endpoint").mapTo(Integer.class).one())).isEqualTo(1);
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM kafka_topic").mapTo(Integer.class).one())).isEqualTo(1);
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM kafka_role").mapTo(Integer.class).one())).isEqualTo(1);
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT kafka_status FROM module").mapTo(String.class).one()))
                .isEqualTo("UNPARSED_STREAM");
    }
}
```

- [ ] **Step 2: Run (fail), implement, run (pass)**

```java
package sdd.index.store;

import org.jdbi.v3.core.Handle;
import sdd.index.spring.RouteNormalizer;
import sdd.index.spring.SpringModel;

public final class SpringPersistence {
    private SpringPersistence() {}

    public static void persistModuleSpring(Handle h, long moduleId, String contextPath,
                                           SpringModel.SpringExtract extract) {
        h.createUpdate("DELETE FROM rest_endpoint WHERE module_id=:m").bind("m", moduleId).execute();
        h.createUpdate("DELETE FROM rest_client WHERE module_id=:m").bind("m", moduleId).execute();
        h.createUpdate("DELETE FROM kafka_role WHERE module_id=:m").bind("m", moduleId).execute();

        for (SpringModel.EndpointInfo e : extract.endpoints()) {
            h.createUpdate("""
                            INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method,
                                                      path_template, norm_path, request_type, response_type)
                            VALUES (:m, :cls, :name, :verb, :path, :norm, :req, :resp)""")
                    .bind("m", moduleId).bind("cls", e.classFqcn()).bind("name", e.methodName())
                    .bind("verb", e.httpMethod()).bind("path", e.pathTemplate())
                    .bind("norm", RouteNormalizer.normalize(
                            RouteNormalizer.join(contextPath, e.pathTemplate())))
                    .bind("req", e.requestType()).bind("resp", e.responseType()).execute();
        }
        for (SpringModel.ClientInfo c : extract.clients()) {
            h.createUpdate("""
                            INSERT INTO rest_client(module_id, kind, class_fqcn, method_or_site,
                                                    http_method, uri_template, norm_path, target_hint,
                                                    resolution, raw_expr)
                            VALUES (:m, :kind, :cls, :site, :verb, :uri, :norm, :hint, :res, :raw)""")
                    .bind("m", moduleId).bind("kind", c.kind()).bind("cls", c.classFqcn())
                    .bind("site", c.methodOrSite()).bind("verb", c.httpMethod())
                    .bind("uri", c.uriTemplate())
                    .bind("norm", c.uriTemplate() == null ? null
                            : RouteNormalizer.normalize(c.uriTemplate()))
                    .bind("hint", c.targetHint()).bind("res", c.resolution())
                    .bind("raw", c.rawExpr()).execute();
        }
        for (SpringModel.KafkaUse k : extract.kafka()) {
            h.createUpdate("INSERT INTO kafka_topic(name, resolution) VALUES (:n, :r) "
                            + "ON CONFLICT(name) DO NOTHING")
                    .bind("n", k.topic()).bind("r", k.resolution()).execute();
            long topicId = h.createQuery("SELECT id FROM kafka_topic WHERE name=:n")
                    .bind("n", k.topic()).mapTo(Long.class).one();
            h.createUpdate("""
                            INSERT INTO kafka_role(module_id, topic_id, role, class_fqcn, group_id, payload_type)
                            VALUES (:m, :t, :role, :cls, :grp, :payload)""")
                    .bind("m", moduleId).bind("t", topicId).bind("role", k.role())
                    .bind("cls", k.classFqcn()).bind("grp", k.groupId())
                    .bind("payload", k.payloadType()).execute();
        }
        h.createUpdate("UPDATE module SET kafka_status=:s WHERE id=:m")
                .bind("s", extract.streamDetected() ? "UNPARSED_STREAM" : null)
                .bind("m", moduleId).execute();
    }
}
```

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.SpringPersistenceTest'` — PASS.

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "feat: spring persistence for endpoints clients and kafka"
```

---

### Task 8: SpringExtraction wired into SourceExtraction + CLI counts

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/spring/SpringExtraction.java`
- Modify: `sdd-index/src/main/java/sdd/index/source/SourceExtraction.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/IndexCommand.java`
- Tests: extend `sdd-index/src/test/java/sdd/index/SourceEndToEndTest.java` (minimal — full e2e is Task 9), new `sdd-cli` assertion not required (CLI line is print-only; ITs cover)

**Interfaces:**
- Consumes: everything above; `ModuleWork` (session + config already carried), `SpringConfigPersistence.defaultProfileProps`.
- Produces:
  - `SpringExtraction.extractModule(SourceParser.Session session, Map<String,String> defaultProps, List<Path> classpathJars, Collection<String> allConfigKeys)` → `SpringModel.SpringExtract` (runs the three extractors; pure).
  - `SourceExtraction.extractRepo`: per module inside the repo transaction, after config persist — compute `defaultProps` from the module's config entries, run `SpringExtraction.extractModule`, then `SpringPersistence.persistModuleSpring(h, moduleId, defaultProps.get("server.servlet.context-path"), extract)`. Carry each module's classpath jar list on `ModuleWork` (it's computed in the work pass already — retain it).
  - `IndexCommand`: after the `usage:` line, print `spring: %d endpoints, %d clients, %d kafka roles` from three scalar queries.

- [ ] **Step 1: Failing test** — extend `SourceEndToEndTest`: give the svc-orders fixture a controller source file:
```java
                .file("src/main/java/com/acme/orders/OrderController.java", """
                        package com.acme.orders;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/orders")
                        public class OrderController {
                            @GetMapping("/{id}") public String get(@PathVariable String id) { return id; }
                        }
                        """)
```
and assert after the existing config assertions:
```java
            Map<String, Object> endpoint = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT http_method, norm_path FROM rest_endpoint").mapToMap().one());
            assertThat(endpoint).containsEntry("http_method", "GET")
                    .containsEntry("norm_path", "/orders/api/orders/{}");
```
(`/orders` context path comes from the fixture's existing application.yml.)

- [ ] **Step 2: Run (fail), implement, run (pass)**

`SpringExtraction.java`:
```java
package sdd.index.spring;

import sdd.index.source.SourceParser;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class SpringExtraction {
    private SpringExtraction() {}

    public static SpringModel.SpringExtract extractModule(SourceParser.Session session,
                                                          Map<String, String> defaultProps,
                                                          List<Path> classpathJars,
                                                          Collection<String> allConfigKeys) {
        List<SpringModel.EndpointInfo> endpoints = RestEndpointExtractor.extract(session, defaultProps);
        List<SpringModel.ClientInfo> clients = RestClientExtractor.extract(session, defaultProps);
        KafkaExtractor.KafkaResult kafka = KafkaExtractor.extract(
                session, defaultProps, classpathJars, allConfigKeys);
        return new SpringModel.SpringExtract(endpoints, clients, kafka.uses(), kafka.streamDetected());
    }
}
```

`SourceExtraction`: retain each module's jar list on `ModuleWork` (add a `List<Path> jars` component); in the transaction loop after `SpringConfigPersistence.persistModuleConfig`:
```java
                Map<String, String> defaults =
                        SpringConfigPersistence.defaultProfileProps(w.config().entries());
                List<String> allKeys = w.config().entries().stream()
                        .map(ConfigFileParser.ConfigEntry::key).toList();
                SpringModel.SpringExtract spring = SpringExtraction.extractModule(
                        w.session(), defaults, w.jars(), allKeys);
                SpringPersistence.persistModuleSpring(h, w.moduleId(),
                        defaults.get("server.servlet.context-path"), spring);
```

`IndexCommand` after the `usage:` printf:
```java
            int[] springCounts = db.jdbi().withHandle(h -> new int[]{
                    h.createQuery("SELECT count(*) FROM rest_endpoint").mapTo(Integer.class).one(),
                    h.createQuery("SELECT count(*) FROM rest_client").mapTo(Integer.class).one(),
                    h.createQuery("SELECT count(*) FROM kafka_role").mapTo(Integer.class).one()});
            out.printf(Locale.ROOT, "spring: %d endpoints, %d clients, %d kafka roles%n",
                    springCounts[0], springCounts[1], springCounts[2]);
```

Run: `./gradlew :sdd-index:test :sdd-cli:test` — green.

- [ ] **Step 3: Commit**

```bash
git add sdd-index sdd-cli
git commit -m "feat: spring extraction wired into the index pipeline"
```

---

### Task 9: End-to-end + IT coverage

**Files:**
- Modify: `sdd-index/src/test/java/sdd/index/SourceEndToEndTest.java` (full Spring fixture: Feign + RestTemplate + Kafka)
- Modify: `sdd-index/src/test/java/sdd/index/IndexServiceIT.java` (endpoint assertion on the real-Gradle path)

**Interfaces:** consumes everything; produces confidence.

- [ ] **Step 1: Extend the e2e fixture** — add to svc-orders:
```java
                .file("src/main/java/com/acme/orders/BillingClient.java", """
                        package com.acme.orders;
                        import org.springframework.cloud.openfeign.FeignClient;
                        import org.springframework.web.bind.annotation.PostMapping;
                        @FeignClient(name = "billing", path = "/pay")
                        public interface BillingClient {
                            @PostMapping("/charge") String charge(String req);
                        }
                        """)
                .file("src/main/java/com/acme/orders/Events.java", """
                        package com.acme.orders;
                        import org.springframework.kafka.annotation.KafkaListener;
                        public class Events {
                            private static final String OUT = "orders.v1.placed";
                            private Object kafkaTemplate;
                            @KafkaListener(topics = "${kafka.in-topic}")
                            public void in(String msg) {}
                            public void out(String e) {
                                ((org.springframework.kafka.core.KafkaTemplate) kafkaTemplate).send(OUT, e);
                            }
                        }
                        """)
```
and `kafka.in-topic: orders.v1.incoming` to the fixture's application.yml. New assertions:
```java
            assertThat(db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM rest_client WHERE kind='FEIGN' AND target_hint='billing'")
                    .mapTo(Integer.class).one())).isEqualTo(1);
            var topics = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT t.name, r.role FROM kafka_role r JOIN kafka_topic t ON t.id=r.topic_id "
                            + "ORDER BY t.name").mapToMap().list());
            assertThat(topics).hasSize(2);
            assertThat(topics.get(0)).containsEntry("name", "orders.v1.incoming")
                    .containsEntry("role", "CONSUMER");
            assertThat(topics.get(1)).containsEntry("name", "orders.v1.placed")
                    .containsEntry("role", "PRODUCER");
```

- [ ] **Step 2: Extend IndexServiceIT** — add a controller + application.yml to the existing svc-orders REAL-Gradle fixture and assert one `rest_endpoint` row exists post-run (counts only — the e2e covers detail):
```java
            Integer endpointCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM rest_endpoint").mapTo(Integer.class).one());
            assertThat(endpointCount).isGreaterThanOrEqualTo(1);
```

- [ ] **Step 3: Full build + commit**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, everything green.

```bash
git add sdd-index/src
git commit -m "test: end-to-end spring extraction coverage"
```

---

## Self-Review (completed at write time)

1. **Spec coverage (2B-2b scope):** REST endpoints with class/method join + context-path prepend at persistence → Tasks 4, 7; REST clients (Feign name/url/path, RestTemplate, WebClient/RestClient) with the ladder and DYNAMIC downgrade → Task 5; Kafka listeners/producers with ladder + topicPattern-as-DYNAMIC + spring-cloud-stream `UNPARSED_STREAM` → Tasks 3, 6, 7; carry-forwards: repo-level shared solver → Task 1; ValueResolver visited set, config precedence, normalize(null), error dedup → Task 2. Deferred (documented in header): `@Bean NewTopic` rows, per-method `@ResponseBody`, `.properties` activation keys, Lombok static accessors, perf measurement.
2. **Placeholder scan:** all code steps complete; contingencies (`TestJars` signature, `parseInitializers` shape, `AnnotationValues` bound) name exact fallback procedures.
3. **Type consistency:** `SpringModel.EndpointInfo(classFqcn, methodName, httpMethod, pathTemplate, requestType, responseType)` consistent across Tasks 4, 7, 8; `ClientInfo(kind, classFqcn, methodOrSite, httpMethod, uriTemplate, targetHint, resolution, rawExpr)` across 5, 7; `KafkaUse(topic, role, classFqcn, groupId, payloadType, resolution, rawExpr)` across 6, 7; `SpringExtract(endpoints, clients, kafka, streamDetected)` across 4, 7, 8; `KafkaExtractor.extract(Session, Map, List<Path>, Collection<String>)` matches Task 8's call; `SpringPersistence.persistModuleSpring(Handle, long, String, SpringExtract)` matches Task 8; `RepoSolver.configFor(List<Path>, List<Path>)` + `SourceParser.parseModule(Path, Path, ParserConfiguration)` consistent between Tasks 1 and later use.
