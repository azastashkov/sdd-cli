package sdd.plan.approve;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Section B handlers for PlanMdParser — pin the renderer's exact row grammar. */
final class Sections {
    private static final Pattern ORDER_LINE = Pattern.compile("(\\d+)\\. (.+?)( \\(co-scheduled\\))?");
    private static final Pattern CONTRACT_HEAD =
            // Every contract kind must appear here or a plan declaring one is simply unparseable,
            // with an error that points at the heading rather than at the missing alternative.
            Pattern.compile("### (.+?) \\((java-api|rest|kafka|ts-api|rest-client|stream-descriptor)"
                    + "(?:, (binary-compatible|type-compatible))?\\) — (\\S+)(?: -> (.+))?");
    private static final Pattern STEP_SCALAR = Pattern.compile("- (covers|version_action|provides|consumes): (.+)");

    private Sections() {
    }

    static void affected(PlanMdParser.Builder b, List<String> body, int startLine) {
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lineNo = startLine + i;
            if (!line.startsWith("- ")) {
                throw new PlanParseException(lineNo, "Affected Repos rows must look like "
                        + "'- <repo> — <role>/<annotation> — covers: <ids> — why: <text>'");
            }
            String[] parts = line.substring(2).split(" — ", 4);
            if (parts.length != 4 || !parts[2].startsWith("covers: ") || !parts[3].startsWith("why: ")
                    || parts[1].indexOf('/') < 0) {
                throw new PlanParseException(lineNo, "Affected Repos rows must look like "
                        + "'- <repo> — <role>/<annotation> — covers: <ids> — why: <text>'");
            }
            int slash = parts[1].indexOf('/');
            b.affected.add(new PlanDocument.PlanRepo(parts[0].strip(),
                    parts[1].substring(0, slash), parts[1].substring(slash + 1),
                    csv(parts[2].substring(8)), parts[3].substring(5)));
        }
    }

    static void excluded(PlanMdParser.Builder b, List<String> body, int startLine) {
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank() || line.equals("- none")) {
                continue;
            }
            int lineNo = startLine + i;
            int sep = line.indexOf(" — ");
            if (!line.startsWith("- ") || sep < 0) {
                throw new PlanParseException(lineNo,
                        "Excluded Candidates rows must look like '- <repo> — <detail>'");
            }
            b.excluded.add(new PlanDocument.PlanExcluded(line.substring(2, sep).strip(),
                    line.substring(sep + 3)));
        }
    }

    static void order(PlanMdParser.Builder b, List<String> body, int startLine) {
        int expected = 1;
        for (int i = 0; i < body.size(); i++) {
            String line = body.get(i);
            if (line.isBlank()) {
                continue;
            }
            int lineNo = startLine + i;
            Matcher m = ORDER_LINE.matcher(line);
            if (!m.matches()) {
                throw new PlanParseException(lineNo,
                        "Execution Order lines must look like '<n>. <repo>[ + <repo>] [(co-scheduled)]'");
            }
            if (Integer.parseInt(m.group(1)) != expected) {
                throw new PlanParseException(lineNo,
                        "Execution Order must be numbered sequentially (expected " + expected + ")");
            }
            expected++;
            b.order.add(List.of(m.group(2).split(" \\+ ")));
        }
    }

    static void contracts(PlanMdParser.Builder b, List<String> body, int startLine) {
        int i = 0;
        while (i < body.size()) {
            String line = body.get(i);
            int lineNo = startLine + i;
            if (line.isBlank() || line.equals("- none") || line.equals("- none (drafting unavailable)")) {
                i++;
                continue;
            }
            Matcher head = CONTRACT_HEAD.matcher(line);
            if (!head.matches()) {
                throw new PlanParseException(lineNo, "contract headings must look like "
                        + "'### <id> (<kind>[, binary-compatible]) — <provider> -> <consumers>'");
            }
            i++;
            if (i >= body.size() || !body.get(i).equals("```yaml")) {
                throw new PlanParseException(startLine + i, "contract body must open with '```yaml'");
            }
            i++;
            List<String> bodyLines = new ArrayList<>();
            while (i < body.size() && !body.get(i).equals("```")) {
                bodyLines.add(body.get(i));
                i++;
            }
            if (i >= body.size()) {
                throw new PlanParseException(startLine + i - 1, "contract body fence is never closed");
            }
            i++;
            // An optional second fence — "declared" contract members — follows immediately after
            // a blank line, exactly as the renderer emits it. Its absence (a pre-5C-1 plan.md, or
            // a contract with no declarations) means empty declarations, not an error.
            List<String> declared = new ArrayList<>();
            if (i < body.size() && body.get(i).isBlank() && i + 1 < body.size()
                    && body.get(i + 1).equals("```contract")) {
                i += 2;
                while (i < body.size() && !body.get(i).equals("```")) {
                    declared.add(body.get(i));
                    i++;
                }
                if (i >= body.size()) {
                    throw new PlanParseException(startLine + i - 1, "contract declared fence is never closed");
                }
                i++;
            }
            b.contracts.add(new PlanDocument.PlanContract(head.group(1), head.group(2),
                    head.group(4), head.group(5) == null ? List.of() : csv(head.group(5)),
                    String.join("\n", bodyLines), head.group(3), declared));
        }
    }

    static void steps(PlanMdParser.Builder b, List<String> body, int startLine) {
        int i = 0;
        while (i < body.size()) {
            String line = body.get(i);
            if (line.isBlank() || line.equals("- none") || line.equals("- none (drafting unavailable)")) {
                i++;
                continue;
            }
            if (!line.startsWith("### ")) {
                throw new PlanParseException(startLine + i, "Repo Steps must start with '### <repo>'");
            }
            String repo = line.substring(4).strip();
            i++;
            String[] scalars = new String[4];   // covers, version_action, provides, consumes
            List<String> keys = List.of("covers", "version_action", "provides", "consumes");
            for (int k = 0; k < 4; k++) {
                if (i >= body.size()) {
                    throw new PlanParseException(startLine + i,
                            "expected '- " + keys.get(k) + ": <value>'");
                }
                Matcher m = STEP_SCALAR.matcher(body.get(i));
                if (!m.matches() || !m.group(1).equals(keys.get(k))) {
                    throw new PlanParseException(startLine + i,
                            "expected '- " + keys.get(k) + ": <value>'");
                }
                scalars[k] = m.group(2);
                i++;
            }
            List<String> files = new ArrayList<>();
            List<String> verification = new ArrayList<>();
            i = sublist(body, i, "- files:", files);
            i = sublist(body, i, "- verification:", verification);
            List<String> prose = new ArrayList<>();
            while (i < body.size() && !body.get(i).startsWith("### ")) {
                prose.add(body.get(i));
                i++;
            }
            b.steps.add(new PlanDocument.PlanStep(repo, csv(scalars[0]), scalars[1],
                    csv(scalars[2]), csv(scalars[3]), files, verification,
                    String.join("\n", prose).strip()));
        }
    }

    private static int sublist(List<String> body, int i, String label, List<String> out) {
        if (i < body.size() && body.get(i).equals(label)) {
            i++;
            while (i < body.size() && body.get(i).startsWith("  - ")) {
                out.add(body.get(i).substring(4));
                i++;
            }
        }
        return i;
    }

    private static List<String> csv(String value) {
        String stripped = value.strip();
        if (stripped.equals("-") || stripped.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : stripped.split(",")) {
            if (!part.strip().isEmpty()) {
                values.add(part.strip());
            }
        }
        return values;
    }
}
