package sdd.index.ts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The manifests here are the estate's own, copied verbatim. */
class PackageEntriesTest {
    @TempDir Path pkg;

    private void write(String rel, String content) throws Exception {
        Path file = pkg.resolve(rel);
        Files.createDirectories(file.getParent() == null ? pkg : file.getParent());
        Files.writeString(file, content);
    }

    private java.util.List<String> specifiers(PackageEntries.Result result) {
        return result.entries().stream().map(PackageEntries.Entry::specifier).sorted().toList();
    }

    @Test
    void anExportsMapResolvesBackToSourceThroughOutDirAndRootDir() throws Exception {
        write("package.json", """
                {"name":"@azastashkov/web-sdk","version":"0.2.1",
                 "main":"./dist/index.js","types":"./dist/index.d.ts",
                 "exports":{
                   ".":{"types":"./dist/index.d.ts","import":"./dist/index.js"},
                   "./contract":{"types":"./dist/contract.d.ts"},
                   "./package.json":"./package.json"}}
                """);
        write("tsconfig.json", """
                {"compilerOptions":{"outDir":"dist","rootDir":"src","declaration":true}}
                """);
        write("src/index.ts", "export * from './types.js';\n");
        write("src/contract.ts", "export interface ShellContext {}\n");

        PackageEntries.Result result = PackageEntries.of(pkg, "@azastashkov/web-sdk");

        // An exports map names build OUTPUT, which does not exist until something is built and is a
        // generated copy when it does. Indexing dist would double every symbol and make the
        // knowledge base depend on whether anyone had run a build.
        assertThat(specifiers(result))
                .containsExactly("@azastashkov/web-sdk", "@azastashkov/web-sdk/contract");
        assertThat(result.entries()).allSatisfy(e ->
                assertThat(e.sourceFile().toString()).contains("/src/").doesNotContain("/dist/"));
        assertThat(result.partial()).isFalse();
    }

    @Test
    void aPackageWhoseSurfaceIsAllCssHasNoTypeScriptEntry() throws Exception {
        // The estate's design system: every export is a stylesheet. Reporting an entry for it
        // would invent a TypeScript surface that does not exist.
        write("package.json", """
                {"name":"@azastashkov/trading-design-system","version":"0.1.0",
                 "exports":{
                   ".":"./dist/trading-design-system.css",
                   "./tokens.css":"./css/tokens.css",
                   "./package.json":"./package.json"},
                 "files":["css","dist"]}
                """);

        PackageEntries.Result result = PackageEntries.of(pkg, "@azastashkov/trading-design-system");

        assertThat(result.entries()).isEmpty();
        assertThat(result.partial()).isFalse();   // nothing failed; there is genuinely nothing
    }

    @Test
    void aPackageWithNoExportsMapFallsBackToTypesThenMain() throws Exception {
        write("package.json", """
                {"name":"legacy","main":"./lib/index.js"}
                """);
        write("lib/index.ts", "export const x = 1;\n");

        assertThat(specifiers(PackageEntries.of(pkg, "legacy"))).containsExactly("legacy");
    }

    @Test
    void anEntryPointingAtSomethingAbsentMarksThePackagePartial() throws Exception {
        write("package.json", """
                {"name":"broken","exports":{".":{"types":"./dist/index.d.ts"}}}
                """);
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\",\"rootDir\":\"src\"}}");
        // no src/index.ts

        PackageEntries.Result result = PackageEntries.of(pkg, "broken");

        // "Nothing found" and "could not follow the map" have to be distinguishable, or a package
        // whose surface was unreadable looks identical to one that genuinely exposes nothing.
        assertThat(result.entries()).isEmpty();
        assertThat(result.partial()).isTrue();
    }

    @Test
    void aDirectoryEntryResolvesThroughItsIndexFile() throws Exception {
        write("package.json", """
                {"name":"@acme/lib","exports":{"./sub":{"types":"./dist/sub/index.d.ts"}}}
                """);
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\",\"rootDir\":\"src\"}}");
        write("src/sub/index.ts", "export const y = 1;\n");

        assertThat(specifiers(PackageEntries.of(pkg, "@acme/lib"))).containsExactly("@acme/lib/sub");
    }

    @Test
    void aWildcardSubpathIsSkippedRatherThanGuessedAt() throws Exception {
        write("package.json", """
                {"name":"@acme/lib","exports":{"./*":{"types":"./dist/*.d.ts"}}}
                """);
        write("tsconfig.json", "{\"compilerOptions\":{\"outDir\":\"dist\",\"rootDir\":\"src\"}}");
        write("src/a.ts", "export const a = 1;\n");

        // A wildcard names a family, not a file. Expanding it would mean deciding which members
        // exist, which is a guess about the package's intent.
        assertThat(PackageEntries.of(pkg, "@acme/lib").entries()).isEmpty();
    }

    @Test
    void tsconfigCommentsDoNotDefeatTheRead() throws Exception {
        write("package.json", """
                {"name":"@acme/lib","exports":{".":{"types":"./out/index.d.ts"}}}
                """);
        write("tsconfig.json", """
                {
                  // where the build lands
                  "compilerOptions": { "outDir": "out", "rootDir": "source" }
                }
                """);
        write("source/index.ts", "export const a = 1;\n");

        assertThat(specifiers(PackageEntries.of(pkg, "@acme/lib"))).containsExactly("@acme/lib");
    }
}
