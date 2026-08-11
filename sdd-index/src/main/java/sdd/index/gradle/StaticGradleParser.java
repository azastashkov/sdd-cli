package sdd.index.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Declared-only fallback when the Tooling API build fails. Never executes build logic. */
public final class StaticGradleParser {
    // implementation 'group:name:version'  /  implementation("group:name:version")
    private static final Pattern GAV = Pattern.compile(
            "\\b(implementation|api|compileOnly|runtimeOnly)\\s*[\\(\\s]\\s*['\"]([\\w.\\-]+):([\\w.\\-]+)(?::([^'\"]+))?['\"]");
    // api group: 'g', name: 'n', version: 'v'
    private static final Pattern MAP_SYNTAX = Pattern.compile(
            "\\b(implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*group:\\s*['\"]([\\w.\\-]+)['\"]\\s*,\\s*name:\\s*['\"]([\\w.\\-]+)['\"]\\s*(?:,\\s*version:\\s*['\"]([^'\"]+)['\"])?");
    // implementation(libs.foo.bar) / implementation libs.foo.bar
    private static final Pattern CATALOG_REF = Pattern.compile(
            "\\b(implementation|api|compileOnly|runtimeOnly)\\s*\\(?\\s*libs((?:\\.[A-Za-z0-9]+)+)\\)?");
    private static final Pattern PLUGIN_ID = Pattern.compile("id\\s*\\(?['\"]([\\w.\\-]+)['\"]\\)?");

    private StaticGradleParser() {}

    public static GradleModel.Extract parse(Path repoDir) {
        Map<String, CatalogEntry> catalog = readCatalog(repoDir);
        List<GradleModel.Project> projects = new ArrayList<>();
        parseModule(repoDir, ":", repoDir, catalog, projects);
        try (Stream<Path> children = Files.list(repoDir)) {
            children.filter(Files::isDirectory).sorted().forEach(sub ->
                    parseModule(repoDir, ":" + sub.getFileName(), sub, catalog, projects));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GradleModel.Extract(List.copyOf(projects), List.of());
    }

    private static void parseModule(Path repoDir, String projectPath, Path moduleDir,
                                    Map<String, CatalogEntry> catalog, List<GradleModel.Project> out) {
        Path buildFile = Files.isRegularFile(moduleDir.resolve("build.gradle"))
                ? moduleDir.resolve("build.gradle")
                : moduleDir.resolve("build.gradle.kts");
        if (!Files.isRegularFile(buildFile)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(buildFile);
        } catch (IOException e) {
            return;
        }
        List<String> plugins = new ArrayList<>();
        List<GradleModel.DeclaredDep> declared = new ArrayList<>();
        for (String line : lines) {
            Matcher plugin = PLUGIN_ID.matcher(line);
            while (plugin.find()) {
                plugins.add(plugin.group(1));
            }
            Matcher gav = GAV.matcher(line);
            if (gav.find()) {
                declared.add(new GradleModel.DeclaredDep(gav.group(2), gav.group(3), gav.group(4)));
                continue;
            }
            Matcher map = MAP_SYNTAX.matcher(line);
            if (map.find()) {
                declared.add(new GradleModel.DeclaredDep(map.group(2), map.group(3), map.group(4)));
                continue;
            }
            Matcher cat = CATALOG_REF.matcher(line);
            if (cat.find()) {
                String alias = cat.group(2).substring(1).replace('.', '-').toLowerCase(Locale.ROOT);
                CatalogEntry entry = catalog.get(alias);
                if (entry != null) {
                    declared.add(new GradleModel.DeclaredDep(entry.group, entry.name, entry.version));
                }
            }
        }
        String name = projectPath.equals(":") ? repoDir.getFileName().toString() : moduleDir.getFileName().toString();
        out.add(new GradleModel.Project(projectPath, name, null, null, moduleDir,
                List.copyOf(plugins), false, List.of(),
                Map.of("compileClasspath",
                        new GradleModel.DepConfig(List.copyOf(declared), List.of(), List.of()))));
    }

    private record CatalogEntry(String group, String name, String version) {}

    private static Map<String, CatalogEntry> readCatalog(Path repoDir) {
        Path file = repoDir.resolve("gradle/libs.versions.toml");
        Map<String, CatalogEntry> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            org.tomlj.TomlParseResult toml = org.tomlj.Toml.parse(file);
            org.tomlj.TomlTable versions = toml.getTable("versions");
            org.tomlj.TomlTable libraries = toml.getTable("libraries");
            if (libraries == null) {
                return out;
            }
            for (String key : libraries.keySet()) {
                org.tomlj.TomlTable lib = libraries.getTable(key);
                if (lib == null) {
                    continue;
                }
                String group;
                String name;
                String module = lib.getString("module");
                if (module != null && module.contains(":")) {
                    group = module.substring(0, module.indexOf(':'));
                    name = module.substring(module.indexOf(':') + 1);
                } else {
                    group = lib.getString("group");
                    name = lib.getString("name");
                }
                if (group == null || name == null) {
                    continue;
                }
                String version = null;
                try {
                    version = lib.getString("version");
                } catch (Exception ignored) {
                    // version might be nested as version.ref
                }
                if (version == null) {
                    String ref = null;
                    try {
                        ref = lib.getString("version.ref");
                    } catch (Exception ignored) {
                        // version.ref might not exist
                    }
                    if (ref != null && versions != null) {
                        version = versions.getString(ref);
                    }
                }
                String aliasKey = key.toLowerCase(Locale.ROOT);
                out.put(aliasKey, new CatalogEntry(group, name, version));
            }
        } catch (Exception ignored) {
            // fallback parser is best-effort by definition
        }
        return out;
    }
}
