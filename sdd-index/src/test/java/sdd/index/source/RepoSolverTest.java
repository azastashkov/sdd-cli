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
        Path jar = TestJars.compiledJar(repo.resolve("libs"), "estate-lib.jar", "Widget",
                "package com.estate.lib;\npublic class Widget {}\n");
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
