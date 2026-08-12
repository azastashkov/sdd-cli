package sdd.cli.implement;

/** The canonical 2-repo approved plan used across implement tests. */
final class PlanJsonReaderTestFixture {
    private PlanJsonReaderTestFixture() {
    }

    static final String PLAN = """
            {
              "spec_id" : "SPEC-101", "plan_version" : 1, "spec_sha256" : "aaa", "plan_sha256" : "bbb",
              "repos" : [
                { "name" : "lib", "role" : "seed", "annotation" : "SEED", "version_action" : "minor", "base_sha" : "sha-lib" },
                { "name" : "svc", "role" : "dependent", "annotation" : "CODE_CHANGE_LIKELY", "version_action" : "patch", "base_sha" : "sha-svc" }
              ],
              "order" : [ [ "lib" ], [ "svc" ] ],
              "edges" : [ { "from_repo" : "svc", "to_repo" : "lib", "mode" : "SNAPSHOT", "mechanism" : "INCLUDE_BUILD" } ],
              "contracts" : [ { "id" : "c1", "kind" : "java-api", "provider" : "lib", "consumers" : [ "svc" ], "body" : "Tier tierFor(String id)" } ],
              "steps" : [
                { "repo" : "lib", "covers" : [ "R1" ], "version_action" : "minor", "provides" : [ "c1" ], "consumes" : [ ], "files" : [ "Tier.java" ], "verification" : [ "Run lib tests" ], "sub_spec" : "Expose tierFor." },
                { "repo" : "svc", "covers" : [ "R1", "R2" ], "version_action" : "patch", "provides" : [ ], "consumes" : [ "c1" ], "files" : [ ], "verification" : [ ], "sub_spec" : "Consume tierFor." }
              ]
            }
            """;
}
