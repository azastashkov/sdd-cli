package sdd.cli.explain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.kb.EntityKind;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatModel;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.ModelException;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionInterpreterTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        ExplainFixture.seed(db.jdbi());
    }

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
    }

    private RetrievalRequest interpret(String question, String content, String finish) {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response(content, finish)));
        return QuestionInterpreter.interpret(db.jdbi(), question, model, "m", 512);
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
        RetrievalRequest r = QuestionInterpreter.interpret(db.jdbi(), "what is lib-core", refusing, "m", 512);

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
    void theFourUnavailableReasonsAreDistinct() {
        String truncated = interpret("q", "{", "length").notes().get(0);
        String nullContent = interpret("q", null, "stop").notes().get(0);
        String nonJson = interpret("q", "garbage", "stop").notes().get(0);
        String jsonArray = interpret("q", "[]", "stop").notes().get(0);

        assertThat(List.of(truncated, nullContent, nonJson, jsonArray)).doesNotHaveDuplicates();
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

    // --- the sent request carries the question and the system prompt -------------------------

    @Test
    void sentRequestCarriesQuestionAndSystemPrompt() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(response("""
                {"intent":"search","restatement":"?","entities":[],"search_terms":[]}""", "stop")));

        QuestionInterpreter.interpret(db.jdbi(), "what is lib-core used for", model, "m", 512);

        assertThat(model.requests()).singleElement().satisfies(req -> {
            assertThat(req.messages()).anySatisfy(m ->
                    assertThat(m.content()).isEqualTo(QuestionInterpreter.SYSTEM_PROMPT));
            assertThat(req.messages()).anySatisfy(m ->
                    assertThat(m.content()).contains("what is lib-core used for"));
            assertThat(req.maxTokens()).isEqualTo(512);
        });
    }
}
