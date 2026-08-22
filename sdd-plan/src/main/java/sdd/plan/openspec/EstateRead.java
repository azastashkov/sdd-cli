package sdd.plan.openspec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one thing a human writes into the workspace's OpenSpec change, read back out.
 *
 * <p>Everything else in that change directory is rendered — the affected set, the order, the
 * contracts, the requirement deltas. The one edit Gate 1 actually requires is answering a blocking
 * question, and until now that could only be done in {@code plan.md}. Being able to do it in
 * {@code design.md} is what makes the OpenSpec tree somewhere you work rather than somewhere you
 * look.
 *
 * <p>Deliberately narrow. This does NOT parse the change back into a plan: {@code ## Why} merges
 * the spec's goal and background irreversibly, and attachments and sources have no home in the
 * format at all, so a general reader would silently lose them. A resolution is a single line under
 * a numbered question — an exact grammar, the same one {@code plan.md} already accepts, and the
 * only part of this document that round-trips by construction.
 */
public final class EstateRead {

    private static final Pattern QUESTION = Pattern.compile("- Q(\\d+)( \\[blocking])?: .+");
    private static final Pattern RESOLUTION = Pattern.compile("  - resolution: (.+)");
    private static final String SECTION = "## Open Questions";

    private EstateRead() {
    }

    /**
     * Question number to the resolution a human wrote beneath it, in first-seen order.
     *
     * <p>Only inside {@code ## Open Questions}: a `- resolution:` line elsewhere in the document is
     * prose that happens to look like one, and treating it as an answer would resolve a blocking
     * question nobody answered — which is the one failure this must not have, since an unresolved
     * blocking question is what stops an approval.
     */
    public static Map<Integer, String> resolutions(String designMarkdown) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (designMarkdown == null) {
            return out;
        }
        boolean inSection = false;
        Integer pending = null;
        for (String line : designMarkdown.split("\n", -1)) {
            if (line.startsWith("## ")) {
                inSection = line.strip().equals(SECTION);
                pending = null;
                continue;
            }
            if (!inSection) {
                continue;
            }
            Matcher question = QUESTION.matcher(line);
            if (question.matches()) {
                pending = Integer.valueOf(question.group(1));
                continue;
            }
            Matcher resolution = RESOLUTION.matcher(line);
            if (resolution.matches() && pending != null) {
                out.putIfAbsent(pending, resolution.group(1).strip());
                pending = null;
            } else if (!line.isBlank()) {
                // Any other line ends the question it followed. Without this a resolution written
                // two bullets later would attach to the wrong question, and attaching an answer to
                // the wrong blocking question is worse than not reading it at all.
                pending = null;
            }
        }
        return out;
    }
}
