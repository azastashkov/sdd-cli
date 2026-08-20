package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plan.json written before the OpenSpec export existed must still load, and still resume. Both
 * frozen runs on the author's estate predate it, and {@code ImplementCommand --resume} reads the
 * run dir's snapshot rather than the live file — so a reader that required the key would strand
 * every run in flight when this shipped.
 */
class PlanJsonOpenSpecBackCompatTest {

    private static final String PRE_OPENSPEC = """
            {
              "spec_id" : "SPEC-101",
              "plan_version" : 1,
              "spec_sha256" : "abc",
              "plan_sha256" : "def",
              "repos" : [ { "name" : "lib-core", "role" : "seed", "annotation" : "SEED",
                            "version_action" : "minor", "base_sha" : "aaaa" } ],
              "order" : [ [ "lib-core" ] ],
              "edges" : [ ],
              "contracts" : [ ],
              "steps" : [ { "repo" : "lib-core", "covers" : [ "R1" ], "version_action" : "minor",
                            "provides" : [ ], "consumes" : [ ], "files" : [ "src/A.java" ],
                            "verification" : [ "./gradlew test" ], "sub_spec" : "Do it." } ]
            }
            """;

    @Test
    void aPlanJsonWithNoOpenspecKeyLoadsWithAnEmptyBlock() {
        PlanModel plan = PlanJsonReader.read(PRE_OPENSPEC);

        assertThat(plan.steps()).singleElement().satisfies(step -> {
            assertThat(step.openspec()).isEmpty();
            assertThat(step.subSpec()).isEqualTo("Do it.");
            assertThat(step.files()).containsExactly("src/A.java");
        });
        PlanJsonReader.validate(plan);   // must not throw
    }

    @Test
    void anOpenspecBlockIsReadWhenPresent() {
        String withBlock = PRE_OPENSPEC.replace("\"sub_spec\" : \"Do it.\"",
                "\"sub_spec\" : \"Do it.\", \"openspec\" : [ \"capability: tier-resolution\","
                        + " \"R1 -> A1\" ]");

        PlanModel plan = PlanJsonReader.read(withBlock);

        assertThat(plan.steps()).singleElement().satisfies(step ->
                assertThat(step.openspec())
                        .containsExactly("capability: tier-resolution", "R1 -> A1"));
    }
}
