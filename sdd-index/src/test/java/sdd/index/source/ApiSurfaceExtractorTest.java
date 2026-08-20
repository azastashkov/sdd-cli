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
    void topLevelPackagePrivateTypesAreExtractedButAreNeverApi() throws Exception {
        // The 2026-08-20 measurement: both TierInvalidationListeners and OrdersConfig are written
        // exactly like this, and none of them had a java_type row. See
        // docs/measurements/2026-08-20-graph-evidence/a0-baseline.md.
        var session = parse("src/main/java/com/acme/auth/TierInvalidationListener.java", """
                package com.acme.auth;
                class TierInvalidationListener {
                    public void onMessage(String channel) {}
                }
                """);

        List<SourceModel.TypeInfo> types = ApiSurfaceExtractor.extract(session, true);

        assertThat(types).extracting(SourceModel.TypeInfo::fqcn)
                .containsExactly("com.acme.auth.TierInvalidationListener");
        // is_api is the primary sort key of the drafter's evidence and the work order's manifest.
        // Nothing outside the package can name this type, so a library module must not promote it.
        assertThat(types.get(0).isApi()).isFalse();
        assertThat(types.get(0).members()).extracting(SourceModel.MemberInfo::name)
                .containsExactly("onMessage");
    }

    @Test
    void packagePrivateNestedInAClassStaysExcludedButNestedInAnInterfaceDoesNot() throws Exception {
        var session = parse("src/main/java/com/acme/Holder.java", """
                package com.acme;
                public class Holder {
                    class NestedInClass {}
                    private class PrivateNested {}
                }
                """);
        assertThat(ApiSurfaceExtractor.extract(session, true))
                .extracting(SourceModel.TypeInfo::fqcn)
                .containsExactly("com.acme.Holder");

        var iface = parse("src/main/java/com/acme/Api.java", """
                package com.acme;
                public interface Api {
                    class Impl {}
                }
                """);
        assertThat(ApiSurfaceExtractor.extract(iface, true))
                .extracting(SourceModel.TypeInfo::fqcn)
                .contains("com.acme.Api.Impl");
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

    @Test
    void signatureHashChangesWhenOnlyAReturnTypeChanges() throws Exception {
        var s1 = parse("src/main/java/com/acme/A.java",
                "package com.acme;\npublic class A { public String quote(String r) { return r; } }\n");
        String h1 = ApiSurfaceExtractor.extract(s1, true).get(0).signatureHash();
        var s2 = parse("src/main/java/com/acme/A.java",
                "package com.acme;\npublic class A { public int quote(String r) { return 1; } }\n");
        String h2 = ApiSurfaceExtractor.extract(s2, true).get(0).signatureHash();
        // same member signature, incompatible API: the hash is the only change signal downstream
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void signatureHashChangesWhenOnlyAFieldTypeChanges() throws Exception {
        var s1 = parse("src/main/java/com/acme/A.java",
                "package com.acme;\npublic class A { public long amount; }\n");
        String h1 = ApiSurfaceExtractor.extract(s1, true).get(0).signatureHash();
        var s2 = parse("src/main/java/com/acme/A.java",
                "package com.acme;\npublic class A { public String amount; }\n");
        String h2 = ApiSurfaceExtractor.extract(s2, true).get(0).signatureHash();
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void recordComponentsAppearAsMembersAndAffectHash() throws Exception {
        var s1 = parse("src/main/java/com/acme/Money.java",
                "package com.acme;\npublic record Money(String currency) {}\n");
        SourceModel.TypeInfo t1 = ApiSurfaceExtractor.extract(s1, true).get(0);
        assertThat(t1.members()).extracting(SourceModel.MemberInfo::signature).contains("currency()");
        assertThat(t1.members()).filteredOn(m -> m.signature().equals("currency()")).first()
                .satisfies(m -> assertThat(m.synthesizedBy()).isEqualTo("record-component"));
        var s2 = parse("src/main/java/com/acme/Money.java",
                "package com.acme;\npublic record Money(String currency, long cents) {}\n");
        String h2 = ApiSurfaceExtractor.extract(s2, true).get(0).signatureHash();
        assertThat(t1.signatureHash()).isNotEqualTo(h2);
    }

    @Test
    void javadocIsCapturedAsItsFirstSentenceOnly() throws Exception {
        var session = parse("src/main/java/com/acme/GroupDirectory.java", """
                package com.acme;
                /**
                 * Write-through state directory closing the ordering gap between a
                 * successful admin PUT and this service's own watcher.
                 *
                 * <p>Uses a HashMap internally and is called from the watcher thread.
                 *
                 * @param <T> the entry type
                 */
                public class GroupDirectory<T> {}
                """);
        assertThat(ApiSurfaceExtractor.extract(session, true).get(0).javadoc())
                .isEqualTo("Write-through state directory closing the ordering gap between a "
                        + "successful admin PUT and this service's own watcher.");
    }

    @Test
    void aTypeWithNoJavadocHasNullJavadoc() throws Exception {
        var session = parse("src/main/java/com/acme/Plain.java", """
                package com.acme;
                // an ordinary comment is not javadoc
                public class Plain {}
                """);
        assertThat(ApiSurfaceExtractor.extract(session, true).get(0).javadoc()).isNull();
    }

    @Test
    void inlineTagsFlattenToTheirTextRatherThanTheirMarkup() throws Exception {
        var session = parse("src/main/java/com/acme/Cache.java", """
                package com.acme;
                /** Backed by a {@code ConcurrentHashMap}, see {@link Registry#lookup}. */
                public class Cache {}
                """);
        // the braces and tag names are markup: indexing "{@code" would put a token in the corpus
        // that no reader would ever type, and hide the one they would
        assertThat(ApiSurfaceExtractor.extract(session, true).get(0).javadoc())
                .isEqualTo("Backed by a ConcurrentHashMap, see Registry#lookup.");
    }

    @Test
    void htmlMarkupInsideTheFirstSentenceIsDroppedRatherThanIndexed() throws Exception {
        var session = parse("src/main/java/com/acme/Cache.java", """
                package com.acme;
                /**
                 * A <b>write-through</b> cache, see <a href="http://acme/docs">the docs</a>
                 * for the 5&nbsp;&lt;p&gt; rules.
                 */
                public class Cache {}
                """);

        String javadoc = ApiSurfaceExtractor.extract(session, true).get(0).javadoc();

        // "b", "href" and "http" would answer queries no reader types while diluting the ones
        // they do; the escaped <p> was written to be read as text, so it survives as text.
        assertThat(javadoc).isEqualTo("A write-through cache, see the docs for the 5 <p> rules.");
    }

    @Test
    void theCapStepsBackRatherThanSplittingASurrogatePair() throws Exception {
        // an emoji straddling the 400-char boundary: cutting at exactly 400 would store its high
        // surrogate alone, which is not a character at all
        String longSentence = "x".repeat(399) + "😀" + " and more text after it.";
        var session = parse("src/main/java/com/acme/Emoji.java",
                "package com.acme;\n/** " + longSentence + " */\npublic class Emoji {}\n");

        String javadoc = ApiSurfaceExtractor.extract(session, true).get(0).javadoc();

        assertThat(javadoc).hasSize(399).isEqualTo("x".repeat(399));
        assertThat(javadoc.chars().anyMatch(c -> Character.isSurrogate((char) c))).isFalse();
    }

    @Test
    void anOverlongJavadocSentenceIsCappedAt400Characters() throws Exception {
        String longSentence = "Resolves tiers " + "and prices ".repeat(90) + "at checkout.";
        assertThat(longSentence.length()).isGreaterThan(900); // the case the cap exists for
        var session = parse("src/main/java/com/acme/Verbose.java",
                "package com.acme;\n/** " + longSentence + " */\npublic class Verbose {}\n");

        String javadoc = ApiSurfaceExtractor.extract(session, true).get(0).javadoc();

        assertThat(javadoc).hasSize(400);
        assertThat(longSentence).startsWith(javadoc);
    }

    @Test
    void nestedTypeInsideAnnotationIsExtracted() throws Exception {
        var session = parse("src/main/java/com/acme/Marker.java",
                "package com.acme;\npublic @interface Marker { enum Nested { A } }\n");
        assertThat(ApiSurfaceExtractor.extract(session, true))
                .extracting(SourceModel.TypeInfo::kind)
                .containsExactlyInAnyOrder("ANNOTATION", "ENUM");
    }
}
