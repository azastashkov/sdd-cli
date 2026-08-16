package sdd.index.ts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TsSourceFilesTest {
    @TempDir Path repo;

    private Path write(String rel, String text) throws Exception {
        Path file = repo.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private List<String> names(List<Path> files) throws Exception {
        Path root = repo.toRealPath();
        return files.stream().map(f -> root.relativize(f).toString().replace('\\', '/')).sorted().toList();
    }

    @Test
    void findsSourcesAndSkipsTestsConfigsAndDeclarations() throws Exception {
        write("src/a.ts", "export const a = 1;\n");
        write("src/b.tsx", "export const b = 1;\n");
        write("src/a.test.ts", "test('x', () => {});\n");
        write("src/c.spec.ts", "test('x', () => {});\n");
        write("src/types.d.ts", "declare const x: number;\n");
        write("src/data.fixture.json", "{}\n");
        write("vite.config.ts", "export default {};\n");
        write("README.md", "# hi\n");

        // Tests and bundler config describe how code is exercised and built, not what the deployed
        // application does — matching the Java side, which indexes only src/main/java. A test's
        // invented endpoints are not estate facts.
        assertThat(names(TsSourceFiles.discover(repo))).containsExactly("src/a.ts", "src/b.tsx");
    }

    @Test
    void neverDescendsIntoVendoredOrGeneratedTrees() throws Exception {
        write("src/a.ts", "export const a = 1;\n");
        write("node_modules/left-pad/index.ts", "export const p = 1;\n");
        write("dist/a.ts", "export const a = 1;\n");
        write("coverage/report.ts", "export const c = 1;\n");

        assertThat(names(TsSourceFiles.discover(repo))).containsExactly("src/a.ts");
    }

    @Test
    void aWorkspaceRootDoesNotSwallowItsOwnPackages() throws Exception {
        write("src/root.ts", "export const r = 1;\n");
        write("packages/shell/src/shell.ts", "export const s = 1;\n");
        write("packages/design/src/d.ts", "export const d = 1;\n");
        Path shell = repo.resolve("packages/shell");
        Path design = repo.resolve("packages/design");

        List<Path> rootFiles = TsSourceFiles.discover(repo, List.of(repo, shell, design));
        List<Path> shellFiles = TsSourceFiles.discover(shell, List.of(repo, shell, design));

        // Without excluding nested modules the root's walk claims every package's files too, and
        // each call site is recorded twice — once against the package that makes it and once
        // against the root — inflating every caller list with a repo that only contains the caller.
        assertThat(names(rootFiles)).containsExactly("src/root.ts");
        assertThat(names(shellFiles)).containsExactly("packages/shell/src/shell.ts");
    }

    @Test
    void anUnreadableDirectoryYieldsNothingRatherThanThrowing() {
        assertThat(TsSourceFiles.discover(repo.resolve("does-not-exist"))).isEmpty();
    }
}
