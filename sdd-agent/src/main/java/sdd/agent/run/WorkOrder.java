package sdd.agent.run;

import org.jdbi.v3.core.Jdbi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the lean, KB-grounded work order (design B2): a fresh 35B coder needs the sub-spec, the
 * contracts it must honor, and a short ranked file manifest — not the whole repo. Deterministic
 * given the KB and the step.
 */
public final class WorkOrder {
    static final int MAX_MANIFEST_FILES = 24;
    static final int MAX_CARD_CHARS = 2000;
    /** Manifest line separator. Shared with the test so the em dash (U+2014) stays byte-identical. */
    public static final String SEP = " — ";

    private WorkOrder() {
    }

    public static String build(Jdbi jdbi, RepoStep step) {
        StringBuilder wo = new StringBuilder();
        wo.append("# Task for repo: ").append(step.repo()).append("\n\n");
        wo.append("## Sub-spec\n").append(step.subSpec().strip()).append("\n\n");

        if (!step.requirements().isEmpty()) {
            wo.append("## Requirements you are implementing\n");
            for (String requirement : step.requirements()) {
                wo.append("- ").append(requirement).append('\n');
            }
            wo.append('\n');
        }
        appendContracts(wo, "Provides (you MUST expose these)", step.provides());
        appendContracts(wo, "Consumes (you may rely on these)", step.consumes());

        wo.append("## Repo card\n");
        String card = repoCard(jdbi, step.repo());
        wo.append(card == null ? "(no repo card)" : card).append("\n\n");

        wo.append("## Files most likely relevant\n");
        for (String line : manifest(jdbi, step)) {
            wo.append("- ").append(line).append('\n');
        }
        wo.append('\n');

        if (!step.acceptanceChecks().isEmpty()) {
            wo.append("## Acceptance checks (a human will confirm these — not run locally)\n");
            for (String check : step.acceptanceChecks()) {
                wo.append("- ").append(check).append('\n');
            }
            wo.append('\n');
        }
        wo.append("""
                ## How to work
                Read the relevant files, make focused edits with apply_edit, and run_gradle to \
                check your work (compileJava, then test). Do not add unrelated changes. When the \
                sub-spec is implemented and compiles, call done with result=success and a short \
                summary. If you cannot proceed, call done with result=blocked and explain why.
                """);
        return wo.toString();
    }

    private static void appendContracts(StringBuilder wo, String title, List<ContractRef> contracts) {
        if (contracts.isEmpty()) {
            return;
        }
        wo.append("## ").append(title).append('\n');
        for (ContractRef contract : contracts) {
            wo.append("### ").append(contract.id()).append(" (").append(contract.kind()).append(")\n");
            wo.append(contract.body().strip()).append("\n\n");
        }
    }

    private static String repoCard(Jdbi jdbi, String repo) {
        // No ORDER BY needed: repo_card.repo_id is the PRIMARY KEY, so at most one row matches
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT c.card_md FROM repo_card c
                        JOIN repo r ON r.id = c.repo_id
                        WHERE r.name = :r""")
                .bind("r", repo).mapTo(String.class).findOne()
                .map(md -> md.length() > MAX_CARD_CHARS ? md.substring(0, MAX_CARD_CHARS) : md)
                .orElse(null));
    }

    private static List<String> manifest(Jdbi jdbi, RepoStep step) {
        Map<String, String> ranked = new LinkedHashMap<>();   // path -> reason, insertion order = rank

        // seeds: the step's named files (resolved) + is_api type files
        List<Map<String, Object>> types = jdbi.withHandle(h -> h.createQuery("""
                        SELECT t.file_path AS path, t.is_api AS is_api
                        FROM java_type t
                        JOIN module m ON m.id = t.module_id
                        JOIN repo r ON r.id = m.repo_id
                        WHERE r.name = :r AND t.file_path IS NOT NULL
                        ORDER BY t.is_api DESC, t.file_path""")
                .bind("r", step.repo()).mapToMap().list());
        for (Map<String, Object> row : types) {
            String path = String.valueOf(row.get("path"));
            boolean seed = step.files().stream().anyMatch(f -> path.equals(f) || path.endsWith("/" + f));
            if (seed) {
                ranked.putIfAbsent(path, "seed");
            }
        }
        for (Map<String, Object> row : types) {
            String path = String.valueOf(row.get("path"));
            boolean api = ((Number) row.get("is_api")).intValue() == 1;
            boolean test = isTest(path);
            if (!ranked.containsKey(path) && api && !test) {
                ranked.putIfAbsent(path, "api surface");
            }
        }
        // 1-hop expansion over file_ref from the seeds
        List<String> seeds = new ArrayList<>(ranked.keySet());
        List<Map<String, Object>> refs = jdbi.withHandle(h -> h.createQuery("""
                        SELECT fr.dst_file AS dst, fr.ref_count AS n
                        FROM file_ref fr JOIN repo r ON r.id = fr.repo_id
                        WHERE r.name = :r AND fr.src_file IN (<seeds>)
                        ORDER BY fr.ref_count DESC, fr.dst_file""")
                .bind("r", step.repo()).bindList("seeds", seeds.isEmpty() ? List.of("") : seeds)
                .mapToMap().list());
        for (Map<String, Object> row : refs) {
            ranked.putIfAbsent(String.valueOf(row.get("dst")),
                    "referenced (" + ((Number) row.get("n")).intValue() + ")");
        }
        // matching tests
        for (Map<String, Object> row : types) {
            String path = String.valueOf(row.get("path"));
            if (!ranked.containsKey(path) && isTest(path)) {
                ranked.putIfAbsent(path, "test");
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : ranked.entrySet()) {
            if (lines.size() >= MAX_MANIFEST_FILES) {
                break;
            }
            lines.add(entry.getKey() + SEP + entry.getValue());
        }
        return lines;
    }

    private static boolean isTest(String path) {
        return path.contains("/test/") || path.endsWith("Test.java") || path.endsWith("IT.java");
    }
}
