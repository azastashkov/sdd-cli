package sdd.index.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExtractJsonParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractJsonParser() {}

    public static GradleModel.Extract parse(String projectsJson, String settingsJson) {
        try {
            List<GradleModel.Project> projects = new ArrayList<>();
            for (JsonNode p : JSON.readTree(projectsJson).path("projects")) {
                projects.add(parseProject(p));
            }
            List<Path> included = new ArrayList<>();
            if (settingsJson != null) {
                for (JsonNode b : JSON.readTree(settingsJson).path("includedBuilds")) {
                    included.add(Path.of(b.asText()));
                }
            }
            return new GradleModel.Extract(List.copyOf(projects), List.copyOf(included));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static GradleModel.Project parseProject(JsonNode p) {
        List<String> plugins = new ArrayList<>();
        p.path("plugins").forEach(n -> plugins.add(n.asText()));
        List<GradleModel.Publication> pubs = new ArrayList<>();
        for (JsonNode pub : p.path("publications")) {
            pubs.add(new GradleModel.Publication(pub.path("groupId").asText(), pub.path("artifactId").asText()));
        }
        Map<String, GradleModel.DepConfig> configs = new LinkedHashMap<>();
        p.path("configurations").fields().forEachRemaining(e ->
                configs.put(e.getKey(), parseConfig(e.getValue())));
        return new GradleModel.Project(
                p.path("path").asText(), p.path("name").asText(),
                textOrNull(p, "group"), textOrNull(p, "version"),
                Path.of(p.path("projectDir").asText()),
                List.copyOf(plugins), p.path("hasBootJarTask").asBoolean(false),
                List.copyOf(pubs), configs);
    }

    private static GradleModel.DepConfig parseConfig(JsonNode c) {
        List<GradleModel.DeclaredDep> declared = new ArrayList<>();
        for (JsonNode d : c.path("declared")) {
            declared.add(new GradleModel.DeclaredDep(
                    textOrNull(d, "group"), d.path("name").asText(), textOrNull(d, "version")));
        }
        List<GradleModel.ResolvedDep> resolved = new ArrayList<>();
        for (JsonNode r : c.path("resolved")) {
            List<Path> files = new ArrayList<>();
            r.path("files").forEach(f -> files.add(Path.of(f.asText())));
            resolved.add(new GradleModel.ResolvedDep(
                    r.path("group").asText(), r.path("name").asText(),
                    textOrNull(r, "version"), List.copyOf(files)));
        }
        List<String> unresolved = new ArrayList<>();
        c.path("unresolved").forEach(u -> unresolved.add(u.asText()));
        return new GradleModel.DepConfig(List.copyOf(declared), List.copyOf(resolved), List.copyOf(unresolved));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
