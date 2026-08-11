package sdd.index.gradle;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class CatalogReader {
    private CatalogReader() {}

    public static Set<String> internalGAs(Path repoDir) {
        Path catalog = repoDir.resolve("gradle/libs.versions.toml");
        if (!Files.isRegularFile(catalog)) {
            return Set.of();
        }
        try {
            TomlParseResult toml = Toml.parse(catalog);
            TomlTable libraries = toml.getTable("libraries");
            if (libraries == null) {
                return Set.of();
            }
            Set<String> gas = new HashSet<>();
            for (String key : libraries.keySet()) {
                TomlTable lib = libraries.getTable(key);
                if (lib == null) {
                    continue;
                }
                String module = lib.getString("module");
                if (module != null && module.contains(":")) {
                    gas.add(module);
                } else {
                    String group = lib.getString("group");
                    String name = lib.getString("name");
                    if (group != null && name != null) {
                        gas.add(group + ":" + name);
                    }
                }
            }
            return Set.copyOf(gas);
        } catch (Exception e) {
            return Set.of();
        }
    }
}
