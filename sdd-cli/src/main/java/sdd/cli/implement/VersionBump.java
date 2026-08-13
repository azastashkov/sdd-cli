package sdd.cli.implement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deterministic version-bump edit at the declaration site (design line 61: "PINNED/BOM edges also
 * get the version-bump edit at the real declaration site"). DIRECT declarations rewrite
 * {@code g:n:old -> g:n:new} in build.gradle(.kts); CATALOG declarations in libs.versions.toml
 * handle inline {@code "g:n:old"}, a {@code module = "g:n"} line carrying {@code version = "old"},
 * and {@code version.ref} indirection into [versions] — both on the module's own line (inline
 * table form) and spread across separate lines under a {@code [libraries.foo]} section header
 * (table form). Line-based and format-conservative: an unmatched declaration edits nothing and
 * the caller records that. BOM declaration sites live in repos the KB cannot locate yet — deferred
 * with the step-less widening.
 */
public final class VersionBump {
    private static final Pattern VERSION_REF = Pattern.compile("version\\.ref\\s*=\\s*\"([^\"]+)\"");

    private VersionBump() {
    }

    public static List<Path> apply(Path repoRoot, String group, String name,
                                   String oldVersion, String newVersion) {
        String coordinate = group + ":" + name;
        List<Path> edited = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            List<Path> buildFiles = walk
                    .filter(p -> {
                        String f = p.getFileName().toString();
                        return f.equals("build.gradle") || f.equals("build.gradle.kts")
                                || f.equals("libs.versions.toml");
                    })
                    .filter(p -> !skipped(repoRoot.relativize(p)))
                    .sorted()
                    .toList();
            for (Path file : buildFiles) {
                String content = Files.readString(file);
                String updated = file.getFileName().toString().equals("libs.versions.toml")
                        ? bumpCatalog(content, coordinate, oldVersion, newVersion)
                        : content.replace(coordinate + ":" + oldVersion, coordinate + ":" + newVersion);
                if (!updated.equals(content)) {
                    Files.writeString(file, updated);
                    edited.add(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return edited;
    }

    static String bumpCatalog(String toml, String coordinate, String oldVersion, String newVersion) {
        String inline = toml.replace("\"" + coordinate + ":" + oldVersion + "\"",
                "\"" + coordinate + ":" + newVersion + "\"");
        if (!inline.equals(toml)) {
            return inline;
        }
        String[] lines = toml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("\"" + coordinate + "\"")) {
                continue;   // not this library's module = "g:n" line
            }
            // Table form spreads module = "g:n" and version.ref/version across separate lines
            // (e.g. under a [libraries.foo] header); scan forward to the next section header.
            // An inline-table entry (module = "g:n", ...) is single-line by definition (it
            // contains '{'), so its window must not widen past its own line — otherwise a
            // version-less inline entry (e.g. BOM-managed) would open a window over sibling
            // entries in a flat [libraries] section and leak the bump into them.
            int windowEnd = i + 1;
            if (!lines[i].contains("{")) {
                windowEnd = lines.length;
                for (int k = i + 1; k < lines.length; k++) {
                    if (lines[k].strip().startsWith("[")) {
                        windowEnd = k;
                        break;
                    }
                }
            }
            for (int w = i; w < windowEnd; w++) {
                String versionKey = "version = \"" + oldVersion + "\"";
                if (lines[w].contains(versionKey)) {
                    lines[w] = lines[w].replace(versionKey, "version = \"" + newVersion + "\"");
                    return String.join("\n", lines);
                }
                Matcher ref = VERSION_REF.matcher(lines[w]);
                if (ref.find()) {
                    String alias = ref.group(1);
                    for (int j = 0; j < lines.length; j++) {
                        String stripped = lines[j].strip();
                        if ((stripped.startsWith(alias + " ") || stripped.startsWith(alias + "="))
                                && lines[j].contains("\"" + oldVersion + "\"")) {
                            lines[j] = lines[j].replace("\"" + oldVersion + "\"", "\"" + newVersion + "\"");
                            return String.join("\n", lines);
                        }
                    }
                }
            }
        }
        return toml;
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            String segment = part.toString();
            if (segment.equals(".git") || segment.equals("build")) {
                return true;
            }
        }
        return false;
    }
}
