package sdd.plan.approve;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict parser for the Gate-1 plan.md — pins PlanMdRenderer's exact layout plus the one
 * human-only extension: "  - resolution: <text>" under a question. Structure errors carry
 * 1-based line numbers; semantics belong to PlanValidator.
 */
public final class PlanMdParser {
    static final List<String> SECTIONS = List.of("Summary", "Open Questions", "Affected Repos",
            "Excluded Candidates", "Execution Order", "Interface Contracts", "Repo Steps",
            "Generation Notes");
    private static final Pattern QUESTION = Pattern.compile("- Q(\\d+)( \\[blocking])?: (.+)");
    private static final Pattern RESOLUTION = Pattern.compile("  - resolution: (.+)");
    private static final Pattern PLAIN = Pattern.compile("- (.+)");

    private PlanMdParser() {
    }

    public static PlanDocument parse(String markdown) {
        List<String> lines = markdown.lines().toList();
        if (lines.size() < 4 || !lines.get(0).equals("---")) {
            throw new PlanParseException(1, "plan must start with '---' front matter");
        }
        if (!lines.get(1).startsWith("spec: ") || lines.get(1).length() <= 6) {
            throw new PlanParseException(2, "expected 'spec: <id>'");
        }
        String specId = lines.get(1).substring(6).strip();
        Matcher version = Pattern.compile("plan_version: (\\d+)").matcher(lines.get(2));
        if (!version.matches()) {
            throw new PlanParseException(3, "expected 'plan_version: <n>'");
        }
        int planVersion = Integer.parseInt(version.group(1));
        if (!lines.get(3).equals("---")) {
            throw new PlanParseException(4, "front matter must close with '---'");
        }

        // split into sections, enforcing renderer order
        Builder b = new Builder(specId, planVersion);
        String section = null;
        int sectionIdx = -1;
        List<String> body = new ArrayList<>();
        int bodyStart = 5;
        boolean inFence = false;
        for (int i = 4; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (!inFence && line.startsWith("## ")) {
                dispatch(b, section, body, bodyStart);
                String name = line.substring(3);
                int idx = SECTIONS.indexOf(name);
                if (idx < 0) {
                    throw new PlanParseException(lineNo, "unknown section '## " + name + "'");
                }
                if (idx <= sectionIdx) {
                    throw new PlanParseException(lineNo,
                            "section '" + name + "' is duplicated or out of order");
                }
                sectionIdx = idx;
                section = name;
                body = new ArrayList<>();
                bodyStart = lineNo + 1;
                b.seen.add(name);
            } else if (section == null) {
                if (!line.isBlank()) {
                    throw new PlanParseException(lineNo, "content before the first section");
                }
            } else {
                body.add(line);
                // Between an exact ```yaml or ```contract open and its exact ``` close, heading
                // detection is suspended: those lines belong to the current section's (contract)
                // body, even if a drafted line happens to start with "## ".
                if (!inFence && (line.equals("```yaml") || line.equals("```contract"))) {
                    inFence = true;
                } else if (inFence && line.equals("```")) {
                    inFence = false;
                }
            }
        }
        dispatch(b, section, body, bodyStart);
        for (String required : SECTIONS) {
            if (!b.seen.contains(required)) {
                throw new PlanParseException(lines.size(),
                        "missing required section '## " + required + "'");
            }
        }
        return new PlanDocument(b.specId, b.planVersion, b.summary, b.questions, b.affected,
                b.excluded, b.order, b.contracts, b.steps, b.notes);
    }

    private static void dispatch(Builder b, String section, List<String> body, int startLine) {
        if (section == null) {
            return;
        }
        switch (section) {
            case "Summary" -> b.summary = String.join("\n", body).strip();
            case "Open Questions" -> questions(b, body, startLine);
            case "Generation Notes" -> b.notes = plainBullets(body, startLine, "Generation Notes");
            case "Affected Repos" -> Sections.affected(b, body, startLine);
            case "Excluded Candidates" -> Sections.excluded(b, body, startLine);
            case "Execution Order" -> Sections.order(b, body, startLine);
            case "Interface Contracts" -> Sections.contracts(b, body, startLine);
            case "Repo Steps" -> Sections.steps(b, body, startLine);
            default -> throw new IllegalStateException(section);
        }
    }

    private static void questions(Builder b, List<String> body, int startLine) {
        PlanDocument.PlanQuestion pending = null;
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            int lineNo = startLine + i;
            if (line.isBlank() || line.equals("- none")) {
                continue;
            }
            Matcher resolution = RESOLUTION.matcher(line);
            if (resolution.matches()) {
                if (pending == null) {
                    throw new PlanParseException(lineNo, "resolution without a question");
                }
                b.questions.add(new PlanDocument.PlanQuestion(pending.number(),
                        pending.blocking(), pending.text(), resolution.group(1).strip()));
                pending = null;
                continue;
            }
            if (pending != null) {
                b.questions.add(pending);
                pending = null;
            }
            Matcher question = QUESTION.matcher(line);
            if (!question.matches()) {
                throw new PlanParseException(lineNo,
                        "Open Questions items must look like '- Q1 [blocking]: <text>'");
            }
            pending = new PlanDocument.PlanQuestion(Integer.parseInt(question.group(1)),
                    question.group(2) != null, question.group(3).strip(), null);
        }
        if (pending != null) {
            b.questions.add(pending);
        }
    }

    static List<String> plainBullets(List<String> body, int startLine, String section) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank() || line.equals("- none")) {
                continue;
            }
            Matcher m = PLAIN.matcher(line);
            if (!m.matches()) {
                throw new PlanParseException(startLine + i,
                        section + " items must look like '- <text>'");
            }
            values.add(m.group(1));
        }
        return values;
    }

    static final class Builder {
        final String specId;
        final int planVersion;
        final java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String summary = "";
        final List<PlanDocument.PlanQuestion> questions = new ArrayList<>();
        List<PlanDocument.PlanRepo> affected = new ArrayList<>();
        List<PlanDocument.PlanExcluded> excluded = new ArrayList<>();
        List<List<String>> order = new ArrayList<>();
        List<PlanDocument.PlanContract> contracts = new ArrayList<>();
        List<PlanDocument.PlanStep> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Builder(String specId, int planVersion) {
            this.specId = specId;
            this.planVersion = planVersion;
        }
    }
}
