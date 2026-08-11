package sdd.index.gradle;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class GradleModel {
    public record Extract(List<Project> projects, List<Path> includedBuilds) {}

    public record Project(String path, String name, String group, String version, Path projectDir,
                          List<String> plugins, boolean hasBootJarTask,
                          List<Publication> publications, Map<String, DepConfig> configurations) {}

    public record Publication(String groupId, String artifactId) {}

    public record DepConfig(List<DeclaredDep> declared, List<ResolvedDep> resolved, List<String> unresolved) {}

    public record DeclaredDep(String group, String name, String version) {}

    public record ResolvedDep(String group, String name, String version, List<Path> files) {}

    private GradleModel() {}
}
