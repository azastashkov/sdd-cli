package sdd.plan.gen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.core.llm.ChatMessage;
import sdd.core.llm.ChatResponse;
import sdd.core.llm.Usage;
import sdd.core.testing.ScriptedChatModel;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanDrafterTest {
    @TempDir Path ws;
    private Database db;

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib-core','/w/1','LIBRARY')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc-pricing','/w/2','SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (1,'com.acme.LoyaltyTier','CLASS',1,'src/main/java/com/acme/LoyaltyTier.java')");
            h.execute("INSERT INTO api_member(type_id, name, signature, return_type) "
                    + "VALUES (1,'tierFor','tierFor(String)','Tier')");
            h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method, path_template, norm_path, request_type, response_type) "
                    + "VALUES (2,'PriceController','get','GET','/price/{sku}','/price/{}',NULL,'PriceResponse')");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "tier pricing")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ImpactResult impact() {
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("lib-core", "seed", "SEED", List.of("R1"), List.of("touchpoint class:LoyaltyTier")),
                        new AffectedRepo("svc-pricing", "dependent", "CODE_CHANGE_LIKELY", List.of(), List.of("depends on lib-core (PINNED)"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static List<ExecutionOrder.Unit> order() {
        return List.of(new ExecutionOrder.Unit(List.of("lib-core")),
                new ExecutionOrder.Unit(List.of("svc-pricing")));
    }

    private static ChatResponse response(String content, String finish) {
        return new ChatResponse(ChatMessage.assistant(content), finish, new Usage(1, 1));
    }

    private static final String GOOD_JSON = """
            {"summary": "Add tier lookups to lib-core and apply them in svc-pricing.",
             "questions": [{"text": "Which tiers exist?", "blocking": false}],
             "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                            "consumers": ["svc-pricing"],
                            "body": "class: com.acme.LoyaltyTier\\nmethod: Tier tierFor(String customerId)"}],
             "repo_steps": [
               {"repo": "lib-core", "covers": ["R1", "R9"], "sub_spec": "Add tierFor lookup.",
                "files": ["src/main/java/com/acme/LoyaltyTier.java"],
                "provides_contracts": ["C-1"], "consumes_contracts": [],
                "version_action": "minor", "verification": ["./gradlew test"]},
               {"repo": "svc-pricing", "covers": [], "sub_spec": "Apply tier spread.",
                "files": ["src/main/java/com/acme/Missing.java"],
                "provides_contracts": [], "consumes_contracts": ["C-1", "C-9"],
                "version_action": "shipit", "verification": ["./gradlew test"]},
               {"repo": "ghost-repo", "covers": [], "sub_spec": "x", "files": [],
                "provides_contracts": [], "consumes_contracts": [], "version_action": "none",
                "verification": []}]}""";

    @Test
    void promptCarriesSpecImpactOrderAndKbEvidence() {
        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact(),
                ExecutionOrder.order(db.jdbi(), impact()), "");

        assertThat(input).contains("- R1: tier pricing")
                .contains("- lib-core | seed | SEED | covers: R1 | why: touchpoint class:LoyaltyTier")
                .contains("1. lib-core").contains("2. svc-pricing")
                .contains("- com.acme.LoyaltyTier (CLASS) @ src/main/java/com/acme/LoyaltyTier.java")
                .contains("- com.acme.LoyaltyTier#tierFor(String): Tier")
                .contains("- GET /price/{} req=null res=PriceResponse");
    }

    /** An npm repo whose one export is recorded the way the indexer records it: {@code
     *  <specifier>.<Export>}, which is NOT how a ts-api declaration addresses it. */
    private ImpactResult seedTypeScriptRepo() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('web-sdk','/w/3','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3,':','LIBRARY')");
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path, language) "
                    + "VALUES (3,'@acme/web-sdk.Tick','INTERFACE',1,'src/types.ts','TYPESCRIPT')");
            h.execute("INSERT INTO api_member(type_id, name, signature, return_type) "
                    + "VALUES (2,'price','price','number')");
        });
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("web-sdk", "seed", "SEED", List.of("R1"),
                        List.of("touchpoint repo:web-sdk"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void aTypeScriptMemberIsRenderedInTheGrammarItsDeclarationMustUse() {
        ImpactResult impact = seedTypeScriptRepo();

        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact,
                ExecutionOrder.order(db.jdbi(), impact), "");

        // The java-api template transposes BOTH separators for TypeScript: the knowledge base
        // records `<specifier>.<Export>` while a ts-api declaration addresses `<specifier>#<Export>`,
        // so the one line the prompt tells a model to copy from cannot be copied — and the prompt
        // also tells it to omit rather than guess, which is exactly what it does.
        assertThat(input).contains("- @acme/web-sdk#Tick.price: number");
        assertThat(input).doesNotContain("@acme/web-sdk.Tick#price");
    }

    @Test
    void aTypeScriptTypeIsNamedByTheSpecifierAConsumerImports() {
        ImpactResult impact = seedTypeScriptRepo();

        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact,
                ExecutionOrder.order(db.jdbi(), impact), "");

        assertThat(input).contains("- @acme/web-sdk#Tick (INTERFACE) @ src/types.ts");
    }

    /** A repo with more surface than the evidence budget, where the type the spec is about sorts
     *  last. This is trading-web-sdk's shape: 260 api_members, so an alphabetical window ends in
     *  the C's and never reaches the type the plan is being written about. */
    private ImpactResult seedCrowdedRepo() {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('big-lib','/w/3','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (3,':','LIBRARY')");
            for (int i = 0; i < 40; i++) {
                String name = String.format("com.acme.Filler%02d", i);
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                        + "VALUES (3,?,'CLASS',1,'src/main/java/Filler.java')", name);
                h.execute("INSERT INTO api_member(type_id, name, signature, return_type) "
                        + "VALUES ((SELECT id FROM java_type WHERE fqcn = ?),'a','a()','void')", name);
                h.execute("INSERT INTO api_member(type_id, name, signature, return_type) "
                        + "VALUES ((SELECT id FROM java_type WHERE fqcn = ?),'b','b()','void')", name);
            }
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (3,'com.acme.ZebraTarget','CLASS',1,'src/main/java/ZebraTarget.java')");
            h.execute("INSERT INTO api_member(type_id, name, signature, return_type) VALUES "
                    + "((SELECT id FROM java_type WHERE fqcn = 'com.acme.ZebraTarget'),"
                    + "'target','target()','String')");
        });
        return new ImpactResult(List.of(),
                List.of(new AffectedRepo("big-lib", "seed", "SEED", List.of("R1"),
                        List.of("touchpoint class:ZebraTarget"))),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static NormalizedSpec specNaming(String term) {
        return new NormalizedSpec("S-2", "T", "o", "draft", "Change " + term + ".", "",
                List.of(new SpecItem("R1", "extend " + term + " with a field")),
                List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void evidenceLeadsWithWhatTheSpecNamesRatherThanWhatSortsFirst() {
        ImpactResult impact = seedCrowdedRepo();

        String input = PlanDrafter.composeInput(db.jdbi(), specNaming("ZebraTarget"), impact,
                ExecutionOrder.order(db.jdbi(), impact), "");

        // Without ranking the window is filled by Filler00..Filler19 and the one type the plan is
        // about never reaches the model — which is why ts-api contracts were drafted undeclared.
        assertThat(input).contains("com.acme.ZebraTarget (CLASS)");
        assertThat(input).contains("- com.acme.ZebraTarget#target(): String");
    }

    @Test
    void anUnnamedRepoStillGetsEvidenceInItsExistingOrder() {
        ImpactResult impact = seedCrowdedRepo();

        String input = PlanDrafter.composeInput(db.jdbi(), specNaming("NothingHere"), impact,
                ExecutionOrder.order(db.jdbi(), impact), "");

        // Nothing matches, so the budget is filled exactly as it was before ranking existed.
        assertThat(input).contains("com.acme.Filler00 (CLASS)");
    }

    @Test
    void validatesEveryUntrustedFieldWithNotes() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(), planner, "m", 4096);

        assertThat(draft.unavailable()).isFalse();
        assertThat(draft.summary()).startsWith("Add tier lookups");
        assertThat(draft.questions()).containsExactly(new Question("Which tiers exist?", false));
        assertThat(draft.contracts()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("C-1");
            assertThat(c.body()).contains("tierFor(String customerId)");
        });
        assertThat(draft.steps()).hasSize(2);   // ghost-repo dropped
        PlanDrafter.DraftStep libCore = draft.steps().get(0);
        assertThat(libCore.covers()).containsExactly("R1");                     // R9 filtered
        PlanDrafter.DraftStep pricing = draft.steps().get(1);
        assertThat(pricing.consumesContracts()).containsExactly("C-1");         // C-9 filtered
        assertThat(pricing.versionAction()).isEqualTo("none");                  // 'shipit' coerced
        assertThat(draft.notes()).anySatisfy(n -> assertThat(n).contains("ghost-repo"))
                .anySatisfy(n -> assertThat(n).contains("R9"))
                .anySatisfy(n -> assertThat(n).contains("C-9"))
                .anySatisfy(n -> assertThat(n).contains("shipit"))
                .anySatisfy(n -> assertThat(n).contains("Missing.java"));
        assertThat(planner.requests()).singleElement().satisfies(r ->
                assertThat(r.maxTokens()).isEqualTo(4096));
    }

    @Test
    void theDrafterCapturesADeclaredBlockFromTheModel() {
        String json = """
                {"summary": "S.", "questions": [], "repo_steps": [],
                 "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                                "consumers": ["svc-pricing"], "body": "b",
                                "declarations": [
                                  "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier",
                                  " ",
                                  "  com.acme.LoyaltyTier#tierFor(String): Tier  "]}]}""";
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(json, "stop")));

        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(), planner, "m", 4096);

        assertThat(draft.contracts()).singleElement().satisfies(c ->
                assertThat(c.declared()).containsExactly(
                        "com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier",
                        "com.acme.LoyaltyTier#tierFor(String): Tier"));   // blank dropped, whitespace trimmed
    }

    @Test
    void aModelThatOmitsDeclarationsDegradesToAnUndeclaredContractRatherThanInventingOne() {
        // Silence must never be filled in: an invented declaration would be checked against
        // reality at Gate 2 and reported as divergence the human never approved.
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(GOOD_JSON, "stop")));

        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(), planner, "m", 4096);

        assertThat(draft.contracts()).singleElement()
                .satisfies(c -> assertThat(c.declared()).isEmpty());
    }

    @Test
    void aDeclaredLineCarryingAFenceMarkerIsNeutralized() {
        String json = """
                {"summary": "S.", "questions": [], "repo_steps": [],
                 "contracts": [{"id": "C-1", "kind": "java-api", "provider": "lib-core",
                                "consumers": [], "body": "b",
                                "declarations": ["evil ``` fence break"]}]}""";
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(json, "stop")));

        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(), planner, "m", 4096);

        assertThat(draft.contracts()).singleElement().satisfies(c ->
                assertThat(c.declared()).containsExactly("evil ''' fence break"));
    }

    @Test
    void fencedJsonResponseIsUnwrapped() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(
                "```json\n{\"summary\": \"S.\", \"questions\": [], \"contracts\": [], \"repo_steps\": []}\n```",
                "stop")));

        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(),
                planner, "m", 256);

        assertThat(draft.unavailable()).isFalse();
        assertThat(draft.summary()).isEqualTo("S.");
    }

    @Test
    void perRepoEvidenceIsCapped() {
        db.jdbi().useHandle(h -> {
            for (int i = 0; i < 25; i++) {
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) VALUES "
                        + "(1,'com.acme.p" + i + "." + "X".repeat(300) + i + "','CLASS',0,'src/"
                        + "y".repeat(300) + i + ".java')");
            }
        });

        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact(),
                List.of(new ExecutionOrder.Unit(List.of("lib-core"))), "");

        // The marker names its section: a truncated section must not read as an empty one.
        assertThat(input).contains("…(types truncated)");
        int start = input.indexOf("## lib-core");
        int end = input.indexOf("## svc-pricing");
        assertThat(end - start).isLessThan(PlanDrafter.EVIDENCE_CAP + 200);
        // Truncation cuts on a line boundary, so no half-rendered line is ever offered to a model
        // as something to copy.
        String block = input.substring(start, end);
        assertThat(block.lines().filter(l -> l.startsWith("- ")).toList())
                .allSatisfy(l -> assertThat(l).doesNotContain("…"));
    }

    @Test
    void aServiceRepoContributesMemberSignaturesEvenThoughNothingInItIsApi() {
        // is_api is set only for LIBRARY modules (ApiSurfaceExtractor), so on a real estate every
        // SERVICE repo scored 0 and contributed not one method signature to the prompt — measured
        // as five of six repos. A model cannot name a member it was never shown.
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) "
                    + "VALUES (2,'com.acme.PriceService','CLASS',0,'src/main/java/com/acme/PriceService.java')");
            h.execute("INSERT INTO api_member(type_id, name, signature, return_type) "
                    + "VALUES ((SELECT id FROM java_type WHERE fqcn='com.acme.PriceService'),"
                    + "'quote','quote(String)','Quote')");
        });

        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact(), order(), "");

        assertThat(input).contains("com.acme.PriceService#quote(String): Quote");
    }

    @Test
    void aLongTypeListCannotStarveTheEndpointSection() {
        // One shared cap over the whole repo block truncated whatever rendered last, so a repo with
        // a wide type surface lost its endpoints entirely — silently, and exactly for the repos
        // most likely to own an API.
        db.jdbi().useHandle(h -> {
            for (int i = 0; i < 60; i++) {
                h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) VALUES "
                        + "(2,'com.acme.q" + i + "." + "Z".repeat(200) + i + "','CLASS',0,'src/"
                        + "w".repeat(200) + i + ".java')");
            }
        });

        String input = PlanDrafter.composeInput(db.jdbi(), spec(), impact(), order(), "");

        assertThat(input).contains("GET /price/{}");
    }

    @Test
    void priorQaSectionIsAppendedWhenPresent() {
        ScriptedChatModel planner = new ScriptedChatModel(List.of(response(
                "{\"summary\": \"S.\", \"questions\": [], \"contracts\": [], \"repo_steps\": []}", "stop")));

        PlanDrafter.draft(db.jdbi(), spec(), impact(), order(),
                "- Q1 [blocking]: which?\n  resolved: tierFor.", planner, "m", 256);

        assertThat(planner.requests().get(0).messages().get(1).content())
                .contains("# Prior questions and human resolutions")
                .contains("resolved: tierFor.");
    }

    @Test
    void allFailureChannelsDegradeToABlockingQuestion() {
        for (ChatResponse bad : List.of(response("{", "length"), response("not json", "stop"),
                response("[1,2]", "stop"))) {
            PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(),
                    new ScriptedChatModel(List.of(bad)), "m", 16);
            assertThat(draft.unavailable()).as(bad.message().content()).isTrue();
            assertThat(draft.questions()).singleElement().satisfies(q -> {
                assertThat(q.blocking()).isTrue();
                assertThat(q.text()).startsWith("plan drafting unavailable: ").endsWith("— rerun sdd plan");
            });
        }
        sdd.core.llm.ChatModel down = req -> {
            throw new sdd.core.llm.ModelException("connection refused", 0);
        };
        PlanDrafter.Draft draft = PlanDrafter.draft(db.jdbi(), spec(), impact(), order(), down, "m", 16);
        assertThat(draft.unavailable()).isTrue();
        assertThat(draft.questions().get(0).text()).contains("connection refused");
    }
}
