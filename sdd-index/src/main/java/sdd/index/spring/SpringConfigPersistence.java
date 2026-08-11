package sdd.index.spring;

import org.jdbi.v3.core.Handle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpringConfigPersistence {
    private SpringConfigPersistence() {}

    public static void persistModuleConfig(Handle h, long moduleId,
                                           List<ConfigFileParser.ConfigEntry> entries) {
        h.createUpdate("DELETE FROM config_property WHERE module_id=:m").bind("m", moduleId).execute();
        for (ConfigFileParser.ConfigEntry e : entries) {
            h.createUpdate("INSERT INTO config_property(module_id, key, value, profile, source_file) "
                            + "VALUES (:m, :k, :v, :p, :f)")
                    .bind("m", moduleId).bind("k", e.key()).bind("v", e.value())
                    .bind("p", e.profile()).bind("f", e.sourceFile()).execute();
        }
        Map<String, String> defaults = defaultProfileProps(entries);
        h.createUpdate("UPDATE module SET spring_app_name=:app, context_path=:ctx WHERE id=:m")
                .bind("app", defaults.get("spring.application.name"))
                .bind("ctx", defaults.get("server.servlet.context-path"))
                .bind("m", moduleId).execute();
    }

    public static Map<String, String> defaultProfileProps(List<ConfigFileParser.ConfigEntry> entries) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ConfigFileParser.ConfigEntry e : entries) {
            if (e.profile() == null) {
                out.put(e.key(), e.value());
            }
        }
        return out;
    }
}
