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

    /**
     * Two modules of one repo sharing a classpath jar and a single {@link JarSolverCache}: the
     * cache must not hand the same JarTypeSolver to both modules' CombinedTypeSolver, because
     * {@code CombinedTypeSolver.add} re-parents it and JarTypeSolver refuses a second parent
     * (JavaParser 3.26.2). Regression for the whole-repo parse failure that bug caused.
     */
    @Test
    void sharedJarCacheParsesEveryModuleOfMultiModuleRepo() throws Exception {
        Path jar = TestJars.tinyJar(repo, "shared-lib.jar");
        Path moduleA = writeModule("moduleA", "Alpha");
        Path moduleB = writeModule("moduleB", "Beta");
        JarSolverCache cache = new JarSolverCache();

        SourceParser.Session a = SourceParser.parseModule(repo, moduleA, List.of(jar), cache);
        SourceParser.Session b = SourceParser.parseModule(repo, moduleB, List.of(jar), cache);

        assertThat(a.units()).hasSize(1);
        assertThat(a.issues()).isEmpty();
        assertThat(b.units()).hasSize(1);
        assertThat(b.issues()).isEmpty();
    }

    private Path writeModule(String moduleName, String typeName) throws Exception {
        Path src = repo.resolve(moduleName + "/src/main/java/com/acme");
        Files.createDirectories(src);
        Files.writeString(src.resolve(typeName + ".java"),
                "package com.acme;\npublic class " + typeName + " {}\n");
        return repo.resolve(moduleName);
    }
}
