package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanJsonReaderTest {
    private static final String PLAN = """
            {
              "spec_id" : "SPEC-101",
              "plan_version" : 1,
              "spec_sha256" : "aaa",
              "plan_sha256" : "bbb",
              "repos" : [ {
                "name" : "lib", "role" : "seed", "annotation" : "SEED",
                "version_action" : "minor", "base_sha" : "sha-lib"
              }, {
                "name" : "svc", "role" : "dependent", "annotation" : "CODE_CHANGE_LIKELY",
                "version_action" : "patch", "base_sha" : "sha-svc"
              } ],
              "order" : [ [ "lib" ], [ "svc" ] ],
              "edges" : [ { "from_repo" : "svc", "to_repo" : "lib", "mode" : "SNAPSHOT", "mechanism" : "INCLUDE_BUILD" } ],
              "contracts" : [ {
                "id" : "c1", "kind" : "java-api", "provider" : "lib",
                "consumers" : [ "svc" ], "body" : "Tier tierFor(String id)"
              } ],
              "steps" : [ {
                "repo" : "lib", "covers" : [ "R1" ], "version_action" : "minor",
                "provides" : [ "c1" ], "consumes" : [ ], "files" : [ "Tier.java" ],
                "verification" : [ "Run lib tests" ], "sub_spec" : "Expose tierFor."
              }, {
                "repo" : "svc", "covers" : [ "R1", "R2" ], "version_action" : "patch",
                "provides" : [ ], "consumes" : [ "c1" ], "files" : [ ],
                "verification" : [ ], "sub_spec" : "Consume tierFor."
              } ]
            }
            """;

    @Test
    void parsesTheApprovedPlan() {
        PlanModel plan = PlanJsonReader.read(PLAN);

        assertThat(plan.specId()).isEqualTo("SPEC-101");
        assertThat(plan.planVersion()).isEqualTo(1);
        assertThat(plan.specSha256()).isEqualTo("aaa");
        assertThat(plan.order()).containsExactly(java.util.List.of("lib"), java.util.List.of("svc"));
        assertThat(plan.repo("svc")).get().extracting(PlanModel.PlanRepo::baseSha).isEqualTo("sha-svc");
        assertThat(plan.edges()).singleElement()
                .extracting(PlanModel.PlanEdge::fromRepo, PlanModel.PlanEdge::toRepo)
                .containsExactly("svc", "lib");   // real plan.json direction: from=consumer, to=provider
        assertThat(plan.contracts()).singleElement()
                .extracting(PlanModel.PlanContract::id, PlanModel.PlanContract::body)
                .containsExactly("c1", "Tier tierFor(String id)");
        PlanModel.PlanStep lib = plan.step("lib").orElseThrow();
        assertThat(lib.covers()).containsExactly("R1");
        assertThat(lib.provides()).containsExactly("c1");
        assertThat(lib.files()).containsExactly("Tier.java");
        assertThat(lib.subSpec()).isEqualTo("Expose tierFor.");
        assertThat(plan.step("svc").orElseThrow().consumes()).containsExactly("c1");
    }

    @Test
    void readsCompatFromPlanJson() {
        String withCompat = """
                {
                  "spec_id" : "S", "plan_version" : 1, "repos" : [], "order" : [], "steps" : [],
                  "contracts" : [
                    { "id" : "c1", "kind" : "java-api", "provider" : "lib", "consumers" : [ ], "body" : "b", "compat" : "binary-compatible" },
                    { "id" : "c2", "kind" : "rest", "provider" : "lib", "consumers" : [ ], "body" : "b" }
                  ]
                }
                """;

        PlanModel plan = PlanJsonReader.read(withCompat);

        assertThat(plan.contracts()).extracting(PlanModel.PlanContract::id, PlanModel.PlanContract::compat)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c1", "binary-compatible"),
                        org.assertj.core.groups.Tuple.tuple("c2", null));
    }

    @Test
    void toleratesMissingOptionalArrays() {
        PlanModel plan = PlanJsonReader.read(
                "{\"spec_id\":\"S\",\"plan_version\":2,\"repos\":[],\"order\":[],\"steps\":[]}");
        assertThat(plan.planVersion()).isEqualTo(2);
        assertThat(plan.edges()).isEmpty();
        assertThat(plan.contracts()).isEmpty();
    }

    @Test
    void validateRejectsAStepWhoseRepoIsMissingFromOrder() {
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(), List.of(),
                List.of(new PlanModel.PlanStep("ghost", List.of(), "patch", List.of(), List.of(),
                        List.of(), List.of(), "x")));
        assertThatThrownBy(() -> PlanJsonReader.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost").hasMessageContaining("order");
    }

    @Test
    void validateRejectsADuplicateOrderEntry() {
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib"), List.of("lib")), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> PlanJsonReader.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lib").hasMessageContaining("twice");
    }

    @Test
    void validateRejectsAnEdgeNamingAnUnknownRepo() {
        PlanModel plan = new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")),
                List.of(new PlanModel.PlanEdge("lib", "ghost", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
        assertThatThrownBy(() -> PlanJsonReader.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void validateAcceptsTheCanonicalFixture() {
        PlanJsonReader.validate(PlanJsonReader.read(PlanJsonReaderTestFixture.PLAN));   // must not throw
    }
}
