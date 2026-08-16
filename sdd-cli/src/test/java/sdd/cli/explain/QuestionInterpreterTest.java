package sdd.cli.explain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.kb.EntityKind;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatRequest;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.retrieve.FtsRetriever;
import sdd.core.retrieve.FtsSymbolWriter;
import sdd.core.retrieve.Retriever;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionInterpreterTest {
    @TempDir Path ws;
    private Database db;
    private Retriever retriever;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        ExplainFixture.seed(db.jdbi());
        retriever = new FtsRetriever(db.jdbi());
    }

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
    }

    private RetrievalRequest interpret(String question, String content, String finish) {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response(content, finish)));
        return QuestionInterpreter.interpret(db.jdbi(), retriever, question, model, "m", 512);
    }

    private static String userMessageOf(ChatRequest request) {
        return request.messages().stream()
                .filter(m -> "user".equals(m.role())).findFirst().orElseThrow().content();
    }

    // --- each intent parses -------------------------------------------------------------

    @Test
    void describeIntentParses() {
        RetrievalRequest r = interpret("what is lib-core", """
                {"intent":"describe","restatement":"What is lib-core?",
                 "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.DESCRIBE);
        assertThat(r.entities()).containsExactly(new EntityRef(EntityKind.REPO, "lib-core", false));
        assertThat(r.restatement()).isEqualTo("What is lib-core?");
        assertThat(r.notes()).isEmpty();
        assertThat(r.modelUnavailable()).isFalse();
    }

    @Test
    void consumersIntentParses() {
        RetrievalRequest r = interpret("what consumes lib-api", """
                {"intent":"consumers","restatement":"What consumes lib-api?",
                 "entities":[{"kind":"repo","value":"lib-api"}],"search_terms":[]}""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.CONSUMERS);
        assertThat(r.entities()).containsExactly(new EntityRef(EntityKind.REPO, "lib-api", false));
    }

    @Test
    void dependencyPathIntentParsesWithSubjectAndObjectRoles() {
        RetrievalRequest r = interpret("why does svc-orders depend on lib-api", """
                {"intent":"dependency_path","restatement":"Why does svc-orders depend on lib-api?",
                 "entities":[{"kind":"repo","value":"svc-orders","role":"subject"},
                             {"kind":"repo","value":"lib-api","role":"object"}],"search_terms":[]}""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.DEPENDENCY_PATH);
        assertThat(r.entities()).containsExactly(
                new EntityRef(EntityKind.REPO, "svc-orders", false),
                new EntityRef(EntityKind.REPO, "lib-api", true));
    }

    @Test
    void impactIntentParses() {
        RetrievalRequest r = interpret("what breaks if lib-core changes", """
                {"intent":"impact","restatement":"What breaks if lib-core changes?",
                 "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.IMPACT);
        assertThat(r.entities()).containsExactly(new EntityRef(EntityKind.REPO, "lib-core", false));
    }

    @Test
    void searchIntentParsesWithTermsAndNoEntities() {
        RetrievalRequest r = interpret("tell me about pricing", """
                {"intent":"search","restatement":"Tell me about pricing.",
                 "entities":[],"search_terms":["pricing","tier"]}""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.SEARCH);
        assertThat(r.entities()).isEmpty();
        assertThat(r.searchTerms()).containsExactly("pricing", "tier");
    }

    // --- validation table -----------------------------------------------------------------

    @Test
    void unknownIntentCoercesToSearchWithNote() {
        RetrievalRequest r = interpret("what is this", """
                {"intent":"frobnicate","restatement":"?","entities":[],"search_terms":["x"]}""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.SEARCH);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("frobnicate"));
    }

    @Test
    void unknownKindIsDroppedWithNote() {
        RetrievalRequest r = interpret("what is lib-core", """
                {"intent":"describe","restatement":"?",
                 "entities":[{"kind":"database","value":"lib-core"}],"search_terms":[]}""", "stop");

        assertThat(r.entities()).isEmpty();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("database"));
        // zero surviving entities on a describe question downgrades to search
        assertThat(r.intent()).isEqualTo(Intent.SEARCH);
    }

    @Test
    void entityResolvingToZeroReposIsDroppedWithMissReason() {
        RetrievalRequest r = interpret("what is ghost-repo", """
                {"intent":"describe","restatement":"?",
                 "entities":[{"kind":"repo","value":"ghost-repo"}],"search_terms":[]}""", "stop");

        assertThat(r.entities()).isEmpty();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("ghost-repo")
                .contains(sdd.core.kb.KbEntities.missReason(EntityKind.REPO)));
        assertThat(r.intent()).isEqualTo(Intent.SEARCH);
    }

    @Test
    void dependencyPathMissingObjectDowngradesToConsumers() {
        RetrievalRequest r = interpret("why does svc-orders depend on ghost-repo", """
                {"intent":"dependency_path","restatement":"?",
                 "entities":[{"kind":"repo","value":"svc-orders","role":"subject"},
                             {"kind":"repo","value":"ghost-repo","role":"object"}],"search_terms":[]}""", "stop");

        assertThat(r.entities()).containsExactly(new EntityRef(EntityKind.REPO, "svc-orders", false));
        assertThat(r.intent()).isEqualTo(Intent.CONSUMERS);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("ghost-repo"));
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("dependency_path").contains("consumers"));
    }

    @Test
    void dependencyPathMissingBothSidesDowngradesToSearch() {
        RetrievalRequest r = interpret("why does ghost-a depend on ghost-b", """
                {"intent":"dependency_path","restatement":"?",
                 "entities":[{"kind":"repo","value":"ghost-a","role":"subject"},
                             {"kind":"repo","value":"ghost-b","role":"object"}],"search_terms":["ghost"]}""", "stop");

        assertThat(r.entities()).isEmpty();
        assertThat(r.intent()).isEqualTo(Intent.SEARCH);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("dependency_path").contains("search"));
    }

    @Test
    void describeConsumersImpactWithZeroEntitiesDowngradeToSearch() {
        for (String intent : List.of("describe", "consumers", "impact")) {
            RetrievalRequest r = interpret("question", """
                    {"intent":"%s","restatement":"?","entities":[],"search_terms":["x"]}"""
                    .formatted(intent), "stop");

            assertThat(r.intent()).as("intent %s with zero entities", intent).isEqualTo(Intent.SEARCH);
            assertThat(r.notes()).as("intent %s notes", intent)
                    .anySatisfy(n -> assertThat(n).contains(intent).contains("search"));
        }
    }

    @Test
    void moreThanFourEntitiesAreTruncated() {
        String entities = """
                {"kind":"repo","value":"lib-core"},{"kind":"repo","value":"lib-api"},
                {"kind":"repo","value":"svc-orders"},{"kind":"repo","value":"svc-billing"},
                {"kind":"repo","value":"svc-notify"},{"kind":"repo","value":"platform"}""";
        RetrievalRequest r = interpret("describe everything", ("""
                {"intent":"search","restatement":"?","entities":[%s],"search_terms":[]}"""
                .formatted(entities)), "stop");

        assertThat(r.entities()).hasSize(4);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("truncated"));
    }

    @Test
    void moreThanEightSearchTermsAreTruncated() {
        String terms = "\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\"";
        RetrievalRequest r = interpret("search a lot", ("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[%s]}"""
                .formatted(terms)), "stop");

        assertThat(r.searchTerms()).hasSize(8);
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("truncated"));
    }

    @Test
    void dependencyPathTruncationKeepsBothRolesEvenWhenObjectSortsLast() {
        // Five valid subject-role entities plus the object-role entity last: a naive first-4
        // truncation would drop the only object, shipping DEPENDENCY_PATH with no object.
        RetrievalRequest r = interpret("why does svc-orders depend on platform", """
                {"intent":"dependency_path","restatement":"?",
                 "entities":[{"kind":"repo","value":"svc-orders","role":"subject"},
                             {"kind":"repo","value":"lib-api","role":"subject"},
                             {"kind":"repo","value":"svc-billing","role":"subject"},
                             {"kind":"repo","value":"svc-notify","role":"subject"},
                             {"kind":"repo","value":"platform","role":"object"}],"search_terms":[]}""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.DEPENDENCY_PATH);
        assertThat(r.entities()).hasSize(4);
        assertThat(r.entities()).anySatisfy(e -> assertThat(e.object()).isFalse());
        assertThat(r.entities()).anySatisfy(e -> {
            assertThat(e.object()).isTrue();
            assertThat(e.value()).isEqualTo("platform");
        });
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("truncated"));
    }

    // --- fence handling ---------------------------------------------------------------------

    @Test
    void fencedJsonResponseIsUnwrapped() {
        RetrievalRequest r = interpret("what is lib-core", """
                ```json
                {"intent":"describe","restatement":"What is lib-core?",
                 "entities":[{"kind":"repo","value":"lib-core"}],"search_terms":[]}
                ```""", "stop");

        assertThat(r.intent()).isEqualTo(Intent.DESCRIBE);
        assertThat(r.entities()).containsExactly(new EntityRef(EntityKind.REPO, "lib-core", false));
        assertThat(r.modelUnavailable()).isFalse();
    }

    // --- model-unavailable paths, each with a distinct reason --------------------------------

    @Test
    void modelExceptionProducesFallback() {
        ChatModel refusing = req -> {
            throw new ModelException("connection refused", 0);
        };
        RetrievalRequest r = QuestionInterpreter.interpret(db.jdbi(), retriever, "what is lib-core", refusing, "m", 512);

        assertThat(r.modelUnavailable()).isTrue();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("interpreter unavailable")
                .contains("connection refused"));
    }

    @Test
    void truncatedResponseProducesFallback() {
        RetrievalRequest r = interpret("what is lib-core", "{", "length");

        assertThat(r.modelUnavailable()).isTrue();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("interpreter unavailable")
                .contains("length"));
    }

    @Test
    void nullContentProducesFallback() {
        RetrievalRequest r = interpret("what is lib-core", null, "stop");

        assertThat(r.modelUnavailable()).isTrue();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("interpreter unavailable")
                .contains("empty response"));
    }

    @Test
    void nonJsonResponseProducesFallback() {
        RetrievalRequest r = interpret("what is lib-core", "not json at all", "stop");

        assertThat(r.modelUnavailable()).isTrue();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("interpreter unavailable")
                .contains("not valid JSON"));
    }

    @Test
    void jsonArrayResponseProducesFallback() {
        RetrievalRequest r = interpret("what is lib-core", "[1,2,3]", "stop");

        assertThat(r.modelUnavailable()).isTrue();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("interpreter unavailable")
                .contains("not a JSON object"));
    }

    @Test
    void theFiveUnavailableReasonsAreDistinct() {
        ChatModel refusing = req -> {
            throw new ModelException("boom", 0);
        };
        String modelError = QuestionInterpreter.interpret(db.jdbi(), retriever, "q", refusing, "m", 512).notes().get(0);
        String truncated = interpret("q", "{", "length").notes().get(0);
        String nullContent = interpret("q", null, "stop").notes().get(0);
        String nonJson = interpret("q", "garbage", "stop").notes().get(0);
        String jsonArray = interpret("q", "[]", "stop").notes().get(0);

        assertThat(List.of(modelError, truncated, nullContent, nonJson, jsonArray)).doesNotHaveDuplicates();
    }

    // --- deterministic fallback: literal matching only, never inferring an intent ------------

    @Test
    void fallbackFindsLiteralRepoTopicFqcnAndEndpoint() {
        RetrievalRequest r = QuestionInterpreter.fallback(db.jdbi(),
                "does svc-orders' com.acme.api.PriceApi and GET /orders/{id} relate to orders.events",
                "test reason");

        assertThat(r.modelUnavailable()).isTrue();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("interpreter unavailable")
                .contains("test reason"));
        assertThat(r.entities()).containsExactlyInAnyOrder(
                new EntityRef(EntityKind.REPO, ExplainFixture.SVC_ORDERS, false),
                new EntityRef(EntityKind.TOPIC, ExplainFixture.ORDERS_TOPIC, false),
                new EntityRef(EntityKind.CLASS, ExplainFixture.PRICE_API_FQCN, false),
                new EntityRef(EntityKind.ENDPOINT, ExplainFixture.ORDERS_ENDPOINT, false));
        assertThat(r.intent()).isEqualTo(Intent.DESCRIBE);
    }

    @Test
    void fallbackWithNoLiteralMatchesDowngradesToSearch() {
        RetrievalRequest r = QuestionInterpreter.fallback(db.jdbi(), "what is going on here", "test reason");

        assertThat(r.entities()).isEmpty();
        assertThat(r.intent()).isEqualTo(Intent.SEARCH);
    }

    @Test
    void fallbackNeverInfersImpactFromTheWordImpact() {
        RetrievalRequest r = QuestionInterpreter.fallback(db.jdbi(),
                "what would be the impact of changing lib-core", "test reason");

        assertThat(r.intent()).isNotEqualTo(Intent.IMPACT);
        // lib-core is a literal repo mention, so this resolves as a describe (or search if the
        // guess were somehow wrong) — the point of this test is solely the isNotEqualTo above.
        assertThat(r.entities()).contains(new EntityRef(EntityKind.REPO, ExplainFixture.LIB_CORE, false));
        assertThat(r.intent()).isEqualTo(Intent.DESCRIBE);
    }

    @Test
    void fallbackSearchTermsLowercaseWithLocaleRootNotTheJvmDefault() {
        // Under a Turkish default locale, String.toLowerCase() (no Locale argument) maps 'I' to
        // the dotless 'ı' rather than 'i' -- EntityKind.label() and SearchFacts both pass
        // Locale.ROOT for exactly this reason; QuestionInterpreter's
        // search-term extraction did not.
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
        try {
            RetrievalRequest r = QuestionInterpreter.fallback(db.jdbi(), "what is the TIER here", "test reason");

            assertThat(r.searchTerms()).contains("tier");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    // --- the sent request carries the question and the system prompt -------------------------

    @Test
    void sentRequestCarriesQuestionAndSystemPrompt() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        QuestionInterpreter.interpret(db.jdbi(), retriever, "what is lib-core used for", model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req -> {
            assertThat(req.messages()).anySatisfy(m ->
                    assertThat(m.content()).isEqualTo(QuestionInterpreter.SYSTEM_PROMPT));
            assertThat(req.messages()).anySatisfy(m ->
                    assertThat(m.content()).contains("what is lib-core used for"));
            assertThat(req.maxTokens()).isEqualTo(512);
        });
    }

    @Test
    void sentRequestCarriesKbRepoAndTopicVocabulary() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        QuestionInterpreter.interpret(db.jdbi(), retriever, "what is going on", model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req ->
                assertThat(req.messages()).anySatisfy(m -> assertThat(m.content())
                        .contains(ExplainFixture.SVC_ORDERS)
                        .contains(ExplainFixture.ORDERS_TOPIC)));
    }

    @Test
    void vocabularyCapMarkerAppearsForLargeEstates() {
        db.jdbi().useHandle(h -> {
            for (int i = 0; i < 250; i++) {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('extra-repo-" + i + "','/w/extra" + i + "','SERVICE')");
            }
        });
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        QuestionInterpreter.interpret(db.jdbi(), retriever, "what is going on", model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req ->
                assertThat(req.messages()).anySatisfy(m -> assertThat(m.content()).contains("more)")));
    }

    // --- question-scoped vocabulary: endpoints and FTS-backed symbol candidates --------------

    /** Seeds a class discoverable only through the FTS candidate vocabulary, never through a
     *  literal mention -- {@code fts_symbol} plus the {@code java_type} row {@link
     *  sdd.core.kb.KbEntities#resolveClass} actually resolves against. */
    private void seedTierResolver() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (2,'com.acme.pricing.TierResolver','CLASS')");
            FtsSymbolWriter.insert(h, 2, "TierResolver", "com.acme.pricing.TierResolver", "");
        });
    }

    @Test
    void callOneUserMessageContainsFtsCandidatesForTheQuestion() {
        seedTierResolver();
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        // "TierResolver" never appears verbatim -- only the constituent word "tier" does.
        QuestionInterpreter.interpret(db.jdbi(), retriever, "what handles tier resolution logic", model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req ->
                assertThat(userMessageOf(req)).contains("TierResolver (com.acme.pricing.TierResolver)"));
    }

    @Test
    void callOneUserMessageListsKnownEndpointsWithAnyForNullHttpMethod() {
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path) "
                        + "VALUES (4,'HealthController','ping',NULL,'/health','/health')"));
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        QuestionInterpreter.interpret(db.jdbi(), retriever, "what is going on", model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req ->
                assertThat(userMessageOf(req)).contains("Known endpoints:")
                        .contains("GET /orders/{}")   // the fixture's http_method IS 'GET'
                        .contains("ANY /health"));     // http_method IS NULL renders ANY
    }

    // --- the regression this feature exists to fix -------------------------------------------

    @Test
    void descriptiveQuestionResolvesViaCandidateVocabularyInsteadOfDowngradingToSearch() {
        seedTierResolver();
        // A stand-in for a real model: it can only name TierResolver exactly once the prompt has
        // actually told it the name exists (the candidate-symbol vocabulary this feature adds).
        // Without that, it can only guess a paraphrase -- exactly what produced the live defect
        // ("what consumes the tier resolver API?"): KbEntities.resolveClass cannot match "tier
        // resolver API", so the entity drops and CONSUMERS has nothing left to downgrade to but
        // SEARCH. This is genuinely conditioned on the prompt content, not a hardcoded answer.
        ChatModel conditionallyInformed = req -> {
            String value = userMessageOf(req).contains("TierResolver") ? "TierResolver" : "tier resolver API";
            return response("""
                    {"intent":"consumers","restatement":"What consumes the tier resolver API?",
                     "entities":[{"kind":"class","value":"%s"}],"search_terms":["tier resolver"]}"""
                    .formatted(value), "stop");
        };

        RetrievalRequest r = QuestionInterpreter.interpret(db.jdbi(), retriever,
                "what consumes the tier resolver API?", conditionallyInformed, "m", 512);

        assertThat(r.intent()).isEqualTo(Intent.CONSUMERS);
        assertThat(r.entities()).containsExactly(
                new EntityRef(EntityKind.CLASS, "TierResolver", false));
        assertThat(r.notes()).isEmpty();
    }

    @Test
    void determinismSameKbAndQuestionProduceByteIdenticalUserMessages() {
        seedTierResolver();
        ScriptedChatModel model = new ScriptedChatModel(List.of(
                response("""
                        {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop"),
                response("""
                        {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        QuestionInterpreter.interpret(db.jdbi(), retriever, "what handles tier resolution", model, "m", 512);
        QuestionInterpreter.interpret(db.jdbi(), retriever, "what handles tier resolution", model, "m", 512);

        List<ChatRequest> requests = model.requests();
        assertThat(requests).hasSize(2);
        assertThat(userMessageOf(requests.get(1))).isEqualTo(userMessageOf(requests.get(0)));
    }

    @Test
    void emptyFtsResultsRenderNoneAndDoNotCrash() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        // No token in this question matches either fixture symbol (PriceApi, OrdersController).
        QuestionInterpreter.interpret(db.jdbi(), retriever, "zzz-nonexistent-term-xyz-unmatched", model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req ->
                assertThat(userMessageOf(req))
                        .contains("only if the question is actually about it): (none)"));
    }

    @Test
    void candidateSymbolListIsCappedAtSymbolCandidates() {
        db.jdbi().useHandle(h -> {
            for (int i = 0; i < 25; i++) {
                String fqcn = "com.acme.pricing.TierResolver" + i;
                h.execute("INSERT INTO java_type(module_id, fqcn, kind) VALUES (2,'" + fqcn + "','CLASS')");
                FtsSymbolWriter.insert(h, 2, "TierResolver" + i, fqcn, "");
            }
        });
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        QuestionInterpreter.interpret(db.jdbi(), retriever, "tier resolver lookup", model, "m", 512);

        String content = userMessageOf(model.requests().get(0));
        long candidateCount = Pattern.compile("com\\.acme\\.pricing\\.TierResolver\\d+")
                .matcher(content).results().count();
        assertThat(candidateCount).isEqualTo(20);   // SYMBOL_CANDIDATES, of 25 seeded matches
    }

    @Test
    void candidateEntityThatStillDoesNotResolveIsStillDroppedWithMissReason() {
        // fts_symbol alone is not resolution -- no java_type row means KbEntities.resolveClass
        // still finds nothing. The vocabulary is an aid to spelling, not a bypass around resolve().
        db.jdbi().useHandle(h -> FtsSymbolWriter.insert(h, 2, "GhostType", "com.acme.ghost.GhostType", ""));
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"describe","restatement":"?",
                 "entities":[{"kind":"class","value":"GhostType"}],"search_terms":[]}""", "stop")));

        RetrievalRequest r = QuestionInterpreter.interpret(db.jdbi(), retriever,
                "what is the ghost type here", model, "m", 512);

        assertThat(r.entities()).isEmpty();
        assertThat(r.notes()).anySatisfy(n -> assertThat(n).contains("GhostType")
                .contains(sdd.core.kb.KbEntities.missReason(EntityKind.CLASS)));
        assertThat(r.intent()).isEqualTo(Intent.SEARCH);
    }

    @Test
    void everyEntityKindTheInterpreterCanEmitIsMappedToItsEnum() {
        // kindOf switches on a STRING, so a kind added to EntityKind and forgotten there compiles
        // fine and silently drops every reference to it. This walks the enum instead of a list, so
        // the next kind added cannot pass without being mapped.
        db.jdbi().useHandle(ExplainFixture::seedNpm);
        java.util.List<String> unmapped = new java.util.ArrayList<>();
        for (EntityKind kind : EntityKind.values()) {
            String word = kind.label();
            RetrievalRequest r = interpret("q", """
                    {"intent":"describe","entities":[{"kind":"%s","value":"x","role":"subject"}],
                     "search_terms":[],"restatement":"r"}""".formatted(word), "stop");
            if (r.notes().stream().anyMatch(n -> n.contains("unknown kind '" + word + "'"))) {
                unmapped.add(word);
            }
        }
        assertThat(unmapped).isEmpty();
    }

    @Test
    void aKindTheInterpreterDoesNotKnowIsNotedRatherThanDroppedSilently() {
        RetrievalRequest r = interpret("q", """
                {"intent":"describe","entities":[{"kind":"module","value":"x","role":"subject"}],
                 "search_terms":[],"restatement":"r"}""", "stop");

        assertThat(r.entities()).isEmpty();
        assertThat(r.notes()).anySatisfy(note ->
                assertThat(note).contains("unknown kind 'module'"));
    }
}
