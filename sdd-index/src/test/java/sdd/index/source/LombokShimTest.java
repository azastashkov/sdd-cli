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
        // type-level @Getter is fully synthesized, so confidence stays OK
        assertThat(t.apiConfidence()).isEqualTo("OK");
    }

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

    @Test
    void fieldAnnotationThatIsNotLombokStaysOk() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import org.junit.jupiter.api.io.TempDir;
                public class T {
                    @TempDir private String id;
                }
                """);
        assertThat(t.apiConfidence()).isEqualTo("OK");
    }

    @Test
    void dataWithExplicitConstructorAnnotationsDoesNotDuplicateInit() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.Data;
                import lombok.NoArgsConstructor;
                import lombok.AllArgsConstructor;
                @Data @NoArgsConstructor @AllArgsConstructor
                public class T { private String a; private int b; }
                """);
        long initCount = t.members().stream().filter(m -> m.signature().equals("<init>()")).count();
        assertThat(initCount).isEqualTo(1);
    }

    @Test
    void wildcardLombokImportDoesNotFlagUnrelatedAnnotations() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.*;
                @Data @Deprecated
                public class T { private String a; }
                """);
        assertThat(t.apiConfidence()).isEqualTo("OK");
    }

    @Test
    void wildcardLombokImportStillFlagsKnownExperimentalAnnotations() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.experimental.*;
                @SuperBuilder
                public class T {}
                """);
        assertThat(t.apiConfidence()).isEqualTo("PARTIAL");
    }

    @Test
    void bareLombokWildcardStillFlagsUnknownLombokAnnotations() throws Exception {
        SourceModel.TypeInfo t = extractFirst("""
                package com.acme;
                import lombok.*;
                @SuperBuilder
                public class T {}
                """);
        assertThat(t.apiConfidence()).isEqualTo("PARTIAL");
    }
}
