package sdd.index.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    private Map<String, Integer> refs(SourceParser.Session session) {
        return ReferenceExtractor.typeRefs(session).stream().collect(Collectors.toMap(
                r -> r.fromFqcn() + " -" + r.refKind() + "-> " + r.toFqcn(),
                SourceModel.TypeRef::count));
    }

    @Test
    void typeRefsKeepBothEndsIncludingTheIntraRepoEdgesApiUsageNeverSees() throws Exception {
        var session = write(Map.of(
                "src/main/java/com/acme/svc/OrderService.java", """
                        package com.acme.svc;
                        import com.acme.pricing.PriceCalculator;
                        import java.util.List;
                        public class OrderService {
                            private final OrderRepo repo = new OrderRepo();
                            public void place() { repo.save(); repo.flush(); }
                        }
                        """,
                "src/main/java/com/acme/svc/OrderRepo.java", """
                        package com.acme.svc;
                        public class OrderRepo {
                            public void save() {}
                            public void flush() {}
                        }
                        """));

        Map<String, Integer> refs = refs(session);

        // The edge api_usage structurally cannot hold: same repo, same module, both ends named.
        // UsageLinker deletes rows whose consumer and provider module coincide, so this pair has
        // only ever existed as a file_ref, at file granularity and without the type names.
        assertThat(refs).containsKey("com.acme.svc.OrderService -TYPE-> com.acme.svc.OrderRepo");
        // Three call sites collapse into one row carrying the count: two method calls plus the
        // construction, since ReferenceExtractor classifies an ObjectCreationExpr as a CALL too.
        assertThat(refs).containsEntry("com.acme.svc.OrderService -CALL-> com.acme.svc.OrderRepo", 3);
        // Cross-repo imports still land, attributed to the importing type rather than the module.
        assertThat(refs)
                .containsKey("com.acme.svc.OrderService -IMPORT-> com.acme.pricing.PriceCalculator");
        // java.* stays out, or every ref_count would be dominated by the JDK.
        assertThat(refs.keySet()).noneMatch(k -> k.contains("java.util.List"));
        // A type never references itself.
        assertThat(refs.keySet()).noneMatch(k -> k.equals("com.acme.svc.OrderRepo -TYPE-> com.acme.svc.OrderRepo"));
    }

    @Test
    void aNestedTypesReferencesAreNotAlsoCountedAgainstItsOuterType() throws Exception {
        var session = write(Map.of(
                "src/main/java/com/acme/Outer.java", """
                        package com.acme;
                        public class Outer {
                            public static class Inner {
                                private final Helper h = new Helper();
                            }
                        }
                        """,
                "src/main/java/com/acme/Helper.java",
                "package com.acme;\npublic class Helper {}\n"));

        Map<String, Integer> refs = refs(session);

        // Nearest enclosing extracted declaration wins: Inner is itself extracted, so Outer must
        // not also claim the reference. Otherwise every outer type inherits its nested types'
        // entire reference set and the graph's distances become meaningless.
        assertThat(refs.keySet()).anyMatch(k -> k.startsWith("com.acme.Outer.Inner -"));
        assertThat(refs.keySet()).noneMatch(k -> k.startsWith("com.acme.Outer -"));
    }

    @Test
    void importsAreAttributedToThePrimaryTypeOnlyNotToEveryTypeInTheFile() throws Exception {
        var session = write(Map.of(
                "src/main/java/com/acme/Main.java", """
                        package com.acme;
                        import com.acme.other.Thing;
                        public class Main {}
                        class Sidecar {}
                        """));

        Map<String, Integer> refs = refs(session);

        // An import belongs to the compilation unit. Spraying it over Sidecar too would turn one
        // written line into two claimed references and inflate every count in the file.
        assertThat(refs).containsOnlyKeys("com.acme.Main -IMPORT-> com.acme.other.Thing");
    }

    @Test
    void aPackagePrivateTopLevelTypeIsAValidSourceNode() throws Exception {
        // The 2026-08-20 widening: before it, this type had no java_type row, so it could be
        // neither end of an edge. That is the whole reason the graph is worth having.
        var session = write(Map.of(
                "src/main/java/com/acme/TierInvalidationListener.java", """
                        package com.acme;
                        class TierInvalidationListener {
                            private final Channels channels = new Channels();
                        }
                        """,
                "src/main/java/com/acme/Channels.java",
                "package com.acme;\npublic class Channels {}\n"));

        assertThat(refs(session).keySet())
                .anyMatch(k -> k.startsWith("com.acme.TierInvalidationListener -")
                        && k.endsWith("-> com.acme.Channels"));
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

    @Test
    void unresolvablePartiallyQualifiedNestedTypeIsDroppedNotLeaked() throws Exception {
        var session = write(Map.of("src/main/java/com/acme/svc/User.java", """
                package com.acme.svc;
                public class User { private Ghost.Inner inner; }
                """));
        ReferenceExtractor.Refs refs = ReferenceExtractor.extract(session, Map.of());
        assertThat(refs.usages()).noneSatisfy(u ->
                assertThat(u.targetFqcn()).isEqualTo("Ghost.Inner"));
    }

    /**
     * Cross-repo ("estate") resolution: a type that lives only in a classpath jar must resolve
     * through the JarTypeSolver. The reference is written as a bare {@code Widget} behind an
     * import, so the literal-text fallback in ReferenceExtractor cannot fire — a TYPE usage for
     * the fully-qualified name can only come from the symbol solver reading the jar.
     */
    @Test
    void estateJarTypeResolvesThroughClasspathJarNotLiteralFallback() throws Exception {
        assumeTrue(TestJars.compilerAvailable(), "system java compiler unavailable");
        Path jar = TestJars.compiledJar(repo, "estate-lib.jar", "Widget", """
                package com.estate.lib;
                public class Widget { public String name() { return "w"; } }
                """);
        Path src = repo.resolve("src/main/java/com/acme/svc");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Consumer.java"), """
                package com.acme.svc;
                import com.estate.lib.Widget;
                public class Consumer { private Widget widget; }
                """);

        SourceParser.Session session = SourceParser.parseModule(repo, repo, List.of(jar));

        assertThat(session.issues()).isEmpty();
        ReferenceExtractor.Refs refs = ReferenceExtractor.extract(session, Map.of());
        assertThat(refs.usages()).anySatisfy(u -> {
            assertThat(u.targetFqcn()).isEqualTo("com.estate.lib.Widget");
            assertThat(u.refKind()).isEqualTo("TYPE");
        });
    }

    @Test
    void unresolvableFullyQualifiedTypeStillFallsBackToLiteralText() throws Exception {
        var session = write(Map.of("src/main/java/com/acme/svc/User.java", """
                package com.acme.svc;
                public class User { private com.ghost.api.Client client; }
                """));
        ReferenceExtractor.Refs refs = ReferenceExtractor.extract(session, Map.of());
        assertThat(refs.usages()).anySatisfy(u -> {
            assertThat(u.targetFqcn()).isEqualTo("com.ghost.api.Client");
            assertThat(u.refKind()).isEqualTo("TYPE");
        });
    }
}
