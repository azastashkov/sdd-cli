package sdd.plan.spec;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders a NormalizedSpec to the canonical markdown form. Law: parse(render(spec)) == spec
 * for every valid spec, and files already in canonical form re-render byte-identically.
 * Required sections (Goal/Requirements/Acceptance Criteria) are always emitted — an
 * incomplete normalized spec must still round-trip so the Gate-1 reviewer can complete it.
 */
public final class SpecRenderer {
    private static final Pattern SAFE_SCALAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._/-]*");

    private SpecRenderer() {
    }

    public static String render(NormalizedSpec spec) {
        StringBuilder md = new StringBuilder("---\n");
        scalar(md, "id", spec.id());
        scalar(md, "title", spec.title());
        scalar(md, "owner", spec.owner());
        scalar(md, "status", spec.status());
        md.append("---\n");
        prose(md, "Goal", spec.goal(), true);
        prose(md, "Background", spec.background(), false);
        items(md, "Requirements", spec.requirements(), true);
        items(md, "Acceptance Criteria", spec.acceptance(), true);
        items(md, "Constraints", spec.constraints(), false);
        touchpoints(md, spec.touchpoints());
        plain(md, "Evidence", spec.evidence());
        plain(md, "Out of Scope", spec.outOfScope());
        items(md, "Open Questions", spec.openQuestions(), false);
        plain(md, "Attachments", spec.attachments());
        plain(md, "Sources", spec.sources());
        return md.toString();
    }

    private static void scalar(StringBuilder md, String key, String value) {
        md.append(key).append(": ");
        if (bareSafe(value)) {
            md.append(value);
        } else {
            md.append('\'').append(value.replace("'", "''")).append('\'');
        }
        md.append('\n');
    }

    /**
     * A value may render unquoted only when YAML reads the bare scalar back as the identical
     * string — 'no'/'123'/'1.10'/'2026-08-11' resolve to Boolean/Integer/Double/Date under
     * YAML 1.1 and would corrupt the round trip.
     */
    private static boolean bareSafe(String value) {
        if (!SAFE_SCALAR.matcher(value).matches() || value.endsWith(" ")) {
            return false;
        }
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(value);
        return value.equals(parsed);
    }

    private static void prose(StringBuilder md, String section, String body, boolean required) {
        if (!required && body.isBlank()) {
            return;
        }
        md.append("\n## ").append(section).append('\n');
        if (!body.isBlank()) {
            md.append(body.strip()).append('\n');
        }
    }

    private static void items(StringBuilder md, String section, List<SpecItem> items, boolean required) {
        if (!required && items.isEmpty()) {
            return;
        }
        md.append("\n## ").append(section).append('\n');
        for (SpecItem item : items) {
            md.append("- ").append(item.id()).append(": ").append(item.text()).append('\n');
        }
    }

    private static void touchpoints(StringBuilder md, List<Touchpoint> touchpoints) {
        if (touchpoints.isEmpty()) {
            return;
        }
        md.append("\n## Touchpoints\n");
        for (Touchpoint t : touchpoints) {
            md.append("- ").append(t.kind().key()).append(": ").append(t.value()).append('\n');
        }
    }

    private static void plain(StringBuilder md, String section, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        md.append("\n## ").append(section).append('\n');
        for (String value : values) {
            md.append("- ").append(value).append('\n');
        }
    }
}
