package sdd.plan.approve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanJsonTest {
    @TempDir Path ws;
    private Database db;
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('lib-core','/w/lib','LIBRARY','" + SHA_A + "')");
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('svc-a','/w/svc','SERVICE','" + SHA_B + "')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
        });
    }

    private static PlanDocument plan() {
        return new PlanDocument("SPEC-9", 3, "S.", List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "body", null)),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1"), "minor",
                        List.of("C-1"), List.of(), List.of("src/A.java"), List.of("t"), "s")),
                List.of());
    }

    @Test
    void compilesDeterministicJsonWithProbedMechanisms() throws Exception {
        List<String> warnings = new ArrayList<>();
        List<String> probed = new ArrayList<>();
        SmokeRunner ok = (consumer, provider) -> {
            probed.add(consumer + "->" + provider);
            return new SmokeRunner.Result(true, "");
        };

        String json = PlanJson.compile(db.jdbi(), plan(), "spec-sha", "plan-sha", ok, warnings);
        String again = PlanJson.compile(db.jdbi(), plan(), "spec-sha", "plan-sha", ok, new ArrayList<>());

        assertThat(json).isEqualTo(again);
        assertThat(probed).containsExactly("/w/svc->/w/lib", "/w/svc->/w/lib");
        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("spec_id").asText()).isEqualTo("SPEC-9");
        assertThat(root.get("plan_version").asInt()).isEqualTo(3);
        assertThat(root.get("spec_sha256").asText()).isEqualTo("spec-sha");
        assertThat(root.get("repos").get(0).get("base_sha").asText()).isEqualTo(SHA_A);
        assertThat(root.get("repos").get(1).get("version_action").asText()).isEqualTo("none");
        assertThat(root.get("edges").get(0).get("from_repo").asText()).isEqualTo("svc-a");   // consumer
        assertThat(root.get("edges").get(0).get("to_repo").asText()).isEqualTo("lib-core");   // provider
        assertThat(root.get("edges").get(0).get("mode").asText()).isEqualTo("PINNED");
        assertThat(root.get("edges").get(0).get("mechanism").asText()).isEqualTo("INCLUDE_BUILD");
        assertThat(root.get("order").get(0).get(0).asText()).isEqualTo("lib-core");
        assertThat(warnings).isEmpty();
    }

    @Test
    void compositeEdgeGetsMechanismNoneWithoutProbing() {
        db.jdbi().useHandle(h -> h.execute(
                "INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (2,'com.acme','lib-included','compileClasspath',NULL,'DIRECT','COMPOSITE',1,1)"));
        List<String> probed = new ArrayList<>();
        SmokeRunner counting = (consumer, provider) -> {
            probed.add("hit");
            return new SmokeRunner.Result(true, "");
        };

        String json = PlanJson.compile(db.jdbi(), plan(), "s", "p", counting, new ArrayList<>());

        assertThat(json).contains("\"mechanism\" : \"NONE\"");
        assertThat(probed).hasSize(1);   // only the PINNED edge probed; COMPOSITE skipped
    }

    @Test
    void compatRoundTripsIntoPlanJson() throws Exception {
        PlanDocument withCompat = new PlanDocument("SPEC-9", 3, "S.", List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "body", "binary-compatible")),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1"), "minor",
                        List.of("C-1"), List.of(), List.of("src/A.java"), List.of("t"), "s")),
                List.of());
        SmokeRunner ok = (consumer, provider) -> new SmokeRunner.Result(true, "");

        String json = PlanJson.compile(db.jdbi(), withCompat, "spec-sha", "plan-sha", ok, new ArrayList<>());

        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.get("contracts").get(0).get("compat").asText()).isEqualTo("binary-compatible");
    }

    @Test
    void probeFailureFallsBackToMavenLocalWithWarning() {
        List<String> warnings = new ArrayList<>();
        SmokeRunner down = (consumer, provider) -> new SmokeRunner.Result(false, "exit 7: boom");

        String json = PlanJson.compile(db.jdbi(), plan(), "s", "p", down, warnings);

        assertThat(json).contains("\"mechanism\" : \"MAVEN_LOCAL\"");
        assertThat(warnings).containsExactly(
                "edge svc-a->lib-core: include-build probe failed (exit 7: boom) — falling back to mavenLocal");
    }
}
