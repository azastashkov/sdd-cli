package sdd.index.extract;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What the indexer needs to know about a repository's build, independent of which build system
 * produced it. {@code sdd.index.gradle.GradleModel} stays as it is — that record set is the
 * deserialization shape of the {@code sdd-init.gradle} JSON contract and has to keep matching
 * whatever Gradle emits — and {@link GradleBuildExtractor} adapts one to the other.
 *
 * <p>The two Gradle-only fields deliberately do NOT appear here. {@code plugins} and
 * {@code hasBootJarTask} exist solely to decide whether a module is a SERVICE or a LIBRARY, so the
 * decision is made inside the extractor that has the evidence and only {@link Module#kind} crosses
 * this boundary. An npm extractor answers the same question from {@code private}, {@code exports}
 * and the presence of a bundler config; neither vocabulary has to leak into the persistence layer.
 */
public final class BuildModel {

    /**
     * @param modules        every module the repo publishes or builds, root module first
     * @param compositeRoots repos this build composes in directly (Gradle included builds); empty
     *                       for build systems with no equivalent
     */
    public record Extract(List<Module> modules, List<Path> compositeRoots) {
        public Extract {
            modules = List.copyOf(modules);
            compositeRoots = List.copyOf(compositeRoots);
        }
    }

    /**
     * @param path      module path within the repo, {@code ":"} for the repo's root module. Stored
     *                  as {@code module.gradle_path}, whose name predates multi-toolchain support.
     * @param kind      {@code SERVICE | LIBRARY | UNKNOWN}, decided by the extractor
     * @param language  {@code JAVA | TYPESCRIPT} — which source extractor owns this module
     * @param publishes coordinates this module publishes under; empty when it publishes nothing
     * @param scopes    dependency scopes in the order the extractor emitted them. Order is
     *                  load-bearing: the persistence layer keeps the FIRST scope that declares a
     *                  given coordinate as that edge's {@code configuration} label.
     */
    public record Module(String path, String name, String group, String version, Path moduleDir,
                         String kind, String language,
                         List<Coordinate> publishes, Map<String, DepScope> scopes) {
        public Module {
            publishes = List.copyOf(publishes);
            // NOT Map.copyOf: that returns a map with unspecified iteration order, which silently
            // breaks the first-seen-wins rule above — a dependency declared in both a compile and a
            // test configuration would get whichever label the hash order happened to produce.
            scopes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(scopes));
        }
    }

    /** A published coordinate: Maven {@code group:artifact}, or npm {@code ("npm", packageName)}. */
    public record Coordinate(String group, String name) {}

    /**
     * @param declared   the module's own declared dependencies — these become {@code dep_edge} rows
     * @param resolved   the full resolved classpath including transitives; used only to enrich a
     *                   declared edge with the version actually selected, and to locate jars
     * @param unresolved coordinates the build could not resolve
     */
    public record DepScope(List<DeclaredDep> declared, List<ResolvedDep> resolved,
                           List<String> unresolved) {
        public DepScope {
            declared = List.copyOf(declared);
            resolved = List.copyOf(resolved);
            unresolved = List.copyOf(unresolved);
        }
    }

    /** @param version the declared version STRING verbatim, so a version bump can rewrite it in place */
    public record DeclaredDep(String group, String name, String version) {}

    /** @param files artifacts backing this dependency; always empty for build systems with none */
    public record ResolvedDep(String group, String name, String version, List<Path> files) {
        public ResolvedDep {
            files = List.copyOf(files);
        }
    }

    private BuildModel() {
    }
}
