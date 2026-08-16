package sdd.cli.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sdd.cli.implement.CompatGate;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which declared compatibility guarantees this run cannot vouch for.
 *
 * <p>Every test here is about the same distinction: a gate that PASSED and a gate that never ran
 * used to be the same three words in a report and the same exit code. They are different claims.
 */
class SkippedGatesTest {
    @TempDir Path runDir;
    private RunStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new RunStore(InstantSource.system());
        Files.createDirectories(runDir);
    }

    private static PlanModel planDeclaring(String compat) {
        return new PlanModel("SPEC-1", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(),
                List.of(new PlanModel.PlanContract("c1", "java-api", "lib", List.of("svc"),
                        "body", compat, List.of())),
                List.of());
    }

    private static RunState succeeded() {
        return new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "b", "sha", "", null)), null, 0L);
    }

    @Test
    void aGateThatPassedIsNotReported() {
        store.writeCompatGates(runDir, "lib", List.of(new CompatGate("binary-compatible",
                CompatGate.Outcome.PASSED, "2 jar(s) compared")));

        assertThat(SkippedGates.of(planDeclaring("binary-compatible"), succeeded(), store, runDir))
                .isEmpty();
    }

    @Test
    void aGateThatCouldNotRunIsReportedWithTheReason() {
        store.writeCompatGates(runDir, "lib", List.of(new CompatGate("binary-compatible",
                CompatGate.Outcome.SKIPPED, "baseline build failed — no such task 'jar'")));

        // The repo is SUCCEEDED, the contract may be perfectly conformant, and the report would
        // otherwise say nothing at all — which reads as the guarantee having been checked.
        assertThat(SkippedGates.of(planDeclaring("binary-compatible"), succeeded(), store, runDir))
                .singleElement().satisfies(gate -> {
                    assertThat(gate.repo()).isEqualTo("lib");
                    assertThat(gate.compat()).isEqualTo("binary-compatible");
                    assertThat(gate.detail()).contains("baseline build failed");
                });
    }

    @Test
    void aGateThatBrokeIsNotReportedHere() {
        // BROKEN already FAILED the repo, so it fails the review on its own account. Repeating it
        // in this section would file a caught break as an unchecked guarantee.
        store.writeCompatGates(runDir, "lib", List.of(new CompatGate("binary-compatible",
                CompatGate.Outcome.BROKEN, "com.acme.Api: METHOD_REMOVED")));

        assertThat(SkippedGates.of(planDeclaring("binary-compatible"), succeeded(), store, runDir))
                .isEmpty();
    }

    @Test
    void aRepoThatNeverSucceededIsNotReported() {
        RunState failed = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.FAILED, "b", null, "verify failed", "VERIFY")), null, 0L);

        // Its gate legitimately never ran, and the repo already fails the review. Listing it here
        // would bury the skips that are actually surprising.
        assertThat(SkippedGates.of(planDeclaring("binary-compatible"), failed, store, runDir))
                .isEmpty();
    }

    @Test
    void aDeclaredGuaranteeWithNoRecordAtAllIsStillSurfaced() {
        // Nothing written: either an older run, or a path that abandoned the gate without saying
        // so. Neither is evidence the guarantee was checked, which is the only thing that would
        // justify silence.
        assertThat(SkippedGates.of(planDeclaring("type-compatible"), succeeded(), store, runDir))
                .singleElement().satisfies(gate ->
                        assertThat(gate.detail()).contains("no gate outcome was recorded"));
    }

    @Test
    void aPlanThatDeclaresNoGuaranteeReportsNothing() {
        PlanModel plan = new PlanModel("SPEC-1", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(),
                List.of(new PlanModel.PlanContract("c1", "java-api", "lib", List.of("svc"),
                        "body", null, List.of())),
                List.of());

        // The overwhelmingly common case, and it must stay silent and exit 0 — a report that
        // warns about every run teaches a reader to skip the warning.
        assertThat(SkippedGates.of(plan, succeeded(), store, runDir)).isEmpty();
    }

    @Test
    void bothGuaranteesOnOneRepoAreTrackedSeparately() {
        PlanModel plan = new PlanModel("SPEC-1", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")), List.of(),
                List.of(new PlanModel.PlanContract("c1", "java-api", "lib", List.of("svc"),
                                "b", "binary-compatible", List.of()),
                        new PlanModel.PlanContract("c2", "ts-api", "lib", List.of("svc"),
                                "b", "type-compatible", List.of())),
                List.of());
        store.writeCompatGates(runDir, "lib", List.of(
                new CompatGate("binary-compatible", CompatGate.Outcome.PASSED, "1 jar(s) compared"),
                new CompatGate("type-compatible", CompatGate.Outcome.SKIPPED, "no node available")));

        // One gate running is not the other gate running. A per-repo verdict would let a green
        // japicmp cover for a TypeScript check that never happened.
        assertThat(SkippedGates.of(plan, succeeded(), store, runDir))
                .singleElement().satisfies(gate ->
                        assertThat(gate.compat()).isEqualTo("type-compatible"));
    }

    @Test
    void anUnreadableRecordIsNotTreatedAsEvidenceOfASkip() throws Exception {
        Files.createDirectories(runDir.resolve("lib"));
        Files.writeString(runDir.resolve("lib").resolve("compat-gates.json"), "{ not json");

        // Same rule as an absent file, one step further: a corrupt record is an absence of
        // evidence. It surfaces as "nothing recorded", never as an asserted skip.
        assertThat(SkippedGates.of(planDeclaring("binary-compatible"), succeeded(), store, runDir))
                .singleElement().satisfies(gate ->
                        assertThat(gate.detail()).contains("no gate outcome was recorded"));
    }
}
