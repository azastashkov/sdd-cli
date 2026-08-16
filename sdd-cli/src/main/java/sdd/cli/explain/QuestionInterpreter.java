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
import sdd.core.retrieve.Retriever;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    static final int SYMBOL_CANDIDATES = 20;

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
             "entities": [{"kind": "repo"|"endpoint"|"topic"|"class"|"symbol"|"artifact",
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

    public static RetrievalRequest interpret(Jdbi jdbi, Retriever retriever, String question, ChatModel model,
                                              String modelName, int maxTokens) {
        ChatResponse response;
        try {
            response = model.complete(new ChatRequest(modelName,
                    List.of(ChatMessage.system(SYSTEM_PROMPT), ChatMessage.user(composeUserMessage(jdbi, retriever, question))),
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
     * KB's repo, topic and endpoint names — names only, never {@code repo_card} text — gives the
     * model a vocabulary to be exact about without letting it judge relevance; the anti-invention
     * rule in {@link #SYSTEM_PROMPT} still governs, so this is an aid, not permission to invent.
     * Capped so a large estate cannot blow up the prompt; validation downstream is unaffected
     * either way since every name still has to survive {@link KbEntities#resolve}.
     *
     * <p>Repos, topics and endpoints are exhaustive lists — small and enumerable for any estate
     * this tool targets. Types are not: a large estate can have thousands, so listing them all
     * would either blow up the prompt or force an arbitrary cut that silently hides whichever
     * names didn't make it. Instead the {@code symbols matching the question} section runs the
     * same deterministic {@link Retriever} full-text search the {@code SEARCH} intent's fallback
     * uses, scoped to this one question, and offers its top {@link #SYMBOL_CANDIDATES} hits as
     * candidate names — question-scoped rather than exhaustive, so it scales. This is still no
     * model in the loop: the search runs before call 1, deterministically, and only narrates
     * candidates the model may or may not use.
     *
     * <p>Labelled honestly as search hits that <strong>may be irrelevant</strong> rather than as
     * confirmed matches: a full-text hit ranks by term overlap, not by whether the question is
     * actually about that symbol, and presenting the top hit as if it were the answer is exactly
     * how a fluent, confident, wrong answer gets anchored onto the wrong entity. The model still
     * has to judge relevance and still has to name an identifier exactly — this list only makes
     * the exact spelling available; the anti-invention rule in {@link #SYSTEM_PROMPT} decides
     * whether it should be used at all.
     *
     * <p><strong>Doc-only hits are deliberately not marked here</strong>, unlike in
     * {@link SearchFacts}, and the asymmetry is a decision rather than an oversight. Since the
     * corpus gained type javadoc, {@code Hit.docOnly()} distinguishes a candidate found only
     * through unverified prose from one found by name, and this is the third consumer of
     * {@link Retriever#search} to have to rule on it. Marking would be actively worse in this one
     * place: the whole list is already labelled as hits that <em>may be irrelevant</em>, and
     * tagging some entries as weaker evidence implies the untagged ones are confirmed — reviving
     * exactly the anchoring the caveat exists to prevent. What a marker would buy elsewhere is
     * provenance for a claim, and no claim is made here: this list only supplies spellings, and
     * whatever the model names still has to survive {@link KbEntities#resolve} against the
     * structural tables, so a stale doc comment can surface a candidate but can never put an
     * entity into the answer that the KB does not independently hold. Provenance is carried where
     * it reaches a reader — {@link SearchFacts} labels the hit, and {@code AnswerNarrator}'s
     * system prompt has a rule for that label.
     */
    private static String composeUserMessage(Jdbi jdbi, Retriever retriever, String question) {
        List<String> symbolLabels = retriever.search(question, SYMBOL_CANDIDATES).stream()
                .map(hit -> hit.identifier() + " (" + hit.fqcn() + ")")
                .toList();
        return "Known repos: " + namesLine(KbEntities.repoNames(jdbi)) + "\n"
                + "Known topics: " + namesLine(KbEntities.topicNames(jdbi)) + "\n"
                + "Known endpoints: " + namesLine(KbEntities.endpointLabels(jdbi)) + "\n"
                + "Symbols matching the question (full-text search hits, best match first — these "
                + "may be irrelevant; name one as an entity only if the question is actually about "
                + "it): " + namesLine(symbolLabels) + "\n\n"
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
            case "symbol" -> EntityKind.SYMBOL;
            case "artifact" -> EntityKind.ARTIFACT;
            // Silent by design elsewhere, but the caller turns null into a visible note. A kind
            // added to EntityKind and forgotten here would drop every reference to it without
            // saying so — this switch is on a STRING, so the compiler cannot catch the omission.
            default -> null;
        };
    }

    /**
     * Used when the model is unavailable or returned garbage. Does only literal matching — never
     * infers an intent from keywords, since that would be inference presented as interpretation.
     * Emits {@link Intent#DESCRIBE} when it names at least one entity (so the deterministic
     * fetch describes each one), {@link Intent#SEARCH} otherwise — the same zero-entities
     * downgrade the model path applies, applied uniformly. Search terms are always populated
     * from the question's significant words, but that does <strong>not</strong> mean a full-text
     * search runs alongside whatever entities were found: {@link EvidenceCollector#collect}
     * dispatches purely on {@link RetrievalRequest#intent()}, and only {@link Intent#SEARCH}
     * ever reads {@link RetrievalRequest#searchTerms()} ({@code SearchFacts.of}) — a
     * {@link Intent#DESCRIBE} result (the common case here, once an entity is named) routes to
     * {@code RepoFacts.of} alone, which never looks at them. The terms are populated regardless
     * of intent purely so they are available if a caller ever does downgrade to search (e.g. the
     * zero-entities case above), not because they are consulted on every path.
     *
     * <p>Public (rather than package-private) because Task 8's {@code ExplainCommand} calls it
     * directly for the one failure {@link #interpret} cannot itself catch: never having had a
     * {@code ChatModel} to call in the first place (missing {@code sdd.yml}, missing
     * {@code models.planner}, or a deferred {@code api_key} failure surfacing at
     * {@code HttpChatModel} construction time).
     */
    public static RetrievalRequest fallback(Jdbi jdbi, String question, String reason) {
        List<String> notes = new ArrayList<>(List.of(
                "interpreter unavailable: " + reason
                        + " — showing the facts about the entities named in your question"));
        List<EntityRef> entities = new ArrayList<>();

        for (String repo : KbEntities.repoNames(jdbi)) {
            if (Mentions.wholeWord(question, repo)) {
                entities.add(new EntityRef(EntityKind.REPO, repo, false));
            }
        }
        for (String topic : KbEntities.topicNames(jdbi)) {
            if (Mentions.wholeWord(question, topic)) {
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
            String word = wordMatcher.group().toLowerCase(Locale.ROOT);
            if (!searchTerms.contains(word)) {
                searchTerms.add(word);
            }
        }

        Intent intent = entities.isEmpty() ? Intent.SEARCH : Intent.DESCRIBE;
        return new RetrievalRequest(intent, entities, searchTerms, question, notes, true);
    }
}
