package sdd.cli.implement;

import org.junit.jupiter.api.Test;
import sdd.agent.run.ContractRef;
import sdd.agent.run.RepoStep;
import sdd.plan.spec.NormalizedSpec;
import sdd.plan.spec.SpecItem;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepoStepResolverTest {
    private static NormalizedSpec spec() {
        return new NormalizedSpec("SPEC-101", "Tiers", "me", "approved", "goal", "bg",
                List.of(new SpecItem("R1", "Price includes the customer tier."),
                        new SpecItem("R2", "Mapping changes need no restart.")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static PlanModel plan() {
        return PlanJsonReader.read(PlanJsonReaderTestFixture.PLAN);
    }

    @Test
    void buildsRepoStepsWithResolvedContractsAndRequirements() {
        Map<String, RepoStep> steps = RepoStepResolver.resolve(plan(), spec(),
                Map.of("lib", Path.of("/w/lib"), "svc", Path.of("/w/svc")));

        assertThat(steps.keySet()).containsExactly("lib", "svc");   // flattened order
        RepoStep lib = steps.get("lib");
        assertThat(lib.repoRoot()).isEqualTo(Path.of("/w/lib"));
        assertThat(lib.subSpec()).isEqualTo("Expose tierFor.");
        assertThat(lib.requirements()).containsExactly("R1: Price includes the customer tier.");
        assertThat(lib.acceptanceChecks()).containsExactly("Run lib tests");
        assertThat(lib.provides()).singleElement()
                .extracting(ContractRef::id, ContractRef::body)
                .containsExactly("c1", "Tier tierFor(String id)");
        RepoStep svc = steps.get("svc");
        assertThat(svc.requirements()).containsExactly(
                "R1: Price includes the customer tier.", "R2: Mapping changes need no restart.");
        assertThat(svc.consumes()).singleElement().extracting(ContractRef::id).isEqualTo("c1");
    }

    @Test
    void failsHardOnAnUnknownRepoPath() {
        assertThatThrownBy(() -> RepoStepResolver.resolve(plan(), spec(), Map.of("lib", Path.of("/w/lib"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("svc");
    }
}
