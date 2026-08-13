package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PropagationPlannerTest {
    @TempDir Path ws;

    private static PlanModel.PlanStep step(String repo) {
        return new PlanModel.PlanStep(repo, List.of(), "patch", List.of(), List.of(),
                List.of(), List.of(), "x");
    }

    private void seedKb(Database db) {
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
            h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                    + "declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (1, 'com.acme', 'lib', 'compileClasspath', '1.2.3', 'DIRECT', 'PINNED', 1, 2)");
        });
    }

    @Test
    void plansPublishForSteppedProvidersAndPinBumpsForConsumers() {
        try (Database db = Database.open(ws)) {
            seedKb(db);
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "PINNED", "MAVEN_LOCAL")),
                    List.of(), List.of(step("lib"), step("svc")));
            List<String> problems = new ArrayList<>();

            Map<String, RepoPropagation> result = PropagationPlanner.plan(db.jdbi(), plan,
                    Path.of("/run"), Map.of("lib", "1.3.0", "svc", "0.1.1"), problems);

            assertThat(problems).isEmpty();
            assertThat(result.get("lib").publish())
                    .isEqualTo(new RepoPropagation.PublishSpec("1.3.0", Path.of("/run/m2")));
            assertThat(result.get("svc").bumps()).containsExactly(
                    new RepoPropagation.BumpEdit("com.acme", "lib", "1.2.3", "1.3.0"));
            assertThat(result.get("svc").publish()).isNull();
        }
    }

    @Test
    void stepLessProvidersNeedNoPublishAndNoBumps() {
        try (Database db = Database.open(ws)) {
            seedKb(db);
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "none", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "PINNED", "MAVEN_LOCAL")),
                    List.of(), List.of(step("svc")));   // lib has NO step -> unchanged artifact
            List<String> problems = new ArrayList<>();

            Map<String, RepoPropagation> result = PropagationPlanner.plan(db.jdbi(), plan,
                    Path.of("/run"), Map.of("lib", "1.2.3", "svc", "0.1.1"), problems);

            assertThat(problems).isEmpty();
            assertThat(result).isEmpty();
        }
    }

    @Test
    void snapshotDeclarationsNeverBump() {
        try (Database db = Database.open(ws)) {
            db.jdbi().useHandle(h -> {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('svc', '/w/svc', 'SERVICE')");
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('lib', '/w/lib', 'LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1, ':', 'SERVICE')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2, ':', 'LIBRARY')");
                h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, "
                        + "declared_version, declared_via, mode, is_internal, to_module_id) "
                        + "VALUES (1, 'com.acme', 'lib', 'compileClasspath', '1.0-SNAPSHOT', 'DIRECT', "
                        + "'SNAPSHOT', 1, 2)");
            });
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "none", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "MAVEN_LOCAL")),
                    List.of(), List.of(step("lib"), step("svc")));
            List<String> problems = new ArrayList<>();

            Map<String, RepoPropagation> result = PropagationPlanner.plan(db.jdbi(), plan,
                    Path.of("/run"), Map.of("lib", "1.0-SNAPSHOT", "svc", "0.1.1"), problems);

            assertThat(problems).isEmpty();
            assertThat(result.containsKey("svc")).isFalse();               // no bump for a snapshot pin
            assertThat(result.get("lib").publish().version()).isEqualTo("1.0-SNAPSHOT");   // republish same snapshot
        }
    }

    @Test
    void missingPlannedVersionForANeededProviderIsAProblem() {
        try (Database db = Database.open(ws)) {
            seedKb(db);
            PlanModel plan = new PlanModel("S", 1, "", "",
                    List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                            new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                    List.of(List.of("lib"), List.of("svc")),
                    List.of(new PlanModel.PlanEdge("svc", "lib", "PINNED", "MAVEN_LOCAL")),
                    List.of(), List.of(step("lib"), step("svc")));
            List<String> problems = new ArrayList<>();

            PropagationPlanner.plan(db.jdbi(), plan, Path.of("/run"), Map.of(), problems);

            assertThat(problems).hasSize(2);   // publish needs it AND svc's pin bump needs it
            assertThat(problems.get(0)).contains("lib");
        }
    }
}
