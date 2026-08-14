package sdd.cli.review;

import org.junit.jupiter.api.Test;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunGit;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReportTest {
    private static PlanModel planWithContracts(int contractCount) {
        List<PlanModel.PlanContract> contracts = java.util.stream.IntStream.range(0, contractCount)
                .mapToObj(i -> new PlanModel.PlanContract(
                        "contract-" + i, "interface", "lib", List.of(), "body", "source", List.of()))
                .toList();
        return new PlanModel("SPEC-1", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a")),
                List.of(List.of("lib")),
                List.of(),
                contracts, List.of());
    }

    private static PlanModel planNoContracts() {
        return planWithContracts(0);
    }

    /** Three repos with one propagation edge: {@code svc} consumes {@code lib}, {@code tool} stands
     *  alone — enough graph for the staging-void and propagation assertions, and a repo with no
     *  decision of its own. */
    private static PlanModel planWithEdge() {
        return planWithEdge(List.of());
    }

    private static PlanModel planWithEdge(List<PlanModel.PlanContract> contracts) {
        return new PlanModel("SPEC-1", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b"),
                        new PlanModel.PlanRepo("tool", "dependent", "X", "patch", "c")),
                List.of(List.of("lib"), List.of("svc"), List.of("tool")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD")),
                contracts, List.of());
    }

    private static RunState threeSucceeded() {
        return new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/lib", "sha", ""),
                new RepoRun("svc", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/svc", "sha", ""),
                new RepoRun("tool", RepoState.SUCCEEDED, "sdd/SPEC-1-v1/tool", "sha", "")), null, 10L);
    }

    /** The no-frills call: no diffs, no rebuilds, no failures, no decisions. */
    private static String render(PlanModel plan, RunState state) {
        return ReviewReport.render("SPEC-1-v1", plan, state, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.skipped());
    }

    @Test
    void summaryIncludesContractRecheckLineWhenNoContracts() {
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "branch", "sha", "ok")), null, 10L);
        PlanModel plan = planNoContracts();

        String report = ReviewReport.render("SPEC-1-v1", plan, state, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.skipped());

        assertThat(report).contains("- Contract re-check: no contracts in this plan");
    }

    @Test
    void summaryIncludesContractRecheckLineWithAllMatches() {
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "branch", "sha", "ok")), null, 10L);
        PlanModel plan = planWithContracts(2);
        List<ContractRecheck.Finding> findings = List.of(
                new ContractRecheck.Finding("contract-0", "lib", "interface",
                        ContractRecheck.Status.MATCHES, "", "main"),
                new ContractRecheck.Finding("contract-1", "lib", "interface",
                        ContractRecheck.Status.MATCHES, "", "main"));

        String report = ReviewReport.render("SPEC-1-v1", plan, state, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), findings, Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.estate());

        assertThat(report).contains("- Contract re-check: 2 checked, 0 mismatches");
        // When all match, no detail section should be present
        assertThat(report).doesNotContain("## Contract re-check\n\n-");
    }

    @Test
    void summaryIncludesContractRecheckLineWithOneMismatch() {
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "branch", "sha", "ok")), null, 10L);
        PlanModel plan = planWithContracts(2);
        List<ContractRecheck.Finding> findings = List.of(
                new ContractRecheck.Finding("contract-0", "lib", "interface",
                        ContractRecheck.Status.MATCHES, "", "main"),
                new ContractRecheck.Finding("contract-1", "lib", "interface",
                        ContractRecheck.Status.DRIFTED, "bodies differ", "main"));

        String report = ReviewReport.render("SPEC-1-v1", plan, state, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), findings, Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.estate());

        assertThat(report).contains("- Contract re-check: 2 checked, 1 mismatch");
        // Detail section should be present when there are mismatches
        assertThat(report).contains("## Contract re-check\n\n- `contract-1`");
    }

    @Test
    void summaryIncludesContractRecheckLineWithMultipleMismatches() {
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "branch", "sha", "ok")), null, 10L);
        PlanModel plan = planWithContracts(3);
        List<ContractRecheck.Finding> findings = List.of(
                new ContractRecheck.Finding("contract-0", "lib", "interface",
                        ContractRecheck.Status.MATCHES, "", "main"),
                new ContractRecheck.Finding("contract-1", "lib", "interface",
                        ContractRecheck.Status.DRIFTED, "bodies differ", "main"),
                new ContractRecheck.Finding("contract-2", "lib", "interface",
                        ContractRecheck.Status.MISSING_RECORD, "no record", "main"));

        String report = ReviewReport.render("SPEC-1-v1", plan, state, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), findings, Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.estate());

        assertThat(report).contains("- Contract re-check: 3 checked, 2 mismatches");
        // Detail section should be present
        assertThat(report).contains("## Contract re-check\n\n- `contract-1`");
        assertThat(report).contains("- `contract-2`");
    }

    @Test
    void everyRepoBulletCarriesItsDecisionAndTheSummaryCountsThem() {
        Map<String, DecisionRecord> decisions = Map.of(
                "lib", new DecisionRecord(Decision.APPROVED, ""),
                "svc", new DecisionRecord(Decision.REJECTED, "wrong API"));

        String report = ReviewReport.render("SPEC-1-v1", planWithEdge(), threeSucceeded(), Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), decisions,
                RunContext.Checkpoints.none(), "runbook", RebuildScope.estate());

        assertThat(report).contains("- **lib**: SUCCEEDED, decision: APPROVED");
        assertThat(report).contains("- **svc**: SUCCEEDED, decision: REJECTED (wrong API)");
        // A repo with no record at all is PENDING — stated, not left blank.
        assertThat(report).contains("- **tool**: SUCCEEDED, decision: PENDING");
        assertThat(report).contains("- Decisions: 1 approved, 1 rejected, 0 redo, 1 pending");
    }

    @Test
    void propagationSectionRecordsEveryEdgesModeAndMechanismAndIsOmittedWhenThereAreNone() {
        String withEdges = render(planWithEdge(), threeSucceeded());

        assertThat(withEdges).contains("## Propagation");
        assertThat(withEdges).contains("- svc -> lib: SNAPSHOT/INCLUDE_BUILD");

        String withoutEdges = render(planNoContracts(), new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "b", "sha", "")), null, 0L));

        assertThat(withoutEdges).doesNotContain("## Propagation");
    }

    @Test
    void checkpointDriftIsItsOwnSectionAndIsCountedInTheSummary() {
        List<String> drift = List.of("lib: branch sdd/SPEC-1-v1/lib is at abcdefg, checkpoint was "
                + "1234567 — diffs and runbook describe the checkpoint");

        String report = ReviewReport.render("SPEC-1-v1", planWithEdge(), threeSucceeded(), Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                new RunContext.Checkpoints(drift, Set.of()), "runbook", RebuildScope.estate());

        assertThat(report).contains("## Checkpoint drift");
        assertThat(report).contains("- lib: branch sdd/SPEC-1-v1/lib is at abcdefg, checkpoint was "
                + "1234567 — diffs and runbook describe the checkpoint");
        assertThat(report).contains("- Checkpoint drift: 1 repo has moved off its checkpoint");
        // The drift section must come BEFORE the reassuring per-repo lines it invalidates.
        assertThat(report.indexOf("## Checkpoint drift")).isLessThan(report.indexOf("## Repos"));

        assertThat(render(planWithEdge(), threeSucceeded())).doesNotContain("## Checkpoint drift");
    }

    @Test
    void aRunBranchThatNoLongerExistsIsStatedOnItsRepoLineButIsNotDrift() {
        // The runbook below still tells a human to merge this branch, so the report may not stay
        // silent about it — but nothing MOVED, so it is not drift and must not fail the review.
        String report = ReviewReport.render("SPEC-1-v1", planWithEdge(), threeSucceeded(), Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(),
                new RunContext.Checkpoints(List.of(), Set.of("lib")), "runbook", RebuildScope.estate());

        assertThat(report).contains("- **lib**: SUCCEEDED, decision: PENDING, checkpoint sha, "
                + "run branch sdd/SPEC-1-v1/lib no longer exists");
        assertThat(report).doesNotContain("## Checkpoint drift");
    }

    @Test
    void contractFindingsRecordTheBranchTheyWereExtractedFrom() {
        PlanModel plan = planWithContracts(2);
        List<ContractRecheck.Finding> findings = List.of(
                new ContractRecheck.Finding("contract-0", "lib", "interface",
                        ContractRecheck.Status.DRIFTED, "bodies differ", "sdd/SPEC-1-v1/lib"),
                new ContractRecheck.Finding("contract-1", "lib", "interface",
                        ContractRecheck.Status.NOT_EXTRACTABLE, "no checkout path", null));
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "b", "sha", "")), null, 0L);

        String report = ReviewReport.render("SPEC-1-v1", plan, state, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), findings, Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.estate());

        assertThat(report).contains("(extracted from: sdd/SPEC-1-v1/lib)");
        // Nothing was extracted at all, so the report must say so rather than print a confident default.
        assertThat(report).contains("(extracted from: unknown)");
    }

    @Test
    void aSubsetRebuildIsNeverReportedAsAnEstateTotal() {
        Map<String, EstateRebuild.Result> subset = Map.of(
                "svc", new EstateRebuild.Result(true, "ok"));

        String report = ReviewReport.render("SPEC-1-v1", planWithEdge(), threeSucceeded(), Map.of(),
                subset, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.none().withReverifiedSubtreeOf("lib"));

        assertThat(report).doesNotContain("- Estate rebuild: 1 passed, 0 failed");
        assertThat(report).contains("- Estate rebuild: not re-run for the whole estate; only lib's "
                + "downstream subtree was re-verified after a redo: 1 passed, 0 failed");
    }

    @Test
    void aReportRefreshedByADecisionDoesNotClaimTheRebuildWasSkippedByAFlag() {
        String report = ReviewReport.render("SPEC-1-v1", planWithEdge(), threeSucceeded(), Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.none());

        assertThat(report).doesNotContain("--no-rebuild");
        assertThat(report).contains("- Estate rebuild: not re-run in this invocation");
    }

    @Test
    void anEstateRebuildThatWasPartlyReVerifiedSaysWhichSubtreesAreNewer() {
        Map<String, EstateRebuild.Result> rebuilds = Map.of(
                "lib", new EstateRebuild.Result(true, "ok"),
                "svc", new EstateRebuild.Result(true, "ok"));

        String report = ReviewReport.render("SPEC-1-v1", planWithEdge(), threeSucceeded(), Map.of(),
                rebuilds, List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.estate().withReverifiedSubtreeOf("lib"));

        assertThat(report).contains("- Estate rebuild: 2 passed, 0 failed (estate-wide, with lib's "
                + "downstream subtree re-verified again after a redo)");
    }

    @Test
    void aStagingFailureVoidsTheDownstreamVerdictsAndTheContractLineAboveIt() {
        Map<String, EstateRebuild.Result> rebuilds = Map.of(
                "svc", new EstateRebuild.Result(true, "ok"),
                "tool", new EstateRebuild.Result(true, "ok"));
        List<String> stagingFailures = List.of("lib: checkout refused");
        List<ContractRecheck.Finding> findings = List.of(
                new ContractRecheck.Finding("contract-0", "lib", "interface",
                        ContractRecheck.Status.MATCHES, "", "main"));
        PlanModel plan = planWithEdge(List.of(new PlanModel.PlanContract(
                "contract-0", "interface", "lib", List.of("svc"), "body", "source", List.of())));

        String report = ReviewReport.render("SPEC-1-v1", plan, threeSucceeded(), Map.of(),
                rebuilds, List.of(), stagingFailures, List.of(), List.of(), findings, Map.of(),
                RunContext.Checkpoints.none(), "runbook", RebuildScope.estate());

        // The provider itself: its own line says it never reached its checkpoint.
        assertThat(report).contains("- **lib**: SUCCEEDED, decision: PENDING, checkpoint sha, "
                + "not staged at its checkpoint");
        // Its consumer: the OK is still printed, but never on its own.
        assertThat(report).contains("- **svc**: SUCCEEDED, decision: PENDING, checkpoint sha, "
                + "rebuild: OK (UNRELIABLE — upstream lib was not staged at its checkpoint)");
        // An unrelated repo keeps its clean verdict.
        assertThat(report).contains("- **tool**: SUCCEEDED, decision: PENDING, checkpoint sha, "
                + "rebuild: OK\n");
        // The summary's contract line cannot read clean when the tree it extracted from was
        // never staged.
        assertThat(report).contains("- Contract re-check: 1 checked, 0 mismatches — UNRELIABLE: "
                + "lib could not be staged at its checkpoint, so its contracts were extracted from "
                + "pre-run code");
        // The legend must name staging failures rather than leaving the reader to find the section.
        assertThat(report).contains("could not be staged at its checkpoint");
        // Staging failures come before the verdicts they invalidate.
        assertThat(report.indexOf("## Staging failures")).isLessThan(report.indexOf("## Repos"));
    }

    @Test
    void aPlanWithContractsThatWereNotRecheckedDoesNotClaimThePlanHasNone() {
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "b", "sha", "")), null, 0L);

        String report = ReviewReport.render("SPEC-1-v1", planWithContracts(2), state, Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.none());

        assertThat(report).doesNotContain("no contracts in this plan");
        assertThat(report).contains("- Contract re-check: none of this plan's 2 contracts were "
                + "re-checked in this invocation");
    }

    @Test
    void aReviewWithoutAnEstateRebuildSaysTheContractsWereReadUnstaged() {
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "b", "sha", "")), null, 0L);
        List<ContractRecheck.Finding> findings = List.of(
                new ContractRecheck.Finding("contract-0", "lib", "interface",
                        ContractRecheck.Status.MATCHES, "", "main"));

        String report = ReviewReport.render("SPEC-1-v1", planWithContracts(1), state, Map.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), findings, Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.skipped());

        assertThat(report).contains("- Contract re-check: 1 checked, 0 mismatches — no rebuild "
                + "staged the estate first, so each provider was read on whatever branch it was "
                + "checked out at");
    }

    @Test
    void diffStatsAndUnknownStateStillRenderAsBefore() {
        RunState state = new RunState("SPEC-1-v1", List.of(), null, 7L);
        Map<String, RunGit.DiffStat> stats = Map.of("lib", new RunGit.DiffStat(2, 10, 3));

        String report = ReviewReport.render("SPEC-1-v1", planNoContracts(), state, stats, Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), RunContext.Checkpoints.none(),
                "runbook", RebuildScope.skipped());

        assertThat(report).contains("- **lib**: UNKNOWN, decision: PENDING, 2 files changed (+10/-3)");
        assertThat(report).contains("- Total tokens spent: 7");
    }
}
