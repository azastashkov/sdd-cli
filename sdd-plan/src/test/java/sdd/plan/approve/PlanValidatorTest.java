package sdd.plan.approve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.core.db.Database;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidatorTest {
    @TempDir Path ws;
    private Database db;
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    @BeforeEach
    void seed() {
        db = Database.open(ws);
        db.jdbi().useHandle(h -> {
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('lib-core','/w/1','LIBRARY','" + SHA_A + "')");
            h.execute("INSERT INTO repo(name, path, kind, head_commit) VALUES ('svc-a','/w/2','SERVICE','" + SHA_B + "')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (1,':','LIBRARY')");
            h.execute("INSERT INTO module(repo_id, gradle_path, kind) VALUES (2,':','SERVICE')");
            h.execute("INSERT INTO dep_edge(from_module_id, to_grp, to_name, configuration, declared_version, declared_via, mode, is_internal, to_module_id) "
                    + "VALUES (2,'com.acme','lib-core','compileClasspath','1.0','DIRECT','PINNED',1,1)");
        });
    }

    private static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-9", "T", "o", "draft", "G.", "",
                List.of(new SpecItem("R1", "req"), new SpecItem("R2", "other")),
                List.of(new SpecItem("A1", "acc")),
                List.of(new SpecItem("C1", "No change to /price/{} response shape.")),
                List.of(), List.of(), List.of(), List.of());
    }

    private static PlanDocument plan(List<List<String>> order, String resolution) {
        return new PlanDocument("SPEC-9", 1, "S.",
                List.of(new PlanDocument.PlanQuestion(1, true, "q", resolution)),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), order,
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core",
                        List.of("svc-a"), "GET /price/{} returns TierPrice", null, List.of())),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1", "R2"), "minor",
                                List.of("C-1"), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("svc-a", List.of(), "none",
                                List.of(), List.of("C-1"), List.of(), List.of(), "s")),
                List.of());
    }

    private Map<String, LiveGit.State> freshStates() {
        return Map.of("lib-core", new LiveGit.State(SHA_A, true),
                "svc-a", new LiveGit.State(SHA_B, true));
    }

    private static PlanDocument planWithDeclared(String... declared) {
        return new PlanDocument("SPEC-9", 1, "S.",
                List.of(new PlanDocument.PlanQuestion(1, true, "q", "resolved")),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("tier-resolver-api", "java-api", "lib-core",
                        List.of("svc-a"), "Resolve a client's pricing tier.", null, List.of(declared))),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1", "R2"), "minor",
                                List.of("tier-resolver-api"), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("svc-a", List.of(), "none",
                                List.of(), List.of("tier-resolver-api"), List.of(), List.of(), "s")),
                List.of());
    }

    @Test
    void aMalformedDeclarationIsAProblem() {
        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                planWithDeclared("resolveTier(String): X"), spec(), freshStates());

        assertThat(verdict.problems()).anySatisfy(p ->
                assertThat(p).contains("tier-resolver-api").contains("<fqcn>#<signature>: <returnType>"));
    }

    @Test
    void anUndeclaredContractIsOnlyAWarning() {
        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                planWithDeclared(), spec(), freshStates());   // none

        assertThat(verdict.problems()).noneMatch(p -> p.contains("declares nothing"));
        assertThat(verdict.warnings()).anySatisfy(w ->
                assertThat(w).contains("tier-resolver-api").contains("declares nothing"));
    }

    @Test
    void anUnqualifiedFqcnIsRejectedAtGateOneWhereAHumanCanStillFixIt() {
        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                planWithDeclared("JdbcTierResolver#resolveTier(String): ClientTier"),
                spec(), freshStates());

        assertThat(verdict.problems()).anySatisfy(p ->
                assertThat(p).contains("tier-resolver-api").contains("fully qualified"));
    }

    @Test
    void aDeclarationThatParsesToNothingWarnsExactlyLikeNoDeclarationAtAll() {
        // A comment-only or blank declared block carries no members and no grammar problems, so
        // testing the RAW list let it pass Gate 1 silently — and then read as DECLARED_MET at
        // Gate 2. The warning must fire off the PARSED result.
        for (String declared : List.of("# TODO: confirm the signature", "")) {
            PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                    planWithDeclared(declared), spec(), freshStates());

            assertThat(verdict.problems()).as("declared=%s", declared).isEmpty();
            assertThat(verdict.warnings()).as("declared=%s", declared).anySatisfy(w ->
                    assertThat(w).contains("tier-resolver-api").contains("declares nothing"));
        }
    }

    @Test
    void aWellFormedDeclarationIsSilent() {
        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                planWithDeclared("com.trading.pricing.core.JdbcTierResolver#resolveTier(String): ClientTier"),
                spec(), freshStates());

        assertThat(verdict.problems()).isEmpty();
    }

    @Test
    void cleanPlanYieldsNoProblemsButConflictWarningFires() {
        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                plan(List.of(List.of("lib-core"), List.of("svc-a")), "resolved"),
                spec(), freshStates());

        assertThat(verdict.problems()).isEmpty();
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w)
                .contains("constraint C1 and contract 'C-1'").contains("/price/{}"));
    }

    @Test
    void blockingResolutionCoverageTopoAndStalenessProblems() {
        PlanDocument bad = new PlanDocument("SPEC-9", 1, "S.",
                List.of(new PlanDocument.PlanQuestion(1, true, "q", null)),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of(), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "X", List.of(), "w")),
                List.of(), List.of(List.of("svc-a"), List.of("lib-core")),
                List.of(),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1"), "shipit",
                        List.of(), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("svc-a", List.of(), "none",
                                List.of(), List.of(), List.of(), List.of(), "s")),
                List.of());
        Map<String, LiveGit.State> stale = Map.of(
                "lib-core", new LiveGit.State("c".repeat(40), true),
                "svc-a", new LiveGit.State(SHA_B, false));

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), bad, spec(), stale);

        assertThat(verdict.problems())
                .anySatisfy(p -> assertThat(p).isEqualTo("Q1 [blocking] has no resolution"))
                .anySatisfy(p -> assertThat(p).isEqualTo("no step covers R2"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "execution order violates dependency: svc-a runs before its provider lib-core"))
                .anySatisfy(p -> assertThat(p).contains("repo lib-core is stale or dirty")
                        .contains("aaaaaaaa").contains("cccccccc"))
                .anySatisfy(p -> assertThat(p).contains("repo svc-a is stale or dirty").contains("dirty"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "version_action 'shipit' on step lib-core is not one of none|patch|minor|major"));
    }

    @Test
    void contractClosureAndSetEqualityProblems() {
        PlanDocument bad = new PlanDocument("SPEC-8", 1, "S.",
                List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("ghost")),
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core", List.of(), "b", null, List.of()),
                        new PlanDocument.PlanContract("C-1", "rest", "lib-core", List.of(), "b", null, List.of())),
                List.of(new PlanDocument.PlanStep("ghost", List.of("R1", "R2"), "none",
                                List.of("C-9"), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("ghost", List.of(), "none",
                                List.of(), List.of(), List.of(), List.of(), "s2")),
                List.of());

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), bad, spec(),
                Map.of("lib-core", new LiveGit.State(SHA_A, true)));

        assertThat(verdict.problems())
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "spec id mismatch: plan says 'SPEC-8' but the spec is 'SPEC-9'"))
                .anySatisfy(p -> assertThat(p).isEqualTo("duplicate contract id 'C-1'"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "contract 'C-1': provider 'lib-core' has no step providing it"))
                .anySatisfy(p -> assertThat(p).isEqualTo(
                        "step ghost references undefined contract 'C-9'"))
                .anySatisfy(p -> assertThat(p).isEqualTo("step repo 'ghost' is not in Affected Repos"))
                .anySatisfy(p -> assertThat(p).isEqualTo("duplicate step for repo 'ghost'"))
                .anySatisfy(p -> assertThat(p).contains("execution order and Affected Repos disagree")
                        .contains("ghost"));
    }

    @Test
    void contractConsumerClosureProblemsAndWarnings() {
        PlanDocument doc = new PlanDocument("SPEC-9", 1, "S.", List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of(), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "BUMP_REBUILD_ONLY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("C-1", "java-api", "lib-core",
                        List.of("svc-a", "nobody"), "b", null, List.of())),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1", "R2"), "minor",
                        List.of("C-1"), List.of(), List.of(), List.of(), "s")),
                List.of());

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), doc, spec(), freshStates());

        assertThat(verdict.problems()).anySatisfy(p -> assertThat(p).isEqualTo(
                "contract 'C-1' names consumer 'nobody' that is not in Affected Repos"));
        assertThat(verdict.warnings()).anySatisfy(w -> assertThat(w).isEqualTo(
                "contract 'C-1' lists consumer 'svc-a' which has no step — rebuild-only dependent?"));
    }

    @Test
    void executionOrderDuplicateRepoDetection() {
        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(),
                plan(List.of(List.of("lib-core"), List.of("svc-a"), List.of("lib-core")), "resolved"),
                spec(), freshStates());

        assertThat(verdict.problems()).anySatisfy(p -> assertThat(p).isEqualTo(
                "repo 'lib-core' appears more than once in Execution Order"));
    }

    @Test
    void compatOnANonJavaApiContractIsAProblem() {
        PlanDocument doc = new PlanDocument("SPEC-9", 1, "S.", List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of(), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("C-1", "rest", "lib-core",
                        List.of("svc-a"), "b", "binary-compatible", List.of())),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1", "R2"), "minor",
                        List.of("C-1"), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("svc-a", List.of(), "none",
                                List.of(), List.of("C-1"), List.of(), List.of(), "s")),
                List.of());

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), doc, spec(), freshStates());

        assertThat(verdict.problems()).anySatisfy(p -> assertThat(p).isEqualTo(
                "contract 'C-1' (rest): compat 'binary-compatible' is only valid on "
                        + "java-api contracts"));
    }

    /** The compat guarantee and the kind it can be checked on move together. */
    @Test
    void typeCompatiblePairsWithTsApiAndNothingElse() {
        PlanValidator.Verdict ok = verdictsFor("ts-api", "type-compatible");
        assertThat(ok.problems()).noneMatch(p -> p.contains("compat"));

        assertThat(verdictsFor("java-api", "type-compatible").problems()).anySatisfy(p ->
                assertThat(p).isEqualTo("contract 'C-1' (java-api): compat 'type-compatible' "
                        + "is only valid on ts-api contracts"));
        assertThat(verdictsFor("ts-api", "binary-compatible").problems()).anySatisfy(p ->
                assertThat(p).isEqualTo("contract 'C-1' (ts-api): compat 'binary-compatible' "
                        + "is only valid on java-api contracts"));
    }

    @Test
    void aTsApiContractProvidedByAGradleRepoIsCaughtAtGateOne() {
        db.jdbi().useHandle(h -> h.execute("UPDATE repo SET build_system='GRADLE' WHERE name='lib-core'"));

        // Left to Gate 2 this actualizes to nothing and reports the grossest divergence
        // available — a wholly missing surface — for what is a one-word typo.
        assertThat(verdictsFor("ts-api", null).problems()).anySatisfy(p -> assertThat(p).isEqualTo(
                "contract 'C-1' (ts-api): provider 'lib-core' is a gradle repo, and ts-api "
                        + "contracts can only be provided by npm repos"));
    }

    @Test
    void aRepoIndexedBeforeTheBuildSystemColumnExistedBlocksNothing() {
        // build_system is NULL until the repo is re-indexed. A plan must not be refused because
        // the knowledge base predates a migration.
        assertThat(verdictsFor("ts-api", null).problems())
                .noneMatch(p -> p.contains("can only be provided by"));
    }

    private PlanValidator.Verdict verdictsFor(String kind, String compat) {
        PlanDocument doc = new PlanDocument("SPEC-9", 1, "S.", List.of(),
                List.of(new PlanDocument.PlanRepo("lib-core", "seed", "SEED", List.of("R1"), "w"),
                        new PlanDocument.PlanRepo("svc-a", "dependent", "CODE_CHANGE_LIKELY", List.of(), "w")),
                List.of(), List.of(List.of("lib-core"), List.of("svc-a")),
                List.of(new PlanDocument.PlanContract("C-1", kind, "lib-core",
                        List.of("svc-a"), "b", compat, List.of())),
                List.of(new PlanDocument.PlanStep("lib-core", List.of("R1", "R2"), "minor",
                        List.of("C-1"), List.of(), List.of(), List.of(), "s"),
                        new PlanDocument.PlanStep("svc-a", List.of(), "none",
                                List.of(), List.of("C-1"), List.of(), List.of(), "s")),
                List.of());
        return PlanValidator.validate(db.jdbi(), doc, spec(), freshStates());
    }

    @Test
    void duplicateAffectedRepoDetection() {
        PlanDocument.PlanRepo repo = new PlanDocument.PlanRepo("lib-core", "seed", "SEED",
                List.of(), "w");
        PlanDocument doc = new PlanDocument("SPEC-9", 1, "S.", List.of(),
                List.of(repo, repo), List.of(), List.of(List.of("lib-core")),
                List.of(), List.of(), List.of());

        PlanValidator.Verdict verdict = PlanValidator.validate(db.jdbi(), doc, spec(), freshStates());

        assertThat(verdict.problems()).anySatisfy(p -> assertThat(p).isEqualTo(
                "repo 'lib-core' appears more than once in Affected Repos"));
    }
}
