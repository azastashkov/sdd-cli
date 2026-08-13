package sdd.cli.review;

import org.junit.jupiter.api.Test;
import sdd.cli.implement.PlanModel;
import sdd.cli.implement.RepoRun;
import sdd.cli.implement.RepoState;
import sdd.cli.implement.RunState;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseRunbookTest {
    private static PlanModel plan(String mechanismMode) {
        return new PlanModel("S", 1, "", "",
                List.of(new PlanModel.PlanRepo("lib", "seed", "SEED", "minor", "a"),
                        new PlanModel.PlanRepo("svc", "dependent", "X", "patch", "b")),
                List.of(List.of("lib"), List.of("svc")),
                List.of(new PlanModel.PlanEdge("svc", "lib", mechanismMode, "INCLUDE_BUILD")),
                List.of(), List.of());
    }

    @Test
    void pinnedConsumersRequireAMergeStep() {
        RunState state = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "aaaaaaa1", "ok"),
                new RepoRun("svc", RepoState.SUCCEEDED, "sdd/S-v1/svc", "bbbbbbb2", "ok")), null, 0L);

        String md = ReleaseRunbook.render(plan("PINNED"), state);

        assertThat(md).contains("1. lib");
        assertThat(md).contains("lib").contains("aaaaaaa1").contains("merge pinned dependents");
        assertThat(md).contains("svc").contains("no downstream release step");
    }

    @Test
    void snapshotConsumersPickUpOnRepublishAndFailedReposAreNotReleasable() {
        RunState state = new RunState("S-v1", List.of(
                new RepoRun("lib", RepoState.SUCCEEDED, "sdd/S-v1/lib", "aaaaaaa1", "ok"),
                new RepoRun("svc", RepoState.FAILED, null, null, "boom")), null, 0L);

        String md = ReleaseRunbook.render(plan("SNAPSHOT"), state);

        assertThat(md).contains("dependents pick up on republish");
        assertThat(md).contains("not releasable (FAILED)");
    }
}
