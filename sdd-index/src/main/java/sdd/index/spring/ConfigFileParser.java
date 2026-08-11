package sdd.index.spring;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ConfigFileParser {
    public record ConfigEntry(String key, String value, String profile, String sourceFile) {}
    public record Result(List<ConfigEntry> entries, List<String> issues) {}

    private static final Pattern CONFIG_FILE = Pattern.compile(
            "(application|bootstrap)(?:-([A-Za-z0-9_]+))?\\.(yml|yaml|properties)");

    private ConfigFileParser() {}

    public static Result parseModuleConfig(Path repoRoot, Path moduleDir) {
        Path resources = moduleDir.resolve("src/main/resources");
        List<ConfigEntry> entries = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        if (!Files.isDirectory(resources)) {
            return new Result(List.of(), List.of());
        }
        try (Stream<Path> files = Files.list(resources)) {
            files.sorted().forEach(file -> {
                Matcher m = CONFIG_FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    return;
                }
                String filenameProfile = m.group(2);
                String rel = repoRoot.relativize(file).toString().replace('\\', '/');
                try {
                    String content = Files.readString(file);
                    if (m.group(3).equals("properties")) {
                        parseProperties(content, filenameProfile, rel, entries);
                    } else {
                        parseYaml(content, filenameProfile, rel, entries);
                    }
                } catch (Exception e) {
                    issues.add(rel + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            issues.add("src/main/resources: " + e.getMessage());
        }
        return new Result(List.copyOf(entries), List.copyOf(issues));
    }

    private static void parseProperties(String content, String profile, String rel,
                                        List<ConfigEntry> out) throws IOException {
        Properties props = new Properties();
        props.load(new StringReader(content));
        props.stringPropertyNames().stream().sorted().forEach(key ->
                out.add(new ConfigEntry(key, props.getProperty(key), profile, rel)));
    }

    private static void parseYaml(String content, String filenameProfile, String rel,
                                  List<ConfigEntry> out) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        for (Object doc : yaml.loadAll(content)) {
            if (!(doc instanceof Map<?, ?> map)) {
                continue;
            }
            List<ConfigEntry> docEntries = new ArrayList<>();
            flatten("", map, docEntries, rel);
            String docProfile = filenameProfile;
            List<ConfigEntry> kept = new ArrayList<>();
            for (ConfigEntry e : docEntries) {
                if (e.key().equals("spring.config.activate.on-profile")
                        || e.key().equals("spring.profiles")) {
                    docProfile = e.value();
                } else {
                    kept.add(e);
                }
            }
            for (ConfigEntry e : kept) {
                out.add(new ConfigEntry(e.key(), e.value(), docProfile, rel));
            }
        }
    }

    private static void flatten(String prefix, Map<?, ?> map, List<ConfigEntry> out, String rel) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(e.getKey())
                    : prefix + "." + e.getKey();
            flattenValue(key, e.getValue(), out, rel);
        }
    }

    private static void flattenValue(String key, Object value, List<ConfigEntry> out, String rel) {
        if (value instanceof Map<?, ?> nested) {
            flatten(key, nested, out, rel);
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flattenValue(key + "[" + i + "]", list.get(i), out, rel);
            }
        } else {
            out.add(new ConfigEntry(key, String.valueOf(value), null, rel));
        }
    }
}
