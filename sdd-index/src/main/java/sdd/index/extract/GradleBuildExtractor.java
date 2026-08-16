package sdd.index.extract;

import sdd.index.gradle.GradleExtractor;
import sdd.index.gradle.GradleModel;
import sdd.index.gradle.StaticGradleParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts the Gradle layer to {@link BuildModel}. The Tooling API extraction, the init script, the
 * JSON parsing and the regex fallback are all unchanged behind this — only the shape crossing into
 * the persistence layer is neutral.
 */
public final class GradleBuildExtractor implements BuildExtractor {
    public static final String BUILD_SYSTEM = "GRADLE";

    private final Map<Integer, Path> jdkHomes;

    public GradleBuildExtractor(Map<Integer, Path> jdkHomes) {
        this.jdkHomes = Map.copyOf(jdkHomes);
    }

    @Override
    public boolean detects(Path repoDir) {
        return Files.exists(repoDir.resolve("settings.gradle"))
                || Files.exists(repoDir.resolve("settings.gradle.kts"))
                || Files.exists(repoDir.resolve("build.gradle"))
                || Files.exists(repoDir.resolve("build.gradle.kts"))
                || Files.exists(repoDir.resolve("gradlew"));
    }

    @Override
    public String buildSystem() {
        return BUILD_SYSTEM;
    }

    @Override
    public BuildModel.Extract extract(Path repoDir) {
        return adapt(new GradleExtractor(jdkHomes).extract(repoDir));
    }

    @Override
    public BuildModel.Extract fallback(Path repoDir) {
        return adapt(StaticGradleParser.parse(repoDir));
    }

    /**
     * The Gradle-shape to neutral-shape conversion, exposed because the Gradle layer's own tests
     * build {@link GradleModel} fixtures and feed them to the neutral pipeline through here. Not a
     * general-purpose entry point: production code should go through {@link #extract} or
     * {@link #fallback}, which are the only callers that know which of the two a repo needs.
     */
    public static BuildModel.Extract adapt(GradleModel.Extract extract) {
        List<BuildModel.Module> modules = new ArrayList<>();
        for (GradleModel.Project p : extract.projects()) {
            modules.add(new BuildModel.Module(
                    p.path(), p.name(), p.group(), p.version(), p.projectDir(),
                    moduleKind(p), "JAVA",
                    p.publications().stream()
                            .map(pub -> new BuildModel.Coordinate(pub.groupId(), pub.artifactId()))
                            .toList(),
                    adaptScopes(p.configurations())));
        }
        return new BuildModel.Extract(modules, extract.includedBuilds());
    }

    /**
     * Preserves the emission order of {@code sdd-init.gradle} (compile/runtime configurations
     * first, test configurations after), which the persistence layer relies on to label a
     * dependency declared in both scopes with the compile-scope name.
     */
    private static Map<String, BuildModel.DepScope> adaptScopes(Map<String, GradleModel.DepConfig> configs) {
        Map<String, BuildModel.DepScope> scopes = new LinkedHashMap<>();
        configs.forEach((name, cfg) -> scopes.put(name, new BuildModel.DepScope(
                cfg.declared().stream()
                        .map(d -> new BuildModel.DeclaredDep(d.group(), d.name(), d.version()))
                        .toList(),
                cfg.resolved().stream()
                        .map(r -> new BuildModel.ResolvedDep(r.group(), r.name(), r.version(), r.files()))
                        .toList(),
                cfg.unresolved())));
        return scopes;
    }

    /**
     * Moved verbatim from {@code IndexPersistence}: a module is a SERVICE when Spring Boot builds
     * it into a runnable jar, a LIBRARY when it is set up to publish, and otherwise UNKNOWN. It
     * lives here now because the evidence is Gradle plugin ids, which no longer cross into
     * persistence.
     */
    private static String moduleKind(GradleModel.Project p) {
        boolean boot = p.plugins().contains("org.springframework.boot") || p.hasBootJarTask();
        if (boot) {
            return "SERVICE";
        }
        if (p.plugins().contains("maven-publish") || !p.publications().isEmpty()) {
            return "LIBRARY";
        }
        return "UNKNOWN";
    }
}
