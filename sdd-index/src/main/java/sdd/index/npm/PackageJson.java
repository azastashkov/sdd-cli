package sdd.index.npm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code package.json}, read for the facts the indexer needs and nothing more.
 *
 * <p>Everything here is a direct reading of the file. Nothing is inferred from the filesystem, and
 * the installed {@code node_modules} tree is never consulted: what a repo declares is a fact about
 * the repo, whereas what happens to be installed is a fact about someone's laptop.
 */
public record PackageJson(String name, String version, boolean isPrivate,
                          List<String> workspacePatterns,
                          Map<String, Map<String, String>> dependencyScopes,
                          boolean hasPublishSignals,
                          Map<String, String> scripts) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The scopes npm dependencies can be declared in, in the order the indexer emits them.
     *
     * <p>Order is load-bearing: {@code IndexPersistence} labels an edge with the FIRST scope that
     * declares it, so runtime scopes come before {@code devDependencies}. devDependencies are
     * included at all because they carry real inter-repo edges — a micro-frontend's dev-time
     * dependency on a shared design system is exactly the kind of coupling impact analysis exists
     * to find — mirroring the decision to index Gradle's test configurations.
     */
    static final List<String> SCOPES =
            List.of("dependencies", "peerDependencies", "optionalDependencies", "devDependencies");

    public static PackageJson read(Path packageJsonFile) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(packageJsonFile));
        if (!root.isObject()) {
            throw new IOException("package.json is not a JSON object: " + packageJsonFile);
        }

        Map<String, Map<String, String>> scopes = new LinkedHashMap<>();
        for (String scope : SCOPES) {
            JsonNode node = root.get(scope);
            if (node != null && node.isObject()) {
                Map<String, String> deps = new LinkedHashMap<>();
                node.fields().forEachRemaining(e -> deps.put(e.getKey(), e.getValue().asText()));
                if (!deps.isEmpty()) {
                    scopes.put(scope, deps);
                }
            }
        }

        // "publishable" is about intent, not about whether a registry has ever seen it: any of
        // these keys means the package is set up to be consumed by something outside its own repo.
        boolean publishSignals = root.has("publishConfig") || root.has("files")
                || root.has("exports") || root.has("main") || root.has("types");

        Map<String, String> scripts = new LinkedHashMap<>();
        JsonNode scriptsNode = root.get("scripts");
        if (scriptsNode != null && scriptsNode.isObject()) {
            scriptsNode.fields().forEachRemaining(e -> scripts.put(e.getKey(), e.getValue().asText()));
        }

        return new PackageJson(
                text(root, "name"), text(root, "version"),
                root.path("private").asBoolean(false),
                workspacePatterns(root),
                scopes, publishSignals, scripts);
    }

    /** Both spellings npm accepts: a bare array, and {@code {"packages": [...]}}. */
    private static List<String> workspacePatterns(JsonNode root) {
        JsonNode ws = root.get("workspaces");
        if (ws == null) {
            return List.of();
        }
        JsonNode array = ws.isArray() ? ws : ws.get("packages");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        array.forEach(n -> {
            if (n.isTextual() && !n.asText().isBlank()) {
                patterns.add(n.asText());
            }
        });
        return patterns;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || !n.isTextual() || n.asText().isBlank() ? null : n.asText();
    }

    public PackageJson {
        workspacePatterns = List.copyOf(workspacePatterns);
        dependencyScopes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(dependencyScopes));
        scripts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(scripts));
    }
}
