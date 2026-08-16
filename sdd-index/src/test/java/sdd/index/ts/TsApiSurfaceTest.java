package sdd.index.ts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sdd.index.source.SourceModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Naming, driven from a recorded sidecar response so it runs without node. What is being tested is
 * the only decision that matters here: whether a symbol ends up under the name a CONSUMER writes.
 */
class TsApiSurfaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode response(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A package that declares Tick in types.ts and re-exports it from its entry point. */
    private static final String RE_EXPORTING_PACKAGE = """
            {"publicExports":[
               {"specifier":"@acme/web-sdk","exportName":"Tick","declFile":"src/types.ts","declName":"Tick"},
               {"specifier":"@acme/web-sdk/contract","exportName":"ShellContext",
                "declFile":"src/contract.ts","declName":"ShellContext"}],
             "files":[
               {"relPath":"src/types.ts","refs":[],"symbols":[
                  {"name":"Tick","kind":"INTERFACE","doc":"A price tick. More detail here.",
                   "decorators":[],"members":[{"name":"price","signature":"price","returnType":"number"}]},
                  {"name":"Internal","kind":"INTERFACE","doc":null,"decorators":[],"members":[]}]},
               {"relPath":"src/contract.ts","refs":[],"symbols":[
                  {"name":"ShellContext","kind":"INTERFACE","doc":null,"decorators":[],"members":[]}]}]}
            """;

    @Test
    void anExportedSymbolIsRecordedUnderTheSpecifierAConsumerWrites() {
        List<SourceModel.TypeInfo> types =
                TsApiSurface.typesOf(response(RE_EXPORTING_PACKAGE), "@acme/web-sdk", false, false);

        // Tick is DECLARED in src/types.ts and re-exported from the entry point. Recording it under
        // its file path would leave every consumer of '@acme/web-sdk' pointing at nothing, because
        // UsageLinker joins on this string and nothing else.
        assertThat(types).extracting(SourceModel.TypeInfo::fqcn)
                .contains("@acme/web-sdk.Tick", "@acme/web-sdk/contract.ShellContext");
    }

    @Test
    void aSymbolThatIsNotExportedKeepsAPathNameAndIsNotApi() {
        List<SourceModel.TypeInfo> types =
                TsApiSurface.typesOf(response(RE_EXPORTING_PACKAGE), "@acme/web-sdk", false, false);

        assertThat(types).filteredOn(t -> t.fqcn().endsWith(".Internal")).singleElement()
                .satisfies(t -> {
                    assertThat(t.fqcn()).isEqualTo("@acme/web-sdk/src/types.Internal");
                    assertThat(t.isApi()).isFalse();
                });
        assertThat(types).filteredOn(t -> t.fqcn().equals("@acme/web-sdk.Tick")).singleElement()
                .satisfies(t -> assertThat(t.isApi()).isTrue());
    }

    @Test
    void nothingInAPrivatePackageIsApiHoweverItIsExported() {
        // `private` is npm's own statement that a package is not for distribution, so its exports
        // are not an estate-wide surface even though they are exports.
        List<SourceModel.TypeInfo> types =
                TsApiSurface.typesOf(response(RE_EXPORTING_PACKAGE), "@acme/web-sdk", true, false);

        assertThat(types).allSatisfy(t -> assertThat(t.isApi()).isFalse());
        // ...but the consumer-facing NAME is still right, so a reference to it still resolves.
        assertThat(types).extracting(SourceModel.TypeInfo::fqcn).contains("@acme/web-sdk.Tick");
    }

    @Test
    void aSymbolReachableUnderTwoSpecifiersTakesTheShorterOne() {
        JsonNode both = response("""
                {"publicExports":[
                   {"specifier":"@acme/lib/contract","exportName":"Thing","declFile":"src/t.ts","declName":"Thing"},
                   {"specifier":"@acme/lib","exportName":"Thing","declFile":"src/t.ts","declName":"Thing"}],
                 "files":[{"relPath":"src/t.ts","refs":[],"symbols":[
                   {"name":"Thing","kind":"CLASS","doc":null,"decorators":[],"members":[]}]}]}
                """);

        // Deterministic, and it picks the name a consumer is likelier to have written.
        assertThat(TsApiSurface.typesOf(both, "@acme/lib", false, false))
                .extracting(SourceModel.TypeInfo::fqcn).containsExactly("@acme/lib.Thing");
    }

    @Test
    void anUnreadableExportsMapMarksTheSurfacePartialRatherThanComplete() {
        List<SourceModel.TypeInfo> types =
                TsApiSurface.typesOf(response(RE_EXPORTING_PACKAGE), "@acme/web-sdk", false, true);

        // "We could not read all of this" and "this is all there is" must be distinguishable.
        assertThat(types).allSatisfy(t -> assertThat(t.apiConfidence()).isEqualTo("PARTIAL"));
    }

    @Test
    void docsGetTheFirstSentenceOnly() {
        List<SourceModel.TypeInfo> types =
                TsApiSurface.typesOf(response(RE_EXPORTING_PACKAGE), "@acme/web-sdk", false, false);

        // Same discipline as javadoc: a doc comment is a retrieval aid, never a structural fact.
        assertThat(types).filteredOn(t -> t.fqcn().equals("@acme/web-sdk.Tick")).singleElement()
                .satisfies(t -> assertThat(t.javadoc()).isEqualTo("A price tick."));
    }

    @Test
    void aCrossPackageImportTargetsTheNameTheProviderRecords() {
        JsonNode consumer = response("""
                {"publicExports":[],"files":[{"relPath":"src/app.ts","symbols":[],"refs":[
                   {"specifier":"@acme/web-sdk","name":"Tick","kind":"IMPORT"},
                   {"specifier":"@acme/design-system","name":null,"kind":"IMPORT"}]}]}
                """);

        List<SourceModel.UsageRef> usages = TsApiSurface.usagesOf(consumer);

        // The first is exactly the string the provider records Tick under, so the two meet by plain
        // equality with no resolution and no node_modules involved. The second is a side-effect
        // import: the dependency is visible, and no symbol is invented for it.
        assertThat(usages).extracting(SourceModel.UsageRef::targetFqcn)
                .containsExactly("@acme/web-sdk.Tick", "@acme/design-system");
    }

    @Test
    void signatureHashMovesWhenAMemberChanges() {
        String hashBefore = TsApiSurface.hash("@acme/lib.Thing",
                List.of(new SourceModel.MemberInfo("f", "f(number)", "void", null)));
        String hashAfter = TsApiSurface.hash("@acme/lib.Thing",
                List.of(new SourceModel.MemberInfo("f", "f(string)", "void", null)));

        assertThat(hashBefore).isNotEqualTo(hashAfter);
    }
}
