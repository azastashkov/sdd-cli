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
}
