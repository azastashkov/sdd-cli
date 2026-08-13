package sdd.index.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class RepoCardGenerator {
    public record CardResult(int generated, int cached, int failed, List<String> failures) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CONSECUTIVE_MODEL_FAILURES = 3;
    private static final int MAX_README_LINES = 150;
    private static final int MAX_ENDPOINTS = 50;
    private static final int MAX_KEY_FILE_CHARS = 12000;
    private static final String SYSTEM_PROMPT =
            "You summarize Java repositories for an engineering knowledge base. Describe ONLY what "
            + "is evidenced in the provided data. No speculation, no filler. Respond with a single "
            + "JSON object: {\"card_line\": string (one sentence, max 30 words), \"card_md\": string "
            + "(markdown, max 300 words, sections: Purpose, Modules, Integrations, Conventions)}";

    private RepoCardGenerator() {}

    public static CardResult generate(Jdbi jdbi, Path workspace, ChatModel model, String modelName) {
        List<Map<String, Object>> repos = jdbi.withHandle(h ->
                h.createQuery("SELECT id, name FROM repo ORDER BY name").mapToMap().list());
        int generated = 0;
        int cached = 0;
        int failed = 0;
        int consecutiveModelFailures = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> repo : repos) {
            long repoId = ((Number) repo.get("id")).longValue();
            String name = (String) repo.get("name");
            String input = composeInput(jdbi, repoId, name, workspace);
            String hash = sha256(input + "\n" + modelName);
            boolean upToDate = jdbi.withHandle(h -> h.createQuery(
                            "SELECT count(*) FROM repo_card WHERE repo_id=:r AND input_hash=:h")
                    .bind("r", repoId).bind("h", hash).mapTo(Integer.class).one()) > 0;
            if (upToDate) {
                cached++;
                continue;
            }
            if (consecutiveModelFailures >= MAX_CONSECUTIVE_MODEL_FAILURES) {
                failed++;
                failures.add(name + ": skipped after " + MAX_CONSECUTIVE_MODEL_FAILURES
                        + " consecutive model failures");
                continue;
            }
            try {
                ChatResponse response = model.complete(new ChatRequest(modelName,
                        List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
                        List.of(), 1200, 0.15));
                consecutiveModelFailures = 0;
                if ("length".equals(response.finishReason())) {
                    failed++;
                    failures.add(name + ": finish_reason=length (thinking model? set extra_body "
                            + "chat_template_kwargs.enable_thinking=false)");
                    continue;
                }
                JsonNode parsed = parseCard(response.message().content());
                if (parsed == null) {
                    failed++;
                    failures.add(name + ": unparseable card JSON");
                    continue;
                }
                jdbi.useHandle(h -> h.createUpdate("""
                                INSERT INTO repo_card(repo_id, card_md, card_line, model, input_hash, created_at)
                                VALUES (:r, :md, :line, :model, :hash, :at)
                                ON CONFLICT(repo_id) DO UPDATE SET card_md=excluded.card_md,
                                  card_line=excluded.card_line, model=excluded.model,
                                  input_hash=excluded.input_hash, created_at=excluded.created_at""")
                        .bind("r", repoId).bind("md", parsed.get("card_md").asText())
                        .bind("line", parsed.get("card_line").asText()).bind("model", modelName)
                        .bind("hash", hash).bind("at", Instant.now().toString()).execute());
                generated++;
            } catch (ModelException e) {
                consecutiveModelFailures++;
                failed++;
                failures.add(name + ": model error: " + e.getMessage());
            }
        }
        return new CardResult(generated, cached, failed, List.copyOf(failures));
    }

    private static JsonNode parseCard(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node.hasNonNull("card_line") && node.hasNonNull("card_md")
                    && !node.get("card_line").asText().isBlank()
                    && !node.get("card_md").asText().isBlank()) {
                return node;
            }
        } catch (Exception ignored) {
            // malformed
        }
        return null;
    }

    static String composeInput(Jdbi jdbi, long repoId, String repoName, Path workspace) {
        return jdbi.withHandle(h -> {
            StringBuilder sb = new StringBuilder();

            String kind = h.createQuery("SELECT kind FROM repo WHERE id = :r")
                    .bind("r", repoId).mapTo(String.class).one();
            sb.append("Repo: ").append(repoName).append(" (").append(kind).append(")\n\n");

            appendModules(sb, h, repoId);
            appendEndpoints(sb, h, repoId);
            appendTopics(sb, h, repoId);
            appendDeps(sb, h, repoId);
            appendReadme(sb, workspace, repoName);
            appendKeyFiles(sb, h, workspace, repoName, repoId);

            return sb.toString();
        });
    }

    private static void appendModules(StringBuilder sb, Handle h, long repoId) {
        List<Map<String, Object>> modules = h.createQuery(
                        "SELECT gradle_path, kind FROM module WHERE repo_id = :r ORDER BY gradle_path")
                .bind("r", repoId).mapToMap().list();
        sb.append("Modules:\n");
        if (modules.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (Map<String, Object> m : modules) {
                sb.append("- ").append(m.get("gradle_path")).append(" (").append(m.get("kind")).append(")\n");
            }
        }
        sb.append('\n');
    }

    private static void appendEndpoints(StringBuilder sb, Handle h, long repoId) {
        List<Map<String, Object>> endpoints = h.createQuery("""
                        SELECT re.http_method AS http_method, re.norm_path AS norm_path
                        FROM rest_endpoint re JOIN module m ON m.id = re.module_id
                        WHERE m.repo_id = :r
                        ORDER BY re.norm_path, re.http_method""")
                .bind("r", repoId).mapToMap().list();
        sb.append("Endpoints:\n");
        if (endpoints.isEmpty()) {
            sb.append("(none)\n");
        } else {
            int shown = Math.min(MAX_ENDPOINTS, endpoints.size());
            for (int i = 0; i < shown; i++) {
                Map<String, Object> e = endpoints.get(i);
                sb.append(e.get("http_method")).append(' ').append(e.get("norm_path")).append('\n');
            }
            if (endpoints.size() > MAX_ENDPOINTS) {
                sb.append("+").append(endpoints.size() - MAX_ENDPOINTS).append(" more\n");
            }
        }
        sb.append('\n');
    }

    private static void appendTopics(StringBuilder sb, Handle h, long repoId) {
        List<Map<String, Object>> topics = h.createQuery("""
                        SELECT t.name AS name, kr.role AS role
                        FROM kafka_role kr
                        JOIN kafka_topic t ON t.id = kr.topic_id
                        JOIN module m ON m.id = kr.module_id
                        WHERE m.repo_id = :r
                        ORDER BY t.name, kr.role""")
                .bind("r", repoId).mapToMap().list();
        sb.append("Topics:\n");
        if (topics.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (Map<String, Object> t : topics) {
                sb.append(t.get("name")).append(" (").append(t.get("role")).append(")\n");
            }
        }
        sb.append('\n');
    }

    private static void appendDeps(StringBuilder sb, Handle h, long repoId) {
        List<String> depsOut = h.createQuery("""
                        SELECT DISTINCT r2.name AS name
                        FROM v_repo_dep_edge e JOIN repo r2 ON r2.id = e.to_repo_id
                        WHERE e.from_repo_id = :r
                        ORDER BY r2.name""")
                .bind("r", repoId).mapTo(String.class).list();
        List<String> depsIn = h.createQuery("""
                        SELECT DISTINCT r2.name AS name
                        FROM v_repo_dep_edge e JOIN repo r2 ON r2.id = e.from_repo_id
                        WHERE e.to_repo_id = :r
                        ORDER BY r2.name""")
                .bind("r", repoId).mapTo(String.class).list();
        sb.append("Depends on: ").append(depsOut.isEmpty() ? "(none)" : String.join(", ", depsOut)).append('\n');
        sb.append("Depended on by: ").append(depsIn.isEmpty() ? "(none)" : String.join(", ", depsIn)).append('\n');
        sb.append('\n');
    }

    private static void appendReadme(StringBuilder sb, Path workspace, String repoName) {
        Path readme = workspace.resolve(repoName).resolve("README.md");
        sb.append("README:\n");
        if (Files.isRegularFile(readme)) {
            try {
                List<String> lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
                int limit = Math.min(MAX_README_LINES, lines.size());
                for (int i = 0; i < limit; i++) {
                    sb.append(lines.get(i)).append('\n');
                }
            } catch (IOException e) {
                sb.append("(unreadable)\n");
            }
        } else {
            sb.append("(none)\n");
        }
        sb.append('\n');
    }

    private static void appendKeyFiles(StringBuilder sb, Handle h, Path workspace, String repoName, long repoId) {
        List<String> keyFilePaths = keyFilePaths(h, repoId);
        sb.append("Key Files:\n");
        if (keyFilePaths.isEmpty()) {
            sb.append("(none)\n");
            return;
        }
        for (String filePath : keyFilePaths) {
            appendKeyFile(sb, workspace, repoName, filePath);
        }
    }

    private static List<String> keyFilePaths(Handle h, long repoId) {
        List<String> paths = new ArrayList<>();
        String springBootFile = h.createQuery("""
                        SELECT file_path FROM java_type jt JOIN module m ON m.id = jt.module_id
                        WHERE m.repo_id = :r AND jt.annotations LIKE '%SpringBootApplication%'
                        ORDER BY file_path LIMIT 1""")
                .bind("r", repoId).mapTo(String.class).findOne().orElse(null);
        if (springBootFile != null) {
            paths.add(springBootFile);
        }
        List<Map<String, Object>> inbound = h.createQuery("""
                        SELECT jt.file_path AS file_path, COUNT(*) AS refs
                        FROM api_usage u
                        JOIN java_type jt ON jt.fqcn = u.target_fqcn
                        JOIN module m ON m.id = jt.module_id
                        WHERE m.repo_id = :r AND u.target_module_id IS NOT NULL
                          AND jt.file_path IS NOT NULL
                        GROUP BY jt.file_path
                        ORDER BY refs DESC, jt.file_path
                        LIMIT 2""")
                .bind("r", repoId).mapToMap().list();
        for (Map<String, Object> row : inbound) {
            paths.add((String) row.get("file_path"));
        }
        return paths.stream().distinct().sorted().toList();
    }

    private static void appendKeyFile(StringBuilder sb, Path workspace, String repoName, String filePath) {
        Path file = workspace.resolve(repoName).resolve(filePath);
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // unreadable -> skip silently
        }
        sb.append("--- ").append(filePath).append(" ---\n");
        if (content.length() > MAX_KEY_FILE_CHARS) {
            sb.append(content, 0, MAX_KEY_FILE_CHARS).append("\n[truncated]\n");
        } else {
            sb.append(content).append('\n');
        }
        sb.append('\n');
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
