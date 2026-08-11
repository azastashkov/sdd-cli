package sdd.plan.spec;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict line-oriented parser for the canonical spec format (design Component 2): YAML front
 * matter with exactly id/title/owner/status, then '## ' sections in canonical order. Structural
 * violations throw SpecParseException with a line number; semantic completeness is
 * SpecValidator's job (an empty Requirements section parses fine and fails validation).
 */
public final class SpecParser {
    static final List<String> ORDER = List.of("Goal", "Background", "Requirements",
            "Acceptance Criteria", "Constraints", "Touchpoints", "Out of Scope",
            "Open Questions", "Attachments");
    private static final List<String> REQUIRED = List.of("Goal", "Requirements", "Acceptance Criteria");
    private static final List<String> FRONT_KEYS = List.of("id", "title", "owner", "status");
    private static final Map<String, Pattern> ITEM_PATTERNS = Map.of(
            "Requirements", Pattern.compile("- (R[1-9][0-9]*): (.+)"),
            "Acceptance Criteria", Pattern.compile("- (A[1-9][0-9]*): (.+)"),
            "Constraints", Pattern.compile("- (C[1-9][0-9]*): (.+)"),
            "Open Questions", Pattern.compile("- (Q[1-9][0-9]*): (.+)"));
    private static final Map<String, String> ITEM_HINTS = Map.of(
            "Requirements", "R1", "Acceptance Criteria", "A1", "Constraints", "C1", "Open Questions", "Q1");
    private static final Pattern TOUCHPOINT = Pattern.compile("- ([a-z]+): (.+)");
    private static final Pattern PLAIN = Pattern.compile("- (.+)");

    private SpecParser() {
    }

    public static NormalizedSpec parse(String markdown) {
        List<String> lines = markdown.lines().toList();
        if (lines.isEmpty() || !lines.get(0).equals("---")) {
            throw new SpecParseException(1, "spec must start with '---' front matter");
        }
        int close = lines.subList(1, lines.size()).indexOf("---");
        if (close < 0) {
            throw new SpecParseException(lines.size(), "front matter is never closed with '---'");
        }
        close += 1;   // index of the closing --- in lines
        Map<String, String> front = frontMatter(lines.subList(1, close), close + 1);

        Builder b = new Builder();
        String section = null;
        List<String> prose = new ArrayList<>();
        for (int i = close + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (line.startsWith("## ")) {
                closeSection(b, section, prose);
                section = heading(line.substring(3), section, lineNo);
                b.seen.add(section);
                prose = new ArrayList<>();
            } else if (line.startsWith("#")) {
                throw new SpecParseException(lineNo, "only '## ' section headings are allowed");
            } else if (section == null) {
                if (!line.isBlank()) {
                    throw new SpecParseException(lineNo, "content before the first '## ' section heading");
                }
            } else if (section.equals("Goal") || section.equals("Background")) {
                prose.add(line);   // prose keeps lines verbatim, including internal blanks
            } else if (!line.isBlank()) {
                bullet(b, section, line, lineNo);
            }
        }
        closeSection(b, section, prose);
        for (String required : REQUIRED) {
            if (!b.seen.contains(required)) {
                throw new SpecParseException(lines.size(), "missing required section '## " + required + "'");
            }
        }
        return new NormalizedSpec(front.get("id"), front.get("title"), front.get("owner"),
                front.get("status"), b.goal, b.background, b.requirements, b.acceptance,
                b.constraints, b.touchpoints, b.outOfScope, b.openQuestions, b.attachments);
    }

    private static Map<String, String> frontMatter(List<String> yamlLines, int closingLine) {
        Object raw;
        try {
            raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(String.join("\n", yamlLines));
        } catch (RuntimeException e) {
            throw new SpecParseException(2, "front matter is not valid YAML: " + e.getMessage());
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new SpecParseException(2, "front matter must be a YAML mapping");
        }
        Map<String, String> front = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!FRONT_KEYS.contains(key)) {
                throw new SpecParseException(2,
                        "unknown front matter key '" + key + "' (allowed: id, title, owner, status)");
            }
            front.put(key, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        for (String key : FRONT_KEYS) {
            if (!front.containsKey(key)) {
                throw new SpecParseException(closingLine, "front matter is missing '" + key + "'");
            }
        }
        return front;
    }

    private static String heading(String name, String current, int lineNo) {
        int idx = ORDER.indexOf(name);
        if (idx < 0) {
            throw new SpecParseException(lineNo,
                    "unknown section '## " + name + "' (known: " + String.join(", ", ORDER) + ")");
        }
        int currentIdx = current == null ? -1 : ORDER.indexOf(current);
        if (idx <= currentIdx) {
            throw new SpecParseException(lineNo,
                    "section '" + name + "' is duplicated or out of canonical order");
        }
        return name;
    }

    private static void bullet(Builder b, String section, String line, int lineNo) {
        Pattern itemPattern = ITEM_PATTERNS.get(section);
        if (itemPattern != null) {
            Matcher m = itemPattern.matcher(line);
            if (!m.matches()) {
                throw new SpecParseException(lineNo, section + " items must look like '- "
                        + ITEM_HINTS.get(section) + ": <text>'");
            }
            SpecItem item = new SpecItem(m.group(1), m.group(2));
            switch (section) {
                case "Requirements" -> b.requirements.add(item);
                case "Acceptance Criteria" -> b.acceptance.add(item);
                case "Constraints" -> b.constraints.add(item);
                default -> b.openQuestions.add(item);
            }
            return;
        }
        if (section.equals("Touchpoints")) {
            Matcher m = TOUCHPOINT.matcher(line);
            Touchpoint.Kind kind = m.matches() ? Touchpoint.Kind.fromKey(m.group(1)) : null;
            if (kind == null) {
                throw new SpecParseException(lineNo, "Touchpoints items must look like "
                        + "'- repo: <value>' (kinds: repo, endpoint, topic, class, artifact)");
            }
            b.touchpoints.add(new Touchpoint(kind, m.group(2)));
            return;
        }
        Matcher m = PLAIN.matcher(line);
        if (!m.matches()) {
            throw new SpecParseException(lineNo, section + " items must look like '- <text>'");
        }
        (section.equals("Out of Scope") ? b.outOfScope : b.attachments).add(m.group(1));
    }

    private static void closeSection(Builder b, String section, List<String> prose) {
        if ("Goal".equals(section)) {
            b.goal = String.join("\n", prose).strip();
        } else if ("Background".equals(section)) {
            b.background = String.join("\n", prose).strip();
        }
    }

    private static final class Builder {
        final Set<String> seen = new HashSet<>();
        String goal = "";
        String background = "";
        final List<SpecItem> requirements = new ArrayList<>();
        final List<SpecItem> acceptance = new ArrayList<>();
        final List<SpecItem> constraints = new ArrayList<>();
        final List<Touchpoint> touchpoints = new ArrayList<>();
        final List<String> outOfScope = new ArrayList<>();
        final List<SpecItem> openQuestions = new ArrayList<>();
        final List<String> attachments = new ArrayList<>();
    }
}
