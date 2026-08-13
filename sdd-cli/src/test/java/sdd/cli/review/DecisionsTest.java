package sdd.cli.review;

import org.junit.jupiter.api.Test;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionsTest {
    // svc consumes lib; app consumes svc  (fromRepo = consumer, toRepo = provider)
    private static PlanModel plan() {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b"),
                        new PlanModel.PlanRepo("app", "dependent", "X", "patch", "c")),
                List.of(List.of("lib"), List.of("svc"), List.of("app")),
                List.of(new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
                        new PlanModel.PlanEdge("app", "svc", "SNAPSHOT", "INCLUDE_BUILD")),
                List.of(), List.of());
    }

    private static RunState allGreen() {
        return new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "aaa", ""),
                new RepoRun("svc", RepoState.SUCCEEDED, "sdd/S-v1/svc", "bbb", ""),
                new RepoRun("app", RepoState.SUCCEEDED, "sdd/S-v1/app", "ccc", "")), null, 0L);
    }

    @Test
    void approveRequiresASucceededRunState() {
        Decisions d = Decisions.empty();
        assertThat(d.of("lib")).isEqualTo(Decision.PENDING);
        assertThat(d.approve("lib", plan(), allGreen()).applied()).isTrue();
        assertThat(d.of("lib")).isEqualTo(Decision.APPROVED);

        RunState failed = new RunState("S-v1", List.of(
                new RepoRun("svc", RepoState.FAILED, null, null, "boom")), null, 0L);
        Decisions.Outcome notGreen = d.approve("svc", plan(), failed);
        assertThat(notGreen.applied()).isFalse();
        assertThat(notGreen.message()).contains("FAILED").contains("only SUCCEEDED");
    }

    @Test
    void aRepoMissingFromTheRunStateIsReportedByName() {
        RunState partial = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "b", "aaa", "")), null, 0L);

        Decisions.Outcome outcome = Decisions.empty().approve("app", plan(), partial);

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.message()).contains("app").contains("no run state").doesNotContain("null");
    }

    @Test
    void aRejectedUpstreamBlocksItsDownstreamTransitively() {
        Decisions d = Decisions.empty();
        d.reject("lib", plan(), "wrong API");

        Decisions.Outcome direct = d.approve("svc", plan(), allGreen());
        Decisions.Outcome transitive = d.approve("app", plan(), allGreen());   // app -> svc -> lib

        assertThat(direct.applied()).isFalse();
        assertThat(direct.message()).contains("lib").contains("REJECTED");
        assertThat(transitive.applied()).isFalse();
        assertThat(d.of("svc")).isEqualTo(Decision.PENDING);
        assertThat(d.reasonOf("lib")).isEqualTo("wrong API");
    }

    @Test
    void aRedoUpstreamAlsoBlocks() {
        Decisions d = Decisions.empty();
        d.redo("lib", plan(), "");
        assertThat(d.approve("svc", plan(), allGreen()).message()).contains("REDO");
    }

    @Test
    void redoingAnUpstreamDowngradesApprovedDownstreamToPending() {
        Decisions d = Decisions.empty();
        d.approve("lib", plan(), allGreen());
        d.approve("svc", plan(), allGreen());
        d.approve("app", plan(), allGreen());

        Decisions.Outcome outcome = d.redo("lib", plan(), "needs rework");

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.downgraded()).containsExactly("app", "svc");
        assertThat(d.of("lib")).isEqualTo(Decision.REDO);
        assertThat(d.of("svc")).isEqualTo(Decision.PENDING);   // re-decide, not auto-rejected
        assertThat(d.of("app")).isEqualTo(Decision.PENDING);
    }

    @Test
    void mapRoundTripPreservesDecisionsAndReasons() {
        Decisions d = Decisions.empty();
        d.approve("lib", plan(), allGreen());
        d.reject("svc", plan(), "flaky test");

        Decisions restored = new Decisions(d.asMap());

        assertThat(restored.of("lib")).isEqualTo(Decision.APPROVED);
        assertThat(restored.of("svc")).isEqualTo(Decision.REJECTED);
        assertThat(restored.reasonOf("svc")).isEqualTo("flaky test");
        assertThat(restored.of("app")).isEqualTo(Decision.PENDING);
    }
}
