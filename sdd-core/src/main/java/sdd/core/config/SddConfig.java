package sdd.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record SddConfig(
        Path workspace,
        String retrieval,
        Map<String, ModelEndpoint> models,
        Map<Integer, Path> jdkHomes,
        List<String> excludes,
        Map<String, String> artifactOverrides,
        List<ManualEdge> manualEdges,
        RunSettings run,
        Map<String, List<String>> verificationExclusions) {}
