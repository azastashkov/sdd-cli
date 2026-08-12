package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void toleratesMissingOptionalArrays() {
        PlanModel plan = PlanJsonReader.read(
                "{\"spec_id\":\"S\",\"plan_version\":2,\"repos\":[],\"order\":[],\"steps\":[]}");
        assertThat(plan.planVersion()).isEqualTo(2);
        assertThat(plan.edges()).isEmpty();
        assertThat(plan.contracts()).isEmpty();
    }
}
