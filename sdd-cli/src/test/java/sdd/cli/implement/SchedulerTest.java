package sdd.cli.implement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTest {
    // real plan.json edge direction: from_repo = consumer, to_repo = provider (svc consumes lib)
    private static final List<PlanModel.PlanEdge> EDGES = List.of(
            new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"));

    @Test
    void flattensOrderAndFindsUpstreams() {
        assertThat(Scheduler.sequence(List.of(List.of("lib"), List.of("svc")))).containsExactly("lib", "svc");
        assertThat(Scheduler.upstreams("svc", EDGES)).containsExactly("lib");
        assertThat(Scheduler.upstreams("lib", EDGES)).isEmpty();
    }

    @Test
    void blocksADownstreamWhenItsUpstreamFailed() {
        RunState state = new RunState("R", List.of("lib", "svc"));
        assertThat(Scheduler.blockedByUpstream("svc", EDGES, state)).isFalse();   // lib PENDING/running
        state.set("lib", RepoState.FAILED, null, null, "boom");
        assertThat(Scheduler.blockedByUpstream("svc", EDGES, state)).isTrue();
        assertThat(Scheduler.blockedByUpstream("lib", EDGES, state)).isFalse();   // no upstreams
    }

    @Test
    void cascadesThroughASkippedUpstream() {
        RunState state = new RunState("R", List.of("lib", "svc", "app"));
        // app consumes svc consumes lib (from=consumer, to=provider)
        List<PlanModel.PlanEdge> chain = List.of(
                new PlanModel.PlanEdge("svc", "lib", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("app", "svc", "SNAPSHOT", "INCLUDE_BUILD"));
        state.set("lib", RepoState.FAILED, null, null, "boom");
        state.set("svc", RepoState.SKIPPED_UPSTREAM_FAILED, null, null, "upstream lib failed");
        assertThat(Scheduler.blockedByUpstream("app", chain, state)).isTrue();
    }

    @Test
    void cascadesThroughAStepLessIntermediate() {
        // app consumes mid consumes lib (from=consumer,to=provider); mid has no step so it is NOT in state.
        RunState state = new RunState("R", java.util.List.of("lib", "app"));   // mid absent (step-less)
        List<PlanModel.PlanEdge> chain = List.of(
                new PlanModel.PlanEdge("app", "mid", "SNAPSHOT", "INCLUDE_BUILD"),
                new PlanModel.PlanEdge("mid", "lib", "SNAPSHOT", "INCLUDE_BUILD"));
        state.set("lib", RepoState.FAILED, null, null, "boom");
        assertThat(Scheduler.blockedByUpstream("app", chain, state)).isTrue();
    }
}
