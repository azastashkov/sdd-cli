# Phase 2B-2a — Extraction Hardening + Spring Config & Resolution Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the 2B-1 carry-forward hardening (shared jar solvers, widened reference recall, real field-level Lombok) and the Spring foundation (config-property extraction with profiles, `spring_app_name`/`context_path`, route normalization, the literal→constant→property→DYNAMIC resolution ladder) that 2B-2b's REST/Kafka extractors will consume.

**Architecture:** New package `sdd.index.spring` for config parsing + persistence; `sdd.index.source` gains `JarSolverCache`, widened `ReferenceExtractor`, field-level Lombok synthesis, `RouteNormalizer`, `ValueResolver`. Config extraction joins `SourceExtraction`'s existing repo-atomic transaction. Deterministic — no model calls.

**Tech Stack:** Existing stack + snakeyaml on sdd-index (already in the catalog from sdd-core's use).

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` — Component 1: config extraction ("SnakeYAML over application*.yml, bootstrap*.yml, .properties → flattened (key, value, profile) rows"), resolution ladder ("literal → constant folding → @Value/config property → DYNAMIC raw expression"), `spring_app_name`/`context_path` module columns. Carry-forwards honored from `docs/superpowers/plans/2026-08-11-phase2b1-java-api-surface.md` execution outcome: ReferenceExtractor widening, field-Lombok synthesis, JarTypeSolver sharing, nested-class fqcn test, ReflectionTypeSolver(jreOnly), markStale append. Plan 2B-2b (REST endpoint/client + Kafka extractors, `module.kafka_status` column) follows; REST/Kafka matching passes are 2C.

## Global Constraints

- Java 21; never push; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` after a blank line.
- Deterministic-first: NO model calls; only `@Tag("gradle-it")` tests run real Gradle.
- Established contracts stay intact: `FtsSymbolWriter` sole FTS write path; `dep_edge` declared-only; `api_usage` consumers filter `target_module_id IS NOT NULL`; all DB-bound paths through `Paths2.canonical*`; parse statuses exactly `OK | DEGRADED | FAILED`; source persistence stays repo-atomic (one transaction per repo); source-extraction failure never sinks the run.
- Resolution ladder values exactly: `LITERAL | CONSTANT | PROPERTY | DYNAMIC` (spec: rest_client.resolution vocabulary; `MANUAL` is assigned only by 2C's curation, never by extractors).
- Config profile storage: default profile rows have `profile = NULL`; named profiles store the profile string; the ladder consults default-profile values only.
- `config_property.source_file` is the repo-relative path of the file the entry came from.

---

### Task 1: JarSolverCache + ReflectionTypeSolver(jreOnly)

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/source/JarSolverCache.java`
- Modify: `sdd-index/src/main/java/sdd/index/source/SourceParser.java` (new 4-arg overload; 3-arg delegates; jreOnly)
- Modify: `sdd-index/src/main/java/sdd/index/source/SourceExtraction.java` (one cache per repo)
- Test: `sdd-index/src/test/java/sdd/index/source/JarSolverCacheTest.java`

**Interfaces:**
- Consumes: JavaParser `JarTypeSolver`.
- Produces:
  - `final class JarSolverCache` — `Optional<TypeSolver> get(Path jar)`: builds a `JarTypeSolver` on first request per canonical path, returns the SAME instance on repeat requests, caches failures as `Optional.empty()` (never retries a bad jar), thread-confined (no synchronization needed — extraction is single-threaded per repo).
  - `SourceParser.parseModule(Path repoRoot, Path moduleDir, List<Path> classpathJars, JarSolverCache jarCache)` — new overload; the existing 3-arg overload delegates with a fresh private cache (existing callers/tests unchanged).
  - `ReflectionTypeSolver` constructed with `jreOnly = true` (the tool's own classpath must not shadow estate jars).

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

class JarSolverCacheTest {
    @TempDir Path tmp;

    private Path writeTinyJar(String name) throws Exception {
        Path jar = tmp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("placeholder.txt"));
            out.write("x".getBytes());
            out.closeEntry();
        }
        return jar;
    }

    @Test
    void samePathYieldsSameSolverInstance() throws Exception {
        Path jar = writeTinyJar("a.jar");
        JarSolverCache cache = new JarSolverCache();
        var first = cache.get(jar);
        var second = cache.get(jar);
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get()).isSameAs(second.get());
    }

    @Test
    void missingJarCachesFailureWithoutThrowing() {
        JarSolverCache cache = new JarSolverCache();
        Path ghost = tmp.resolve("no-such.jar");
        assertThat(cache.get(ghost)).isEmpty();
        assertThat(cache.get(ghost)).isEmpty(); // second call also clean, from failure cache
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.JarSolverCacheTest'`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement**

`JarSolverCache.java`:
```java
package sdd.index.source;

import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.resolution.TypeSolver;
import sdd.index.store.Paths2;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Per-repo cache: one JarTypeSolver per jar path; failures cached as empty. */
public final class JarSolverCache {
    private final Map<String, Optional<TypeSolver>> cache = new HashMap<>();

    public Optional<TypeSolver> get(Path jar) {
        return cache.computeIfAbsent(Paths2.canonicalString(jar), key -> {
            try {
                return Optional.of(new JarTypeSolver(jar));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}
```
(If `Paths2.canonicalString(Path)` doesn't exist under that exact name, use whatever the canonical-string helper in `sdd.index.store.Paths2` is actually called — read the file first; do not duplicate the logic.)

In `SourceParser`:
```java
    public static Session parseModule(Path repoRoot, Path moduleDir, List<Path> classpathJars) {
        return parseModule(repoRoot, moduleDir, classpathJars, new JarSolverCache());
    }

    public static Session parseModule(Path repoRoot, Path moduleDir, List<Path> classpathJars,
                                      JarSolverCache jarCache) {
        ...existing body, with the jar loop replaced by:
        for (Path jar : classpathJars) {
            jarCache.get(jar).ifPresent(solver::add);
        }
        ...and the reflection solver line changed to:
        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(true));
```
(`ReflectionTypeSolver(true)` = jreOnly; estate classes resolve from estate jars, not the tool's classpath. If any existing SourceParserTest asserts resolution of a NON-JRE tool-classpath type, adjust that test — JRE types like `java.lang.String` are unaffected.)

In `SourceExtraction.extractRepo`: create `JarSolverCache jarCache = new JarSolverCache();` before the module loop and pass it to every `SourceParser.parseModule(...)` call.

- [ ] **Step 4: Run the module suites**

Run: `./gradlew :sdd-index:test`
Expected: PASS (new tests + all existing source tests; gradle-it ITs may run — fine).

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: shared per-repo jar solver cache and jre-only reflection solver"
```

---

### Task 2: ReferenceExtractor widening — object creations + type references

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/source/ReferenceExtractor.java`
- Modify: `sdd-index/src/test/java/sdd/index/source/ReferenceExtractorTest.java` (add cases)
- Modify: `sdd-index/src/test/java/sdd/index/SourceEndToEndTest.java` (remove the artificial same-package import from the fixture)

**Interfaces:**
- Consumes: existing `ReferenceExtractor.extract(Session, Map<String,String>)` contract (unchanged signature).
- Produces: widened recall — additional target kinds: `ObjectCreationExpr` (resolved constructor declaring type, refKind `CALL`) and every resolved `ClassOrInterfaceType` node (field/return/param/local/generic type references, refKind `TYPE`). Existing kinds (`IMPORT`, `EXTENDS`, `CALL`) unchanged. Dedup and JDK-filter rules unchanged. `refKind` vocabulary becomes `IMPORT | EXTENDS | CALL | TYPE`.

- [ ] **Step 1: Write the failing tests** (add to `ReferenceExtractorTest`)

```java
    @Test
    void samePackageNewWithoutImportProducesFileRef() throws Exception {
        var session = write(Map.of(
                "src/main/java/com/acme/svc/OrderService.java", """
                        package com.acme.svc;
                        public class OrderService {
                            public OrderHelper helper() { return new OrderHelper(); }
                        }
                        """,
                "src/main/java/com/acme/svc/OrderHelper.java",
                        "package com.acme.svc;\npublic class OrderHelper {}\n"));
        Map<String, String> index = session.units().stream().collect(Collectors.toMap(
                u -> "com.acme.svc." + u.file().getFileName().toString().replace(".java", ""),
                SourceParser.ParsedUnit::relPath));

        ReferenceExtractor.Refs refs = ReferenceExtractor.extract(session, index);

        assertThat(refs.fileRefs()).anySatisfy(fr -> {
            assertThat(fr.srcRel()).endsWith("OrderService.java");
            assertThat(fr.dstRel()).endsWith("OrderHelper.java");
        });
    }

    @Test
    void fieldTypeOnlyReferenceProducesFileRefAndExternalTypeProducesUsage() throws Exception {
        var session = write(Map.of(
                "src/main/java/com/acme/svc/Holder.java", """
                        package com.acme.svc;
                        public class Holder {
                            private Held held;
                            public com.acme.pricing.PriceCalculator calc() { return null; }
                        }
                        """,
                "src/main/java/com/acme/svc/Held.java",
                        "package com.acme.svc;\npublic class Held {}\n"));
        Map<String, String> index = session.units().stream().collect(Collectors.toMap(
                u -> "com.acme.svc." + u.file().getFileName().toString().replace(".java", ""),
                SourceParser.ParsedUnit::relPath));

        ReferenceExtractor.Refs refs = ReferenceExtractor.extract(session, index);

        assertThat(refs.fileRefs()).anySatisfy(fr -> assertThat(fr.dstRel()).endsWith("Held.java"));
        assertThat(refs.usages()).anySatisfy(u -> {
            assertThat(u.targetFqcn()).isEqualTo("com.acme.pricing.PriceCalculator");
            assertThat(u.refKind()).isEqualTo("TYPE");
        });
    }

    @Test
    void nestedClassReferenceResolvesWithCanonicalFqcn() throws Exception {
        var session = write(Map.of(
                "src/main/java/com/acme/svc/Outer.java", """
                        package com.acme.svc;
                        public class Outer { public static class Inner {} }
                        """,
                "src/main/java/com/acme/svc/User.java", """
                        package com.acme.svc;
                        public class User { private Outer.Inner inner; }
                        """));
        // index keyed the way ApiSurfaceExtractor keys it: JavaParser getFullyQualifiedName (dots)
        Map<String, String> index = Map.of(
                "com.acme.svc.Outer", "src/main/java/com/acme/svc/Outer.java",
                "com.acme.svc.Outer.Inner", "src/main/java/com/acme/svc/Outer.java",
                "com.acme.svc.User", "src/main/java/com/acme/svc/User.java");

        ReferenceExtractor.Refs refs = ReferenceExtractor.extract(session, index);

        assertThat(refs.fileRefs()).anySatisfy(fr -> {
            assertThat(fr.srcRel()).endsWith("User.java");
            assertThat(fr.dstRel()).endsWith("Outer.java");
        });
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.ReferenceExtractorTest'`
Expected: the three new tests FAIL (no ObjectCreation/TYPE capture yet); existing test still passes.

- [ ] **Step 3: Implement**

In `ReferenceExtractor.extract`, inside the per-unit loop, add after the method-call block:

```java
            for (com.github.javaparser.ast.expr.ObjectCreationExpr creation
                    : unit.cu().findAll(com.github.javaparser.ast.expr.ObjectCreationExpr.class)) {
                try {
                    targets.add(new Target(
                            creation.resolve().declaringType().getQualifiedName(), "CALL"));
                } catch (Exception | StackOverflowError ignored) {
                    // best-effort
                }
            }
            for (ClassOrInterfaceType typeRef : unit.cu().findAll(ClassOrInterfaceType.class)) {
                resolveType(typeRef).ifPresent(fqcn -> targets.add(new Target(fqcn, "TYPE")));
            }
```

Notes for the implementer:
- `resolveType` already exists (used for extends/implements) and already swallows failures; extends/implements clauses will now ALSO surface as `TYPE` targets — harmless: dedup is per `(fqcn, refKind)`, and file_ref counts just increase by one per pair, which is the intended "more evidence" semantics.
- Nested-type resolution: JavaParser's `getQualifiedName()` for `Outer.Inner` returns `com.acme.svc.Outer.Inner` (dots) — matching the index keys produced by `ApiSurfaceExtractor` (`getFullyQualifiedName()`). The nested test pins this agreement (2B-1 carry-forward).
- Keep the JDK-prefix filter and dedup logic untouched — they apply to the new targets automatically because they run at the common target-processing loop.

In `SourceEndToEndTest`: remove the `import com.acme.pricing.PriceCalculator;`-adjacent artificial same-package import line (the fixture's `OrderService` had an explicit import of `OrderHelper` added in Task 8 of 2B-1 — delete that import line so the fixture is realistic same-package code). The file_ref assertion must still pass (now via the widened extractor). Also update the api_usage assertion if the cross-repo usage now appears with refKind TYPE in addition to IMPORT — the e2e asserts `hasSize(1)` on linked usages joined per fqcn; with dedup per (fqcn, refKind) there may now be TWO api_usage rows (IMPORT + TYPE) for PriceCalculator, both linked. Change the assertion to select DISTINCT target_fqcn (still exactly one distinct target) and adjust `lastUsageReport().internalRefs()` expectation accordingly (>=1 rather than ==1, or exact 2 — pick the exact number the data shows and assert it precisely with a comment).

- [ ] **Step 4: Run to verify green**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.ReferenceExtractorTest' --tests 'sdd.index.SourceEndToEndTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: widen reference recall to object creations and type references"
```

---

### Task 3: Real field-level Lombok synthesis

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/source/LombokShim.java`
- Modify: `sdd-index/src/test/java/sdd/index/source/LombokShimTest.java`

**Interfaces:**
- Consumes: existing `LombokShim.apply(TypeDeclaration<?>)` → `Result(synthesized, unknownLombok)` (signature unchanged).
- Produces: field-level `@Getter`/`@Setter` synthesize real members for THAT field (`synthesizedBy = "lombok:@Getter"` / `"lombok:@Setter"`, same rules as type-level: boolean→isX, final→no setter); field-level Getter/Setter no longer trip the PARTIAL stopgap; any OTHER field-level lombok-imported annotation still sets `unknownLombok = true`.

- [ ] **Step 1: Write the failing tests** (add/adjust in `LombokShimTest`)

```java
    @Test
    void fieldLevelGetterSynthesizesAccessorForThatFieldOnlyAndStaysOk() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.Getter;
                public class T {
                    @Getter private String id;
                    private int hidden;
                }
                """);
        assertThat(t.members()).extracting(SourceModel.MemberInfo::signature)
                .contains("getId()").doesNotContain("getHidden()");
        assertThat(t.apiConfidence()).isEqualTo("OK");
    }

    @Test
    void fieldLevelSetterSkipsFinalFields() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.Setter;
                public class T {
                    @Setter private String name;
                    @Setter private final String fixed = "x";
                }
                """);
        assertThat(t.members()).extracting(SourceModel.MemberInfo::signature)
                .contains("setName(String)").doesNotContain("setFixed(String)");
    }

    @Test
    void unknownFieldLevelLombokAnnotationStillMarksPartial() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.experimental.Wither;
                public class T { @Wither private String id; }
                """);
        assertThat(t.apiConfidence()).isEqualTo("PARTIAL");
    }
```

Also UPDATE the 2B-1 stopgap test `fieldLevelGeneratingAnnotationMarksPartial` (or its actual name — read the file): field-level `@Getter` must now yield `OK` with a synthesized member, so rewrite that test's expectation or delete it in favor of the first new test above — do NOT leave a contradictory test.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.LombokShimTest'`
Expected: new tests FAIL; note which existing stopgap test conflicts.

- [ ] **Step 3: Implement**

In `LombokShim.apply`:
1. Remove the I8 stopgap that sets `unknownLombok` for field-level GENERATING annotations.
2. Add per-field synthesis after the type-level blocks (dedup map handles overlaps with type-level synthesis):

```java
        for (FieldDeclaration f : type.getFields()) {
            if (f.isStatic()) {
                continue;
            }
            java.util.Set<String> fieldAnnotations = f.getAnnotations().stream()
                    .map(a -> a.getName().getIdentifier())
                    .collect(java.util.stream.Collectors.toSet());
            for (VariableDeclarator v : f.getVariables()) {
                if (fieldAnnotations.contains("Getter")) {
                    String prefix = v.getTypeAsString().equals("boolean") ? "is" : "get";
                    String name = prefix + capitalize(v.getNameAsString());
                    synthesized.add(new SourceModel.MemberInfo(name, name + "()",
                            v.getTypeAsString(), "lombok:@Getter"));
                }
                if (fieldAnnotations.contains("Setter") && !f.isFinal()) {
                    String name = "set" + capitalize(v.getNameAsString());
                    synthesized.add(new SourceModel.MemberInfo(name,
                            name + "(" + v.getTypeAsString() + ")", "void", "lombok:@Setter"));
                }
            }
        }
```
3. Field-level unknown detection: keep flagging `unknownLombok` when a FIELD annotation is lombok-imported and NOT in `GENERATING ∪ IGNORED` (the `@Wither` test) — i.e. narrow the removed stopgap from "any GENERATING field annotation → PARTIAL" to "any UNKNOWN lombok field annotation → PARTIAL", reusing `importedFromLombok`.
(Adapt names to the actual current code — read `LombokShim.java` first; the structure evolved through two fix rounds.)

- [ ] **Step 4: Run to verify green**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.LombokShimTest' --tests 'sdd.index.source.ApiSurfaceExtractorTest'`
Expected: PASS, no contradictory tests remain.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: synthesize field-level lombok accessors instead of PARTIAL stopgap"
```

---

### Task 4: ConfigFileParser — flattened properties with profiles

**Files:**
- Modify: `sdd-index/build.gradle.kts` (add `implementation(libs.snakeyaml)`)
- Create: `sdd-index/src/main/java/sdd/index/spring/ConfigFileParser.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/ConfigFileParserTest.java`

**Interfaces:**
- Consumes: snakeyaml, `java.util.Properties`.
- Produces:
```java
public final class ConfigFileParser {
    public record ConfigEntry(String key, String value, String profile, String sourceFile) {}
    public record Result(List<ConfigEntry> entries, List<String> issues) {}
    public static Result parseModuleConfig(Path repoRoot, Path moduleDir)
}
```
Scans `moduleDir/src/main/resources` for `application*.yml|yaml|properties` and `bootstrap*.yml|yaml|properties` (non-recursive). Profile determination: filename suffix (`application-prod.yml` → `"prod"`); YAML multi-document `---` sections with `spring.config.activate.on-profile` or legacy `spring.profiles` → that profile (the activation key itself is not emitted as an entry); otherwise `null` (default). Nested maps flatten with dots; list items flatten as `key[0]`, `key[1]`; scalars via `String.valueOf`. Unparseable files append to `issues` (`"relPath: message"`), never throw. `sourceFile` = repo-relative path.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigFileParserTest {
    @TempDir Path repo;

    private Path resources() throws Exception {
        Path dir = repo.resolve("src/main/resources");
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void flattensYamlWithProfilesFilenameAndDocumentBased() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("application.yml"), """
                spring:
                  application:
                    name: order-service
                server:
                  servlet:
                    context-path: /orders
                billing:
                  base-url: http://billing:8080
                  retries: 3
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                billing:
                  base-url: https://billing.prod
                """);
        Files.writeString(res.resolve("application-dev.yml"), "billing:\n  base-url: http://localhost\n");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);

        assertThat(r.issues()).isEmpty();
        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("spring.application.name");
            assertThat(e.value()).isEqualTo("order-service");
            assertThat(e.profile()).isNull();
            assertThat(e.sourceFile()).isEqualTo("src/main/resources/application.yml");
        });
        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("billing.base-url");
            assertThat(e.value()).isEqualTo("https://billing.prod");
            assertThat(e.profile()).isEqualTo("prod");
        });
        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("billing.base-url");
            assertThat(e.profile()).isEqualTo("dev");
        });
        assertThat(r.entries()).anySatisfy(e ->
                assertThat(e.key()).isEqualTo("billing.retries"));
        assertThat(r.entries()).noneSatisfy(e ->
                assertThat(e.key()).isEqualTo("spring.config.activate.on-profile"));
    }

    @Test
    void parsesPropertiesFilesAndLists() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("application.properties"), "kafka.topic=orders.v1\n");
        Files.writeString(res.resolve("bootstrap.yml"), "servers:\n  - a\n  - b\n");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);

        assertThat(r.entries()).anySatisfy(e -> {
            assertThat(e.key()).isEqualTo("kafka.topic");
            assertThat(e.value()).isEqualTo("orders.v1");
        });
        assertThat(r.entries()).anySatisfy(e -> assertThat(e.key()).isEqualTo("servers[0]"));
        assertThat(r.entries()).anySatisfy(e -> assertThat(e.key()).isEqualTo("servers[1]"));
    }

    @Test
    void unparseableYamlBecomesIssueNotException() throws Exception {
        Path res = resources();
        Files.writeString(res.resolve("application.yml"), "key: [unclosed\n  broken");

        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);

        assertThat(r.entries()).isEmpty();
        assertThat(r.issues()).hasSize(1);
        assertThat(r.issues().get(0)).contains("application.yml");
    }

    @Test
    void missingResourcesDirYieldsEmptyResult() {
        ConfigFileParser.Result r = ConfigFileParser.parseModuleConfig(repo, repo);
        assertThat(r.entries()).isEmpty();
        assertThat(r.issues()).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.*'`
Expected: FAIL — class doesn't exist. (First add `implementation(libs.snakeyaml)` to `sdd-index/build.gradle.kts` and confirm the catalog has the `snakeyaml` alias — it does, from Phase 1.)

- [ ] **Step 3: Implement**

```java
package sdd.index.spring;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ConfigFileParser {
    public record ConfigEntry(String key, String value, String profile, String sourceFile) {}
    public record Result(List<ConfigEntry> entries, List<String> issues) {}

    private static final Pattern CONFIG_FILE = Pattern.compile(
            "(application|bootstrap)(?:-([A-Za-z0-9_]+))?\\.(yml|yaml|properties)");

    private ConfigFileParser() {}

    public static Result parseModuleConfig(Path repoRoot, Path moduleDir) {
        Path resources = moduleDir.resolve("src/main/resources");
        List<ConfigEntry> entries = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        if (!Files.isDirectory(resources)) {
            return new Result(List.of(), List.of());
        }
        try (Stream<Path> files = Files.list(resources)) {
            files.sorted().forEach(file -> {
                Matcher m = CONFIG_FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    return;
                }
                String filenameProfile = m.group(2);
                String rel = repoRoot.relativize(file).toString().replace('\\', '/');
                try {
                    String content = Files.readString(file);
                    if (m.group(3).equals("properties")) {
                        parseProperties(content, filenameProfile, rel, entries);
                    } else {
                        parseYaml(content, filenameProfile, rel, entries);
                    }
                } catch (Exception e) {
                    issues.add(rel + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            issues.add("src/main/resources: " + e.getMessage());
        }
        return new Result(List.copyOf(entries), List.copyOf(issues));
    }

    private static void parseProperties(String content, String profile, String rel,
                                        List<ConfigEntry> out) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(content));
        props.stringPropertyNames().stream().sorted().forEach(key ->
                out.add(new ConfigEntry(key, props.getProperty(key), profile, rel)));
    }

    private static void parseYaml(String content, String filenameProfile, String rel,
                                  List<ConfigEntry> out) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        for (Object doc : yaml.loadAll(content)) {
            if (!(doc instanceof Map<?, ?> map)) {
                continue;
            }
            List<ConfigEntry> docEntries = new ArrayList<>();
            flatten("", map, docEntries, rel);
            String docProfile = filenameProfile;
            List<ConfigEntry> kept = new ArrayList<>();
            for (ConfigEntry e : docEntries) {
                if (e.key().equals("spring.config.activate.on-profile")
                        || e.key().equals("spring.profiles")) {
                    docProfile = e.value();
                } else {
                    kept.add(e);
                }
            }
            for (ConfigEntry e : kept) {
                out.add(new ConfigEntry(e.key(), e.value(), docProfile, rel));
            }
        }
    }

    private static void flatten(String prefix, Map<?, ?> map, List<ConfigEntry> out, String rel) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey())
                    : prefix + "." + e.getKey();
            flattenValue(key, e.getValue(), out, rel);
        }
    }

    private static void flattenValue(String key, Object value, List<ConfigEntry> out, String rel) {
        if (value instanceof Map<?, ?> nested) {
            flatten(key, nested, out, rel);
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flattenValue(key + "[" + i + "]", list.get(i), out, rel);
            }
        } else {
            out.add(new ConfigEntry(key, String.valueOf(value), null, rel));
        }
    }
}
```
(Note the profile threading: `flatten` emits `profile = null`; `parseYaml` rewrites each kept entry with the document profile. Properties files carry only the filename profile.)

- [ ] **Step 4: Run to verify green**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-index
git commit -m "feat: spring config file parser with profile-aware flattening"
```

---

### Task 5: SpringConfigPersistence + module identity columns

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/spring/SpringConfigPersistence.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/SpringConfigPersistenceTest.java`

**Interfaces:**
- Consumes: `Database`, `ConfigFileParser.ConfigEntry`, existing `config_property` table and `module.spring_app_name`/`module.context_path` columns (in V1 since Phase 1).
- Produces:
```java
public final class SpringConfigPersistence {
    public static void persistModuleConfig(org.jdbi.v3.core.Handle h, long moduleId,
                                           List<ConfigFileParser.ConfigEntry> entries)
    public static Map<String, String> defaultProfileProps(List<ConfigFileParser.ConfigEntry> entries)
}
```
`persistModuleConfig` — delete module's `config_property` rows, insert entries, then UPDATE the module row: `spring_app_name` = default-profile `spring.application.name` (null if absent), `context_path` = default-profile `server.servlet.context-path` (null if absent). Handle-based (joins the repo transaction; no Jdbi overload needed — the only production caller is Task 6's integration).
`defaultProfileProps` — pure helper: `profile == null` entries → map (last-wins on duplicates), used by the ladder (Task 7) and Task 6.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringConfigPersistenceTest {
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

    private static ConfigFileParser.ConfigEntry entry(String key, String value, String profile) {
        return new ConfigFileParser.ConfigEntry(key, value, profile, "src/main/resources/application.yml");
    }

    @Test
    void persistsEntriesAndModuleIdentity() {
        db.jdbi().useHandle(h -> SpringConfigPersistence.persistModuleConfig(h, moduleId, List.of(
                entry("spring.application.name", "order-service", null),
                entry("server.servlet.context-path", "/orders", null),
                entry("billing.base-url", "http://billing", null),
                entry("billing.base-url", "https://prod", "prod"))));

        Integer count = db.jdbi().withHandle(h ->
                h.createQuery("SELECT count(*) FROM config_property").mapTo(Integer.class).one());
        assertThat(count).isEqualTo(4);
        Map<String, Object> module = db.jdbi().withHandle(h ->
                h.createQuery("SELECT spring_app_name, context_path FROM module").mapToMap().one());
        assertThat(module.get("spring_app_name")).isEqualTo("order-service");
        assertThat(module.get("context_path")).isEqualTo("/orders");
    }

    @Test
    void repersistReplacesRowsAndProfileOnlyNameDoesNotSetIdentity() {
        db.jdbi().useHandle(h -> {
            SpringConfigPersistence.persistModuleConfig(h, moduleId, List.of(
                    entry("spring.application.name", "old-name", null)));
            SpringConfigPersistence.persistModuleConfig(h, moduleId, List.of(
                    entry("spring.application.name", "prod-only", "prod")));
        });
        Integer count = db.jdbi().withHandle(h ->
                h.createQuery("SELECT count(*) FROM config_property").mapTo(Integer.class).one());
        assertThat(count).isEqualTo(1);
        Map<String, Object> module = db.jdbi().withHandle(h ->
                h.createQuery("SELECT spring_app_name FROM module").mapToMap().one());
        assertThat(module.get("spring_app_name")).isNull();
    }

    @Test
    void defaultProfilePropsFiltersAndLastWins() {
        Map<String, String> props = SpringConfigPersistence.defaultProfileProps(List.of(
                entry("a", "1", null), entry("a", "2", null), entry("b", "x", "prod")));
        assertThat(props).containsEntry("a", "2").doesNotContainKey("b");
    }
}
```

- [ ] **Step 2: Run to verify it fails, implement, verify green**

Run (fail): `./gradlew :sdd-index:test --tests 'sdd.index.spring.SpringConfigPersistenceTest'`

```java
package sdd.index.spring;

import org.jdbi.v3.core.Handle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpringConfigPersistence {
    private SpringConfigPersistence() {}

    public static void persistModuleConfig(Handle h, long moduleId,
                                           List<ConfigFileParser.ConfigEntry> entries) {
        h.createUpdate("DELETE FROM config_property WHERE module_id=:m").bind("m", moduleId).execute();
        for (ConfigFileParser.ConfigEntry e : entries) {
            h.createUpdate("INSERT INTO config_property(module_id, key, value, profile, source_file) "
                            + "VALUES (:m, :k, :v, :p, :f)")
                    .bind("m", moduleId).bind("k", e.key()).bind("v", e.value())
                    .bind("p", e.profile()).bind("f", e.sourceFile()).execute();
        }
        Map<String, String> defaults = defaultProfileProps(entries);
        h.createUpdate("UPDATE module SET spring_app_name=:app, context_path=:ctx WHERE id=:m")
                .bind("app", defaults.get("spring.application.name"))
                .bind("ctx", defaults.get("server.servlet.context-path"))
                .bind("m", moduleId).execute();
    }

    public static Map<String, String> defaultProfileProps(List<ConfigFileParser.ConfigEntry> entries) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ConfigFileParser.ConfigEntry e : entries) {
            if (e.profile() == null) {
                out.put(e.key(), e.value());
            }
        }
        return out;
    }
}
```

Run (pass): same command. Expected: 3/3.

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "feat: config property persistence with module spring identity"
```

---

### Task 6: Wire config extraction into SourceExtraction (+ markStale append fix)

**Files:**
- Modify: `sdd-index/src/main/java/sdd/index/source/SourceExtraction.java`
- Modify: `sdd-index/src/main/java/sdd/index/store/IndexPersistence.java` (markStale appends instead of overwriting)
- Test: extend `sdd-index/src/test/java/sdd/index/SourceEndToEndTest.java` and `sdd-index/src/test/java/sdd/index/store/IndexPersistenceTest.java`

**Interfaces:**
- Consumes: Tasks 4–5.
- Produces: `SourceExtraction.extractRepo` additionally, per module INSIDE the existing repo-atomic transaction: `ConfigFileParser.parseModuleConfig(repoPath, projectDir)` (canonicalized paths already available) → `SpringConfigPersistence.persistModuleConfig(h, moduleId, entries)`; config issues count into the same DEGRADED/issue tally as parse issues. `IndexPersistence.markStale` appends to `repo.error` (separator-safe like `updateParseStatus`) instead of overwriting.

- [ ] **Step 1: Write the failing tests**

Extend `SourceEndToEndTest.fullPipelinePopulatesTypesUsagesFileRefsAndFts` — add an `application.yml` to the svc-orders fixture:
```java
                .file("src/main/resources/application.yml", """
                        spring:
                          application:
                            name: order-service
                        server:
                          servlet:
                            context-path: /orders
                        """)
```
and assertions after the existing ones:
```java
            Map<String, Object> module = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT m.spring_app_name, m.context_path FROM module m
                            JOIN repo r ON r.id = m.repo_id WHERE r.name='svc-orders'""")
                    .mapToMap().one());
            assertThat(module.get("spring_app_name")).isEqualTo("order-service");
            assertThat(module.get("context_path")).isEqualTo("/orders");
            Integer propCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM config_property").mapTo(Integer.class).one());
            assertThat(propCount).isEqualTo(2);
```

Add to `IndexPersistenceTest`:
```java
    @Test
    void markStaleAppendsToExistingErrorInsteadOfOverwriting() {
        try (Database db = Database.open(ws)) {
            RepoScan scan = new RepoScan("svc-orders", Path.of("/w/svc-orders"), "a".repeat(40), "main", "");
            IndexPersistence.persistRepo(db.jdbi(), scan, serviceExtract(), "OK", null);
            db.jdbi().useHandle(h -> h.execute("UPDATE repo SET error='prior note' WHERE name='svc-orders'"));
            IndexPersistence.markStale(db.jdbi(), "svc-orders", "network down");
            String error = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT error FROM repo WHERE name='svc-orders'").mapTo(String.class).one());
            assertThat(error).contains("prior note").contains("network down");
        }
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.SourceEndToEndTest' --tests 'sdd.index.store.IndexPersistenceTest'`
Expected: the new assertions/tests FAIL.

- [ ] **Step 3: Implement**

In `SourceExtraction.extractRepo`:
- During the module-work pass (where sessions are built), also run `ConfigFileParser.Result config = ConfigFileParser.parseModuleConfig(repoRoot, projectDir);` (use the SAME canonicalized paths as parseModule), carry it on `ModuleWork`, and add `config.issues().size()` to the issue tally.
- Inside the repo transaction loop, after `persistModuleSource(...)`: `SpringConfigPersistence.persistModuleConfig(h, w.moduleId(), w.config().entries());`

In `IndexPersistence.markStale`, replace the overwrite with the same separator-safe append pattern `updateParseStatus` uses (read `SourcePersistence.updateParseStatus` and mirror its SQL CASE expression against `repo.error`).

- [ ] **Step 4: Run to verify green, full build**

Run: `./gradlew :sdd-index:test` then `./gradlew build`
Expected: all green (including gradle-it ITs).

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: wire config extraction into the repo-atomic source transaction"
```

---

### Task 7: RouteNormalizer

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/spring/RouteNormalizer.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/RouteNormalizerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces (2B-2b's endpoint/client extractors consume):
```java
public final class RouteNormalizer {
    public static String join(String basePath, String methodPath)   // null-safe, slash-normalized
    public static String normalize(String template)                 // {var}→{}, collapse //, trim trailing /, ensure leading /
}
```

- [ ] **Step 1: Write the failing table-driven tests**

```java
package sdd.index.spring;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RouteNormalizerTest {
    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "/api,      /orders,        /api/orders",
            "/api/,     orders,         /api/orders",
            "NULL,      /orders,        /orders",
            "/api,      NULL,           /api",
            "NULL,      NULL,           /",
            "'',        orders/,        /orders",
    })
    void joins(String base, String method, String expected) {
        assertThat(RouteNormalizer.join(base, method)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "/orders/{id},              /orders/{}",
            "/orders/{orderId}/items,   /orders/{}/items",
            "orders//items/,            /orders/items",
            "/,                         /",
    })
    void normalizes(String input, String expected) {
        assertThat(RouteNormalizer.normalize(input)).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Run (fail), implement, run (pass)**

```java
package sdd.index.spring;

public final class RouteNormalizer {
    private RouteNormalizer() {}

    public static String join(String basePath, String methodPath) {
        String base = strip(basePath);
        String method = strip(methodPath);
        String joined = base + (method.isEmpty() ? "" : "/" + method);
        return joined.isEmpty() ? "/" : "/" + joined;
    }

    public static String normalize(String template) {
        String collapsed = ("/" + template).replaceAll("\\{[^}]*}", "{}")
                .replaceAll("/{2,}", "/");
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
```

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.RouteNormalizerTest'` — PASS.

- [ ] **Step 3: Commit**

```bash
git add sdd-index/src
git commit -m "feat: route template normalizer"
```

---

### Task 8: ValueResolver — the resolution ladder

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/spring/ValueResolver.java`
- Test: `sdd-index/src/test/java/sdd/index/spring/ValueResolverTest.java`

**Interfaces:**
- Consumes: JavaParser expressions (from a symbol-solver-enabled parse), `SpringConfigPersistence.defaultProfileProps` output shape (`Map<String,String>`).
- Produces (2B-2b's client/Kafka extractors consume):
```java
public final class ValueResolver {
    public enum Resolution { LITERAL, CONSTANT, PROPERTY, DYNAMIC }
    public record Resolved(String value, Resolution resolution, String rawExpr) {}
    public static Resolved resolve(com.github.javaparser.ast.expr.Expression expr,
                                   Map<String, String> defaultProfileProps)
}
```
Ladder, first match wins:
1. String literal → `LITERAL` (but if the text contains `${...}`, continue to placeholder substitution — result `PROPERTY` if every placeholder resolves from props or an inline `:default`, else `DYNAMIC`).
2. Name/field-access resolving (symbol solver) to a `static final` String with a literal initializer → treat that literal as in rule 1, resolution `CONSTANT` (or `PROPERTY` if it contained placeholders that resolved).
3. Binary `+` of resolvable parts → concatenation; resolution = strongest of parts (any PROPERTY part → PROPERTY, else any CONSTANT → CONSTANT, else LITERAL); any unresolvable part → `DYNAMIC`.
4. Anything else → `DYNAMIC` with `value = null`.
`rawExpr` = `expr.toString()` always.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.spring;

import com.github.javaparser.ast.expr.Expression;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.source.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValueResolverTest {
    @TempDir Path repo;

    private static final Map<String, String> PROPS = Map.of(
            "billing.base-url", "http://billing:8080",
            "kafka.topic", "orders.v1");

    /** Parses a class whose field initializers are the expressions under test. */
    private List<Expression> parseInitializers(String fieldsSource) throws Exception {
        Path f = repo.resolve("src/main/java/com/acme/T.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, """
                package com.acme;
                public class T {
                    static final String BASE = "/api";
                    static final String COMPOSED = BASE + "/v2";
                %s
                }
                """.formatted(fieldsSource));
        var session = SourceParser.parseModule(repo, repo, List.of());
        var cu = session.units().get(0).cu();
        return cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .filter(fd -> fd.getVariable(0).getNameAsString().startsWith("X"))
                .map(fd -> fd.getVariable(0).getInitializer().orElseThrow())
                .toList();
    }

    @Test
    void ladderRungs() throws Exception {
        List<Expression> exprs = parseInitializers("""
                    Object X1 = "/orders";
                    Object X2 = BASE;
                    Object X3 = BASE + "/orders";
                    Object X4 = "${billing.base-url}/pay";
                    Object X5 = "${missing.key}/x";
                    Object X6 = "${missing.key:fallback}/x";
                    Object X7 = System.getenv("URL");
                    Object X8 = COMPOSED;
                """);

        assertThat(ValueResolver.resolve(exprs.get(0), PROPS))
                .isEqualTo(new ValueResolver.Resolved("/orders", ValueResolver.Resolution.LITERAL, "\"/orders\""));
        var x2 = ValueResolver.resolve(exprs.get(1), PROPS);
        assertThat(x2.value()).isEqualTo("/api");
        assertThat(x2.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
        var x3 = ValueResolver.resolve(exprs.get(2), PROPS);
        assertThat(x3.value()).isEqualTo("/api/orders");
        assertThat(x3.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
        var x4 = ValueResolver.resolve(exprs.get(3), PROPS);
        assertThat(x4.value()).isEqualTo("http://billing:8080/pay");
        assertThat(x4.resolution()).isEqualTo(ValueResolver.Resolution.PROPERTY);
        var x5 = ValueResolver.resolve(exprs.get(4), PROPS);
        assertThat(x5.value()).isNull();
        assertThat(x5.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
        var x6 = ValueResolver.resolve(exprs.get(5), PROPS);
        assertThat(x6.value()).isEqualTo("fallback/x");
        assertThat(x6.resolution()).isEqualTo(ValueResolver.Resolution.PROPERTY);
        var x7 = ValueResolver.resolve(exprs.get(6), PROPS);
        assertThat(x7.value()).isNull();
        assertThat(x7.resolution()).isEqualTo(ValueResolver.Resolution.DYNAMIC);
        assertThat(x7.rawExpr()).contains("System.getenv");
        var x8 = ValueResolver.resolve(exprs.get(7), PROPS);
        assertThat(x8.value()).isEqualTo("/api/v2");
        assertThat(x8.resolution()).isEqualTo(ValueResolver.Resolution.CONSTANT);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.ValueResolverTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package sdd.index.spring;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ValueResolver {
    public enum Resolution { LITERAL, CONSTANT, PROPERTY, DYNAMIC }
    public record Resolved(String value, Resolution resolution, String rawExpr) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private ValueResolver() {}

    public static Resolved resolve(Expression expr, Map<String, String> defaultProfileProps) {
        String raw = expr.toString();
        Optional<Part> part = resolvePart(expr);
        if (part.isEmpty()) {
            return new Resolved(null, Resolution.DYNAMIC, raw);
        }
        return substitute(part.get(), defaultProfileProps, raw);
    }

    private record Part(String text, Resolution origin) {}

    private static Optional<Part> resolvePart(Expression expr) {
        if (expr instanceof StringLiteralExpr lit) {
            return Optional.of(new Part(lit.asString(), Resolution.LITERAL));
        }
        if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
            Optional<Part> left = resolvePart(bin.getLeft());
            Optional<Part> right = resolvePart(bin.getRight());
            if (left.isPresent() && right.isPresent()) {
                Resolution origin = left.get().origin() == Resolution.CONSTANT
                        || right.get().origin() == Resolution.CONSTANT
                        ? Resolution.CONSTANT : Resolution.LITERAL;
                return Optional.of(new Part(left.get().text() + right.get().text(), origin));
            }
            return Optional.empty();
        }
        if (expr instanceof NameExpr || expr instanceof FieldAccessExpr) {
            try {
                var resolved = expr instanceof NameExpr n ? n.resolve() : ((FieldAccessExpr) expr).resolve();
                if (resolved instanceof com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration
                        || resolved instanceof com.github.javaparser.resolution.declarations.ResolvedValueDeclaration) {
                    var node = resolved.toAst().orElse(null);
                    if (node instanceof VariableDeclarator v && v.getInitializer().isPresent()) {
                        return resolvePart(v.getInitializer().get())
                                .map(p -> new Part(p.text(), Resolution.CONSTANT));
                    }
                }
            } catch (Exception | StackOverflowError ignored) {
                // fall through to empty
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Resolved substitute(Part part, Map<String, String> props, String raw) {
        Matcher m = PLACEHOLDER.matcher(part.text());
        if (!m.find()) {
            return new Resolved(part.text(), part.origin(), raw);
        }
        m.reset();
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String fallback = m.group(2);
            String value = props.getOrDefault(key, fallback);
            if (value == null) {
                return new Resolved(null, Resolution.DYNAMIC, raw);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return new Resolved(out.toString(), Resolution.PROPERTY, raw);
    }
}
```
(Note: `resolved.toAst()` API name may differ on JavaParser 3.26.2 — e.g. `toAst()` on `ResolvedFieldDeclaration` returns `Optional<Node>`; if the exact accessor differs, find the working equivalent via the jar's javadoc/javap, fix minimally, and document in your report. The test is the arbiter.)

- [ ] **Step 4: Run to verify green + full build**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.spring.*'` then `./gradlew build`
Expected: PASS everywhere.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: literal-constant-property-dynamic resolution ladder"
```

---

## Self-Review (completed at write time)

1. **Spec coverage (2B-2a scope):** config extraction with profile flattening + `spring_app_name`/`context_path` → Tasks 4–6; resolution ladder exactly per spec's four-rung design → Task 8; route normalization (spec: "normalize template (`/orders/{id}` → segments, vars wildcarded)") → Task 7; carry-forwards: JarTypeSolver sharing + jreOnly → Task 1, ReferenceExtractor widening + nested-class fqcn test + e2e fixture realism → Task 2, field-Lombok synthesis → Task 3, markStale append → Task 6. Deliberately deferred to 2B-2b: REST endpoint/client extractors, Kafka extractor, `module.kafka_status` column, context-path prepending at endpoint-persistence time; to 2C: client↔endpoint matching, curation report, repo cards; not planned: perf measurement (needs the real estate — run `sdd index` against it and record numbers before 2B-2b if possible).
2. **Placeholder scan:** all steps carry complete code; the two flagged API-uncertainty contingencies (Paths2 helper name, `toAst()` accessor) specify the exact fallback procedure.
3. **Type consistency:** `ConfigFileParser.ConfigEntry(key, value, profile, sourceFile)` used identically in Tasks 4, 5, 6; `Result(entries, issues)` in 4, 6; `SpringConfigPersistence.persistModuleConfig(Handle, long, List<ConfigEntry>)` + `defaultProfileProps(List<ConfigEntry>)` in 5, 6, 8; `ValueResolver.Resolved(value, resolution, rawExpr)` self-consistent; `JarSolverCache.get(Path)` → `Optional<TypeSolver>` in 1; refKind vocabulary `IMPORT|EXTENDS|CALL|TYPE` in Task 2 only (no other task references it).

---

## Execution outcome (2026-08-11)

All 8 tasks complete; final whole-branch review found 1 Critical — the plan's own Task-1 design (shared JarTypeSolver instances) is architecturally unsupported on JavaParser 3.26.2 (`setParent` throws on reuse; every multi-module repo with shared jars would FAIL source extraction) — masked by a green build because no test exercised the shared-cache path. Fixed as a failure-only cache with red-first regression coverage. Implementers also fixed two plan bugs en route (RouteNormalizer join() double-slash; ValueResolver toAst() shape) and reviews forced guards on the reference-extractor literal fallback (package-shape) and constant folding (static-final gate, mutation-verified).

**Carry into 2B-2b:**
1. Consider a repo-level shared CombinedTypeSolver (one per repo: all source roots + jar union) — restores the memory/perf win the failure-only cache gave up AND fixes the JarFile handle multiplication + O(modules×jars) entry re-reads. Needs its own validation (getRoot semantics).
2. ValueResolver: replace SOE-based cycle safety with a depth budget/visited set; callers must never pass null to RouteNormalizer.normalize.
3. ConfigFileParser: .properties activation-key handling; spring.profiles list form; YAML "null"-string substitution is the failure shape to watch in ladder consumers; defaultProfileProps precedence is alphabetical (bootstrap beats application) — align with real Spring precedence when the first consumer lands.
4. LombokShim: static-field accessors not synthesized (silently incomplete, confidence stays OK).
5. Measure real-estate indexing time/memory (TYPE-widening resolve cost + per-module jar solver construction are unmeasured) before building 2B-2b extractors on top.
6. Error-append dedup (repeated identical failure messages grow repo.error unboundedly).
