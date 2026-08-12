package sdd.plan.impact;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;
import sdd.plan.spec.SpecRenderer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Impact stage B (design): ONE assistive planner call — spec + repo cards + deterministic
 * seeds in, {repo, role, covers, reason} out. Assistive means: any failure (endpoint down,
 * truncation, malformed JSON) degrades to a warning and analysis continues deterministic-only.
 * The model can propose repos; it can never veto deterministic ones or abort the run.
 */
public final class ModelSeeder {
    private static final ObjectMapper JSON = new ObjectMapper();
    // covers a full design-conforming card (<=450 tokens); prompt-budget cap, recorded deviation
    private static final int CARD_MD_CAP = 2000;
    static final String SYSTEM_PROMPT = """
            You select which repositories of a multi-repo estate a feature specification will \
            touch. You receive the spec, one summary card per repository, and deterministic \
            seed evidence. Return exactly ONE JSON object, no markdown fences:
            {"repos": [{"repo": string, "role": "primary"|"contributor",
                        "covers": [requirement ids like "R1"], "reason": string}, ...]}
            Rules:
            - Name only repositories from the provided inventory, using their exact names.
            - "primary" = implements requirements directly; "contributor" = needs changes to support them.
            - Base every selection on evidence in the spec or cards; do not speculate.
            - Prefer precision: an empty list is better than guessed repositories.
            """;

    public record ModelSeed(String repo, String role, List<String> covers, String reason) {
        public ModelSeed {
            covers = List.copyOf(covers);
        }
    }

    public record SeedingOutcome(List<ModelSeed> seeds, List<String> warnings) {
        public SeedingOutcome {
            seeds = List.copyOf(seeds);
            warnings = List.copyOf(warnings);
        }
    }

    private ModelSeeder() {
    }

    public static SeedingOutcome seed(Jdbi jdbi, NormalizedSpec spec, List<Seed> deterministicSeeds,
                                      List<Seed> candidates, ChatModel planner, String modelName,
                                      int maxTokens) {
        String input = composeInput(jdbi, spec, deterministicSeeds, candidates);
        ChatResponse response;
        try {
            response = planner.complete(new ChatRequest(modelName,
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(input)),
                    List.of(), maxTokens, 0.15));
        } catch (ModelException e) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: " + e.getMessage()));
        }
        if ("length".equals(response.finishReason())) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: response truncated (finish_reason=length)"));
        }
        return parse(jdbi, spec, response.message().content());
    }

    private static SeedingOutcome parse(Jdbi jdbi, NormalizedSpec spec, String content) {
        if (content == null) {
            return new SeedingOutcome(List.of(), List.of("model seeding unavailable: empty response"));
        }
        String stripped = content.strip();
        if (stripped.startsWith("```")) {
            int firstNewline = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                stripped = stripped.substring(firstNewline + 1, lastFence).strip();
            }
        }
        JsonNode root;
        try {
            root = JSON.readTree(stripped);
        } catch (JacksonException e) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: response is not valid JSON"));
        }
        if (!root.isObject()) {
            return new SeedingOutcome(List.of(),
                    List.of("model seeding unavailable: response is not a JSON object"));
        }
        Set<String> knownRepos = new LinkedHashSet<>(jdbi.withHandle(h ->
                h.createQuery("SELECT name FROM repo ORDER BY name").mapTo(String.class).list()));
        Set<String> requirementIds = new LinkedHashSet<>(
                spec.requirements().stream().map(SpecItem::id).toList());
        List<ModelSeed> seeds = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (JsonNode node : root.path("repos")) {
            String repo = node.path("repo").asText().strip();
            if (!knownRepos.contains(repo)) {
                warnings.add("model named unknown repo '" + repo + "' — ignored");
                continue;
            }
            List<String> covers = new ArrayList<>();
            for (JsonNode cover : node.path("covers")) {
                String id = cover.asText().strip();
                if (requirementIds.contains(id)) {
                    covers.add(id);
                } else if (!id.isBlank()) {
                    warnings.add("model claimed " + repo + " covers unknown requirement '" + id + "' — ignored");
                }
            }
            String role = "primary".equals(node.path("role").asText()) ? "primary" : "contributor";
            seeds.add(new ModelSeed(repo, role, covers, node.path("reason").asText().strip()));
        }
        return new SeedingOutcome(seeds, warnings);
    }

    static String composeInput(Jdbi jdbi, NormalizedSpec spec, List<Seed> deterministicSeeds,
                               List<Seed> candidates) {
        StringBuilder input = new StringBuilder("# Specification\n\n");
        input.append(SpecRenderer.render(spec));
        input.append("\n# Repository inventory\n\n");
        List<Map<String, Object>> repos = jdbi.withHandle(h -> h.createQuery("""
                        SELECT r.name AS name, r.kind AS kind, c.card_line AS line, c.card_md AS md
                        FROM repo r LEFT JOIN repo_card c ON c.repo_id = r.id
                        ORDER BY r.name""")
                .mapToMap().list());
        for (Map<String, Object> repo : repos) {
            input.append("## ").append(repo.get("name")).append(" (").append(repo.get("kind")).append(")");
            if (repo.get("line") != null) {
                input.append(": ").append(repo.get("line"));
            }
            input.append('\n');
            if (repo.get("md") != null) {
                String md = String.valueOf(repo.get("md"));
                input.append(md.length() > CARD_MD_CAP ? md.substring(0, CARD_MD_CAP) : md).append('\n');
            }
        }
        input.append("\n# Deterministic seed evidence\n\n");
        for (Seed seed : deterministicSeeds) {
            input.append("- seed ").append(seed.repo()).append(" — ")
                    .append(seed.source()).append(' ').append(seed.detail()).append('\n');
        }
        for (Seed candidate : candidates) {
            input.append("- candidate ").append(candidate.repo()).append(" — ")
                    .append(candidate.source()).append(' ').append(candidate.detail()).append('\n');
        }
        return input.toString();
    }
}
