package sdd.plan.gen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.plan.impact.AffectedRepo;
import sdd.plan.impact.ImpactResult;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the drafter prompt grows with the size of the affected set.
 *
 * <p>Every budget in {@link PlanDrafter} is per-repo, so the prompt is O(affected repos) with no
 * ceiling of its own. That is invisible on a six-repo estate and is not invisible on a fifty-repo
 * one, which is the scale this tool exists for — the design's own context is "40+ repos".
 */
class PromptScalingTest {
    @TempDir Path ws;

    private static NormalizedSpec spec() {
        return new NormalizedSpec("S-1", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "tier pricing")), List.of(new SpecItem("A1", "acc")),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** n repos, each with a realistic-ish surface: 50 types, 3 members each, 5 endpoints. */
    private ImpactResult estateOf(Database db, int n, int codeChangeLikely) {
        List<AffectedRepo> affected = new ArrayList<>();
        db.jdbi().useHandle(h -> {
            for (int r = 1; r <= n; r++) {
                h.execute("INSERT INTO repo(name, path, kind) VALUES ('repo" + r + "','/w/" + r + "','LIBRARY')");
                h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (" + r + ",':','LIBRARY')");
                for (int t = 0; t < 50; t++) {
                    h.execute("INSERT INTO java_type(module_id, fqcn, kind, is_api, file_path) VALUES ("
                            + r + ",'com.acme.repo" + r + ".pkg" + t + ".SomeServiceType" + t
                            + "','CLASS',1,'src/main/java/com/acme/repo" + r + "/pkg" + t
                            + "/SomeServiceType" + t + ".java')");
                    long typeId = h.createQuery("SELECT last_insert_rowid()").mapTo(Long.class).one();
                    for (int m = 0; m < 3; m++) {
                        h.execute("INSERT INTO api_member(type_id, name, signature, return_type) VALUES ("
                                + typeId + ",'method" + m + "','method" + m
                                + "(java.lang.String,java.lang.Integer)','java.util.Optional')");
                    }
                }
                for (int e = 0; e < 5; e++) {
                    h.execute("INSERT INTO rest_endpoint(module_id, class_fqcn, method_name, http_method,"
                            + " path_template, norm_path, response_type) VALUES (" + r
                            + ",'C','m','GET','/api/repo" + r + "/thing" + e + "/{id}','/api/repo" + r
                            + "/thing" + e + "/{}','SomeResponseDto')");
                }
            }
        });
        for (int r = 1; r <= n; r++) {
            affected.add(new AffectedRepo("repo" + r, r == 1 ? "seed" : "dependent",
                    r == 1 ? "SEED" : (r <= codeChangeLikely ? "CODE_CHANGE_LIKELY" : "BUMP_REBUILD_ONLY"),
                    List.of(), List.of("depends on repo1 (PINNED)")));
        }
        return new ImpactResult(List.of(), affected, List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    }

    @Test
    void reportsPromptSizeAcrossEstateSizes() {
        // Environment, not a system property: Gradle inherits the former into the test JVM and
        // does not forward the latter — a trap this repo has now hit twice.
        int n = envInt("SDD_SCALE_REPOS", 53);
        int ccl = envInt("SDD_SCALE_CODE_CHANGE", 4);
        try (Database db = Database.open(ws)) {
            ImpactResult result = estateOf(db, n, ccl);
            List<ExecutionOrder.Unit> order = new ArrayList<>();
            for (AffectedRepo a : result.affected()) {
                order.add(new ExecutionOrder.Unit(List.of(a.repo())));
            }

            String prompt = PlanDrafter.composeInput(db.jdbi(), spec(), result, order, "");

            System.out.printf("SCALE repos=%d codeChangeLikely=%d prompt=%,d chars ~%,d tokens%n",
                    n, ccl, prompt.length(), prompt.length() / 4);
            assertThat(prompt).contains("repo" + n);
        }
    }
}
