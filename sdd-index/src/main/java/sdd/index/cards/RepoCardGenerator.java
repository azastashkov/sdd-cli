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
import sdd.core.progress.Progress;

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
            "You summarize source repositories for an engineering knowledge base. Describe ONLY what "
            + "is evidenced in the provided data. No speculation, no filler. Respond with a single "
            + "JSON object: {\"card_line\": string (one sentence, max 30 words), \"card_md\": string "
            + "(markdown, max 300 words, sections: Purpose, Modules, Integrations, Conventions)}";

    private RepoCardGenerator() {}

    /**
     * The cap the card request used before it became configurable. Kept as the default for callers
     * that do not supply one, so behaviour is unchanged where nothing was configured.
     */
    public static final int DEFAULT_CARD_MAX_TOKENS = 1200;

    public static CardResult generate(Jdbi jdbi, Path workspace, ChatModel model, String modelName) {
        return generate(jdbi, workspace, model, modelName, Progress.noOp());
    }

    /**
     * Same as {@link #generate(Jdbi, Path, ChatModel, String)}, with progress reporting threaded
     * through the per-repo loop. {@link Progress#start}/{@link Progress#finish} bracket only a
     * repo that actually reaches the model: the {@code upToDate} cache hit just below (this
     * method's common steady-state case — most repos' inputs are unchanged on most runs) and the
     * consecutive-failure circuit breaker a little further down both {@code continue} before
     * either call, on purpose, so neither shows up as work in a renderer. Every pre-existing
     * caller of the four-arg overload above (this class's own test, and {@code IndexService})
     * keeps getting exactly today's behaviour via {@link Progress#noOp()}.
     */
    public static CardResult generate(Jdbi jdbi, Path workspace, ChatModel model, String modelName,
            Progress progress) {
        return generate(jdbi, workspace, model, modelName, progress, DEFAULT_CARD_MAX_TOKENS);
    }

    /**
     * @param maxTokens the card endpoint's configured {@code max_tokens}. This used to be hardcoded
     *     at {@value #DEFAULT_CARD_MAX_TOKENS}, which made {@code models.<tier>.max_tokens} a
     *     dead lever for cards — and cards are exactly where a thinking model's reasoning overruns
     *     the budget before any content is emitted, so the one knob a reader would reach for did
     *     nothing.
     */
    public static CardResult generate(Jdbi jdbi, Path workspace, ChatModel model, String modelName,
            Progress progress, int maxTokens) {
        List<Map<String, Object>> repos = jdbi.withHandle(h ->
                h.createQuery("SELECT id, name FROM repo ORDER BY name").mapToMap().list());
        progress.phase("cards", repos.size());
        int generated = 0;
        int cached = 0;
        int failed = 0;
        int consecutiveModelFailures = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> repo : repos) {
            long repoId = ((Number) repo.get("id")).longValue();
            String name = (String) repo.get("name");
            String input = composeInput(jdbi, repoId, name, workspace);
            // The system prompt is part of the input in every sense that matters: change it and the
            // model is being asked a different question, so a cached card no longer answers the
            // question that would be asked now. Leaving it out made a prompt-only change a
            // permanent no-op for every repo that already had a card — the fix would ship, and
            // nothing would ever regenerate to use it.
            String hash = sha256(input + "\n" + modelName + "\n" + SYSTEM_PROMPT);
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
            progress.start(name);
            try {
                ChatResponse response = model.complete(new ChatRequest(modelName,
                        List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
                        List.of(), maxTokens, 0.15));
                if ("length".equals(response.finishReason())) {
                    // Counted as a model failure rather than reset, so the breaker below trips.
                    // Truncation on every repo is ONE misconfiguration, and the first failure
                    // already carries the whole diagnosis; re-learning it once per repo cost 53
                    // live calls on the real estate before anything said so.
                    consecutiveModelFailures++;
                    failed++;
                    failures.add(name + ": finish_reason=length — the request asked for "
                            + maxTokens + " tokens. If this is a reasoning model, raise this tier's "
                            + "max_tokens; some models also accept extra_body "
                            + "chat_template_kwargs.enable_thinking=false, but others (GigaChat) "
                            + "have no off switch and spend the budget on reasoning regardless");
                    continue;
                }
                consecutiveModelFailures = 0;
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
            } finally {
                progress.finish(name);
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

            Map<String, Object> repo = h.createQuery(
                            "SELECT kind, build_system FROM repo WHERE id = :r")
                    .bind("r", repoId).mapToMap().one();
            String kind = String.valueOf(repo.get("kind"));
            Object buildSystem = repo.get("build_system");
            // The ecosystem is stated because it changes what everything else in this summary
            // means — "modules" are Gradle projects or npm packages, and a model told nothing will
            // describe whichever it assumes.
            sb.append("Repo: ").append(repoName).append(" (").append(kind);
            if (buildSystem != null) {
                sb.append(", ").append(String.valueOf(buildSystem).toLowerCase(java.util.Locale.ROOT));
            }
            sb.append(")\n\n");

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
        // Every signal below is read out of java_type, which an npm repo has none of, so without
        // this such a repo contributes no key file at all. Its package.json is the equivalent: it
        // names the package, its entry points and its dependencies in a few lines.
        boolean npm = h.createQuery("SELECT build_system FROM repo WHERE id = :r")
                .bind("r", repoId).mapTo(String.class).findOne()
                .map("NPM"::equals).orElse(false);
        if (npm) {
            paths.add("package.json");
        }
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
