# Phase 2B-1 — Java API Surface Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `sdd index` learns to read Java: per-module API surfaces (types + members, Lombok-aware, signature-hashed), cross-repo type usage (`api_usage`), intra-repo file references (`file_ref`), and FTS symbol population — with per-repo `parse_status` degradation.

**Architecture:** New package `sdd.index.source` in `sdd-index`: `SourceParser` (JavaParser + symbol solver backed by the resolved classpath jars the Gradle extract already carries) → `ApiSurfaceExtractor` (+`LombokShim`) → `ReferenceExtractor` → `SourcePersistence` → global `UsageLinker` pass. `IndexService` runs source extraction after each repo's Gradle persist and `UsageLinker` after `ArtifactLinker`. Deterministic — no model calls (repo cards are 2C).

**Tech Stack:** Existing stack + `com.github.javaparser:javaparser-symbol-solver-core:3.26.2`.

**Spec:** `docs/superpowers/specs/2026-08-10-sdd-pipeline-design.md` → Component 1, source-extraction bullets. Carry-forwards honored from `docs/superpowers/plans/2026-08-11-phase2a-scan-gradle-graph.md` execution outcome: #1 (classpath from extractor output, NOT dep_edge), #2 (composite-build IT — Task 1 here). Plan 2B-2 (Spring config/REST/Kafka + resolution ladder) and 2C (link passes, repo cards, curation) follow.

## Global Constraints

- Java 21; never push; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` after a blank line.
- Deterministic-first: NO model calls anywhere in this plan; only `@Tag("gradle-it")` tests may run real Gradle.
- `FtsSymbolWriter` is the SOLE write path for `fts_symbol` (Phase-2 entry criterion — already enforced).
- `dep_edge` stays declared-only; classpath jars come from `GradleModel.ResolvedDep.files` (2A contract).
- Parse status values exactly `OK | DEGRADED | FAILED` in `repo.parse_status`; source-extraction failure NEVER sinks the run (statuses degrade per repo).
- API-surface rule (spec): public/protected types; `isApi` only for LIBRARY modules, excluding FQCNs containing `.internal.` (config knob deliberately deferred to 2B-2 — constant in code, documented).
- Lombok is simulated, never executed: member synthesis for member-generating annotations; explicit ignore-list for non-generating ones; unknown `lombok.*` annotations → `api_confidence = PARTIAL` (never for non-Lombok unknowns — flood guard).

---

### Task 1: JavaParser wiring + composite-build IT (2A carry-forward)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `sdd-index/build.gradle.kts`
- Test: `sdd-index/src/test/java/sdd/index/gradle/CompositeBuildIT.java` (`@Tag("gradle-it")`)

**Interfaces:**
- Consumes: `FixtureGradleRepo`, `GradleExtractor`, `IndexPersistence`, `ArtifactLinker`, `WorkspaceScanner`.
- Produces: catalog alias `libs.javaparser.symbol.solver`; empirical proof that a real `includeBuild` flows extractor → persistence → linker as `COMPOSITE`.

- [ ] **Step 1: Wiring**

`gradle/libs.versions.toml` — `[versions]`: `javaparser = "3.26.2"`; `[libraries]`:
```toml
javaparser-symbol-solver = { module = "com.github.javaparser:javaparser-symbol-solver-core", version.ref = "javaparser" }
```
`sdd-index/build.gradle.kts` dependencies: add `implementation(libs.javaparser.symbol.solver)`.
Run: `./gradlew :sdd-index:build --quiet` — green.

- [ ] **Step 2: Write the failing IT**

```java
package sdd.index.gradle;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.index.scan.WorkspaceScanner;
import sdd.index.store.ArtifactLinker;
import sdd.index.store.IndexPersistence;
import sdd.index.testing.FixtureGradleRepo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("gradle-it")
class CompositeBuildIT {
    @TempDir Path ws;

    @Test
    void realIncludeBuildFlowsThroughExtractorPersistenceAndLinkerAsComposite() {
        FixtureGradleRepo.in(ws, "lib-x", "8.10.2")
                .withSettings("rootProject.name = 'lib-x'\n")
                .withBuildFile("""
                        plugins { id 'java-library'; id 'maven-publish' }
                        group = 'com.acme'
                        version = '1.0.0'
                        publishing { publications { maven(MavenPublication) { from components.java } } }
                        """)
                .withFile("src/main/java/X.java", "public class X {}\n")
                .withFile(".gitignore", ".gradle/\nbuild/\n")
                .commit();
        FixtureGradleRepo.in(ws, "svc-y", "8.10.2")
                .withSettings("""
                        rootProject.name = 'svc-y'
                        includeBuild('../lib-x')
                        """)
                .withBuildFile("""
                        plugins { id 'java' }
                        group = 'com.acme'
                        repositories { mavenCentral() }
                        dependencies { implementation 'com.acme:lib-x:1.0.0' }
                        """)
                .withFile("src/main/java/Y.java", "public class Y {}\n")
                .withFile(".gitignore", ".gradle/\nbuild/\n")
                .commit();

        GradleModel.Extract svcExtract = new GradleExtractor(Map.of()).extract(ws.resolve("svc-y"));
        assertThat(svcExtract.includedBuilds())
                .anySatisfy(p -> assertThat(p.toString()).endsWith("lib-x"));

        GradleModel.Extract libExtract = new GradleExtractor(Map.of()).extract(ws.resolve("lib-x"));
        try (Database db = Database.open(ws)) {
            var scans = WorkspaceScanner.scan(ws, List.of());
            IndexPersistence.persistRepo(db.jdbi(), scans.get(0), libExtract, "OK", null);
            IndexPersistence.persistRepo(db.jdbi(), scans.get(1), svcExtract, "OK", null);
            ArtifactLinker.link(db.jdbi(), Map.of());
            String mode = db.jdbi().withHandle(h -> h.createQuery(
                            "SELECT mode FROM dep_edge WHERE to_name='lib-x' AND is_internal=1")
                    .mapTo(String.class).one());
            assertThat(mode).isEqualTo("COMPOSITE");
        }
    }
}
```

- [ ] **Step 3: Run to verify current state**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.gradle.CompositeBuildIT'`
Expected: compiles and RUNS — this is a characterization IT of existing 2A behavior; if it FAILS, the failure is a real 2A composite-path bug (the `projectDir.absolutePath`-vs-scan-path equality the 2A review warned about). Investigate and fix minimally in the linker LIKE pattern or extractor path emission — document whatever you find in your report. If it PASSES immediately, that is the expected outcome.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml sdd-index
git commit -m "feat: javaparser dependency and composite-build integration test"
```

---

### Task 2: SourceParser — best-effort parsing with a resolved-classpath symbol solver

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/source/SourceParser.java`
- Test: `sdd-index/src/test/java/sdd/index/source/SourceParserTest.java`

**Interfaces:**
- Consumes: JavaParser, `GradleModel.ResolvedDep.files` (callers pass the jar list).
- Produces:
```java
public final class SourceParser {
    public record ParsedUnit(java.nio.file.Path file, String relPath,
                             com.github.javaparser.ast.CompilationUnit cu) {}
    public record Session(List<ParsedUnit> units, List<String> issues) {}   // issue = "relPath: message"
    public static Session parseModule(java.nio.file.Path repoRoot,
                                      java.nio.file.Path moduleDir,
                                      List<java.nio.file.Path> classpathJars)
}
```
Source roots: `moduleDir/src/main/java` (absent → empty session) plus `moduleDir/build/generated` when present (any `.java` beneath). Per-file parse failures append to `issues`, never throw. Symbol solver: `CombinedTypeSolver` = `ReflectionTypeSolver(false)` + `JavaParserTypeSolver` per source root + `JarTypeSolver` per jar (unreadable jars skipped silently). `relPath` = `repoRoot.relativize(file)` with `/` separators.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceParserTest {
    @TempDir Path repo;

    private Path module() throws Exception {
        Path src = repo.resolve("src/main/java/com/acme");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Ok.java"), """
                package com.acme;
                public class Ok { public int add(int a, int b) { return a + b; } }
                """);
        Files.writeString(src.resolve("Broken.java"), "public class {{{ nope");
        return repo;
    }

    @Test
    void parsesGoodFilesAndRecordsIssuesForBadOnes() throws Exception {
        SourceParser.Session s = SourceParser.parseModule(repo, module(), List.of());
        assertThat(s.units()).hasSize(1);
        assertThat(s.units().get(0).relPath()).isEqualTo("src/main/java/com/acme/Ok.java");
        assertThat(s.units().get(0).cu().getPrimaryTypeName()).contains("Ok");
        assertThat(s.issues()).hasSize(1);
        assertThat(s.issues().get(0)).contains("Broken.java");
    }

    @Test
    void missingSourceRootYieldsEmptySession() {
        SourceParser.Session s = SourceParser.parseModule(repo, repo, List.of());
        assertThat(s.units()).isEmpty();
        assertThat(s.issues()).isEmpty();
    }

    @Test
    void symbolSolverResolvesAcrossFilesInSameRoot() throws Exception {
        Path src = repo.resolve("src/main/java/com/acme");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"),
                "package com.acme;\npublic class A { public B makeB() { return new B(); } }\n");
        Files.writeString(src.resolve("B.java"), "package com.acme;\npublic class B {}\n");
        SourceParser.Session s = SourceParser.parseModule(repo, repo, List.of());
        var aUnit = s.units().stream().filter(u -> u.relPath().endsWith("A.java")).findFirst().orElseThrow();
        var method = aUnit.cu().findAll(com.github.javaparser.ast.body.MethodDeclaration.class).get(0);
        String resolved = method.getType().resolve().describe();
        assertThat(resolved).isEqualTo("com.acme.B");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.*'`
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement**

```java
package sdd.index.source;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class SourceParser {
    public record ParsedUnit(Path file, String relPath, CompilationUnit cu) {}
    public record Session(List<ParsedUnit> units, List<String> issues) {}

    private SourceParser() {}

    public static Session parseModule(Path repoRoot, Path moduleDir, List<Path> classpathJars) {
        List<Path> roots = new ArrayList<>();
        Path main = moduleDir.resolve("src/main/java");
        if (Files.isDirectory(main)) {
            roots.add(main);
        }
        Path generated = moduleDir.resolve("build/generated");
        if (Files.isDirectory(generated)) {
            roots.add(generated);
        }
        if (roots.isEmpty()) {
            return new Session(List.of(), List.of());
        }

        CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        for (Path root : roots) {
            solver.add(new JavaParserTypeSolver(root));
        }
        for (Path jar : classpathJars) {
            try {
                solver.add(new JarTypeSolver(jar));
            } catch (Exception ignored) {
                // unreadable/missing jar — best-effort resolution without it
            }
        }
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(solver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);
        JavaParser parser = new JavaParser(config);

        List<ParsedUnit> units = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(f -> {
                    String rel = repoRoot.relativize(f).toString().replace('\\', '/');
                    try {
                        var result = parser.parse(f);
                        if (result.isSuccessful() && result.getResult().isPresent()) {
                            units.add(new ParsedUnit(f, rel, result.getResult().get()));
                        } else {
                            issues.add(rel + ": " + result.getProblems());
                        }
                    } catch (Exception e) {
                        issues.add(rel + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Session(List.copyOf(units), List.copyOf(issues));
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.*'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: best-effort source parser with resolved-classpath symbol solver"
```

---

### Task 3: SourceModel + ApiSurfaceExtractor (types, members, signature hashes)

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/source/SourceModel.java`
- Create: `sdd-index/src/main/java/sdd/index/source/ApiSurfaceExtractor.java`
- Test: `sdd-index/src/test/java/sdd/index/source/ApiSurfaceExtractorTest.java`

**Interfaces:**
- Consumes: `SourceParser.Session`.
- Produces:
```java
public final class SourceModel {
    public record TypeInfo(String fqcn, String kind, boolean isApi, String relPath,
                           List<String> annotations, String apiConfidence,
                           String signatureHash, List<MemberInfo> members) {}
    public record MemberInfo(String name, String signature, String returnType, String synthesizedBy) {}
    public record UsageRef(String targetFqcn, String refKind) {}     // IMPORT | EXTENDS | CALL
    public record FileRef(String srcRel, String dstRel, int count) {}
}
public final class ApiSurfaceExtractor {
    public static List<SourceModel.TypeInfo> extract(SourceParser.Session session, boolean libraryModule)
}
```
Rules: all top-level and nested types with public/protected modifiers; `kind` ∈ `CLASS|INTERFACE|ENUM|RECORD|ANNOTATION`; `isApi = libraryModule && !fqcn.contains(".internal.")`; `annotations` = simple names; members = public/protected methods (`signature` = `name(paramType,...)` using declared type strings, `returnType` declared string) and fields (`signature` = name, `returnType` = field type); `synthesizedBy = null` for real members; `signatureHash` = SHA-256 hex of the sorted member signatures joined with `\n`, prefixed by the fqcn; `apiConfidence = "OK"` (Task 4 adds `PARTIAL`).

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSurfaceExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session parse(String relPath, String source) throws Exception {
        Path f = repo.resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void extractsPublicTypesMembersAndKinds() throws Exception {
        var session = parse("src/main/java/com/acme/pricing/PriceCalculator.java", """
                package com.acme.pricing;
                public class PriceCalculator {
                    public static final int SCALE = 2;
                    public String quote(String req, int tier) { return req + tier; }
                    protected void recalc() {}
                    private void hidden() {}
                }
                """);
        List<SourceModel.TypeInfo> types = ApiSurfaceExtractor.extract(session, true);
        assertThat(types).hasSize(1);
        SourceModel.TypeInfo t = types.get(0);
        assertThat(t.fqcn()).isEqualTo("com.acme.pricing.PriceCalculator");
        assertThat(t.kind()).isEqualTo("CLASS");
        assertThat(t.isApi()).isTrue();
        assertThat(t.relPath()).isEqualTo("src/main/java/com/acme/pricing/PriceCalculator.java");
        assertThat(t.members()).extracting(SourceModel.MemberInfo::name)
                .containsExactlyInAnyOrder("SCALE", "quote", "recalc")
                .doesNotContain("hidden");
        assertThat(t.members()).filteredOn(m -> m.name().equals("quote")).first()
                .satisfies(m -> {
                    assertThat(m.signature()).isEqualTo("quote(String,int)");
                    assertThat(m.returnType()).isEqualTo("String");
                });
        assertThat(t.signatureHash()).hasSize(64);
        assertThat(t.apiConfidence()).isEqualTo("OK");
    }

    @Test
    void internalPackagesAndServiceModulesAreNotApi() throws Exception {
        var session = parse("src/main/java/com/acme/internal/Impl.java",
                "package com.acme.internal;\npublic class Impl {}\n");
        assertThat(ApiSurfaceExtractor.extract(session, true).get(0).isApi()).isFalse();
        assertThat(ApiSurfaceExtractor.extract(session, false).get(0).isApi()).isFalse();
    }

    @Test
    void recordsEnumsInterfacesAnnotationsGetKinds() throws Exception {
        var session = parse("src/main/java/com/acme/K.java", """
                package com.acme;
                public interface K {
                    enum Tier { GOLD }
                    record Quote(String id) {}
                    @interface Marker {}
                }
                """);
        List<SourceModel.TypeInfo> types = ApiSurfaceExtractor.extract(session, true);
        assertThat(types).extracting(SourceModel.TypeInfo::kind)
                .containsExactlyInAnyOrder("INTERFACE", "ENUM", "RECORD", "ANNOTATION");
    }

    @Test
    void signatureHashChangesWhenApiChanges() throws Exception {
        var s1 = parse("src/main/java/com/acme/A.java",
                "package com.acme;\npublic class A { public void x() {} }\n");
        String h1 = ApiSurfaceExtractor.extract(s1, true).get(0).signatureHash();
        var s2 = parse("src/main/java/com/acme/A.java",
                "package com.acme;\npublic class A { public void x() {} public void y() {} }\n");
        String h2 = ApiSurfaceExtractor.extract(s2, true).get(0).signatureHash();
        assertThat(h1).isNotEqualTo(h2);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.ApiSurfaceExtractorTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`SourceModel.java` exactly as the Interfaces block (package `sdd.index.source`, private constructor, imports `java.util.List`).

`ApiSurfaceExtractor.java`:
```java
package sdd.index.source;

import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

public final class ApiSurfaceExtractor {
    private ApiSurfaceExtractor() {}

    public static List<SourceModel.TypeInfo> extract(SourceParser.Session session, boolean libraryModule) {
        List<SourceModel.TypeInfo> out = new ArrayList<>();
        for (SourceParser.ParsedUnit unit : session.units()) {
            String pkg = unit.cu().getPackageDeclaration()
                    .map(p -> p.getNameAsString()).orElse("");
            unit.cu().findAll(TypeDeclaration.class).stream()
                    .filter(t -> t.isPublic() || t.hasModifier(com.github.javaparser.ast.Modifier.Keyword.PROTECTED))
                    .forEach(t -> out.add(toTypeInfo(t, pkg, unit.relPath(), libraryModule)));
        }
        return List.copyOf(out);
    }

    private static SourceModel.TypeInfo toTypeInfo(TypeDeclaration<?> t, String pkg,
                                                   String relPath, boolean libraryModule) {
        String fqcn = t.getFullyQualifiedName().orElse(pkg.isEmpty()
                ? t.getNameAsString() : pkg + "." + t.getNameAsString());
        List<String> annotations = t.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier()).toList();
        List<SourceModel.MemberInfo> members = extractMembers(t);
        boolean isApi = libraryModule && !fqcn.contains(".internal.");
        return new SourceModel.TypeInfo(fqcn, kindOf(t), isApi, relPath,
                annotations, "OK", hash(fqcn, members), members);
    }

    static List<SourceModel.MemberInfo> extractMembers(TypeDeclaration<?> t) {
        List<SourceModel.MemberInfo> members = new ArrayList<>();
        for (MethodDeclaration m : t.getMethods()) {
            if (m.isPublic() || m.isProtected()) {
                String params = m.getParameters().stream()
                        .map(p -> p.getType().asString())
                        .collect(Collectors.joining(","));
                members.add(new SourceModel.MemberInfo(m.getNameAsString(),
                        m.getNameAsString() + "(" + params + ")",
                        m.getType().asString(), null));
            }
        }
        for (FieldDeclaration f : t.getFields()) {
            if (f.isPublic() || f.isProtected()) {
                f.getVariables().forEach(v -> members.add(new SourceModel.MemberInfo(
                        v.getNameAsString(), v.getNameAsString(),
                        v.getType().asString(), null)));
            }
        }
        return members;
    }

    private static String kindOf(TypeDeclaration<?> t) {
        if (t instanceof AnnotationDeclaration) {
            return "ANNOTATION";
        }
        if (t instanceof EnumDeclaration) {
            return "ENUM";
        }
        if (t instanceof RecordDeclaration) {
            return "RECORD";
        }
        if (t instanceof ClassOrInterfaceDeclaration c && c.isInterface()) {
            return "INTERFACE";
        }
        return "CLASS";
    }

    static String hash(String fqcn, List<SourceModel.MemberInfo> members) {
        String canonical = fqcn + "\n" + members.stream()
                .map(SourceModel.MemberInfo::signature).sorted()
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

Note: nested non-public types inside a public interface (the `K` test) — members of an interface are implicitly public; JavaParser's `isPublic()` on nested `enum/record/@interface` inside an interface returns false for the modifier but they are implicitly public. If the `recordsEnumsInterfacesAnnotationsGetKinds` test fails on filtering, extend the filter to also accept types whose parent is an interface (`t.getParentNode().filter(p -> p instanceof ClassOrInterfaceDeclaration c && c.isInterface()).isPresent()`), verify, and document in the report.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.ApiSurfaceExtractorTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: api surface extractor with signature hashes"
```

---

### Task 4: LombokShim — member synthesis + PARTIAL confidence

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/source/LombokShim.java`
- Modify: `sdd-index/src/main/java/sdd/index/source/ApiSurfaceExtractor.java` (wire the shim into `toTypeInfo`)
- Test: `sdd-index/src/test/java/sdd/index/source/LombokShimTest.java`

**Interfaces:**
- Consumes: JavaParser `TypeDeclaration`, `SourceModel.MemberInfo`.
- Produces:
```java
public final class LombokShim {
    public record Result(List<SourceModel.MemberInfo> synthesized, boolean unknownLombok) {}
    public static Result apply(com.github.javaparser.ast.body.TypeDeclaration<?> type)
}
```
Behavior: only consult annotations when the unit imports `lombok.` (checked by the caller via any annotation name in the KNOWN sets or an unknown detected through imports — see wiring below). Synthesis (all `synthesizedBy = "lombok:@<Name>"`):
  - `@Getter`/`@Data`/`@Value`: per non-static field `getX()` (or `isX()` for `boolean`), returnType = field type.
  - `@Setter`/`@Data`: per non-static non-final field `setX(<type>)`, returnType `void`.
  - `@Builder`: static `builder()`, returnType `<TypeName>.Builder`.
  - `@NoArgsConstructor`/`@AllArgsConstructor`/`@RequiredArgsConstructor`/`@Data`(implies required)/`@Value`(implies all): `<init>(...)` with param types (empty / all fields / final-without-initializer fields), returnType = type name.
  - Deduplicate: a synthesized member whose `signature` matches a real member is dropped by the CALLER (extractor merges with real members first-wins-real).
  - IGNORE set (never synthesize, never PARTIAL): `Slf4j, Log4j2, CustomLog, UtilityClass, FieldDefaults, EqualsAndHashCode, ToString, NonNull, SneakyThrows, Synchronized, Cleanup, With` — note `With` deliberately ignored in v1 (rare; note, not TODO).
  - `unknownLombok = true` when the type has an annotation NOT in KNOWN∪IGNORE whose name resolves from a `lombok.` import (single-type import `lombok.X` or wildcard `lombok.*`).
Wiring in `ApiSurfaceExtractor.toTypeInfo`: call `LombokShim.apply(t)`; merged members = real + synthesized-not-shadowed; `apiConfidence = result.unknownLombok() ? "PARTIAL" : "OK"`; hash over merged members.

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LombokShimTest {
    @TempDir Path repo;

    private SourceModel.TypeInfo extractFirst(String source) throws Exception {
        Path f = repo.resolve("src/main/java/com/acme/T.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        var session = SourceParser.parseModule(repo, repo, List.of());
        return ApiSurfaceExtractor.extract(session, true).get(0);
    }

    @Test
    void dataSynthesizesGettersSettersAndRequiredCtor() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.Data;
                @Data
                public class T {
                    private final String id;
                    private int count;
                    private boolean active;
                }
                """);
        assertThat(t.members()).extracting(SourceModel.MemberInfo::signature)
                .contains("getId()", "getCount()", "isActive()",
                        "setCount(int)", "setActive(boolean)", "<init>(String)")
                .doesNotContain("setId(String)");   // final field: no setter
        assertThat(t.members()).filteredOn(m -> m.signature().equals("getId()")).first()
                .satisfies(m -> assertThat(m.synthesizedBy()).isEqualTo("lombok:@Data"));
        assertThat(t.apiConfidence()).isEqualTo("OK");
    }

    @Test
    void builderSynthesizesBuilderMethod() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.Builder;
                @Builder
                public class T { private String x; }
                """);
        assertThat(t.members()).extracting(SourceModel.MemberInfo::signature).contains("builder()");
    }

    @Test
    void ignoreListNeverTriggersPartial() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.extern.slf4j.Slf4j;
                import lombok.EqualsAndHashCode;
                @Slf4j @EqualsAndHashCode
                public class T { public void real() {} }
                """);
        assertThat(t.apiConfidence()).isEqualTo("OK");
        assertThat(t.members()).hasSize(1);
    }

    @Test
    void unknownLombokAnnotationMarksPartial() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.experimental.SuperBuilder;
                @SuperBuilder
                public class T {}
                """);
        assertThat(t.apiConfidence()).isEqualTo("PARTIAL");
    }

    @Test
    void nonLombokUnknownAnnotationStaysOk() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import org.springframework.stereotype.Service;
                @Service
                public class T {}
                """);
        assertThat(t.apiConfidence()).isEqualTo("OK");
    }

    @Test
    void realMemberShadowsSynthesized() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.Getter;
                @Getter
                public class T {
                    private String id;
                    public String getId() { return "custom"; }
                }
                """);
        long count = t.members().stream().filter(m -> m.signature().equals("getId()")).count();
        assertThat(count).isEqualTo(1);
        assertThat(t.members()).filteredOn(m -> m.signature().equals("getId()")).first()
                .satisfies(m -> assertThat(m.synthesizedBy()).isNull());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.LombokShimTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`LombokShim.java`:
```java
package sdd.index.source;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class LombokShim {
    public record Result(List<SourceModel.MemberInfo> synthesized, boolean unknownLombok) {}

    private static final Set<String> GENERATING = Set.of(
            "Getter", "Setter", "Data", "Value", "Builder",
            "NoArgsConstructor", "AllArgsConstructor", "RequiredArgsConstructor");
    private static final Set<String> IGNORED = Set.of(
            "Slf4j", "Log4j2", "CustomLog", "UtilityClass", "FieldDefaults",
            "EqualsAndHashCode", "ToString", "NonNull", "SneakyThrows",
            "Synchronized", "Cleanup", "With");

    private LombokShim() {}

    public static Result apply(TypeDeclaration<?> type) {
        Set<String> annotationNames = type.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier()).collect(Collectors.toSet());
        boolean unknownLombok = annotationNames.stream()
                .anyMatch(n -> !GENERATING.contains(n) && !IGNORED.contains(n)
                        && importedFromLombok(type, n));
        List<SourceModel.MemberInfo> synthesized = new ArrayList<>();
        boolean data = annotationNames.contains("Data");
        boolean value = annotationNames.contains("Value");
        String typeName = type.getNameAsString();

        List<VariableDeclarator> fields = new ArrayList<>();
        for (FieldDeclaration f : type.getFields()) {
            if (!f.isStatic()) {
                fields.addAll(f.getVariables());
            }
        }
        if (annotationNames.contains("Getter") || data || value) {
            String by = "lombok:@" + (data ? "Data" : value ? "Value" : "Getter");
            for (VariableDeclarator v : fields) {
                String prefix = v.getTypeAsString().equals("boolean") ? "is" : "get";
                String name = prefix + capitalize(v.getNameAsString());
                synthesized.add(new SourceModel.MemberInfo(name, name + "()", v.getTypeAsString(), by));
            }
        }
        if (annotationNames.contains("Setter") || data) {
            String by = "lombok:@" + (data ? "Data" : "Setter");
            for (VariableDeclarator v : fields) {
                if (!isFinal(v)) {
                    String name = "set" + capitalize(v.getNameAsString());
                    synthesized.add(new SourceModel.MemberInfo(name,
                            name + "(" + v.getTypeAsString() + ")", "void", by));
                }
            }
        }
        if (annotationNames.contains("Builder")) {
            synthesized.add(new SourceModel.MemberInfo("builder", "builder()",
                    typeName + ".Builder", "lombok:@Builder"));
        }
        if (annotationNames.contains("NoArgsConstructor")) {
            synthesized.add(ctor(typeName, List.of(), "NoArgsConstructor"));
        }
        if (annotationNames.contains("AllArgsConstructor") || value) {
            synthesized.add(ctor(typeName, fields.stream().map(VariableDeclarator::getTypeAsString).toList(),
                    value ? "Value" : "AllArgsConstructor"));
        }
        if (annotationNames.contains("RequiredArgsConstructor") || data) {
            synthesized.add(ctor(typeName, fields.stream().filter(LombokShim::isFinal)
                            .filter(v -> v.getInitializer().isEmpty())
                            .map(VariableDeclarator::getTypeAsString).toList(),
                    data ? "Data" : "RequiredArgsConstructor"));
        }
        return new Result(List.copyOf(synthesized), unknownLombok);
    }

    private static SourceModel.MemberInfo ctor(String typeName, List<String> paramTypes, String by) {
        return new SourceModel.MemberInfo("<init>",
                "<init>(" + String.join(",", paramTypes) + ")", typeName, "lombok:@" + by);
    }

    private static boolean isFinal(VariableDeclarator v) {
        return v.getParentNode()
                .filter(p -> p instanceof FieldDeclaration fd && fd.isFinal()).isPresent();
    }

    private static boolean importedFromLombok(TypeDeclaration<?> type, String simpleName) {
        return type.findCompilationUnit().map(CompilationUnit::getImports).stream()
                .flatMap(List::stream)
                .anyMatch(imp -> {
                    String name = imp.getNameAsString();
                    return name.startsWith("lombok")
                            && (imp.isAsterisk() || name.endsWith("." + simpleName));
                });
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
```

Wire into `ApiSurfaceExtractor.toTypeInfo` (replace body):
```java
    private static SourceModel.TypeInfo toTypeInfo(TypeDeclaration<?> t, String pkg,
                                                   String relPath, boolean libraryModule) {
        String fqcn = t.getFullyQualifiedName().orElse(pkg.isEmpty()
                ? t.getNameAsString() : pkg + "." + t.getNameAsString());
        List<String> annotations = t.getAnnotations().stream()
                .map(a -> a.getName().getIdentifier()).toList();
        List<SourceModel.MemberInfo> real = extractMembers(t);
        LombokShim.Result lombok = LombokShim.apply(t);
        java.util.Set<String> realSignatures = real.stream()
                .map(SourceModel.MemberInfo::signature).collect(java.util.stream.Collectors.toSet());
        List<SourceModel.MemberInfo> members = new ArrayList<>(real);
        lombok.synthesized().stream()
                .filter(m -> !realSignatures.contains(m.signature()))
                .forEach(members::add);
        boolean isApi = libraryModule && !fqcn.contains(".internal.");
        return new SourceModel.TypeInfo(fqcn, kindOf(t), isApi, relPath, annotations,
                lombok.unknownLombok() ? "PARTIAL" : "OK", hash(fqcn, members), List.copyOf(members));
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.*'`
Expected: PASS (Task 2+3 suites must stay green too).

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: lombok member synthesis with PARTIAL confidence flood guard"
```

---

### Task 5: ReferenceExtractor — api_usage candidates + file_ref edges

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/source/ReferenceExtractor.java`
- Test: `sdd-index/src/test/java/sdd/index/source/ReferenceExtractorTest.java`

**Interfaces:**
- Consumes: `SourceParser.Session`, `SourceModel`.
- Produces:
```java
public final class ReferenceExtractor {
    public record Refs(List<SourceModel.UsageRef> usages, List<SourceModel.FileRef> fileRefs) {}
    public static Refs extract(SourceParser.Session session, Map<String, String> repoTypeIndex)
}
```
`repoTypeIndex` = fqcn → relPath for every type in the WHOLE repo (all modules; caller builds it). Per unit: collect candidate target FQCNs from (a) non-static, non-wildcard imports (`IMPORT`), (b) resolved `extends`/`implements` types (`EXTENDS`), (c) resolved method-call declaring types (`CALL`, best-effort — unresolvable calls skipped silently). Targets in `repoTypeIndex` and a different file → `FileRef(srcRel, dstRel, count)` aggregated per file pair (refKind irrelevant intra-repo). Targets NOT in the index and not `java.*`/`javax.*`/`jakarta.*` → `UsageRef` deduplicated per (targetFqcn, refKind).

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceExtractorTest {
    @TempDir Path repo;

    private SourceParser.Session write(Map<String, String> files) throws Exception {
        for (var e : files.entrySet()) {
            Path f = repo.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
        return SourceParser.parseModule(repo, repo, List.of());
    }

    @Test
    void splitsIntraRepoFileRefsFromExternalUsages() throws Exception {
        var session = write(Map.of(
                "src/main/java/com/acme/svc/OrderService.java", """
                        package com.acme.svc;
                        import com.acme.svc.OrderRepo;
                        import com.acme.pricing.PriceCalculator;
                        import java.util.List;
                        public class OrderService extends BaseService {
                            private final OrderRepo repo = new OrderRepo();
                            public void place() { repo.save(); }
                        }
                        """,
                "src/main/java/com/acme/svc/OrderRepo.java",
                        "package com.acme.svc;\npublic class OrderRepo { public void save() {} }\n",
                "src/main/java/com/acme/svc/BaseService.java",
                        "package com.acme.svc;\npublic class BaseService {}\n"));

        Map<String, String> index = session.units().stream().collect(Collectors.toMap(
                u -> "com.acme.svc." + u.file().getFileName().toString().replace(".java", ""),
                SourceParser.ParsedUnit::relPath));

        ReferenceExtractor.Refs refs = ReferenceExtractor.extract(session, index);

        // intra-repo: OrderService -> OrderRepo (import + call), OrderService -> BaseService (extends)
        assertThat(refs.fileRefs()).anySatisfy(fr -> {
            assertThat(fr.srcRel()).endsWith("OrderService.java");
            assertThat(fr.dstRel()).endsWith("OrderRepo.java");
            assertThat(fr.count()).isGreaterThanOrEqualTo(1);
        });
        assertThat(refs.fileRefs()).anySatisfy(fr ->
                assertThat(fr.dstRel()).endsWith("BaseService.java"));
        // cross-repo candidate: PriceCalculator; JDK filtered out
        assertThat(refs.usages()).extracting(SourceModel.UsageRef::targetFqcn)
                .contains("com.acme.pricing.PriceCalculator")
                .doesNotContain("java.util.List");
        // no self file refs
        assertThat(refs.fileRefs()).noneSatisfy(fr ->
                assertThat(fr.srcRel()).isEqualTo(fr.dstRel()));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.ReferenceExtractorTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package sdd.index.source;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReferenceExtractor {
    public record Refs(List<SourceModel.UsageRef> usages, List<SourceModel.FileRef> fileRefs) {}

    private ReferenceExtractor() {}

    public static Refs extract(SourceParser.Session session, Map<String, String> repoTypeIndex) {
        Set<String> usageKeys = new LinkedHashSet<>();
        List<SourceModel.UsageRef> usages = new ArrayList<>();
        Map<String, Integer> fileRefCounts = new LinkedHashMap<>();

        for (SourceParser.ParsedUnit unit : session.units()) {
            List<Target> targets = new ArrayList<>();
            unit.cu().getImports().stream()
                    .filter(i -> !i.isStatic() && !i.isAsterisk())
                    .forEach(i -> targets.add(new Target(i.getNameAsString(), "IMPORT")));
            for (ClassOrInterfaceDeclaration c : unit.cu().findAll(ClassOrInterfaceDeclaration.class)) {
                for (ClassOrInterfaceType ext : c.getExtendedTypes()) {
                    resolveType(ext).ifPresent(fqcn -> targets.add(new Target(fqcn, "EXTENDS")));
                }
                for (ClassOrInterfaceType impl : c.getImplementedTypes()) {
                    resolveType(impl).ifPresent(fqcn -> targets.add(new Target(fqcn, "EXTENDS")));
                }
            }
            for (MethodCallExpr call : unit.cu().findAll(MethodCallExpr.class)) {
                try {
                    String declaring = call.resolve().declaringType().getQualifiedName();
                    targets.add(new Target(declaring, "CALL"));
                } catch (Exception ignored) {
                    // best-effort: unresolvable call sites are skipped
                }
            }

            for (Target target : targets) {
                if (target.fqcn.startsWith("java.") || target.fqcn.startsWith("javax.")
                        || target.fqcn.startsWith("jakarta.")) {
                    continue;
                }
                String dstRel = repoTypeIndex.get(target.fqcn);
                if (dstRel != null) {
                    if (!dstRel.equals(unit.relPath())) {
                        fileRefCounts.merge(unit.relPath() + " " + dstRel, 1, Integer::sum);
                    }
                } else if (usageKeys.add(target.fqcn + " " + target.refKind)) {
                    usages.add(new SourceModel.UsageRef(target.fqcn, target.refKind));
                }
            }
        }
        List<SourceModel.FileRef> fileRefs = fileRefCounts.entrySet().stream()
                .map(e -> {
                    String[] parts = e.getKey().split(" ", 2);
                    return new SourceModel.FileRef(parts[0], parts[1], e.getValue());
                }).toList();
        return new Refs(List.copyOf(usages), fileRefs);
    }

    private record Target(String fqcn, String refKind) {}

    private static java.util.Optional<String> resolveType(ClassOrInterfaceType type) {
        try {
            return java.util.Optional.of(type.resolve().asReferenceType().getQualifiedName());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.source.ReferenceExtractorTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: reference extractor splitting file refs from cross-repo usage candidates"
```

---

### Task 6: SourcePersistence — java_type/api_member/api_usage/file_ref/FTS + parse_status

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/store/SourcePersistence.java`
- Test: `sdd-index/src/test/java/sdd/index/store/SourcePersistenceTest.java`

**Interfaces:**
- Consumes: `Database`, `SourceModel`, `FtsSymbolWriter` (sole FTS path), Jackson (annotations JSON).
- Produces:
```java
public final class SourcePersistence {
    public static void clearRepoFileRefs(org.jdbi.v3.core.Jdbi jdbi, long repoId)
    public static void persistModuleSource(org.jdbi.v3.core.Jdbi jdbi, long repoId, long moduleId,
            List<SourceModel.TypeInfo> types, List<SourceModel.UsageRef> usages,
            List<SourceModel.FileRef> fileRefs)
    public static void updateParseStatus(org.jdbi.v3.core.Jdbi jdbi, String repoName,
            String parseStatus, String errorAppend)
}
```
`persistModuleSource` — one transaction: delete this module's `java_type` rows (cascade clears `api_member`), delete its `api_usage` rows, `FtsSymbolWriter.deleteForModule`; insert `java_type` (annotations as JSON array string) + `api_member` per member + `api_usage` per usage (`target_module_id` NULL — Task 7 links) + `file_ref` rows bound to `repoId` + FTS rows: one per type (identifier = simple name) and one per non-`<init>` member name (identifier = member name, fqcn = declaring type). `updateParseStatus` sets `repo.parse_status` and appends `errorAppend` (when non-null) to `repo.error` (never overwrites existing content).

- [ ] **Step 1: Write the failing tests**

```java
package sdd.index.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.index.source.SourceModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourcePersistenceTest {
    @TempDir Path ws;
    private Database db;
    private long repoId;
    private long moduleId;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core', '/w/lib-core', 'LIBRARY')");
            repoId = h.createQuery("SELECT id FROM repo").mapTo(Long.class).one();
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + repoId + ", ':', 'LIBRARY')");
            moduleId = h.createQuery("SELECT id FROM module").mapTo(Long.class).one();
        });
    }

    private static SourceModel.TypeInfo type() {
        return new SourceModel.TypeInfo("com.acme.pricing.LoyaltyTier", "ENUM", true,
                "src/main/java/com/acme/pricing/LoyaltyTier.java",
                List.of("Generated"), "OK", "f".repeat(64),
                List.of(new SourceModel.MemberInfo("values", "values()", "LoyaltyTier[]", null)));
    }

    @Test
    void persistsTypesMembersUsagesFileRefsAndFts() {
        SourcePersistence.clearRepoFileRefs(db.jdbi(), repoId);
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(type()),
                List.of(new SourceModel.UsageRef("com.other.Thing", "IMPORT")),
                List.of(new SourceModel.FileRef("a/A.java", "b/B.java", 3)));

        Map<String, Object> jt = db.jdbi().withHandle(h ->
                h.createQuery("SELECT fqcn, kind, is_api, annotations FROM java_type").mapToMap().one());
        assertThat(jt).containsEntry("fqcn", "com.acme.pricing.LoyaltyTier").containsEntry("kind", "ENUM");
        assertThat((String) jt.get("annotations")).contains("Generated");
        assertThat(db.jdbi().withHandle(h -> h.createQuery("SELECT count(*) FROM api_member")
                .mapTo(Integer.class).one())).isEqualTo(1);
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT target_fqcn FROM api_usage WHERE from_module_id=" + moduleId)
                .mapTo(String.class).one())).isEqualTo("com.other.Thing");
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT ref_count FROM file_ref WHERE src_file='a/A.java'")
                .mapTo(Integer.class).one())).isEqualTo(3);
        // FTS finds by camel-split word AND by member name
        assertThat(new FtsRetriever(db.jdbi()).search("loyalty", 10)).isNotEmpty();
        assertThat(new FtsRetriever(db.jdbi()).search("values", 10)).isNotEmpty();
    }

    @Test
    void reperistReplacesInsteadOfDuplicating() {
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(type()), List.of(), List.of());
        SourcePersistence.persistModuleSource(db.jdbi(), repoId, moduleId,
                List.of(type()), List.of(), List.of());
        assertThat(db.jdbi().withHandle(h -> h.createQuery("SELECT count(*) FROM java_type")
                .mapTo(Integer.class).one())).isEqualTo(1);
        assertThat(db.jdbi().withHandle(h -> h.createQuery(
                "SELECT count(*) FROM fts_symbol").mapTo(Integer.class).one())).isEqualTo(2);
    }

    @Test
    void parseStatusAppendsErrorInsteadOfOverwriting() {
        db.jdbi().useHandle(h -> h.execute("UPDATE repo SET error='old note; ' WHERE id=" + repoId));
        SourcePersistence.updateParseStatus(db.jdbi(), "lib-core", "DEGRADED", "3 files failed");
        Map<String, Object> repo = db.jdbi().withHandle(h ->
                h.createQuery("SELECT parse_status, error FROM repo").mapToMap().one());
        assertThat(repo.get("parse_status")).isEqualTo("DEGRADED");
        assertThat((String) repo.get("error")).contains("old note").contains("3 files failed");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.SourcePersistenceTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package sdd.index.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.index.source.SourceModel;

public final class SourcePersistence {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SourcePersistence() {}

    public static void clearRepoFileRefs(Jdbi jdbi, long repoId) {
        jdbi.useHandle(h -> h.createUpdate("DELETE FROM file_ref WHERE repo_id=:r")
                .bind("r", repoId).execute());
    }

    public static void persistModuleSource(Jdbi jdbi, long repoId, long moduleId,
                                           java.util.List<SourceModel.TypeInfo> types,
                                           java.util.List<SourceModel.UsageRef> usages,
                                           java.util.List<SourceModel.FileRef> fileRefs) {
        jdbi.useTransaction(h -> {
            h.createUpdate("DELETE FROM java_type WHERE module_id=:m").bind("m", moduleId).execute();
            h.createUpdate("DELETE FROM api_usage WHERE from_module_id=:m").bind("m", moduleId).execute();
            FtsSymbolWriter.deleteForModule(h, moduleId);
            for (SourceModel.TypeInfo t : types) {
                insertType(h, moduleId, t);
            }
            for (SourceModel.UsageRef u : usages) {
                h.createUpdate("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) "
                                + "VALUES (:m, :fqcn, :kind)")
                        .bind("m", moduleId).bind("fqcn", u.targetFqcn())
                        .bind("kind", u.refKind()).execute();
            }
            for (SourceModel.FileRef fr : fileRefs) {
                h.createUpdate("INSERT INTO file_ref(repo_id, src_file, dst_file, ref_count) "
                                + "VALUES (:r, :src, :dst, :count)")
                        .bind("r", repoId).bind("src", fr.srcRel())
                        .bind("dst", fr.dstRel()).bind("count", fr.count()).execute();
            }
        });
    }

    private static void insertType(Handle h, long moduleId, SourceModel.TypeInfo t) {
        String annotationsJson;
        try {
            annotationsJson = JSON.writeValueAsString(t.annotations());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        h.createUpdate("""
                        INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path,
                                              signature_hash, api_confidence, annotations)
                        VALUES (:m, :fqcn, :kind, :api, :file, :hash, :conf, :ann)""")
                .bind("m", moduleId).bind("fqcn", t.fqcn()).bind("kind", t.kind())
                .bind("api", t.isApi() ? 1 : 0).bind("file", t.relPath())
                .bind("hash", t.signatureHash()).bind("conf", t.apiConfidence())
                .bind("ann", annotationsJson).execute();
        long typeId = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
        String simpleName = t.fqcn().substring(t.fqcn().lastIndexOf('.') + 1);
        FtsSymbolWriter.insert(h, moduleId, simpleName, t.fqcn());
        for (SourceModel.MemberInfo m : t.members()) {
            h.createUpdate("INSERT INTO api_member(type_id, name, signature, return_type, synthesized_by) "
                            + "VALUES (:t, :name, :sig, :ret, :by)")
                    .bind("t", typeId).bind("name", m.name()).bind("sig", m.signature())
                    .bind("ret", m.returnType()).bind("by", m.synthesizedBy()).execute();
            if (!m.name().equals("<init>")) {
                FtsSymbolWriter.insert(h, moduleId, m.name(), t.fqcn());
            }
        }
    }

    public static void updateParseStatus(Jdbi jdbi, String repoName, String parseStatus, String errorAppend) {
        jdbi.useHandle(h -> h.createUpdate("""
                        UPDATE repo SET parse_status=:status,
                          error = CASE WHEN :append IS NULL THEN error
                                       ELSE COALESCE(error, '') || :append || '; ' END
                        WHERE name=:name""")
                .bind("status", parseStatus).bind("append", errorAppend)
                .bind("name", repoName).execute());
    }
}
```

Note: the `reperistReplacesInsteadOfDuplicating` FTS count expects 2 rows (type `LoyaltyTier` + member `values`) — `deleteForModule` inside the transaction guarantees replacement.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.SourcePersistenceTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add sdd-index/src
git commit -m "feat: source persistence with FTS population and parse-status tracking"
```

---

### Task 7: UsageLinker + IndexService/CLI integration

**Files:**
- Create: `sdd-index/src/main/java/sdd/index/store/UsageLinker.java`
- Create: `sdd-index/src/main/java/sdd/index/source/SourceExtraction.java`
- Modify: `sdd-index/src/main/java/sdd/index/IndexService.java`
- Modify: `sdd-cli/src/main/java/sdd/cli/IndexCommand.java`
- Test: `sdd-index/src/test/java/sdd/index/store/UsageLinkerTest.java`
- Modify: `sdd-index/src/test/java/sdd/index/IndexServiceTest.java` (RepoResult gains parseStatus)

**Interfaces:**
- Consumes: everything above.
- Produces:
  - `UsageLinker.link(Jdbi)` → `record Report(int internalRefs, int prunedExternal)`: sets `api_usage.target_module_id` from `java_type.fqcn` matches (MIN(module_id) on ties), deletes rows with no match (external) and rows where `target_module_id = from_module_id` (self).
  - `SourceExtraction.extractRepo(Jdbi jdbi, long repoId, String repoName, Path repoPath, GradleModel.Extract extract)` → `String parseStatus` — for each project: locate `moduleId` by `(repoId, gradle_path)`; classpath jars from the project's `compileClasspath` resolved files; two passes: parse ALL modules first, build repo-wide `Map<String,String>` type index (fqcn→relPath), then extract+persist per module (`clearRepoFileRefs` once before). Status: `OK` (zero issues), `DEGRADED` (some issues; counts appended to error), `FAILED` (thrown exception — caught by caller). Calls `SourcePersistence.updateParseStatus` itself for OK/DEGRADED.
  - `IndexService`: `RepoResult` gains a 7th component `String parseStatus` (after `status`); after a successful `persistRepo` (`OK`/`DEGRADED` gradle status), calls `SourceExtraction.extractRepo` inside try/catch (`RuntimeException` → `updateParseStatus(FAILED)`, run continues); skipped repos report their stored `parse_status`; `run()` calls `UsageLinker.link` after `ArtifactLinker.link` (expose via `lastUsageReport()`).
  - `IndexCommand`: per-repo line gains `parse=<status>`; link summary line gains `, <n> internal type refs`.

- [ ] **Step 1: Write the failing UsageLinker test**

```java
package sdd.index.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UsageLinkerTest {
    @TempDir Path ws;

    @Test
    void linksInternalPrunesExternalAndSelf() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r1', '/w/r1', 'LIBRARY')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('r2', '/w/r2', 'SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'SERVICE')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (1, 'com.acme.Lib', 'CLASS')");
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (2, 'com.acme.Svc', 'CLASS')");
                // internal ref: svc -> lib; self ref: svc -> svc; external ref
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Lib', 'IMPORT')");
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'com.acme.Svc', 'CALL')");
                h.execute("INSERT INTO api_usage(from_module_id, target_fqcn, ref_kind) VALUES (2, 'org.ext.Gone', 'IMPORT')");
            });

            UsageLinker.Report report = UsageLinker.link(db.jdbi());

            assertThat(report.internalRefs()).isEqualTo(1);
            assertThat(report.prunedExternal()).isEqualTo(2);
            var rows = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT target_fqcn, target_module_id FROM api_usage").mapToMap().list());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("target_fqcn", "com.acme.Lib")
                    .containsEntry("target_module_id", 1);
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails, implement UsageLinker, verify it passes**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.store.UsageLinkerTest'` (FAIL first)

```java
package sdd.index.store;

import org.jdbi.v3.core.Jdbi;

public final class UsageLinker {
    public record Report(int internalRefs, int prunedExternal) {}

    private UsageLinker() {}

    public static Report link(Jdbi jdbi) {
        return jdbi.inTransaction(h -> {
            h.execute("""
                    UPDATE api_usage SET target_module_id =
                      (SELECT MIN(jt.module_id) FROM java_type jt WHERE jt.fqcn = api_usage.target_fqcn)
                    WHERE EXISTS(SELECT 1 FROM java_type jt WHERE jt.fqcn = api_usage.target_fqcn)""");
            int pruned = h.createUpdate(
                            "DELETE FROM api_usage WHERE target_module_id IS NULL "
                                    + "OR target_module_id = from_module_id")
                    .execute();
            int internal = h.createQuery("SELECT count(*) FROM api_usage")
                    .mapTo(Integer.class).one();
            return new Report(internal, pruned);
        });
    }
}
```

Then: PASS.

- [ ] **Step 3: Implement SourceExtraction**

```java
package sdd.index.source;

import org.jdbi.v3.core.Jdbi;
import sdd.index.gradle.GradleModel;
import sdd.index.store.SourcePersistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SourceExtraction {
    private SourceExtraction() {}

    public static String extractRepo(Jdbi jdbi, long repoId, String repoName,
                                     Path repoPath, GradleModel.Extract extract) {
        record ModuleWork(long moduleId, boolean library, SourceParser.Session session) {}
        List<ModuleWork> work = new ArrayList<>();
        int totalIssues = 0;
        for (GradleModel.Project p : extract.projects()) {
            Optional<Long> moduleId = jdbi.withHandle(h -> h.createQuery(
                            "SELECT id FROM module WHERE repo_id=:r AND gradle_path=:p")
                    .bind("r", repoId).bind("p", p.path()).mapTo(Long.class).findOne());
            if (moduleId.isEmpty()) {
                continue;
            }
            List<Path> jars = Optional.ofNullable(p.configurations().get("compileClasspath"))
                    .map(c -> c.resolved().stream().flatMap(r -> r.files().stream()).toList())
                    .orElse(List.of());
            SourceParser.Session session = SourceParser.parseModule(repoPath, p.projectDir(), jars);
            totalIssues += session.issues().size();
            boolean library = jdbi.withHandle(h -> h.createQuery(
                            "SELECT kind FROM module WHERE id=:m").bind("m", moduleId.get())
                    .mapTo(String.class).one()).equals("LIBRARY");
            work.add(new ModuleWork(moduleId.get(), library, session));
        }

        Map<String, String> repoTypeIndex = new LinkedHashMap<>();
        Map<Long, List<SourceModel.TypeInfo>> typesByModule = new LinkedHashMap<>();
        for (ModuleWork w : work) {
            List<SourceModel.TypeInfo> types = ApiSurfaceExtractor.extract(w.session(), w.library());
            typesByModule.put(w.moduleId(), types);
            types.forEach(t -> repoTypeIndex.putIfAbsent(t.fqcn(), t.relPath()));
        }

        SourcePersistence.clearRepoFileRefs(jdbi, repoId);
        for (ModuleWork w : work) {
            ReferenceExtractor.Refs refs = ReferenceExtractor.extract(w.session(), repoTypeIndex);
            SourcePersistence.persistModuleSource(jdbi, repoId, w.moduleId(),
                    typesByModule.get(w.moduleId()), refs.usages(), refs.fileRefs());
        }

        String status = totalIssues == 0 ? "OK" : "DEGRADED";
        SourcePersistence.updateParseStatus(jdbi, repoName, status,
                totalIssues == 0 ? null : totalIssues + " source files failed to parse");
        return status;
    }
}
```

- [ ] **Step 4: Integrate into IndexService + CLI**

In `IndexService`: add `String parseStatus` as the component AFTER `status` in `RepoResult` (update every construction site; scan-failure and FAILED paths use `"FAILED"`, skipped repos read stored `parse_status` in `withCounts` — simplest: `withCounts` also selects `parse_status` from repo and rebuilds the record). After a successful gradle persist in `indexRepo` (both OK and DEGRADED paths), run:
```java
            String parseStatus;
            try {
                long repoId = jdbi.withHandle(h -> h.createQuery("SELECT id FROM repo WHERE name=:n")
                        .bind("n", scan.name()).mapTo(Long.class).one());
                parseStatus = SourceExtraction.extractRepo(jdbi, repoId, scan.name(), scan.path(), extract);
            } catch (RuntimeException e) {
                SourcePersistence.updateParseStatus(jdbi, scan.name(), "FAILED",
                        "source extraction failed: " + firstLine(e.getMessage()));
                parseStatus = "FAILED";
            }
```
(add a private `firstLine` helper mirroring IndexCommand's). In `run()`, after `ArtifactLinker.link`, add `lastUsageReport = UsageLinker.link(db.jdbi());` with accessor `lastUsageReport()`. Update `IndexServiceTest` construction sites and add an assertion that a stub-extractor repo with a real Java source dir gets `parseStatus="OK"` and `java_type` rows (extend the existing stub-extractor test: point the extract's project `projectDir` at a temp dir containing `src/main/java/P.java` with a public class).

In `IndexCommand`: per-repo line becomes
```java
out.printf(Locale.ROOT, "%-28s %-9s parse=%-8s modules=%-3d internal-deps=%-3d%s%s%n",
        r.repo(), r.status(), r.parseStatus(), r.modules(), r.internalDeps(), ...);
```
and after the link line: `out.printf(Locale.ROOT, "usage: %d internal type refs%n", service.lastUsageReport().internalRefs());`

- [ ] **Step 5: Run the affected suites**

Run: `./gradlew :sdd-index:test :sdd-cli:test`
Expected: PASS (all updated tests green; update `IndexCommandTest`/`IndexServiceIT` expectations if the new column breaks exact-string assertions — `IndexServiceIT` asserts on record fields, so add `parseStatus` checks there: svc-orders `"OK"`).

- [ ] **Step 6: Commit**

```bash
git add sdd-index sdd-cli
git commit -m "feat: source extraction wired into sdd index with usage linking"
```

---

### Task 8: End-to-end estate test (no Gradle) + IT extension

**Files:**
- Test: `sdd-index/src/test/java/sdd/index/SourceEndToEndTest.java`
- Modify: `sdd-index/src/test/java/sdd/index/IndexServiceIT.java` (one new assertion block)

**Interfaces:**
- Consumes: `IndexService` package-visible `Extractor` seam, `FixtureRepo`, everything above.
- Produces: proof the whole source pipeline works estate-wide without Gradle, and that the real-Gradle path populates `java_type`.

- [ ] **Step 1: Write the failing end-to-end test**

```java
package sdd.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.config.SddConfig;
import sdd.core.db.Database;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.testing.FixtureRepo;
import sdd.index.gradle.GradleModel;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceEndToEndTest {
    @TempDir Path ws;

    private static GradleModel.Extract extractFor(Path repoDir, String name, String grp,
                                                  List<String> plugins,
                                                  List<GradleModel.DeclaredDep> deps) {
        return new GradleModel.Extract(List.of(new GradleModel.Project(
                ":", name, grp, "1.0", repoDir, plugins, false, List.of(),
                Map.of("compileClasspath",
                        new GradleModel.DepConfig(deps, List.of(), List.of())))),
                List.of());
    }

    @Test
    void fullPipelinePopulatesTypesUsagesFileRefsAndFts() {
        FixtureRepo.in(ws, "lib-pricing")
                .file("src/main/java/com/acme/pricing/PriceCalculator.java", """
                        package com.acme.pricing;
                        import lombok.Getter;
                        @Getter
                        public class PriceCalculator {
                            private String currency;
                            public String quote(String req) { return req; }
                        }
                        """)
                .commit("init");
        FixtureRepo.in(ws, "svc-orders")
                .file("src/main/java/com/acme/orders/OrderService.java", """
                        package com.acme.orders;
                        import com.acme.pricing.PriceCalculator;
                        public class OrderService {
                            public OrderHelper helper() { return new OrderHelper(); }
                        }
                        """)
                .file("src/main/java/com/acme/orders/OrderHelper.java",
                        "package com.acme.orders;\npublic class OrderHelper {}\n")
                .commit("init");

        SddConfig config = new SddConfig(ws, "fts", Map.of(), Map.of(), List.of(), Map.of());
        try (Database db = Database.open(ws)) {
            IndexService service = new IndexService(repoDir -> {
                String name = repoDir.getFileName().toString();
                return name.equals("lib-pricing")
                        ? extractFor(repoDir, "lib-pricing", "com.acme",
                                List.of("java-library", "maven-publish"), List.of())
                        : extractFor(repoDir, "svc-orders", "com.acme",
                                List.of("java", "org.springframework.boot"),
                                List.of(new GradleModel.DeclaredDep("com.acme", "lib-pricing", "1.0")));
            });
            List<IndexService.RepoResult> results = service.run(config, db);

            assertThat(results).allSatisfy(r -> assertThat(r.parseStatus()).isEqualTo("OK"));
            // lombok-synthesized getter present
            assertThat(db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM api_member WHERE signature='getCurrency()' "
                            + "AND synthesized_by='lombok:@Getter'").mapTo(Integer.class).one()))
                    .isEqualTo(1);
            // cross-repo usage linked to lib-pricing's module
            var usage = db.jdbi().withHandle(h -> h.createQuery("""
                            SELECT u.target_fqcn, m.repo_id FROM api_usage u
                            JOIN module m ON m.id = u.target_module_id""").mapToMap().list());
            assertThat(usage).hasSize(1);
            assertThat(usage.get(0)).containsEntry("target_fqcn", "com.acme.pricing.PriceCalculator");
            // intra-repo file ref OrderService -> OrderHelper
            assertThat(db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM file_ref WHERE src_file LIKE '%OrderService.java' "
                            + "AND dst_file LIKE '%OrderHelper.java'").mapTo(Integer.class).one()))
                    .isEqualTo(1);
            // FTS finds the calculator by split word
            assertThat(new FtsRetriever(db.jdbi()).search("calculator", 10)).isNotEmpty();
            assertThat(service.lastUsageReport().internalRefs()).isEqualTo(1);
        }
    }
}
```

(Note: `IndexService`'s stub-extractor constructor is the package-visible seam from 2A's fix wave — `IndexService(Extractor)`; if 2A left only a package-visible `indexRepo` seam without the constructor, ADD the package-visible constructor in this task: `IndexService() { this(null); }` + `IndexService(Extractor extractor)` storing it, `run()` using it when non-null. The Lombok import in the fixture parses fine without Lombok on any classpath — the shim is syntactic.)

- [ ] **Step 2: Run to verify it fails, then make it pass**

Run: `./gradlew :sdd-index:test --tests 'sdd.index.SourceEndToEndTest'`
Expected: FAIL first (constructor seam or wiring gaps); implement the missing glue (constructor seam only — everything else landed in Tasks 2–7); then PASS.

- [ ] **Step 3: Extend IndexServiceIT**

Add to the existing `@Tag("gradle-it")` test, after the existing assertions (svc-orders fixture already has `src/main/java/A.java`):
```java
            // source extraction ran on the real-Gradle path
            Integer typeCount = db.jdbi().withHandle(h -> h.createQuery(
                    "SELECT count(*) FROM java_type").mapTo(Integer.class).one());
            assertThat(typeCount).isGreaterThanOrEqualTo(2); // A (svc-orders) + C (lib-core)
```

Run: `./gradlew :sdd-index:test --tests 'sdd.index.IndexServiceIT'`
Expected: PASS.

- [ ] **Step 4: Full build + commit**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, everything green.

```bash
git add sdd-index/src
git commit -m "test: end-to-end source pipeline coverage without and with real gradle"
```

---

## Self-Review (completed at write time)

1. **Spec coverage (2B-1 scope):** JavaParser + symbol solver backed by resolved classpath → Task 2; library API surface with signature hashes + `.internal.` exclusion → Task 3; Lombok synthesis + ignore-list + PARTIAL flood guard → Task 4; `api_usage` (type granularity) + `file_ref` → Tasks 5–7; FTS population via `FtsSymbolWriter` (types + members, words invariant enforced) → Task 6; `parse_status` per repo with per-file degradation → Tasks 6–7; generated-code source root (`build/generated`) → Task 2; composite-build IT carry-forward → Task 1. Deliberately deferred: REST/Kafka/config extraction + resolution ladder + `spring_app_name`/`context_path` (Plan 2B-2); repo cards, REST matching, curation report (2C); `.internal.` config knob (2B-2, constant for now).
2. **Placeholder scan:** all code steps complete; the two documented contingencies (nested-type modifier filter in Task 3, constructor seam in Task 8) specify exactly what to do, not "handle later".
3. **Type consistency:** `SourceParser.Session/ParsedUnit` used identically in Tasks 2–5, 7; `SourceModel.TypeInfo(fqcn, kind, isApi, relPath, annotations, apiConfidence, signatureHash, members)` order consistent across Tasks 3, 4, 6; `LombokShim.Result(synthesized, unknownLombok)` matches wiring; `SourcePersistence` signatures match Task 7's calls; `UsageLinker.Report(internalRefs, prunedExternal)` matches Tasks 7–8; `RepoResult` gains `parseStatus` after `status` with all construction sites enumerated.

---

## Execution outcome (2026-08-11)

All 8 tasks complete; final whole-branch review found 2 Criticals (api_usage FK wedge on referenced-repo re-index; FTS orphan leak on module-id churn) + 6 Importants — all fixed in one wave and re-review-verified (138 tests green incl. real-Gradle ITs). Bonus: the composite IT caught and fixed a real 2A bug (macOS symlink path canonicalization).

**Key contracts established for 2B-2/2C:**
1. `api_usage` retains unmatched rows with NULL `target_module_id` (non-destructive linking, self-healing) — consumers MUST filter `target_module_id IS NOT NULL`.
2. Signature hashes now include return/field/component types (`signature:returnType`) — safe to build contract detection on.
3. All paths entering the DB go through `Paths2.canonical*` — keep it that way.
4. FTS writes remain exclusively via `FtsSymbolWriter` (now incl. `deleteForRepo`).

**Carry into 2B-2:** widen ReferenceExtractor (ObjectCreationExpr, field/return/param types — same-package recall near zero today; e2e test masks it with an artificial import); real field-level Lombok synthesis (PARTIAL flag is a stopgap); share JarTypeSolver instances across modules keyed by jar path (memory: ~150-jar classpaths × modules); measure parse+resolve time on 2–3 real repos before scaling; nested-class fqcn agreement test; enum constants/annotation members as members; consider ReflectionTypeSolver(jreOnly=true); markStale overwrites error (append instead); stream per-repo CLI output during long runs.
