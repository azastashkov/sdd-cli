package sdd.plan.approve;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sidecar exists because OpenSpec has nowhere to put an estate graph. These tests pin the two
 * properties that makes it usable as a gate input: it carries everything plan.json does, and it is
 * byte-deterministic.
 */
class EstateYamlTest {

    private static final String PLAN_JSON = """
            {
              "spec_id" : "SPEC-9",
              "plan_version" : 2,
              "spec_sha256" : "aaa",
              "plan_sha256" : "bbb",
              "repos" : [ {
                "name" : "lib-core",
                "role" : "seed",
                "annotation" : "SEED",
                "version_action" : "minor",
                "base_sha" : "c0ffee"
              } ],
              "order" : [ [ "lib-core" ], [ "svc-a", "svc-b" ] ],
              "edges" : [ {
                "from_repo" : "svc-a",
                "to_repo" : "lib-core",
                "mode" : "direct",
                "mechanism" : "MAVEN_LOCAL"
              } ],
              "contracts" : [ {
                "id" : "tier-api",
                "kind" : "java-api",
                "provider" : "lib-core",
                "consumers" : [ "svc-a" ],
                "body" : "TierResolver gains:\\n  invalidate(String): void",
                "compat" : "binary-compatible",
                "declared" : [ "com.acme.TierResolver#invalidate(String): void" ]
              } ],
              "steps" : [ {
                "repo" : "lib-core",
                "covers" : [ "R1" ],
                "version_action" : "minor",
                "provides" : [ "tier-api" ],
                "consumes" : [ ],
                "files" : [ "src/main/java/A.java" ],
                "verification" : [ ":lib-core:test" ],
                "sub_spec" : "Add invalidate.",
                "openspec" : [ "capability: tier-resolution", "R1 -> A1" ]
              } ]
            }
            """;

    private static PlanDocument plan() {
        return new PlanDocument("SPEC-9", 2, "Do the thing.",
                List.of(new PlanDocument.PlanQuestion(1, true, "Which method?", "Use tierFor."),
                        new PlanDocument.PlanQuestion(2, false, "Optional?", null)),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "touchpoint")),
                List.of(new PlanDocument.PlanExcluded("svc-legacy", "no dependency path")),
                List.of(List.of("lib-core")), List.of(), List.of(), List.of("drafter note"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsed() {
        return new Yaml().load(EstateYaml.render(PLAN_JSON, plan()));
    }

    /** The whole point of step one: nothing plan.json carries may be lost on the way across. */
    @Test
    void everyPlanJsonFieldSurvives() {
        Map<String, Object> estate = parsed();

        assertThat(estate.get("spec_id")).isEqualTo("SPEC-9");
        assertThat(estate.get("plan_version")).isEqualTo(2);
        assertThat(estate.get("spec_sha256")).isEqualTo("aaa");
        assertThat(estate.get("order")).isEqualTo(List.of(List.of("lib-core"), List.of("svc-a", "svc-b")));
        assertThat((List<Map<String, Object>>) estate.get("repos")).singleElement()
                .satisfies(r -> {
                    assertThat(r.get("name")).isEqualTo("lib-core");
                    assertThat(r.get("base_sha")).isEqualTo("c0ffee");
                });
        assertThat((List<Map<String, Object>>) estate.get("edges")).singleElement()
                .satisfies(e -> assertThat(e.get("mechanism")).isEqualTo("MAVEN_LOCAL"));
    }

    /** A contract body is multi-line and contains a colon — the shapes a naive emitter mangles. */
    @Test
    void aMultiLineContractBodyAndItsDeclaredMembersSurviveIntact() {
        List<Map<String, Object>> contracts = (List<Map<String, Object>>) parsed().get("contracts");

        assertThat(contracts).singleElement().satisfies(c -> {
            assertThat(c.get("body")).isEqualTo("TierResolver gains:\n  invalidate(String): void");
            assertThat(c.get("declared"))
                    .isEqualTo(List.of("com.acme.TierResolver#invalidate(String): void"));
            assertThat(c.get("compat")).isEqualTo("binary-compatible");
        });
    }

    /**
     * The four fields plan.json has always dropped. Nothing after approve can see them today, so a
     * Gate-2 reviewer cannot learn that a blocking question was answered or why a repo was excluded.
     */
    @Test
    void carriesTheFourThingsPlanJsonDrops() {
        Map<String, Object> estate = parsed();

        assertThat(estate.get("summary")).isEqualTo("Do the thing.");
        assertThat(estate.get("notes")).isEqualTo(List.of("drafter note"));
        assertThat((List<Map<String, Object>>) estate.get("excluded")).singleElement()
                .satisfies(x -> assertThat(x.get("detail")).isEqualTo("no dependency path"));
        assertThat((List<Map<String, Object>>) estate.get("questions")).hasSize(2)
                .satisfies(qs -> {
                    assertThat(qs.get(0).get("blocking")).isEqualTo(true);
                    assertThat(qs.get(0).get("resolution")).isEqualTo("Use tierFor.");
                    assertThat(qs.get(1).get("blocking")).isEqualTo(false);
                });
    }

    /** Empty, not absent: a reader must tell "asked and unanswered" from "answered with nothing". */
    @Test
    void anUnansweredQuestionCarriesAnEmptyResolutionRatherThanNothing() {
        List<Map<String, Object>> questions = (List<Map<String, Object>>) parsed().get("questions");

        assertThat(questions.get(1)).containsKey("resolution");
        assertThat(questions.get(1).get("resolution")).isEqualTo("");
    }

    /**
     * Compared byte-for-byte, exactly as the OpenSpec export already is. Map.copyOf has produced a
     * per-JVM ordering bug twice in this codebase, so key order is asserted rather than assumed.
     */
    @Test
    void renderingTwiceProducesTheSameBytesInAFixedKeyOrder() {
        String once = EstateYaml.render(PLAN_JSON, plan());

        assertThat(once).isEqualTo(EstateYaml.render(PLAN_JSON, plan()));
        assertThat(parsed().keySet()).containsExactly("spec_id", "plan_version", "spec_sha256",
                "plan_sha256", "summary", "questions", "repos", "excluded", "order", "edges",
                "contracts", "steps", "notes");
    }

    /** No wall clock, for the reason the export's own test gives: it would break byte comparison. */
    @Test
    void noWallClockAppearsAnywhere() {
        assertThat(EstateYaml.render(PLAN_JSON, plan()))
                .doesNotContain("created")
                .doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    /**
     * The property step one exists to establish: the sidecar is not a lossy summary of plan.json,
     * it is plan.json plus four fields. Asserted by converting back and comparing trees rather than
     * field by field — a hand-written comparison would silently pass over a key nobody thought to
     * check, which is exactly the failure mode of adding a second serialization of the same data.
     */
    @Test
    void convertingBackYieldsPlanJsonsOwnTreeForEveryKeyItHas() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode original = json.readTree(PLAN_JSON);
        com.fasterxml.jackson.databind.JsonNode roundTripped =
                json.readTree(EstateYaml.toJson(EstateYaml.render(PLAN_JSON, plan())));

        original.fieldNames().forEachRemaining(field ->
                assertThat(roundTripped.get(field))
                        .as("plan.json field '%s' after a trip through estate.yaml", field)
                        .isEqualTo(original.get(field)));
    }

    @Test
    void aDocumentThatIsNotAMappingIsRejectedByName() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EstateYaml.toJson("- just\n- a list\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a mapping");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EstateYaml.toJson(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("an empty document");
    }

    /**
     * The plan-time estate: what makes the change directory self-contained.
     *
     * <p>Everything approve needs lives inside {@code openspec/changes/<id>/} once this is there,
     * which is the step the workspace has to take before the OpenSpec tree can be the primary
     * artifact rather than a second view of one.
     */
    @Test
    void thePlanTimeEstateCarriesTheSpecTheMarkdownCannotExpress() {
        Map<String, Object> estate = new Yaml().load(
                sdd.plan.openspec.EstateChangeFixtureAccess.planTimeEstate());

        assertThat(estate.get("approved")).isEqualTo(false);
        assertThat(estate).doesNotContainKeys("edges", "spec_sha256", "plan_sha256");

        Map<String, Object> spec = (Map<String, Object>) estate.get("spec");
        // The three the OpenSpec markdown cannot hold: Why merges goal and background, and
        // attachments and sources have nowhere to go in the format at all.
        assertThat(spec.get("goal")).isEqualTo("Tier updates do not take effect until the service restarts.");
        assertThat(spec.get("background")).isEqualTo("Pricing caches the resolved tier for the process lifetime.");
        assertThat(spec).containsKeys("attachments", "sources", "touchpoints", "evidence");
        assertThat((List<Map<String, Object>>) spec.get("requirements"))
                .extracting(r -> r.get("id")).containsExactly("R1", "R2");
    }

    /** Questions are numbered here exactly as design.md numbers them, so a resolution can be
     *  matched back to the question a human answered. */
    @Test
    void questionsAreNumberedToMatchTheRenderedDesignDocument() {
        Map<String, Object> estate = new Yaml().load(
                sdd.plan.openspec.EstateChangeFixtureAccess.planTimeEstate());

        assertThat((List<Map<String, Object>>) estate.get("questions")).singleElement()
                .satisfies(q -> {
                    assertThat(q.get("number")).isEqualTo(1);
                    assertThat(q.get("blocking")).isEqualTo(true);
                    assertThat(q.get("resolution")).isEqualTo("");
                });
    }
}
