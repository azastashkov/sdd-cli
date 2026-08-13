package sdd.cli.review;

import org.junit.jupiter.api.Test;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;
import sdd.cli.implement.RunGit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReportTest {
    private static PlanModel planWithContracts(int contractCount) {
        List<PlanModel.PlanContract> contracts = java.util.stream.IntStream.range(0, contractCount)
                .mapToObj(i -> new PlanModel.PlanContract(
                        "contract-" + i, "interface", "lib", List.of(), "body", "source"))
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

    @Test
    void summaryIncludesContractRecheckLineWhenNoContracts() {
        RunState state = new RunState("SPEC-1-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "branch", "sha", "ok")), null, 10L);
        PlanModel plan = planNoContracts();

        String report = ReviewReport.render("SPEC-1-v1", plan, state, Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), "runbook", false);

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
                List.of(), List.of(), List.of(), findings, "runbook", false);

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
                List.of(), List.of(), List.of(), findings, "runbook", false);

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
                List.of(), List.of(), List.of(), findings, "runbook", false);

        assertThat(report).contains("- Contract re-check: 3 checked, 2 mismatches");
        // Detail section should be present
        assertThat(report).contains("## Contract re-check\n\n- `contract-1`");
        assertThat(report).contains("- `contract-2`");
    }
}
