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
