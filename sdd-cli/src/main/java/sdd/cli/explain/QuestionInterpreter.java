package sdd.cli.explain;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import sdd.core.kb.EntityKind;
import sdd.core.kb.KbEntities;
import sdd.core.kb.Resolution;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpret half of {@code sdd explain}'s interpret -> deterministic fetch -> narrate shape: one
 * model call turns a free-text question into a {@link RetrievalRequest} whose every field has
 * already been validated against the KB. This class never supplies a fact and never chooses how
 * to query — the deterministic collectors (Tasks 4-5) do that. Validation mirrors
 * {@code ModelSeeder.parse} / {@code PlanDrafter.parse}: every rejection is appended to
 * {@link RetrievalRequest#notes()} as a human-readable sentence, never dropped silently.
 */
public final class QuestionInterpreter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ENTITIES = 4;
    private static final int MAX_TERMS = 8;
    private static final int MAX_VOCAB_NAMES = 200;

    private static final Pattern DOTTED_IDENTIFIER =
            Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+\\b");
    private static final Pattern ENDPOINT_SHAPE =
            Pattern.compile("\\b([A-Z]{2,8})\\s+(/[\\w{}/.\\-]*)");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}");

    static final String SYSTEM_PROMPT = """
            You interpret a free-text question about a multi-repo software estate stored in a \
            knowledge base. You do not answer the question and you know no facts about the \
            estate yourself — you only decide what kind of question it is and which named \
            things it refers to. Return exactly ONE JSON object, no markdown fences:
            {"intent": "describe"|"consumers"|"dependency_path"|"impact"|"search",
             "restatement": string,
             "entities": [{"kind": "repo"|"endpoint"|"topic"|"class"|"artifact",
                            "value": string, "role": "subject"|"object"}],
             "search_terms": [string, ...]}
            Rules:
            - "restatement" is your one-sentence paraphrase of the question, so a misreading is visible.
            - "describe" = what is this thing; "consumers" = what calls or depends on it; \
              "dependency_path" = why does A depend on or call B (role orients A as subject, B \
              as object); "impact" = what would break if this thing changes; "search" = none of \
              the above, or a general keyword lookup.
            - "role" is optional and defaults to "subject"; only "dependency_path" uses it.
            - Name entities exactly as they appear in the estate: repo names, topic names, fully \
              qualified class names, "VERB /path" for endpoints. Never invent or guess a name — \
              omit an entity you are not sure about instead.
            - "search_terms" are free keywords for a full-text fallback, not entity names.
            """;

    private QuestionInterpreter() {
    }

    public static RetrievalRequest interpret(Jdbi jdbi, String question, ChatModel model,
                                              String modelName, int maxTokens) {
        ChatResponse response;
        try {
            response = model.complete(new ChatRequest(modelName,
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(composeUserMessage(jdbi, question))),
                    List.of(), maxTokens, 0.15));
        } catch (ModelException e) {
            return fallback(jdbi, question, "model error: " + e.getMessage());
        }
        if ("length".equals(response.finishReason())) {
            return fallback(jdbi, question, "response truncated (finish_reason=length)");
        }
        return parse(jdbi, question, response.message().content());
    }

    /**
     * The system prompt commands "name entities exactly as they appear in the estate", which is
     * an unreasonable demand of a model that has never seen the estate's names. Handing over the
     * KB's repo and topic names — names only, never {@code repo_card} text — gives the model a
     * vocabulary to be exact about without letting it judge relevance; the anti-invention rule in
     * {@link #SYSTEM_PROMPT} still governs, so this is an aid, not permission to invent. Capped
     * so a large estate cannot blow up the prompt; validation downstream is unaffected either way
     * since every name still has to survive {@link KbEntities#resolve}.
     */
    private static String composeUserMessage(Jdbi jdbi, String question) {
        return "Known repos: " + namesLine(KbEntities.repoNames(jdbi)) + "\n"
                + "Known topics: " + namesLine(KbEntities.topicNames(jdbi)) + "\n\n"
                + "Question: " + question;
    }

    private static String namesLine(List<String> names) {
        if (names.isEmpty()) {
            return "(none)";
        }
        if (names.size() <= MAX_VOCAB_NAMES) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_VOCAB_NAMES))
                + " (+" + (names.size() - MAX_VOCAB_NAMES) + " more)";
    }

    private static RetrievalRequest parse(Jdbi jdbi, String question, String content) {
        if (content == null) {
            return fallback(jdbi, question, "empty response");
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
            return fallback(jdbi, question, "response is not valid JSON");
        }
        if (!root.isObject()) {
            return fallback(jdbi, question, "response is not a JSON object");
        }

        List<String> notes = new ArrayList<>();
        Intent intent = intentOf(root.path("intent").asText(""), notes);
        String restatement = root.path("restatement").asText("");

        List<EntityRef> entities = new ArrayList<>();
        for (JsonNode node : root.path("entities")) {
            String kindText = node.path("kind").asText("");
            EntityKind kind = kindOf(kindText);
            if (kind == null) {
                notes.add("model referenced entity with unknown kind '" + kindText + "' — dropped");
                continue;
            }
            String value = node.path("value").asText("").strip();
            Resolution resolution = KbEntities.resolve(jdbi, kind, value);
            if (resolution.isEmpty()) {
                notes.add("model referenced " + kindText + " '" + value + "' — "
                        + KbEntities.missReason(kind) + " — dropped");
                continue;
            }
            boolean object = "object".equals(node.path("role").asText("subject"));
            entities.add(new EntityRef(kind, value, object));
        }

        List<String> searchTerms = new ArrayList<>();
        for (JsonNode term : root.path("search_terms")) {
            String t = term.asText("").strip();
            if (!t.isBlank()) {
                searchTerms.add(t);
            }
        }

        // dependency_path needs a surviving subject AND a surviving object.
        intent = enforceDependencyPathInvariant(intent, entities, notes);
        // describe/consumers/impact with zero surviving entities have nothing to look up.
        if ((intent == Intent.DESCRIBE || intent == Intent.CONSUMERS || intent == Intent.IMPACT)
                && entities.isEmpty()) {
            notes.add("intent '" + intentText(intent) + "' needs at least one named entity; none "
                    + "survived validation — downgraded to search");
            intent = Intent.SEARCH;
        }

        entities = truncateEntities(intent, entities, notes);
        // Truncation can only shrink the list. For every other intent that is harmless, but a
        // DEPENDENCY_PATH result that just lost its only surviving object (or subject) to the
        // cap would ship claiming a role pair that isn't actually there — truncateEntities keeps
        // both roles when it can, but re-run the same check on the shipped list as a safety net
        // rather than trust that invariant blindly.
        intent = enforceDependencyPathInvariant(intent, entities, notes);
        if (searchTerms.size() > MAX_TERMS) {
            notes.add("model supplied " + searchTerms.size() + " search terms — truncated to " + MAX_TERMS);
            searchTerms = new ArrayList<>(searchTerms.subList(0, MAX_TERMS));
        }

        return new RetrievalRequest(intent, entities, searchTerms, restatement, notes, false);
    }

    private static Intent enforceDependencyPathInvariant(Intent intent, List<EntityRef> entities,
                                                          List<String> notes) {
        if (intent != Intent.DEPENDENCY_PATH) {
            return intent;
        }
        boolean hasSubject = entities.stream().anyMatch(e -> !e.object());
        boolean hasObject = entities.stream().anyMatch(EntityRef::object);
        if (hasSubject && hasObject) {
            return intent;
        }
        if (entities.isEmpty()) {
            notes.add("dependency_path needs two named entities; none survived validation"
                    + " — downgraded to search");
            return Intent.SEARCH;
        }
        notes.add("dependency_path needs both a subject and an object entity; only "
                + "one side survived validation — downgraded to consumers");
        return Intent.CONSUMERS;
    }

    /**
     * For every intent but DEPENDENCY_PATH, truncation is just "keep the first N". For
     * DEPENDENCY_PATH, the earlier {@link #enforceDependencyPathInvariant} call already proved
     * the pre-truncation list has both a subject and an object; a naive first-N cut can still
     * lose whichever role's only survivor sits at index &gt;= {@link #MAX_ENTITIES} (e.g. an
     * object named last among five entities), shipping a DEPENDENCY_PATH request with no object
     * — exactly the state that check exists to prevent. So for DEPENDENCY_PATH the first
     * surviving subject and first surviving object are kept unconditionally, and the remaining
     * slots are filled in original order.
     */
    private static List<EntityRef> truncateEntities(Intent intent, List<EntityRef> entities, List<String> notes) {
        if (entities.size() <= MAX_ENTITIES) {
            return entities;
        }
        notes.add("model named " + entities.size() + " entities — truncated to " + MAX_ENTITIES);
        if (intent != Intent.DEPENDENCY_PATH) {
            return new ArrayList<>(entities.subList(0, MAX_ENTITIES));
        }
        int subjectIdx = -1;
        int objectIdx = -1;
        for (int i = 0; i < entities.size(); i++) {
            EntityRef e = entities.get(i);
            if (subjectIdx < 0 && !e.object()) {
                subjectIdx = i;
            }
            if (objectIdx < 0 && e.object()) {
                objectIdx = i;
            }
        }
        LinkedHashSet<Integer> keep = new LinkedHashSet<>();
        if (subjectIdx >= 0) {
            keep.add(subjectIdx);
        }
        if (objectIdx >= 0) {
            keep.add(objectIdx);
        }
        for (int i = 0; i < entities.size() && keep.size() < MAX_ENTITIES; i++) {
            keep.add(i);
        }
        List<Integer> ordered = new ArrayList<>(keep);
        ordered.sort(null);
        List<EntityRef> truncated = new ArrayList<>();
        for (int idx : ordered) {
            truncated.add(entities.get(idx));
        }
        return truncated;
    }

    private static Intent intentOf(String raw, List<String> notes) {
        return switch (raw) {
            case "describe" -> Intent.DESCRIBE;
            case "consumers" -> Intent.CONSUMERS;
            case "dependency_path" -> Intent.DEPENDENCY_PATH;
            case "impact" -> Intent.IMPACT;
            case "search" -> Intent.SEARCH;
            default -> {
                notes.add("model requested unknown intent '" + raw + "' — coerced to search");
                yield Intent.SEARCH;
            }
        };
    }

    private static String intentText(Intent intent) {
        return switch (intent) {
            case DESCRIBE -> "describe";
            case CONSUMERS -> "consumers";
            case DEPENDENCY_PATH -> "dependency_path";
            case IMPACT -> "impact";
            case SEARCH -> "search";
        };
    }

    private static EntityKind kindOf(String raw) {
        return switch (raw) {
            case "repo" -> EntityKind.REPO;
            case "endpoint" -> EntityKind.ENDPOINT;
            case "topic" -> EntityKind.TOPIC;
            case "class" -> EntityKind.CLASS;
            case "artifact" -> EntityKind.ARTIFACT;
            default -> null;
        };
    }

    /**
     * Used when the model is unavailable or returned garbage. Does only literal matching — never
     * infers an intent from keywords, since that would be inference presented as interpretation.
     * Emits {@link Intent#DESCRIBE} when it names at least one entity (so the deterministic
     * fetch describes each one), {@link Intent#SEARCH} otherwise — the same zero-entities
     * downgrade the model path applies, applied uniformly. Search terms are always populated
     * from the question's significant words, so a full-text search still runs alongside whatever
     * entities were found.
     */
    static RetrievalRequest fallback(Jdbi jdbi, String question, String reason) {
        List<String> notes = new ArrayList<>(List.of(
                "interpreter unavailable: " + reason
                        + " — showing the facts about the entities named in your question"));
        List<EntityRef> entities = new ArrayList<>();

        for (String repo : KbEntities.repoNames(jdbi)) {
            if (mentionsWholeWord(question, repo)) {
                entities.add(new EntityRef(EntityKind.REPO, repo, false));
            }
        }
        for (String topic : KbEntities.topicNames(jdbi)) {
            if (mentionsWholeWord(question, topic)) {
                entities.add(new EntityRef(EntityKind.TOPIC, topic, false));
            }
        }
        Matcher classMatcher = DOTTED_IDENTIFIER.matcher(question);
        while (classMatcher.find()) {
            String candidate = classMatcher.group();
            if (!KbEntities.resolve(jdbi, EntityKind.CLASS, candidate).isEmpty()) {
                entities.add(new EntityRef(EntityKind.CLASS, candidate, false));
            }
        }
        Matcher endpointMatcher = ENDPOINT_SHAPE.matcher(question);
        while (endpointMatcher.find()) {
            String candidate = endpointMatcher.group();
            if (!KbEntities.resolve(jdbi, EntityKind.ENDPOINT, candidate).isEmpty()) {
                entities.add(new EntityRef(EntityKind.ENDPOINT, candidate, false));
            }
        }
        if (entities.size() > MAX_ENTITIES) {
            notes.add("literal matching named " + entities.size() + " entities — truncated to " + MAX_ENTITIES);
            entities = new ArrayList<>(entities.subList(0, MAX_ENTITIES));
        }

        List<String> searchTerms = new ArrayList<>();
        Matcher wordMatcher = WORD.matcher(question);
        while (wordMatcher.find() && searchTerms.size() < MAX_TERMS) {
            String word = wordMatcher.group().toLowerCase();
            if (!searchTerms.contains(word)) {
                searchTerms.add(word);
            }
        }

        Intent intent = entities.isEmpty() ? Intent.SEARCH : Intent.DESCRIBE;
        return new RetrievalRequest(intent, entities, searchTerms, question, notes, true);
    }

    private static boolean mentionsWholeWord(String question, String candidate) {
        return Pattern.compile("\\b" + Pattern.quote(candidate) + "\\b").matcher(question).find();
    }
}
