package sdd.core.ts;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TsSidecarTest {

    /**
     * The script and the compiler must both be reachable on the runtime classpath. The compiler's
     * resource path contains its version, so this is what catches {@code TsSidecar.TS_VERSION}
     * drifting from the pin in {@code libs.versions.toml} — the alternative is TypeScript support
     * silently reporting "no node" on every machine.
     */
    @Test
    void bothSidecarAssetsAreOnTheClasspath() throws Exception {
        try (InputStream script = TsSidecar.class.getResourceAsStream("/sdd/ts/sdd-ts-extract.cjs")) {
            assertThat(script).as("sidecar script resource").isNotNull();
        }
        String compiler = "/META-INF/resources/webjars/typescript/" + TsSidecar.TS_VERSION
                + "/lib/typescript.js";
        try (InputStream in = TsSidecar.class.getResourceAsStream(compiler)) {
            assertThat(in).as("TypeScript compiler at %s — check libs.versions.toml", compiler).isNotNull();
        }
    }

    @Test
    void configuredNodeHomeThatDoesNotExistIsReportedRatherThanFallingBackToPath() {
        // Falling back to whatever node is on PATH would mean a typo in sdd.yml silently changes
        // which compiler reads the estate, which is exactly the kind of quiet substitution the
        // jdk_homes lever avoids on the Java side.
        Optional<TsSidecar> sidecar = TsSidecar.create(java.nio.file.Path.of("/nonexistent/node/home"));

        assertThat(sidecar).isEmpty();
        assertThat(NodeLocator.notFoundHint(java.nio.file.Path.of("/nonexistent/node/home")))
                .contains("/nonexistent/node/home").contains("node_home");
    }

    @Test
    void versionParsingReadsTheMajor() {
        assertThat(NodeLocator.majorOf("v22.1.0")).contains(22);
        assertThat(NodeLocator.majorOf("22.1.0")).contains(22);
        assertThat(NodeLocator.majorOf("not a version")).isEmpty();
        assertThat(NodeLocator.majorOf(null)).isEmpty();
    }

    // ------------------------------------------------------------------ real node required

    private static TsSidecar requireSidecar() {
        Optional<TsSidecar> sidecar = TsSidecar.create(null);
        Assumptions.assumeTrue(sidecar.isPresent(), "node not available on this machine");
        return sidecar.get();
    }

    @Test
    @Tag("node-it")
    void pingLoadsTheCompilerAndAgreesOnTheProtocol() {
        TsSidecar.Result result = requireSidecar().ping();

        assertThat(result.ok()).as("%s", result.error()).isTrue();
        assertThat(result.json().path("tsVersion").asText()).isEqualTo(TsSidecar.TS_VERSION);
    }

    @Test
    @Tag("node-it")
    void syntaxCheckAcceptsValidTypeScript() {
        TsSidecar.Result result = requireSidecar().syntaxCheck("src/a.ts",
                "export interface Tick { price: number }\nexport const x: Tick = { price: 1 };\n");

        assertThat(result.ok()).as("%s", result.error()).isTrue();
        assertThat(result.json().path("ok").asBoolean()).isTrue();
    }

    @Test
    @Tag("node-it")
    void syntaxCheckRejectsBrokenTypeScriptWithAPosition() {
        TsSidecar.Result result = requireSidecar().syntaxCheck("src/a.ts",
                "export function f( {\n  return 1;\n}\n");

        assertThat(result.ok()).as("%s", result.error()).isTrue();
        assertThat(result.json().path("ok").asBoolean()).isFalse();
        assertThat(result.json().path("error").asText()).isNotBlank().contains("(");
    }

    @Test
    @Tag("node-it")
    void genericsAndJsxAreNotMistakenForSyntaxErrors() {
        // The reason a real parser is used rather than a brace/angle-bracket heuristic: both of
        // these are valid and both defeat the obvious hand-rolled check.
        TsSidecar sidecar = requireSidecar();

        assertThat(sidecar.syntaxCheck("src/g.ts", "export const f = (a: number, b: number) => a < b;\n")
                .json().path("ok").asBoolean()).isTrue();
        assertThat(sidecar.syntaxCheck("src/g.tsx", "export const C = () => <div a={1 < 2}>x</div>;\n")
                .json().path("ok").asBoolean()).isTrue();
    }
}
