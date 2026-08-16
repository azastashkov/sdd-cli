package sdd.index.npm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.index.extract.BuildModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shapes here are copied from the real estate rather than invented: a published SDK with an
 * exports map, a private Vite app, and a workspaces monorepo whose root publishes nothing.
 */
class NpmExtractorTest {
    @TempDir Path repo;

    private final NpmExtractor extractor = new NpmExtractor();

    private void pkg(String relDir, String json) throws Exception {
        Path dir = relDir.isEmpty() ? repo : Files.createDirectories(repo.resolve(relDir));
        Files.writeString(dir.resolve("package.json"), json);
    }

    private static BuildModel.Module moduleAt(BuildModel.Extract e, String path) {
        return e.modules().stream().filter(m -> m.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no module at " + path + " in "
                        + e.modules().stream().map(BuildModel.Module::path).toList()));
    }

    @Test
    void detectsARepoByItsRootPackageJson() throws Exception {
        assertThat(extractor.detects(repo)).isFalse();
        pkg("", "{\"name\":\"thing\"}");
        assertThat(extractor.detects(repo)).isTrue();
    }

    @Test
    void publishedPackageIsALibraryUnderTheNpmGroup() throws Exception {
        pkg("", """
                {"name":"@azastashkov/web-sdk","version":"0.2.1",
                 "main":"./dist/index.js","types":"./dist/index.d.ts",
                 "files":["dist"],
                 "peerDependencies":{"react":"^18.3.1"},
                 "devDependencies":{"typescript":"~5.5"}}
                """);

        BuildModel.Module root = moduleAt(extractor.extract(repo), ":");

        assertThat(root.kind()).isEqualTo("LIBRARY");
        assertThat(root.language()).isEqualTo("TYPESCRIPT");
        assertThat(root.name()).isEqualTo("@azastashkov/web-sdk");
        assertThat(root.version()).isEqualTo("0.2.1");
        // The whole package name is the artifact name; the scope is NOT the group. An unscoped
        // package would otherwise have an empty group and become unaddressable.
        assertThat(root.publishes()).containsExactly(new BuildModel.Coordinate("npm", "@azastashkov/web-sdk"));
    }

    @Test
    void privateAppWithABundlerConfigIsAService() throws Exception {
        pkg("", """
                {"name":"mfe-a","version":"0.1.0","private":true,
                 "dependencies":{"@azastashkov/web-sdk":"^0.2.1","react":"18.3.1"}}
                """);
        Files.writeString(repo.resolve("vite.config.ts"), "export default {};\n");

        assertThat(moduleAt(extractor.extract(repo), ":").kind()).isEqualTo("SERVICE");
    }

    @Test
    void privatePackageWithNothingToRunOrPublishIsUnknown() throws Exception {
        pkg("", "{\"name\":\"scratch\",\"private\":true}");

        assertThat(moduleAt(extractor.extract(repo), ":").kind()).isEqualTo("UNKNOWN");
    }

    @Test
    void scopesAreEmittedRuntimeFirstSoEdgeLabellingPrefersThem() throws Exception {
        pkg("", """
                {"name":"app","private":true,
                 "devDependencies":{"shared":"^1.0.0"},
                 "dependencies":{"shared":"^1.0.0"}}
                """);

        // package.json happens to list devDependencies first; the extractor must still emit the
        // runtime scope first, because the persistence layer labels an edge with the first scope
        // that declares it.
        assertThat(moduleAt(extractor.extract(repo), ":").scopes().keySet())
                .containsExactly("dependencies", "devDependencies");
    }

    @Test
    void workspacesBecomeModulesAndTheRootStaysTheAnchor() throws Exception {
        pkg("", """
                {"name":"trading-frontend-root-config","private":true,
                 "workspaces":["packages/*"]}
                """);
        pkg("packages/design-system", "{\"name\":\"@azastashkov/trading-design-system\",\"version\":\"0.1.0\",\"exports\":{\".\":\"./dist/index.js\"}}");
        pkg("packages/shell", """
                {"name":"shell","version":"0.1.0","private":true,
                 "dependencies":{"@azastashkov/trading-design-system":"^0.1.0"}}
                """);

        BuildModel.Extract e = extractor.extract(repo);

        assertThat(e.modules()).extracting(BuildModel.Module::path)
                .containsExactly(":", ":packages/design-system", ":packages/shell");
        // The root has no version and is not publishable, and is still a module: it is what ":"
        // addresses for artifact overrides and version planning.
        assertThat(moduleAt(e, ":").version()).isNull();
        assertThat(moduleAt(e, ":").kind()).isEqualTo("UNKNOWN");
        // It does still claim its own name. The coordinate list is the name-to-module index that
        // dependency resolution reads, and npm workspaces let a sibling depend on a private
        // package by name, so a private package that is unreachable in the registry is still
        // reachable inside its own repo.
        assertThat(moduleAt(e, ":").publishes())
                .containsExactly(new BuildModel.Coordinate("npm", "trading-frontend-root-config"));
        assertThat(moduleAt(e, ":packages/design-system").publishes())
                .containsExactly(new BuildModel.Coordinate("npm", "@azastashkov/trading-design-system"));
    }

    @Test
    void workspacesResolveThroughASymlinkedRepoDirectory() throws Exception {
        // A workspace holds its repos as symlinks, so this is the NORMAL deployment shape, not an
        // edge case. Files.walk does not follow links, so walking the link yields exactly one entry
        // — the link — and every workspaces monorepo silently indexes as a single module with none
        // of its packages and none of their dependency edges. Nothing reports it.
        pkg("", "{\"name\":\"root\",\"private\":true,\"workspaces\":[\"packages/*\"]}");
        pkg("packages/lib", "{\"name\":\"lib\",\"version\":\"1.0.0\",\"main\":\"./dist/i.js\"}");

        Path link = Files.createSymbolicLink(
                repo.resolveSibling(repo.getFileName() + "-link"), repo);

        assertThat(extractor.extract(link).modules()).extracting(BuildModel.Module::path)
                .containsExactly(":", ":packages/lib");
    }

    @Test
    void nodeModulesIsNeverTreatedAsAWorkspace() throws Exception {
        pkg("", "{\"name\":\"app\",\"private\":true,\"workspaces\":[\"*\"]}");
        pkg("node_modules/left-pad", "{\"name\":\"left-pad\",\"version\":\"1.0.0\"}");
        pkg("real-pkg", "{\"name\":\"real-pkg\",\"version\":\"1.0.0\"}");

        assertThat(extractor.extract(repo).modules()).extracting(BuildModel.Module::path)
                .containsExactly(":", ":real-pkg");
    }

    @Test
    void unreadableRootFallsBackToNothingRatherThanThrowing() throws Exception {
        pkg("", "{ this is not json");

        assertThat(extractor.fallback(repo).modules()).isEmpty();
    }

    @Test
    void fallbackKeepsTheRootPackageWhenAWorkspaceMemberIsBroken() throws Exception {
        pkg("", """
                {"name":"root","private":true,"workspaces":["packages/*"],
                 "dependencies":{"react":"18.3.1"}}
                """);
        pkg("packages/broken", "{ nope");

        // The accurate read fails, but the root's own declared dependencies are still true.
        assertThat(extractor.fallback(repo).modules()).extracting(BuildModel.Module::name)
                .containsExactly("root");
    }

    @Test
    void compositeRootsAreEmptyBecauseNpmHasNoIncludedBuilds() throws Exception {
        pkg("", "{\"name\":\"thing\",\"version\":\"1.0.0\"}");

        assertThat(extractor.extract(repo).compositeRoots()).isEmpty();
    }

    @Test
    void specifiersAreCarriedThroughVerbatimSoAVersionBumpCanRewriteThem() throws Exception {
        pkg("", """
                {"name":"app","private":true,
                 "dependencies":{"@azastashkov/web-sdk":"^0.2.1","react":"18.3.1"}}
                """);

        List<BuildModel.DeclaredDep> declared =
                moduleAt(extractor.extract(repo), ":").scopes().get("dependencies").declared();

        assertThat(declared).containsExactlyInAnyOrder(
                new BuildModel.DeclaredDep("npm", "@azastashkov/web-sdk", "^0.2.1"),
                new BuildModel.DeclaredDep("npm", "react", "18.3.1"));
    }
}
